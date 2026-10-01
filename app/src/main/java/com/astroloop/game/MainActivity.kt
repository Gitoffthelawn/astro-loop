package com.astroloop.game

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.astroloop.game.core.GameSurfaceView
import com.astroloop.game.core.shouldResumeAudio
import com.astroloop.game.core.SoundManager
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.hangar.HangarSurfaceView
import com.astroloop.game.input.InputModeState
import com.astroloop.game.input.InputRouter
import com.astroloop.game.input.InputSurface
import com.astroloop.game.input.shouldPauseOnDisconnect
import com.astroloop.game.render.FontManager
import com.astroloop.game.tuning.RunLoadout

class MainActivity : ComponentActivity() {

    companion object {
        /** An encoded RunLoadout. With it, this activity plays that one run and then finishes. */
        const val EXTRA_LOADOUT = "com.astroloop.game.EXTRA_LOADOUT"
    }

    private var directLoadout: RunLoadout? = null

    private var hangarView: HangarSurfaceView? = null
    private var gameView: GameSurfaceView? = null

    private val inputRouter = InputRouter()

    /**
     * Held so it can be unregistered. InputManager keeps a strong reference to its listeners,
     * so an anonymous one registered and forgotten outlives the Activity that owns the router.
     */
    private var deviceListener: android.hardware.input.InputManager.InputDeviceListener? = null

    /** Every view swap already assigns this, so pointing the router here catches every site
     *  at once — and the reset drops any direction still held by the outgoing screen,
     *  which would otherwise arrive at the new one as a phantom press. */
    private var currentView: View? = null
        set(value) {
            field = value
            inputRouter.reset()
            inputRouter.surface = value as? InputSurface
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The cabinet overlay swallows back so it can route to its pause screen instead of
        // leaving the run. Everywhere else — including a cabinet-less hangar and mid-game —
        // fall straight through to the default (finish the Activity), same as before this
        // callback existed.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hangarView?.onBackPressedFromActivity() == true) return
                // Back during a run pauses rather than leaving the game. On a TV remote Back is
                // the only spare button, so the old fall-through dropped the player out of a run.
                if (inputRouter.surface?.onCancel() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // Losing the controller you were playing with pauses the run, so a flat battery mid-run
        // is a pause rather than a death. Nothing else disconnecting may interrupt anyone.
        val inputManager = getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager
        deviceListener = object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {}
            override fun onInputDeviceChanged(deviceId: Int) {}
            override fun onInputDeviceRemoved(deviceId: Int) {
                if (shouldPauseOnDisconnect(deviceId == inputRouter.lastDeviceId, InputModeState.mode)) {
                    inputRouter.reset()
                    inputRouter.surface?.onPause()
                }
            }
        }.also { inputManager.registerInputDeviceListener(it, null) }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Black background to prevent white flash on view transitions
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        // Load fonts BEFORE any view/renderer is constructed — renderer Paint
        // fields capture the typeface at construction time.
        FontManager.initialize(this)

        // Initialize sound system
        SoundManager.init(this)

        // Load persisted sound set before any ambient playback
        val persistence = com.astroloop.game.data.PersistenceManager(this)
        // Fold any legacy story flags into the new story_stage / story_loop keys
        // before anything reads them. Idempotent — runs at most once per save.
        persistence.migrateStoryState()
        // Backfill desert_good_ending on astro-loop saves that predate the good
        // ending writing it (it only ever entered the stage) — see healDesertGoodEnding.
        persistence.healDesertGoodEnding()
        SoundManager.activeSet = StoryStateManager.stageMusicSet(persistence)

        when (val d = directLoadoutFrom(intent, BuildConfig.LAB)) {
            is DirectLoadout.Invalid -> {
                android.util.Log.w("AstroLoop", "Undecodable EXTRA_LOADOUT; finishing instead of opening the hangar")
                finish()
                return
            }
            is DirectLoadout.Run -> directLoadout = d.loadout
            is DirectLoadout.None -> directLoadout = null
        }
        val direct = directLoadout
        if (direct != null) launchGame(direct.shipId, direct.pilotId) else showHangar()

        // Fullscreen immersive mode (must be after setContentView)
        setupFullscreen()
    }

    private fun showHangar() {
        gameView?.pause()
        gameView = null

        hangarView = HangarSurfaceView(this) { shipId, pilotId ->
            runOnUiThread {
                launchGame(shipId, pilotId)
            }
        }
        setContentView(hangarView)
        wireInsets(hangarView!!)
        currentView = hangarView
        hangarView?.resume()
        // The first-launch intro cinematic opens in silence — no hangar BGM until the swell.
        if (com.astroloop.game.data.PersistenceManager(this).isIntroDone()) {
            SoundManager.playAmbient("bgm_${SoundManager.activeSet}_hangar")
        }
    }

    private fun launchGame(shipId: String, pilotId: String) {
        hangarView?.pause()

        gameView = GameSurfaceView(this, shipId, pilotId, directLoadout) { yenEarned, fadeFromWhite ->
            // Bank the payout HERE, before the hop to the UI thread.
            //
            // This runs on the game thread, inside GameThread's catch(Throwable), whereas
            // returnToHangar below runs on the UI thread with no such net — so anything that
            // throws while rebuilding the hangar used to cost the player the run's earnings
            // while still counting the run toward pilot unlocks. Two separate player reports
            // describe exactly that: one froze on the way back, one crashed on the death
            // cutscene, and both lost the money.
            //
            // Banking the amount passed rather than state.goldCollected is deliberate: several
            // onGameOver call sites pass 0 on purpose — the timeline shift and the debug exits
            // grant nothing however much was collected. (The reckoning win used to be on that
            // list; stage 3 moved the ending into the cabinet, which never routes through here.)
            if (yenEarned > 0) PersistenceManager(this).addYen(yenEarned)
            runOnUiThread {
                if (directLoadout != null) endDirectRun() else returnToHangar(yenEarned, fadeFromWhite)
            }
        }
        setContentView(gameView)
        wireInsets(gameView!!)
        currentView = gameView
        gameView?.resume()
        SoundManager.startCombatMusic(this)
        setupFullscreen()
    }

    /** A direct run hands back to whoever launched it instead of opening the hangar. */
    private fun endDirectRun() {
        gameView?.pause()
        gameView = null
        SoundManager.stopCombatMusic()
        window.decorView.post { if (!isFinishing && !isDestroyed) finish() }
    }

    private fun returnToHangar(yenEarned: Int, fadeFromWhite: Boolean = false) {
        // Stop the producer first, and synchronously. pause() joins the game thread, so once this
        // returns no further frame can be posted for the outgoing surface.
        gameView?.pause()
        gameView = null

        // Then let the current frame retire before swapping. setContentView destroys the outgoing
        // SurfaceView's surface, and a draw still sitting in HWUI's queue for that surface aborts
        // the process on some drivers rather than failing softly: on a reported Samsung Android 9
        // stack it asserts in EglManager::damageFrame with EGL_BAD_ACCESS.
        //
        // decorView rather than the outgoing view: it stays attached, so the post is guaranteed to
        // run. Costs one frame on the way back to the bar.
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                swapToHangar(yenEarned, fadeFromWhite)
            }
        }
    }

    private fun swapToHangar(yenEarned: Int, fadeFromWhite: Boolean) {
        if (hangarView == null) {
            hangarView = HangarSurfaceView(this) { shipId, pilotId ->
                runOnUiThread {
                    launchGame(shipId, pilotId)
                }
            }
        }

        setContentView(hangarView)
        wireInsets(hangarView!!)
        currentView = hangarView
        // resetForReturn BEFORE resume — state.phase must be BROWSING before the thread starts,
        // otherwise the thread's first frame sees LAUNCHING with launchProgress>=1 and fires onLaunch again.
        // surfaceCreated() is deferred (fires after this method returns), and state.initialize()
        // is guarded by stateInitialized so it won't overwrite resetForReturn's work.
        hangarView?.addYenFromRun(yenEarned)
        hangarView?.resetForReturn(fadeFromWhite)
        hangarView?.resume()
        SoundManager.stopCombatMusic()
        SoundManager.playAmbient("bgm_${SoundManager.activeSet}_hangar")
        setupFullscreen()
    }

    private fun wireInsets(target: View) {
        target.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Cutout is the binding constraint in immersive mode; combine with system bars
                // so transient (swipe-revealed) bars never hide combat HUD.
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val l = maxOf(cutout.left, bars.left).toFloat()
                val t = maxOf(cutout.top, bars.top).toFloat()
                val r = maxOf(cutout.right, bars.right).toFloat()
                val b = maxOf(cutout.bottom, bars.bottom).toFloat()
                (v as? GameSurfaceView)?.applyInsets(l, t, r, b)
                (v as? HangarSurfaceView)?.applyInsets(l, t, r, b, roundedCornerRadius(insets))
            }
            insets
        }
        target.requestApplyInsets()
    }

    /**
     * The display's rounded-corner radius in pixels — the largest of the four — or 0 where the
     * platform does not report them (below API 31) or the display has square corners.
     *
     * The largest rather than the top-right one specifically: every phone this matters for has
     * four equal corners, so they agree, and taking the max means a display that somehow differs
     * errs toward keeping chrome further in rather than clipping it. Only the hangar's yen counter
     * consumes this — see `ScreenLayout.cornerSafeRight` for why a corner is not an inset.
     *
     * Android reports corners in the CURRENT rotation's coordinate space, which is the same space
     * the surface is measured in, so this needs no rotation handling of its own — it is re-read on
     * every inset delivery, and a rotation always delivers.
     */
    private fun roundedCornerRadius(insets: WindowInsets): Float {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return 0f
        val positions = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT
        )
        var radius = 0
        for (p in positions) radius = maxOf(radius, insets.getRoundedCorner(p)?.radius ?: 0)
        return radius.toFloat()
    }

    private fun setupFullscreen() {
        // Hide system bars for immersive experience
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onPause() {
        super.onPause()
        hangarView?.pause()
        gameView?.pause()
        SoundManager.pause()   // catch-all for phases not handled inside gameView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceListener?.let {
            (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
                .unregisterInputDeviceListener(it)
        }
        deviceListener = null
        SoundManager.release()
    }

    override fun onResume() {
        super.onResume()
        val gameShowing = currentView == gameView
        if (shouldResumeAudio(gameShowing, gameShowing && gameView?.isRunPaused() == true)) {
            SoundManager.resume()
        }
        if (currentView == hangarView) {
            hangarView?.resume()
        } else {
            gameView?.resume()
        }
        setupFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back keeps going through onBackPressedDispatcher, which the cabinet overlay already
        // relies on to reach its pause screen rather than leaving the run.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (inputRouter.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputRouter.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }
}

/** What the launching intent asks for. */
internal sealed class DirectLoadout {
    object None : DirectLoadout()
    object Invalid : DirectLoadout()
    data class Run(val loadout: RunLoadout) : DirectLoadout()
}

/** Only the Lab acts on [MainActivity.EXTRA_LOADOUT]; the shipped game never does. */
internal fun directLoadoutFrom(intent: android.content.Intent?, isLab: Boolean): DirectLoadout {
    if (!isLab) return DirectLoadout.None
    val raw = intent?.getStringExtra(MainActivity.EXTRA_LOADOUT) ?: return DirectLoadout.None
    val decoded = RunLoadout.decode(raw) ?: return DirectLoadout.Invalid
    return DirectLoadout.Run(decoded)
}
