package com.astroloop.game.hangar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.astroloop.game.BuildConfig
import com.astroloop.game.MainActivity
import com.astroloop.game.cabinet.CabinetAttract
import com.astroloop.game.cabinet.CabinetBezelRenderer
import com.astroloop.game.cabinet.CabinetDebugIntent
import com.astroloop.game.cabinet.CabinetHeartbeat
import com.astroloop.game.cabinet.CabinetInput
import com.astroloop.game.cabinet.CabinetMusicFade
import com.astroloop.game.cabinet.CabinetMarqueeDrift
import com.astroloop.game.cabinet.CabinetMetrics
import com.astroloop.game.cabinet.CabinetRenderer
import com.astroloop.game.cabinet.CabinetScreen
import com.astroloop.game.cabinet.CabinetShell
import com.astroloop.game.cabinet.ReckoningOpening
import com.astroloop.game.cabinet.CabinetCrystal
import com.astroloop.game.cabinet.CabinetCues
import com.astroloop.game.entity.Boss
import com.astroloop.game.cabinet.CrystalVoice
import com.astroloop.game.cabinet.CabinetShellRenderer
import com.astroloop.game.cabinet.CabinetSim
import com.astroloop.game.cabinet.PilotCabinetRules
import com.astroloop.game.cabinet.ReckoningOutcomeWriter
import com.astroloop.game.cabinet.ReckoningRun
import com.astroloop.game.cabinet.ShellButton
import com.astroloop.game.core.DebugActionDispatch
import com.astroloop.game.core.DebugActionHost
import com.astroloop.game.core.DebugMirror
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.core.SoundManager
import com.astroloop.game.input.Direction
import com.astroloop.game.input.DirectionalInput
import com.astroloop.game.input.FocusNavigator
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import com.astroloop.game.input.InputSurface
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.TelemetryManager
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.data.SlotOdds
import com.astroloop.game.data.StoreUpgradeDefinitions
import com.astroloop.game.render.CrystalOrbPath
import com.astroloop.game.input.InputModeState
import com.astroloop.game.input.PadHaptics
import com.astroloop.game.render.FocusRingRenderer
import com.astroloop.game.render.StickReadoutRenderer
import com.astroloop.game.render.DebugFloatingButton
import com.astroloop.game.render.DebugMenuRenderer
import com.astroloop.game.render.FontManager
import com.astroloop.game.render.IconCache
import com.astroloop.game.system.CrystalReckoning
import kotlin.math.abs
import kotlin.math.pow

class HangarSurfaceView(
    context: Context,
    private val onLaunch: (shipId: String, pilotId: String) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Runnable, InputSurface {

    /**
     * The registry that currently owns directional input, or null when nothing does.
     *
     * The cabinet overlay is modal — while it is up it owns the screen, so hangar focus must not
     * leak through it. It answers to the shell's own registry instead, so a controller drives
     * the cabinet's buttons and never reaches the hangar behind it.
     */
    private fun activeFocus(): FocusRegistry? = when {
        state.cabinetOpen && cabinetShell != null -> cabinetShellRenderer?.focusRegistry
        state.phase == HangarPhase.BROWSING -> hangarFocus
        else -> null
    }

    /**
     * BELT RUN flies from the same stick the finger drags, so the cabinet's flight and its
     * fairness maths are untouched. Off the PLAY screen the external hold is released, or the
     * ship would keep its last heading through the menus.
     */
    override fun onDirectionalState(input: DirectionalInput) {
        val shell = cabinetShell
        if (shell != null && shell.screen == CabinetScreen.PLAY) {
            cabinetInput?.setExternal(input.direction.x, input.direction.y, input.magnitude)
        } else {
            cabinetInput?.clearExternal()
        }
    }

    /** During a BELT RUN, pause and back are what the cabinet's own double-tap does. */
    override fun onPause(): Boolean = pauseCabinetRun()

    /**
     * Back, and the pad's back buttons — [com.astroloop.game.input.InputRouter] puts B, the small
     * select button (Switch minus, Xbox View, PlayStation Share) and Escape here.
     *
     * In BELT RUN it is exactly the Android back button ([onBackPressedFromActivity]): pause a
     * run, leave SCORES or GAME OVER for the menu. The codex closes, as a tap anywhere closes it.
     * A landscape panel is the only other modal layer the hangar has, so Back closes it rather
     * than leaving. Only an OPEN one: a panel already fading shut has nothing to close, and
     * `togglePanel` would REOPEN it. Not consumed otherwise, so the Android back button on a
     * plain hangar page still reaches MainActivity's default exactly as it always has — and a
     * pad's B there does nothing, rather than quitting the game.
     */
    override fun onCancel(): Boolean = onBackPressedFromActivity() || closeCodex() || closeOpenPanel()

    private fun closeCodex(): Boolean {
        if (state.phase != HangarPhase.CODEX) return false
        state.phase = HangarPhase.BROWSING
        return true
    }

    /** @return true iff a panel was actually asked to close. Shared by Back and cancel. */
    private fun closeOpenPanel(): Boolean {
        // The cabinet overlay is modal — while it is up it owns the screen, and the hangar behind
        // it is neither drawn nor ticked (see activeFocus and render). A panel left open under it
        // is not something cancel may reach past the overlay to close; Back never gets here at all
        // in that case (MainActivity routes it to the shell first), but the pad's cancel button
        // would.
        if (state.cabinetOpen) return false
        if (!landscape || state.introCinematic || crystalRevealFlying()) return false
        // visiblePanel() with nothing closing is exactly "the open panel, on the page that owns it"
        // — the same identity handlePanelTap's button loop would toggle.
        val open = state.visiblePanel()?.takeIf { state.closingPanel == null } ?: return false
        togglePanel(open)
        return true
    }

    private fun pauseCabinetRun(): Boolean {
        val shell = cabinetShell ?: return false
        if (shell.screen != CabinetScreen.PLAY) return false
        shell.onPause()
        return true
    }

    override fun onDirectionalPress(direction: Direction): Boolean {
        val registry = activeFocus() ?: return false
        // The codex's way in on a pad. Shop page only, and only while the hatch is shut; the press
        // still falls through to the focus move below, so the ring keeps behaving normally for a
        // player who is only navigating the shop.
        //
        // Not under a panel's scrim: the touch half of this secret is a swipe sequence, and a scrim
        // eats swipes (the ACTION_DOWN gate), so the pad's half must be eaten too — otherwise a
        // player simply walking the ring around the SHOP panel's board would trip the hatch open by
        // accident. With the panel SHUT the shop page still walks it, which is landscape's way in.
        if (registry === hangarFocus && state.currentPage == 2 && !state.hatchOpen &&
            !panelScrimShown()
        ) {
            state.codexSequenceProgress =
                HangarGestures.codexSequenceStep(state.codexSequenceProgress, direction)
            if (state.codexSequenceProgress == HangarGestures.CODEX_SEQUENCE.size) {
                openCodexHatch()
            }
        }
        if (registry === hangarFocus) {
            val step = HangarGestures.shipCarouselStep(registry.focusedId, direction)
            if (step != 0) {
                cycleShip(step)
                return true
            }
        }
        FocusNavigator.next(registry.targets(), registry.focusedId, direction)?.let {
            registry.focusedId = it
        }
        return true
    }

    override fun onActivateDown(): Boolean {
        // The codex is a tap-anywhere dismiss, exactly as it is for touch.
        if (state.phase == HangarPhase.CODEX) {
            state.phase = HangarPhase.BROWSING
            return true
        }
        val registry = activeFocus() ?: return false
        val id = registry.focusedId
        // A store tile is bought by holding, so the press starts the fill rather than acting.
        // The release decides what it was — see onActivateUp.
        if (id != null && id.startsWith("store:upgrade:")) {
            val index = id.substringAfterLast(':').toIntOrNull() ?: return false
            padStoreTileIndex = index
            // Maxed and unaffordable tiles start no fill at all, exactly as under a finger; the
            // release then falls through to a plain tap, which flips the card that explains why.
            if (canStartHold(index)) {
                storeHold.start(index)
                heldUpgradeIndex = index
            }
            return true
        }
        return registry.activate()
    }

    /**
     * The release half of a store tile press.
     *
     * Mirrors the ACTION_UP rule: a press released inside the tap window — or one that never
     * started a fill — is a tap, and a longer one is an abandoned purchase. Without this the
     * button would have to be held forever or would buy on contact, and neither is what a
     * finger does.
     */
    override fun onActivateUp(): Boolean {
        if (padStoreTileIndex < 0) return false
        val index = padStoreTileIndex
        padStoreTileIndex = -1
        val wasTap = !storeHold.isActive || HoldToBuy.isTap(storeHold.heldSeconds)
        cancelHold()
        if (wasTap) {
            val rect = renderer.upgradeRects.getOrNull(index) ?: return true
            handleStoreTap(storeRectToTapX(rect.centerX()), rect.centerY())
        }
        return true
    }

    /** Shoulder buttons. A remote has none, which is why the nav row is focusable too. */
    override fun onPage(delta: Int): Boolean {
        if (state.cabinetOpen && cabinetShell != null) return false
        if (state.phase != HangarPhase.BROWSING) return false
        val target = (state.currentPage + delta).coerceIn(0, 2)
        if (target == state.currentPage) return false
        if (!HangarGestures.introPageHopAllowed(state.introCinematic, state.currentPage, target)) return false
        navigateToPage(target)
        return true
    }

    companion object {
        /** The ship row's half-height, and the half-width of the centre (buy) zone. Shared by the
         *  tap path and the focus target so the two cannot drift. */
        internal const val SHIP_HIT_SIZE = 70f

        /** A panel item's focus id, by the index it was published at. */
        internal fun panelCardId(index: Int): String = "panel:$index"

        /** A panel BUTTON's focus id — its home while the panel is shut, its tab while it is open. */
        internal fun panelButtonId(panel: HangarPanels.Panel): String = "panelbtn:${panel.name}"

        /**
         * The landscape panel layer's focus targets: the buttons a page is showing, and the items
         * inside whichever panel is open.
         *
         * **Screen space, untranslated.** Unlike [BarPageRenderer.publishPilotTargets] and
         * [StorePageRenderer.publishStoreTargets], which take a room origin because their rects are
         * recorded inside a room's `canvas.translate`, a panel draws on the SCREEN — so there is no
         * origin to apply, and applying one is the defect this replaces: `publishBarFocus` used to
         * hand the CREW panel's own screen-space rects to the bar's room-local publisher, putting
         * every card's ring a room's width (1178 units on a rotated Pixel 9 Pro) to the right of
         * the card it stood for.
         *
         * **One action for every target.** Both cards and buttons press [onPress] at their own
         * centre, and the caller points that at `handlePanelTap` — the very function a finger
         * reaches. That is what makes the gates inherited rather than copied: the crystal reveal's
         * refusal, the closing-panel refusal, the button loop that reverses a close, and the
         * backdrop rule are all a single implementation, and a gate added there later covers the
         * pad for free. Three earlier reviews caught this layer with a gate on one side only.
         *
         * [enabled] rides on every target published here rather than omitting them: a control that
         * is drawn and unavailable should be landable so the player can see it is there, which is
         * [FocusTarget.enabled]'s own contract.
         *
         * Buttons are published in [HangarPanels.Panel] order — `panelButtonRects` is a
         * ConcurrentHashMap, whose iteration order is not the stack's — so the published list is
         * the same every frame.
         */
        fun publishPanelTargets(
            focus: FocusRegistry,
            cards: List<LayoutRect>,
            buttons: Map<HangarPanels.Panel, LayoutRect>,
            enabled: Boolean = true,
            onPress: (Float, Float) -> Unit
        ) {
            fun add(id: String, r: LayoutRect) {
                val rect = RectF(r.left, r.top, r.right, r.bottom)
                focus.add(FocusTarget(id, rect, enabled) { onPress(rect.centerX(), rect.centerY()) })
            }
            for ((index, r) in cards.withIndex()) add(panelCardId(index), r)
            for (panel in HangarPanels.Panel.values()) buttons[panel]?.let { add(panelButtonId(panel), it) }
        }

        /**
         * Haptics, in one place so they can be tuned as a set rather than one call site at a time.
         *
         * Three strengths, and the distinction is what each one is *for*:
         * - **Button tap** — a control acknowledging a press. Short and light, the feel of a small
         *   physical button. Every button in the hangar gets this; card flips deliberately do not,
         *   because a flip is reading rather than acting.
         * - **Hold hum** — the store tile filling under a held finger. The only repeating one.
         * - **Purchase knock** — money actually left the wallet. Upgrades and ships share it, so
         *   a spend feels the same wherever it happens.
         */
        const val HAPTIC_BUTTON_MS = 12L
        const val HAPTIC_BUTTON_AMPLITUDE = 70

        /**
         * Hold-fill pulse, on/off in milliseconds.
         *
         * The fill sweeps from `TAP_SECONDS` to the purchase — 750ms — so the period sets how many
         * pulses that sweep contains. At the original 90/90 it was about four, which read as a slow
         * tick running alongside the fill rather than as the tile charging. At 35/35 it is about
         * eleven: fast enough to feel continuous, slow enough that the motor still articulates each
         * pulse. This is the number to turn if it wants more or less urgency.
         */
        const val HAPTIC_HOLD_ON_MS = 35L
        const val HAPTIC_HOLD_OFF_MS = 35L
        const val HAPTIC_HOLD_AMPLITUDE = 22   // raised with the rate; short pulses read weaker

        const val HAPTIC_PURCHASE_MS = 45L
        const val HAPTIC_PURCHASE_AMPLITUDE = 110

        /**
         * The eight permanent store upgrades, in tile order, with the names the slot machine
         * announces them by. One list rather than two parallel ones so a jackpot can name the
         * upgrade at the reveal and grant that same upgrade when the reels stop.
         */
        val SLOT_UPGRADES: List<Pair<String, String>> = listOf(
            "health" to "SALVAGE PLATE",
            "shields" to "DEFLECTOR RIG",
            "speed" to "NITRO BOOST",
            "damage" to "HOT ROUNDS",
            "crit" to "LUCKY ROUNDS",
            "magnet" to "HAUL LINE",
            "yen_bonus" to "FINDER'S FEE",
            "salvage" to "SCAVENGER RIG"
        )

        fun upgradeDisplayName(id: String): String? =
            SLOT_UPGRADES.firstOrNull { it.first == id }?.second

        // Pilot card flip (tap the selected portrait to read its passive).
        // The cycle is: FADE out the portrait, hold the passive face, FADE it back out.
        // So the passive is on screen for (DURATION - FADE) — keep that at the number
        // you actually want players to have for reading it.
        const val PILOT_FLIP_FADE = 0.225f      // one cross-fade leg
        const val PILOT_FLIP_DURATION = 2.225f  // → passive readable for 2.0s

        // Store card flip (tap a tile to read its back). Same cycle as the pilot flip — FADE the
        // front out, hold the back, FADE it out — but a stat block is a denser read than one line
        // of passive text, so the back gets ~4s rather than 2s.
        const val STORE_FLIP_FADE = 0.225f      // one cross-fade leg, matched to the pilot card
        const val STORE_FLIP_DURATION = 4.225f  // → back readable for 4.0s

        // Hold-to-buy fill exit. Nothing on screen may go without a visible exit, UI elements
        // included, and HoldToBuy.advance() zeroes its own progress the instant it cancels or
        // completes — without this the fill would snap to nothing on an early release, or never
        // be seen at 100% before vanishing on a successful purchase. Short: it only covers the
        // one-frame gap HoldToBuy leaves behind, not a deliberate animation beat of its own.
        const val STORE_HOLD_EXIT_DURATION = 0.3f

        // The Time Crystal tile — 9th tile, index 8, auto-equipped rather than purchased. Named
        // here so later work (drawing its card back) can reference the tile without a bare 8.
        const val CRYSTAL_TILE_INDEX = 8

        // --- BELT RUN overlay ---
        const val COIN_COST = 100
        const val DOUBLE_TAP_MS = 280L
        const val POWER_UP_SECONDS = 0.25f
        const val POWER_DOWN_SECONDS = 0.15f

        // --- Landscape panel layer open/close fade ---
        // Owner spec: ~0.15s each way, same order of magnitude as the cabinet's own
        // POWER_DOWN_SECONDS just above. One constant for both directions — HangarState.
        // advancePanelFade divides by it whichever way panelFade is moving, and
        // HangarState.togglePanel resuming a reversed fade from wherever it was (rather
        // than restarting) is what makes a single shared rate correct for both legs.
        const val PANEL_FADE_SECONDS = 0.15f

        // --- First-launch intro ---
        // How long the player may sit on the bar page before TB-26 points them next door.
        // Ten seconds is roughly eight after Medic's line lands, which is long enough that it
        // reads as him noticing they have not moved rather than as more opening chatter.
        // Policy, so it lives here rather than on HangarState, which holds values.
        const val INTRO_HINT_SECONDS = 10f
    }

    private var gameThread: Thread? = null
    @Volatile private var running = false

    val persistence = PersistenceManager(context)
    private val telemetryManager = TelemetryManager(context)
    // internal (not private): the touch handlers can't be driven through real MotionEvent
    // dispatch in a unit test — upgradeRects is only populated by a live Canvas draw pass, which
    // Robolectric can't provide a valid Surface for. Tests inject a rect directly and call the
    // handlers, which needs a way to read state/renderer back out. Same convention as
    // HangarState's own `internal val persistence` and ChatSystem's internal test members.
    internal val state = HangarState(persistence)
    internal val renderer = HangarRenderer(persistence)
    private val chatSystem = ChatSystem()

    // Debug menu, reached from DebugFloatingButton. debugState is a GameState used
    // only as a persistence mirror (DebugMirror populates its ten debug* fields) and to carry
    // debugMenuOpen / debugMenuPage — it is never a source of truth and nothing here reads
    // gameplay off it, because the hangar has no run.
    private val debugState = GameState()
    private val debugMenuRenderer = DebugMenuRenderer().also { it.runContext = false }
    private val debugButton = DebugFloatingButton(radiusPx = 54f)
    // @Volatile: written on the render thread (render()/renderCabinet()), read on the UI
    // thread too — onTouchEvent gates on it so a touch dispatched before the first frame
    // can't hit the button at its unloaded (0,0) default. Same convention as HangarState's
    // own cross-thread fields and the ordering note on cabinetShell/cabinetOpen above.
    @Volatile private var debugButtonLoaded = false

    private val debugHost = object : DebugActionHost {
        // No run exists here, so the seven run-only actions are unreachable by construction —
        // their rows are dimmed and register no rect (DebugMenuRenderer.runContext), and this
        // is the second gate.
        override fun isInRun() = false
        override fun returnToHangar() { /* already here */ }
        override fun closeMenu() { debugState.debugMenuOpen = false }
        override fun toggleLoadout(action: String) = Unit
        override fun runOnlyAction(action: String) = Unit
        override fun clearTelemetry() = Unit
    }

    private var screenWidth = 0f
    private var screenHeight = 0f
    private var renderScale: Float = 1f
    private var roomWidth: Float = 0f
    private var crystalGlowSoundPlayed = false

    // System-cutout insets in physical px, forwarded by MainActivity. Divided by
    // renderScale into design units when building the ScreenLayout.
    private var insetLeftPx = 0f
    private var insetTopPx = 0f
    private var insetRightPx = 0f
    private var insetBottomPx = 0f

    /**
     * The display's rounded-corner radius in physical pixels, 0 on a square-cornered display or
     * below API 31 where Android does not report it.
     *
     * Not an inset, and deliberately not folded into one: shrinking `safe` by the radius would
     * pull every edge-anchored thing in the hangar inward on most phones. Only the yen counter
     * actually sits in a corner — see `ScreenLayout.cornerSafeRight`.
     */
    private var cornerRadiusPx = 0f

    // @Volatile: written on the UI thread (applyInsets/surfaceChanged), read by the render thread.
    @Volatile var layout: ScreenLayout = ScreenLayout.compute(GameConfig.DESIGN_WIDTH, GameConfig.DESIGN_HEIGHT)
        private set

    /** Called by MainActivity when display-cutout insets become known or change. */
    fun applyInsets(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float = 0f) {
        // Android delivers identical insets repeatedly; ignore no-op deliveries so the
        // steady state never re-touches state the render thread reads. Real work happens
        // only on an actual cutout change (e.g. fold/unfold), which also fires surfaceChanged.
        if (left == insetLeftPx && top == insetTopPx &&
            right == insetRightPx && bottom == insetBottomPx && cornerRadius == cornerRadiusPx
        ) return
        insetLeftPx = left; insetTopPx = top; insetRightPx = right; insetBottomPx = bottom
        cornerRadiusPx = cornerRadius
        if (state.cabinetOpen) cabinetResizePending = true
        if (width > 0 && height > 0) {
            applyScreenDimensions(width, height)
            renderer.initialize(layout, roomWidth)
            state.pilotScreenWidth = screenWidth
            state.pilotScreenHeight = screenHeight
            state.roomWidth = roomWidth
            initShipPositions()
        }
    }

    // Vibration
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private var isVibratingHalo = false
    private var isVibratingHold = false
    private val hapticsLock = Any()
    /**
     * Set before the render thread is stopped, cleared on resume.
     *
     * The render thread starts the halo rumble from updateBrowsing while the UI thread cancels it
     * from pause/surfaceDestroyed. Cancelling first and joining second let a frame already past the
     * inHalo check start an infinite-repeat waveform after the cancel, with nothing left running to
     * take it back down — the phone buzzed until something else touched the vibrator.
     */
    @Volatile private var hapticsShutDown = false

    /**
     * The platform's own drag threshold, in real pixels for this screen's density (8dp).
     *
     * Read from ViewConfiguration rather than hard-coded so it means the same thing on a phone and
     * a tablet. The previous 15f was raw pixels — roughly 5dp on a modern phone, tighter than
     * Android's minimum for calling a movement a drag at all, which is why holding a store tile
     * asked for more steadiness than it should have.
     */
    private val swipeSlop: Float =
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var isVibrationMuted: Boolean = context.getSharedPreferences("astrohunt_save", Context.MODE_PRIVATE)
        .getBoolean("vibration_muted", false)

    // Touch handling
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime: Long = 0
    private var isDragging = false
    /**
     * Set when a gesture is abandoned, and consumed by the ACTION_UP that may still follow.
     *
     * ACTION_CANCEL ends a gesture outright, but ACTION_POINTER_UP does not — the last finger
     * lifting still delivers an ACTION_UP. Abandoning clears [isDragging], so without this flag
     * that release would take the `!isDragging` branch and be dispatched as a *tap*, turning a
     * deliberate drag into a press. In this hangar a press buys things: on the shipyard it can
     * unlock a ship outright for up to ¥100,000 with no confirmation.
     */
    private var gestureAbandoned = false
    private var activeSwipe: SwipeTarget = SwipeTarget.NONE
    private var shipDragPossible = false
    // internal (not private): test seam, same convention as heldUpgradeIndex below — lets a test
    // confirm the spin button was (or wasn't) grabbed from ACTION_DOWN without a live draw pass.
    internal var spinButtonHeld = false
    private var stateInitialized = false
    /**
     * internal (not private): test seam, same convention as [heldUpgradeIndex] below.
     *
     * The clock is normally ticked by `updateBrowsing` on the render thread, which a unit test has
     * no way to run — so without this a test could dispatch a press and a release but never age the
     * press in between, which is precisely the distinction the release path now turns on.
     */
    internal val storeHold = HoldToBuy()
    /**
     * Which tile the in-flight hold belongs to; survives HoldToBuy going idle on completion.
     *
     * internal (not private): test seam, same convention as state/renderer above — lets a test
     * confirm whether ACTION_DOWN started a hold without driving the render thread's update loop,
     * which is the only other place this is otherwise read.
     */
    internal var heldUpgradeIndex = -1
    /**
     * Seconds left in the hold-fill's exit fade — a completion flash on a finished purchase, a
     * plain fade on an early release or cancel. Lives here rather than in [HoldToBuy] because it
     * is a rendering concern, not part of the buy/no-buy decision; [HangarState]'s
     * `storeHoldExit*` fields are what the renderer actually reads, mirrored from this each frame
     * the same way `storeHoldIndex`/`storeHoldProgress` mirror [storeHold] itself.
     *
     * `@Volatile` because it is armed on the UI thread (via touch handling and `surfaceDestroyed`)
     * and decayed on the game thread. A missed write would leave the decay block never running and
     * `storeHoldExitAlpha` stuck at 1f — a fill drawn on a tile nobody is touching.
     */
    @Volatile private var storeHoldExitTimer = 0f
    /**
     * Tile a completed hold just bought. The finger is still down when a hold completes, so the
     * ACTION_UP that follows falls through to handleStoreTap on that same tile — this tells that
     * call to skip the flip exactly once. Consumed (cleared) the moment it's checked, so it can
     * never leak into a later, genuine tap on the same tile.
     */
    private var suppressFlipIndex = -1

    private enum class SwipeTarget { NONE, PAGE, SHIP_DRAG }

    // Page scroll physics — all values are per-second, applied via deltaTime
    private val pageFrictionPerSecond = 0.05f     // 5% velocity remains after 1s (iOS paging feel)
    private val pageSnapDecay = 1e-10f            // snap settles in ~0.3s
    private val pageVelocityThreshold = 250f      // px/s — lower = responds to gentle flicks

    /**
     * One registry for the whole hangar, rebuilt each frame for whichever page is showing.
     *
     * One rather than one per page: focus then moves between page content and the
     * [CREW] [LAUNCH] [SHOP] row without a second navigation concept, and that row is how a bare
     * TV remote — which has no shoulder buttons — changes page at all.
     */
    internal val hangarFocus = FocusRegistry()

    /**
     * The identity of the panel the focus pass last saw on screen, so it can tell the frame a panel
     * APPEARS from the frames it merely stays open. Render-thread only — written and read from
     * [publishPanelFocus] alone.
     */
    private var lastFocusedPanel: HangarPanels.Panel? = null

    /**
     * The hangar's focus ring. A second instance from combat's, since the two views advance
     * their own clocks — nothing is shared between them.
     */
    private val focusRing = FocusRingRenderer()

    /**
     * BELT RUN's own stick readout. A second instance rather than a shared one: the two sticks
     * fade independently, and combat's is drawn by a different view entirely.
     */
    private val cabinetStickReadout = StickReadoutRenderer()

    /** The store tile a controller is currently pressing, or -1. See onActivateUp. */
    private var padStoreTileIndex = -1

    // --- BELT RUN overlay ---
    private var cabinetShell: CabinetShell? = null
    private var cabinetRenderer: CabinetRenderer? = null
    private var cabinetShellRenderer: CabinetShellRenderer? = null
    private var cabinetLastTapTime = 0L
    /**
     * The reckoning attempt whose outcome has already been persisted.
     *
     * Identity, not a boolean. `updateCabinet` runs every frame and an outcome is sticky,
     * so something must stop it writing twice — but a session-scoped flag is the wrong
     * something: `AGAIN?` during a reckoning goes through `CabinetShell.beginRun()`, which
     * calls `startReckoning` again WITHOUT reopening the cabinet. A flag cleared only in
     * `openCabinet()` therefore stays set across the retry, and a win after a loss would
     * never be recorded at all — silently, since the shell closes on its own win-hold
     * regardless. Comparing the run itself is correct for both one attempt and many, and
     * needs no clearing anywhere.
     */
    private var cabinetReckoningResolvedRun: ReckoningRun? = null
    /**
     * The cabinet's joystick. Rebuilt in openCabinet() because its dead zone and radius
     * are playfield pixels, which are not known until the metrics exist.
     */
    private var cabinetInput: CabinetInput? = null

    // --- BELT RUN audio: the two-tone pulse, driven by per-frame tallies the sim itself
    // publishes (CabinetSim.*ThisFrame). All of this only ever runs from
    // updateCabinet/handleCabinetTouch, both of which are gated on the overlay being open —
    // the store page never reaches any of it, so the machine stays silent on the bezel.
    private val cabinetHeartbeat = CabinetHeartbeat()
    /** Rock count at the moment the current wave started — the heartbeat's thinning baseline. */
    private var cabinetRocksAtWaveStart = 0
    /** Reused across frames so a fight does not allocate a list per frame. Never outlives one. */
    private val cabinetCues = ArrayList<CabinetCues.Cue>(8)
    /** Decides the one frame the hangar bed is asked to fade out — a design decision. */
    private val cabinetMusicFade = CabinetMusicFade()

    /**
     * The tube warming up and dying, 0..1. Opening runs it up, EXIT runs it back down
     * and only then closes the overlay.
     *
     * Deliberately the cheapest transition available: a vertical scale about the centre
     * plus a brightness multiplier, wrapped around the draw that was already happening.
     * No second render path, no hangar drawn underneath, no offscreen pass — which
     * matters while frame time on slower devices is still a standing complaint.
     */
    private var cabinetPower = 0f
    private var cabinetPoweringDown = false
    private val cabinetVeil = Paint().apply { style = Paint.Style.FILL }

    // --- BELT RUN bezel: the attract demo the machine plays to itself on the store page ---
    /**
     * The demo the machine plays to itself on the store page, with its own metrics sized
     * to the bezel's CRT rect. Every cabinet constant is a fraction of minEdge, so the
     * same sim is correct at bezel scale with no special-casing.
     *
     * A [CabinetAttract], not a bare [CabinetSim]: the loop this needs — the autopilot and
     * the restart-on-a-beat hold — is the same loop the cabinet's own menu runs, and the
     * bezel's hand-rolled version of it had neither. It restarted in the frame it died,
     * which wiped the wreck one frame after it appeared, and it steered with a hardcoded
     * zero vector, so the demo ship sat dead centre firing straight up until something
     * drifted into it.
     *
     * Silent by construction: nothing in this path calls SoundManager, which is what
     * keeps a design decision's "the cabinet is silent on the bezel" true.
     */
    private var bezelAttract: CabinetAttract? = null
    private var bezelRenderer: CabinetRenderer? = null

    /**
     * The marquee plate's ambient rock drift (polish pass), built and gated the same
     * way as [bezelAttract] just above — lazily once `state.cabinetMarqueeRect` is known,
     * rebuilt when that rect resizes, ticked only while BROWSING on the store page in
     * Astro Loop (never while the cabinet overlay is open), and silent: nothing on this
     * path touches SoundManager.
     */
    private var marqueeDrift: CabinetMarqueeDrift? = null

    init {
        holder.addCallback(this)
        // Never focusable: every key reaches the game through MainActivity.dispatchKeyEvent, and a
        // focusable view lets ViewRootImpl swallow the first D-pad or OK press after a touch.
        isFocusable = false
        FontManager.initialize(context)
    }

    private fun applyScreenDimensions(physW: Int, physH: Int) {
        val m = DesignSpace.metricsFor(
            physW.toFloat(), physH.toFloat(),
            insetLeftPx, insetTopPx, insetRightPx, insetBottomPx, cornerRadiusPx
        )
        renderScale = m.renderScale
        screenWidth = m.width
        screenHeight = m.height
        layout = m.layout
        // The room keeps its PORTRAIT width in either orientation, so every consumer of
        // roomWidth — the counter, the stools, the chat column, the props, the mini machine,
        // TB-26's pacing band, the walker band — gets its portrait number without knowing the
        // screen has rotated. In portrait portraitShaped returns the layout unchanged, so this is
        // bit-for-bit what shipped.
        val portrait = DesignSpace.portraitShaped(layout)
        roomWidth = HangarMetrics.roomWidth(
            screenWidth = portrait.width,
            contentWidth = portrait.content.width,
            smallestScreenWidthDp = context.resources.configuration.smallestScreenWidthDp
        )
        // Panels exist only in landscape, so a rotation closes the open one rather than leaving
        // it set for the next rotation to reopen. Cheap, and it keeps openPanel's meaning exactly
        // "the panel that is on screen". closingPanel and panelFade go with it — a rotation
        // must not leave a half-faded panel (or a scrim stuck mid-release) for the next
        // landscape entry to inherit.
        if (!landscape) {
            state.openPanel = null
            state.closingPanel = null
            state.panelFade = 0f
        }
        // Called from both surfaceCreated and surfaceChanged, so sizing the debug menu here
        // covers both without a second copy of this pair.
        debugMenuRenderer.renderScale = renderScale
        debugMenuRenderer.initialize(screenWidth, screenHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        IconCache.preload(context)

        applyScreenDimensions(width, height)

        renderer.initialize(layout, roomWidth)
        state.pilotScreenWidth = screenWidth
        state.pilotScreenHeight = screenHeight
        state.roomWidth = roomWidth
        // Only initialize state on first surface creation — on re-attach after
        // a run, resetForReturn() has already set the correct state (e.g. bar page)
        if (!stateInitialized) {
            state.initialize()
            stateInitialized = true
        }
        SoundManager.applyAudioMode(state.audioMode)
        isVibrationMuted = state.vibrationMuted
        initShipPositions()

        if (persistence.isFirstLaunch()) {
            // Start on bar page
            state.currentPage = 0
            state.pilotX = state.getPilotWorldTarget(0)
            state.pilotTargetX = state.pilotX
            state.pilotWalking = false

            // Start with no yen — the counter is hidden during the intro cinematic anyway.
            persistence.setYen(0)
            state.actualYen = 0
            state.displayedYen = 0

            // Queue intro messages
            chatSystem.onFirstLaunch(state)

            persistence.setFirstLaunchComplete()
        }

        // Refresh the music set from current story stage before any ambient playback
        SoundManager.activeSet = StoryStateManager.stageMusicSet(persistence)

        // Start ambient for the initial page — but the intro cinematic plays the drone bed.
        if (state.cabinetOpen && cabinetMusicFade.faded) {
            // The surface can be recreated under an OPEN cabinet — a background/resume
            // during a run does exactly that, and cabinetOpen is @Volatile state on
            // HangarState rather than anything this method clears. Starting the hangar bed
            // here would put the music back on top of a machine that had already faded it.
        } else if (state.introCinematic) {
            SoundManager.playAmbient("sfx_intro_drone")   // looping bed under the silent bar
        } else {
            SoundManager.playAmbient(getAmbientForPage(state.currentPage))
        }

        if (!running) {
            // Clear the haptics latch here as well as in resume(). surfaceDestroyed() sets it and
            // this method restarts the render thread directly, so a surface destroy->create that
            // never passes through the activity's pause/resume would leave every hangar rumble
            // permanently dead. That path is newly reachable: adding smallestScreenSize to
            // configChanges means the activity now SURVIVES a configuration change that can
            // recycle the surface underneath it.
            hapticsShutDown = false
            running = true
            gameThread = Thread(this)
            gameThread?.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (state.cabinetOpen) cabinetResizePending = true
        val prevWidth = screenWidth
        applyScreenDimensions(width, height)
        renderer.initialize(layout, roomWidth)
        state.pilotScreenWidth = screenWidth
        state.pilotScreenHeight = screenHeight
        state.roomWidth = roomWidth
        initShipPositions()
        // Re-anchor pilot / TB-26 to the new layout whenever the surface dimensions change
        // (a live fold/unfold) or on the first valid layout (screenWidth was NaN — 0/0 in IEEE
        // 754 — when surfaceCreated() fired before this.width/height were known). A same-size
        // resume (prevWidth == screenWidth) is left alone so an in-progress walk isn't snapped.
        // Position fields are @Volatile and written together, so the render thread never reads a
        // torn/inconsistent pair; a fold is disruptive anyway, so we snap rather than animate.
        if (stateInitialized && (state.pilotX.isNaN() || screenWidth != prevWidth)) {
            state.pilotX = state.getPilotWorldTarget(state.currentPage)
            state.pilotTargetX = state.pilotX
            state.pilotWalking = false
            state.tb26BarX = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth) / 2f
            state.tb26BarTargetX = state.tb26BarX
        }
    }

    private fun initShipPositions() {
        val walkwayY = screenHeight * 0.60f
        state.shipRestingY = walkwayY + (screenHeight - walkwayY) * 0.4f
        if (!state.isDraggingShip) {
            state.shipDragY = state.shipRestingY
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hapticsShutDown = true
        // A hold or a held spin button otherwise survives the surface being destroyed: this only
        // stops the render thread, so on resume the retained elapsed/held state would pick right
        // back up and a purchase (or an auto-spin) could fire with no finger on screen.
        cancelHold()
        spinButtonHeld = false
        running = false
        gameThread?.join()
        cancelAllHaptics()
    }

    override fun run() {
        var lastTime = System.nanoTime()

        while (running) {
            val currentTime = System.nanoTime()
            val deltaTime = (currentTime - lastTime) / 1_000_000_000f
            lastTime = currentTime

            // Mirror GameThread's resilience: a throwable from a single update/render
            // frame (e.g. on resume from background) must not kill the process.
            try {
                update(deltaTime)
                render()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Opens the cabinet. Spending the coin is the caller's business - this is only
     * reached once a credit exists, so the overlay always represents a paid session.
     */
    private fun openCabinet() {
        val pilotId = PilotDefinitions.getPilotByIndex(state.selectedPilotIndex)?.id ?: return
        val metrics = CabinetMetrics(width.toFloat(), height.toFloat())
        val renderer = CabinetRenderer(metrics)
        cabinetRenderer = renderer
        cabinetShellRenderer = CabinetShellRenderer(metrics, renderer)
        applyCabinetGlass()
        cabinetInput = CabinetInput(metrics.stickDeadZone, metrics.stickRadius)
        cabinetShell = CabinetShell(
            metrics,
            kotlin.random.Random(System.nanoTime()),
            spendCredit = { persistence.spendCabinetCredit() },
            recordScore = { score -> persistence.setArcadeScoreIfBetter(pilotId, score) },
            shouldStartReckoning = {
                CrystalReckoning.shouldEnter(
                    persistence.allPilotsCleared(),
                    persistence.isCrystalReleased()
                )
            }
        )
        // Point the shell's focus at the same dispatch the tap path uses. Set after the shell
        // exists, since the callback closes over it.
        cabinetShellRenderer?.onShellButton = { button ->
            cabinetShell?.let { dispatchShellButton(button, it) }
        }

        state.cabinetOpen = true
        cabinetPower = 0f
        cabinetPoweringDown = false

        // Fresh session, fresh baselines — a stale heartbeat phase from a previous session
        // would carry its tone and timing into the very first frame here.
        cabinetHeartbeat.reset()
        cabinetRocksAtWaveStart = 0
        // Re-armed per SESSION, not per run: the bed comes back when you walk away from the
        // machine, never between one attempt and the next.
        cabinetMusicFade.reset()
    }

    private fun closeCabinet() {
        // Put the hangar back, but only if the cabinet actually took it away. A session
        // spent reading the high score table never faded anything, and restarting a track
        // that is already playing is an audible hiccup for nothing.
        if (cabinetMusicFade.faded) SoundManager.playAmbient(getAmbientForPage(state.currentPage))
        state.cabinetOpen = false
        cabinetShell = null
        cabinetRenderer = null
        cabinetShellRenderer = null
        cabinetInput = null
        setCabinetOrientationLock(false)
    }

    /**
     * Set on the UI thread when the screen changes under an open cabinet (a rotation, or new
     * insets), applied on the render thread at the top of [updateCabinet]. The shell's fields
     * are walked by the render thread every frame, so resizing them from the UI thread would
     * race it — the same reason the shell publishes its hit rects rather than sharing them.
     */
    @Volatile private var cabinetResizePending = false

    /** The cutout insets and corner radius, handed to the cabinet's two renderers. */
    private fun applyCabinetGlass() {
        cabinetShellRenderer?.topInset = insetTopPx
        cabinetRenderer?.let {
            it.leftInset = insetLeftPx
            it.rightInset = insetRightPx
            it.cornerRadius = cornerRadiusPx
        }
    }

    /**
     * Whether a run is holding the screen's orientation — see [setCabinetOrientationLock].
     * Render thread only.
     */
    private var cabinetOrientationLocked = false

    /**
     * A BELT RUN run holds the orientation it began in; the menus follow the device (owner,
     * 2026-09-28). A live run keeps every rock, bullet and reckoning pattern in the pixels of
     * the screen it started on, so rotating under one would mean remapping all of it; the menus
     * lay out from the metrics every frame and only need [CabinetShell.resize].
     *
     * LOCKED freezes whatever the device is showing now; releasing it restores the manifest's
     * fullUser, so a phone turned during the run rotates the moment the run ends — onto a menu,
     * which can take it.
     */
    private fun setCabinetOrientationLock(lock: Boolean) {
        if (lock == cabinetOrientationLocked) return
        cabinetOrientationLocked = lock
        val activity = context as? android.app.Activity ?: return
        activity.runOnUiThread {
            activity.requestedOrientation =
                if (lock) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun update(deltaTime: Float) {
        // The hangar underneath is fully paused while the overlay is up — see updateCabinet
        // and renderCabinet, hooked the same way at the top of render().
        // Read the @Volatile flag FIRST. openCabinet() runs on the UI thread and writes
        // the shell before setting cabinetOpen, so the volatile write publishes it - but
        // only for a reader that reads the flag before the handle. Reading the handle
        // first forfeits that edge and can see a null shell on the frame the overlay
        // opens, dropping one frame back to the hangar underneath.
        focusRing.update(deltaTime)

        if (state.cabinetOpen) {
            val shell = cabinetShell
            if (shell != null) {
                updateCabinet(shell, deltaTime)
                return
            }
        }

        when (state.phase) {
            HangarPhase.BROWSING -> {
                updateBrowsing(deltaTime)

                // Built lazily once StorePageRenderer has computed the CRT rect at bezel
                // scale and published it on state.cabinetScreenRect — every cabinet
                // constant is a fraction of minEdge, so CabinetMetrics(rect.width(),
                // rect.height()) is correct at bezel scale with no special-casing.
                //
                // Rebuilt whenever the rect's size drifts from the sim's own metrics, not
                // only on first build: the manifest keeps this view alive across orientation/
                // screenSize/screenLayout changes, and a real cutout change (fold/unfold) can
                // resize the rect via surfaceChanged without recreating the view at all. A
                // stale sim isn't a crash — CabinetBezelRenderer.drawScreen would keep scaling
                // x and y independently against the new rect while every constant inside the
                // sim is still a fraction of the old minEdge, so the attract demo would
                // silently stretch non-uniformly instead of staying proportional.
                val rect = state.cabinetScreenRect
                val currentBezel = bezelAttract
                val bezelIsStale = rect != null && currentBezel != null &&
                    (kotlin.math.abs(currentBezel.sim.m.width - rect.width()) > 0.5f ||
                        kotlin.math.abs(currentBezel.sim.m.height - rect.height()) > 0.5f)
                if (rect != null && (currentBezel == null || bezelIsStale)) {
                    val bm = CabinetMetrics(rect.width(), rect.height())
                    bezelRenderer = CabinetRenderer(bm)
                    bezelAttract = CabinetAttract(bm)
                }

                // Same lazy build / rebuild-on-resize as bezelAttract above, at the
                // marquee plate's own rect.
                val marqueeRect = state.cabinetMarqueeRect
                val currentDrift = marqueeDrift
                val driftIsStale = marqueeRect != null && currentDrift != null &&
                    (kotlin.math.abs(currentDrift.metrics.width - marqueeRect.width()) > 0.5f ||
                        kotlin.math.abs(currentDrift.metrics.height - marqueeRect.height()) > 0.5f)
                if (marqueeRect != null && (currentDrift == null || driftIsStale)) {
                    marqueeDrift = CabinetMarqueeDrift(
                        marqueeRect.width(), marqueeRect.height(), kotlin.random.Random(System.nanoTime())
                    )
                }

                // Only while the store page is actually up, and only in Astro Loop.
                // CabinetAttract owns the flying and the restart beat — see its own doc.
                if (state.currentPage == 2 && StoryStateManager.isAstroLoop(persistence)) {
                    bezelAttract?.update(deltaTime)
                    marqueeDrift?.update(deltaTime)
                }
            }
            HangarPhase.LAUNCHING -> {
                // updateBrowsing owns the per-frame halo-rumble net, and it stops running here.
                // The ACTION_UP that started the launch already stopped the rumble; this is the
                // net for every other way the phase can change.
                stopHaloRumble()
                updateLaunching(deltaTime)
            }
            else -> {}
        }

        // Update pilot walker
        state.updatePilotWalker(deltaTime)

        // Update NPC walkers
        state.updateNPCWalkers(deltaTime)

        // Animate yen counter
        state.updateYenDisplay(deltaTime)

        // Tick fade-from-black timer (corruption death return)
        if (state.fadeFromBlackTimer > 0f) {
            state.fadeFromBlackTimer = (state.fadeFromBlackTimer - deltaTime).coerceAtLeast(0f)
        }
        if (state.glitchTimer > 0f) {
            state.glitchTimer = (state.glitchTimer - deltaTime).coerceAtLeast(0f)
        }
    }

    private fun updateBrowsing(deltaTime: Float) {
        // --- Page scroll physics ---
        if (activeSwipe != SwipeTarget.PAGE) {
            if (abs(state.pageVelocity) > 0.1f) {
                state.pageScrollOffset += state.pageVelocity * deltaTime
                state.pageVelocity *= pageFrictionPerSecond.pow(deltaTime)
            }
            if (abs(state.pageVelocity) < 50f) {
                val diff = -state.pageScrollOffset
                if (abs(diff) > 1f) {
                    state.pageScrollOffset += diff * (1f - pageSnapDecay.pow(deltaTime))
                } else {
                    state.pageScrollOffset = 0f
                    state.pageVelocity = 0f
                }
            }
        }

        // Intro cinematic: advance the ASTRO LOOP title fade-in once on the launchpad.
        if (state.introCinematic && state.currentPage == 1) {
            state.introTitleTimer += deltaTime
        }

        // Intro cinematic: nudge a player who has not worked out there is a room next door.
        //
        // Page 0 only, mirroring the title timer above — two cinematic clocks, neither of them
        // running while the player is somewhere its beat does not belong. introHintDone is what
        // actually stops this firing more than once: without it, the condition is satisfied
        // again the very next frame after the conversation ends, and would re-queue forever on
        // a ~13s cycle. The page gate is only a secondary guard, and a partial one — it stops
        // the timer once the player swipes to the launchpad, but does nothing while they stay
        // on page 0, which is exactly the case the latch has to cover.
        //
        // Gated on activeConversation being null so it can never cut across Medic volunteering,
        // whose line is queued with its own timer by onFirstLaunch.
        if (state.introCinematic && state.currentPage == 0 && !state.introHintDone) {
            state.introHintTimer += deltaTime
            if (state.introHintTimer >= INTRO_HINT_SECONDS && state.activeConversation == null) {
                state.introHintDone = true
                chatSystem.onIntroSwipeHint(state)
            }
        }

        // Crystal reveal: play the glow shimmer once when the GLOW phase begins.
        if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.GLOW) {
            if (!crystalGlowSoundPlayed) {
                crystalGlowSoundPlayed = true
                SoundManager.playSFX("sfx_crystal_glow")
            }
        } else if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.NONE) {
            crystalGlowSoundPlayed = false
        }

        // Crystal reveal: trigger orb travel when store page settles
        if (state.awaitingCrystalReveal && state.currentPage == 2
            && state.crystalRevealPhase == HangarState.CrystalRevealPhase.GLOW
            && abs(state.pageScrollOffset) < 2f && abs(state.pageVelocity) < 50f) {
            // The tile the orb flies to is inside the SHOP panel in landscape, and that panel is
            // shut by default — so the beat opens its own door. A no-op in portrait.
            HangarState.armCrystalRevealPanel(state)
            if (revealDestinationReady()) {
                state.crystalRevealPhase = HangarState.CrystalRevealPhase.ORB_TRAVEL
                state.crystalRevealTimer = 0f
                SoundManager.playSFX("sfx_crystal_orb")
            }
        }

        // Crystal reveal animation
        if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.ORB_TRAVEL) {
            state.crystalRevealTimer += deltaTime
            if (state.crystalRevealTimer >= CrystalOrbPath.TRAVEL_DURATION) {
                state.crystalRevealPhase = HangarState.CrystalRevealPhase.FLASH
                state.crystalRevealTimer = 0f
                SoundManager.playSFX("sfx_crystal_activate")
            }
        } else if (state.crystalRevealPhase == HangarState.CrystalRevealPhase.FLASH) {
            state.crystalRevealTimer += deltaTime
            if (state.crystalRevealTimer >= CrystalOrbPath.FLASH_DURATION) {
                // Reveal complete — select Astro
                // Astro's actual slot machine position (base, without the walker's own offset)
                val slotMachinePos = state.slotMachineWorldX()
                state.crystalRevealPhase = HangarState.CrystalRevealPhase.DONE
                state.awaitingCrystalReveal = false
                state.astroAtSlotMachine = false
                persistence.setAwaitingCrystalReveal(false)
                val astroIndex = PilotDefinitions.pilots.indexOfFirst { it.id == "pilot_astro" }
                if (astroIndex >= 0) state.selectedPilotIndex = astroIndex
                // Place pilot walker exactly where Astro was at the slot machine
                state.pilotX = slotMachinePos
                state.pilotTargetX = slotMachinePos
                state.pilotWalking = false
            }
        }

        // --- Lamp sway momentum (feeds from velocity, decays over time) ---
        if (abs(state.pageVelocity) > 50f) {
            state.swayMomentum = state.pageVelocity
        }
        state.swayMomentum *= 0.93f
        if (abs(state.swayMomentum) < 1f) state.swayMomentum = 0f

        // --- Ship scroll animation ---
        if (kotlin.math.abs(state.shipScrollOffset) > 1f) {
            state.shipScrollOffset += (0f - state.shipScrollOffset) * 0.12f
        } else {
            state.shipScrollOffset = 0f
        }

        // --- Controller launch: rise into the halo, hold, then go ---
        if (state.padLaunchActive) {
            state.padLaunchTimer += deltaTime
            state.shipDragY = HangarGestures.padLaunchY(
                state.padLaunchTimer, state.shipRestingY, screenHeight / 2f
            )
            if (HangarGestures.padLaunchDone(state.padLaunchTimer)) {
                state.padLaunchActive = false
                beginLaunch()
            }
        }

        // --- Ship drag snap-back animation ---
        if (!state.isDraggingShip && abs(state.shipDragY - state.shipRestingY) > 1f) {
            state.shipDragY += (state.shipRestingY - state.shipDragY) * 0.15f
        } else if (!state.isDraggingShip) {
            state.shipDragY = state.shipRestingY
        }

        // Halo vibration — light rumble while ship is held in halo zone
        val haloCenter = screenHeight / 2f
        val inHalo = state.isDraggingShip && abs(state.shipDragY - haloCenter) < 60f
        if (inHalo) startHaloRumble() else stopHaloRumble()

        // Update slot machine animation
        if (state.isSpinning) {
            val now = System.currentTimeMillis()
            if (now >= state.reelStopTimes[2]) completeSpin(now)
        }

        // Auto-spin when holding the spin button (disabled after jackpot)
        if (spinButtonHeld && !state.isSpinning && state.spinResultTime > 0 && state.spinResultUpgrade == null) {
            val sinceResult = System.currentTimeMillis() - state.spinResultTime
            if (sinceResult > 600L) {
                handleSlotSpin()
            }
        }

        // Astro auto-gambling at slot machine in corruption
        if (state.astroAtSlotMachine && state.currentPage == 2) {
            state.astroAutoSpinTimer += deltaTime
            if (state.astroAutoSpinTimer >= state.astroAutoSpinInterval && !state.isSpinning) {
                if (state.actualYen >= 100) {
                    handleSlotSpin()
                }
                state.astroAutoSpinTimer = 0f
                state.astroAutoSpinInterval = 5f + kotlin.random.Random.nextFloat() * 3f
            }
        }

        // Pilot card fade — selected card full opacity, every other card dims to 35% whether it is
        // locked or not. The next recruit's card briefly had a brighter floor here to rescue its
        // silhouette, which made it outshine an unlocked crewmate sitting next to it: with MEDIC
        // selected, the locked BRUTUS read as more prominent than the recruited RASCAL. A locked
        // card differs by what it contains, not by how brightly the grid draws it.
        val lerpSpeed = 16f * deltaTime
        for (i in state.pilotCardFades.indices) {
            val target = if (i == state.selectedPilotIndex) 1f else 0.35f
            state.pilotCardFades[i] += (target - state.pilotCardFades[i]) * lerpSpeed
        }

        // Pilot card fade animation (tap selected pilot to reveal passive effect)
        if (state.pilotFlipTimer > 0f) {
            state.pilotFlipTimer -= deltaTime
            if (state.pilotFlipTimer <= 0f) {
                state.pilotFlipTimer = 0f
                state.pilotFlipIndex = -1
                state.pilotFlipProgress = 0f
                state.pilotFlipShowBack = false
            } else {
                val elapsed = PILOT_FLIP_DURATION - state.pilotFlipTimer
                state.pilotFlipShowBack = elapsed >= PILOT_FLIP_FADE
                // pilotFlipProgress = alpha of current visible content (1=fully visible, 0=invisible)
                state.pilotFlipProgress = when {
                    elapsed < PILOT_FLIP_FADE -> 1f - elapsed / PILOT_FLIP_FADE          // fading out: 1→0
                    state.pilotFlipTimer < PILOT_FLIP_FADE -> state.pilotFlipTimer / PILOT_FLIP_FADE  // fading in: 0→1
                    else -> 1f                                                            // back fully visible
                }
            }
        }

        // Store card flips — same cycle as the pilot flip above, its own duration, and one clock
        // per tile so turning over a second card leaves the first one turned over.
        state.advanceStoreFlips(deltaTime)
        state.advanceHintNoteReveal(deltaTime)

        // Landscape panel layer open/close fade. A no-op in portrait — see its own doc.
        state.advancePanelFade(deltaTime)

        // Hold-to-buy clock. Completing buys exactly one level; the machine goes idle on its own.
        if (storeHold.advance(deltaTime)) {
            // HoldToBuy.advance() has already reset its own progress to 0 by the time it returns
            // true, so without capturing the completed index first and freezing the fill full,
            // the bar would never be seen at 100% — it would just disappear.
            // The success flash is armed before the purchase runs, so it depends on canStartHold()
            // having already refused holds on maxed and unaffordable tiles. If those guards ever
            // move or loosen, a declined purchase would flash as though it had succeeded.
            beginHoldExit(heldUpgradeIndex, progress = 1f, success = true)
            purchaseHeldUpgrade(heldUpgradeIndex)
            stopHoldRumble()
            purchasePulse()
        }
        // The hum tracks the fill exactly — see HoldToBuy.isFilling.
        if (storeHold.isFilling) startHoldRumble() else stopHoldRumble()
        state.storeHoldIndex = storeHold.index
        state.storeHoldProgress = storeHold.progress

        // Decay the hold-fill's exit — a completion flash or an early-release fade, never a snap.
        if (storeHoldExitTimer > 0f) {
            storeHoldExitTimer = (storeHoldExitTimer - deltaTime).coerceAtLeast(0f)
            state.storeHoldExitAlpha = (storeHoldExitTimer / STORE_HOLD_EXIT_DURATION).coerceIn(0f, 1f)
            if (storeHoldExitTimer <= 0f) {
                state.storeHoldExitIndex = -1
            }
        }

        // Update TB-26 bartender movement and beer sliding on bar page
        updateTb26Bar(deltaTime)

        // The reckoning's one-shot, delivered where it can actually be read.
        //
        // Beating the crystal writes reckoning_just_won at the cabinet, but the only thing
        // that used to drain it was the return from a FLIGHT — so the win itself said nothing
        // and Tobar's line waited behind an unrelated death. It fires here instead, on the
        // first frame the player is standing in the bar with nothing else being said.
        //
        // Gated on activeConversation being null rather than clearing the chat the way
        // onDeathReturn does: arriving mid-line and having it cut off would cost more than the
        // second or two this waits. The flag is persisted, so waiting risks nothing.
        if (state.currentPage == 0 &&
            state.activeConversation == null &&
            StoryStateManager.isAstroLoop(persistence) &&
            persistence.isReckoningJustWon()
        ) {
            persistence.setReckoningJustWon(false)
            chatSystem.onReckoningWon(state)
        }

        // Update chat system
        chatSystem.update(deltaTime, state)
    }

    private fun updateLaunching(deltaTime: Float) {
        state.launchProgress += deltaTime / 2f  // 2 seconds total

        when {
            state.launchProgress < 0.20f -> state.launchPhase = 0   // Pilot boards (0-0.8s)
            state.launchProgress < 0.375f -> state.launchPhase = 1  // Engine charge (0.8-1.5s)
            state.launchProgress < 0.55f -> state.launchPhase = 2   // Liftoff (1.5-2.2s)
            else -> state.launchPhase = 3                           // Hyperspace (2.2-4.0s)
        }

        // Trigger game launch
        if (state.launchProgress >= 1f) {
            val ship = state.getSelectedShip()
            val pilot = state.getSelectedPilot()
            if (ship != null && pilot != null) {
                state.saveSelection()
                onLaunch(ship.id, pilot.id)
            }
        }
    }

    private fun updateTb26Bar(deltaTime: Float) {
        // Only update on bar page
        if (state.currentPage != 0) return

        // TB-26 not present in corruption state — no pacing, no beers
        if (StoryStateManager.isCorrupted(persistence)) return

        // Counter and beer band are room-local (BarPageRenderer's barLeft = 10f,
        // barRight = roomWidth - 10f), so pacing and beer targets must be too or TB-26 paces
        // past the counter and beers slide into the neighbouring room on wide screens.
        val rw = HangarMetrics.effectiveRoomWidth(state.roomWidth, screenWidth)

        val counterLeft = 30f
        val counterRight = rw - 30f

        // TB-26 pacing
        if (state.tb26BarMoving) {
            val dx = state.tb26BarTargetX - state.tb26BarX
            if (kotlin.math.abs(dx) < 3f) {
                state.tb26BarX = state.tb26BarTargetX
                state.tb26BarMoving = false
                state.tb26BarPauseTimer = 1f + kotlin.random.Random.nextFloat() * 1.5f
            } else {
                state.tb26BarX += kotlin.math.sign(dx) * 40f * deltaTime
            }
        } else {
            state.tb26BarPauseTimer -= deltaTime
            if (state.tb26BarPauseTimer <= 0f) {
                state.tb26BarTargetX = counterLeft + kotlin.random.Random.nextFloat() * (counterRight - counterLeft)
                state.tb26BarMoving = true
            }
        }

        // Beer timer
        state.beerTimer += deltaTime
        if (state.beerTimer >= state.beerInterval && !state.beerActive) {
            val walkers = state.npcWalkers
            if (walkers.isNotEmpty()) {
                val target = walkers[kotlin.random.Random.nextInt(walkers.size)]
                state.beerTargetPilotIndex = target.pilotIndex
                state.beerX = state.tb26BarX
                val margin = rw * 0.1f
                val walkableWidth = rw - 2 * margin
                state.beerTargetX = margin + target.x * walkableWidth
                state.beerFading = false
                state.beerFadeAlpha = 1f
                state.beerActive = true
                state.tb26BarMoving = false
                state.tb26BarPauseTimer = 0.25f
                state.beerTimer = 0f
                state.beerInterval = 5f + kotlin.random.Random.nextFloat() * 2.5f
            }
        }

        // Beer movement
        if (state.beerActive) {
            if (state.beerFading) {
                state.beerFadeAlpha -= deltaTime * 4f
                if (state.beerFadeAlpha <= 0f) {
                    state.beerActive = false
                    state.beerFading = false
                }
            } else {
                val dx = state.beerTargetX - state.beerX
                if (kotlin.math.abs(dx) < 5f) {
                    val margin = rw * 0.1f
                    val walkableWidth = rw - 2 * margin
                    val targetWalker = state.npcWalkers.find { it.pilotIndex == state.beerTargetPilotIndex }
                    if (targetWalker != null) {
                        val walkerScreenX = margin + targetWalker.x * walkableWidth
                        if (kotlin.math.abs(walkerScreenX - state.beerTargetX) < 20f) {
                            // Beer grabbed!
                            state.beerActive = false
                            targetWalker.armRaiseTimer = 0.5f
                        } else {
                            // Walker moved away — unclaimed
                            state.beerFading = true
                        }
                    } else {
                        // Target walker gone — unclaimed
                        state.beerFading = true
                    }
                } else {
                    state.beerX += kotlin.math.sign(dx) * 300f * deltaTime
                }
            }
        }

        // Arm raise tick
        for (npc in state.npcWalkers) {
            if (npc.armRaiseTimer > 0f) {
                npc.armRaiseTimer = (npc.armRaiseTimer - deltaTime).coerceAtLeast(0f)
            }
        }
    }

    private fun render() {
        // Mirrors the early return at the top of update(): the overlay owns the whole
        // screen while it's open, so the hangar behind it is neither ticked nor drawn.
        if (state.cabinetOpen && cabinetShell != null) {
            renderCabinet()
            return
        }

        // Below API 29, take the software canvas deliberately — see GameThread for why
        // (Android P's HWUI aborts the process on an abandoned swap at surface teardown).
        val canvas: Canvas? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
        } else {
            holder.lockCanvas()
        }
        canvas ?: return
        try {
            canvas.save()
            canvas.scale(renderScale, renderScale)
            renderer.render(canvas, state, bezelAttract?.sim, bezelRenderer, marqueeDrift)
            publishFocusPass()
            // Drawn in the same pass and the same space the targets were published in, so the
            // ring cannot lag a frame behind the layout it is pointing at.
            focusRing.render(canvas, hangarFocus, renderer.shipDragFade(state))
            // Still inside the renderScale scope: DebugMenuRenderer.initialize() was handed
            // design units (applyScreenDimensions), same as this renderer.
            if (BuildConfig.DEBUG && debugState.debugMenuOpen) debugMenuRenderer.render(canvas, debugState)
        } finally {
            canvas.restore()
            // Outside the scale, on purpose: DebugFloatingButton works in raw device pixels
            // (its own KDoc), the one coordinate space it shares with the cabinet overlay below.
            if (BuildConfig.DEBUG) {
                if (!debugButtonLoaded) {
                    debugButton.load(persistence, width.toFloat(), height.toFloat())
                    debugButtonLoaded = true
                }
                debugButton.draw(canvas)
            }
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // Surface was destroyed mid-frame
            }
        }
    }

    /**
     * The one place a shell button acts, whether it was tapped or focused.
     *
     * A pure extraction of what handleCabinetTouch inlined — same branches, same order — so a
     * focused PLAY still charges a coin and still chirps. A null button is a press that hit no
     * rect, which is not an error: the tap path could always miss.
     */
    private fun dispatchShellButton(button: ShellButton?, shell: CabinetShell) {
        when (button) {
            // The chirp marks a credit actually being spent to start a run — never
            // the coin-to-yen conversion itself, which can also happen from the
            // store page's INSERT COIN tile before the overlay is even open.
            ShellButton.PLAY -> {
                val started = shell.onPlay() || chargeAndPlay(shell)
                if (started) SoundManager.playSFX("sfx_belt_coin", CabinetCues.VOL_COIN)
            }
            ShellButton.AGAIN -> {
                // A design decision retired the free retry: the reckoning is priced like
                // every other run now, so the coin chirp - which marks a credit
                // actually being spent - plays here unconditionally too.
                val started = shell.onAgain() || chargeAndAgain(shell)
                if (started) SoundManager.playSFX("sfx_belt_coin", CabinetCues.VOL_COIN)
            }
            ShellButton.REPLAY -> {
                // Same charge-then-chirp shape as PLAY and AGAIN — the replay is
                // priced like every other run, not a special free case.
                val started = shell.onReplay() || chargeAndReplay(shell)
                if (started) SoundManager.playSFX("sfx_belt_coin", CabinetCues.VOL_COIN)
            }
            ShellButton.SCORES -> shell.onScores()
            ShellButton.EXIT -> shell.onExit()
            ShellButton.BACK -> shell.onBack()
            ShellButton.RESUME -> shell.onResume()
            ShellButton.QUIT -> shell.onQuit()
            null -> Unit
        }
    }

    private fun updateCabinet(shell: CabinetShell, deltaTime: Float) {
        if (cabinetResizePending) {
            cabinetResizePending = false
            shell.resize(width.toFloat(), height.toFloat())
            applyCabinetGlass()
            // A planted stick belongs to the old screen's pixels; let go of it.
            cabinetInput?.cancel()
        }
        setCabinetOrientationLock(
            shell.screen == CabinetScreen.PLAY || shell.screen == CabinetScreen.PAUSE
        )
        cabinetStickReadout.update(cabinetInput?.active == true, deltaTime)
        if (cabinetPoweringDown) {
            cabinetPower = (cabinetPower - deltaTime / POWER_DOWN_SECONDS).coerceAtLeast(0f)
            if (cabinetPower <= 0f) { closeCabinet(); cabinetPoweringDown = false }
            return
        }
        cabinetPower = (cabinetPower + deltaTime / POWER_UP_SECONDS).coerceAtMost(1f)

        // The joystick already holds direction x magnitude. It is relative to its own
        // planted origin, never to the ship, so there is no position delta here to wrap
        // and nothing to promote: a still finger inside the dead zone reads as zero and
        // the ship coasts, which is correct.
        val stick = cabinetInput
        val ix = stick?.x ?: 0f
        val iy = stick?.y ?: 0f
        val steering = stick?.steering == true
        // Captured before the sim ticks: CabinetShell.update()'s PLAY branch can move the
        // screen to OVER in this very call the instant the ship dies, so the screen we must
        // gate on is the one that was true while the sim was actually running — not the one
        // left behind afterward.
        val screenBefore = shell.screen
        shell.update(deltaTime, ix, iy, steering)

        // The hangar bed steps aside once a run is actually under way — a design decision.
        // Read AFTER shell.update(), because the frame a run begins is the frame the screen
        // becomes PLAY, and the audio block below is gated on the screen the sim RAN under.
        // SoundManager owns the ramp; this only decides when to ask for it.
        if (cabinetMusicFade.update(shell.screen)) {
            SoundManager.stopAmbient(fadeOutMillis = CabinetMusicFade.FADE_OUT_MILLIS)
        }

        // The ending's result, written once. reckoning_just_won is the flag
        // ChatSystem.onDeathReturn already consumes, so the bar reacts without any change
        // to the chat side. crystal_released is what blocks a re-trigger. Only a WIN writes
        // anything at all — a loss returns you to the bar in silence.
        //
        // A replay resolves for the shell's own screen handling and writes NOTHING to the
        // story — not crystal_released, not the post-win flag. Without the run.isReplay
        // guard inside the writer, replaying the ending would re-fire the one-shot post-win
        // bar chatter every time. The latch assignment itself stays exactly where it was,
        // inside WON and LOST only: setting it while the outcome is still RUNNING would
        // mark the run resolved before it ever finished.
        val run = shell.reckoning
        if (run != null && cabinetReckoningResolvedRun !== run) {
            // The writes live in ReckoningOutcomeWriter so they are testable. It returns true
            // only for a finished outcome, which is what gates the latch — assigning it while
            // the run is still RUNNING would mark it resolved before it ever finished.
            if (ReckoningOutcomeWriter.apply(
                    run.outcome, run.isReplay, run.fightSeconds.toInt(), persistence
                )) {
                cabinetReckoningResolvedRun = run
            }
        }

        // The heartbeat, fire, break and death cues only make sense while a run is live —
        // the menu/scores/pause/over screens have no sim ticking bullets and rocks against
        // each other worth reading. (The coin chirp is wired separately, in
        // handleCabinetTouch, at the moment a credit is actually spent.)
        if (screenBefore == CabinetScreen.PLAY) {
            val sim = shell.sim

            // A new wave resets the heartbeat's thinning baseline to what it just spawned.
            if (sim.consumeWaveStart()) {
                cabinetRocksAtWaveStart = sim.rocks.size
                cabinetHeartbeat.reset()
            }

            // WHAT to play is CabinetCues' decision, not this view's. Every cue used to be
            // an `if` right here, and since the samples did not exist until 2026-08-29 not
            // one of them had ever been exercised by a player or by a test — the exact
            // shape of defect this branch keeps finding at the end of a stage. What is left
            // here is the gate, the heartbeat's clock, and a loop.
            val beat = cabinetHeartbeat.update(deltaTime, sim.rocks.size, cabinetRocksAtWaveStart)
            cabinetCues.clear()
            CabinetCues.forFrame(sim, shell.reckoning, beat, cabinetHeartbeat.isHighTone, cabinetCues)
            for (cue in cabinetCues) SoundManager.playSFX(cue.eventId, cue.volume, cue.rate)
        }

        if (shell.requestExit) {
            shell.clearExitRequest()
            cabinetPoweringDown = true
            return
        }
    }

    private fun renderCabinet() {
        val shell = cabinetShell ?: return
        val r = cabinetRenderer ?: return

        // Acquire the canvas EXACTLY as render() does. A SurfaceHolder connects its
        // buffer producer in a different mode for each, so alternating
        // lockHardwareCanvas() and lockCanvas() on the same surface throws - which is
        // what crashed the app the moment the overlay opened.
        // Below API 29, take the software canvas deliberately — see GameThread for why
        // (Android P's HWUI aborts the process on an abandoned swap at surface teardown).
        val canvas: Canvas? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
        } else {
            holder.lockCanvas()
        }
        if (canvas == null) return

        // Save level at entry, visible to both try and finally: if a draw call throws
        // before canvas.save() below ever runs, finally still has a valid (no-op) level
        // to restore to instead of an uninitialized one.
        var saved = canvas.saveCount
        try {
            // Always clear. A locked canvas never carries the previous frame's pixels -
            // the surface is multi-buffered and its docs say content is not preserved -
            // so fading it cannot produce a trail, only a strobe. Beam persistence is
            // drawn explicitly instead, by CabinetRenderer.
            r.clearScreen(canvas)

            // The tube. Content is squeezed toward the centre line and dimmed; at phase
            // zero this is a bright horizontal sliver, which is exactly how a CRT dies.
            val m = shell.m
            val phase = cabinetPower.coerceIn(0f, 1f)
            val eased = phase * phase * (3f - 2f * phase)
            saved = canvas.save()
            canvas.scale(1f, eased.coerceAtLeast(0.002f), m.width / 2f, m.height / 2f)

            val pilot = PilotDefinitions.getPilotByIndex(state.selectedPilotIndex)
            val rules = PilotCabinetRules.forPilot(pilot?.id ?: "")
            val gel = StoryStateManager.corruptColor(pilot?.color ?: 0xFFFFFFFF.toInt())

            when (shell.screen) {
                CabinetScreen.PLAY, CabinetScreen.PAUSE -> {
                    // The crystal carries its own hasBody now, so no screen has to pass it —
                    // GAME OVER was the one that did not, and the exit's `reckoning = null`
                    // would have defeated a fix that read it off the run anyway.
                    r.drawPlayfield(canvas, shell.sim)

                    // Inside the CRT squeeze on purpose: the stick's coordinates are playfield
                    // pixels, so drawing it outside the scale would put it beside the picture.
                    val stick = cabinetInput
                    if (stick != null && shell.screen == CabinetScreen.PLAY) {
                        cabinetStickReadout.render(
                            canvas, stick.originX, stick.originY, stick.currentX, stick.currentY,
                            stick.deadZoneRadius, stick.stickRadius,
                            // The phosphor the cabinet draws its ship in — the readout belongs to
                            // the same tube as the thing it is steering.
                            CabinetRenderer.PHOSPHOR_BEAM
                        )
                    }
                    val c = shell.sim.crystal
                    val run = shell.reckoning
                    // No outcome filter here any more. The win is exactly the state
                    // a design decision's last word belongs to, and gating on RUNNING silenced the
                    // crystal on the very frame it dies. CrystalVoice decides per outcome.
                    val voice = run?.let { CrystalVoice.lineFor(it) }

                    // The crystal, mid-flight. It has no body in the sim until it lands, so
                    // the entrance is drawn rather than simulated — which is also what stops
                    // the health bar reporting on a thing that does not exist yet.
                    if (run != null && run.opening.stage == ReckoningOpening.Stage.ARRIVAL) {
                        r.drawCrystalAt(
                            canvas, run.opening.arrivalX(), run.opening.arrivalY(),
                            CabinetCrystal.RADIUS_FRAC * shell.sim.m.minEdge, 1f,
                            // A replay flies in an empty shell — a design decision.
                            hasCrystal = run.crystalHasBody
                        )
                    }

                    // THE HEALTH BAR ONLY ONCE THERE IS HEALTH. Branching on isReckoning
                    // alone put a crystal readout over the whole authored opening — twelve
                    // seconds of ordinary BELT RUN in which the player is scoring, with
                    // their score replaced by an empty bar, and a zero-length fill drawn
                    // with a ROUND cap as a 56px blob at screen centre. The final review
                    // called this out and the owner saw it on device.
                    //
                    // Not `c != null` either: shatterCrystal() nulls the crystal in the same
                    // call that sets WON while the shell holds on PLAY for the win, so that
                    // would swap back to a 000 score for the whole ending. The stage is the
                    // honest test — FIGHT from the landing onward, and it stays FIGHT.
                    if (run != null && run.opening.stage == ReckoningOpening.Stage.FIGHT) {
                        r.drawCrystalBar(
                            canvas, c?.healthFrac ?: 0f, gel, insetTopPx.toFloat(), voice
                        )
                    } else {
                        r.drawTopBar(
                            canvas, rules, shell.sim.score, gel, insetTopPx.toFloat(), voice,
                            // The reckoning's opening does not score, so it shows no score —
                            // sim.scores is the same flag that stops rocks paying, which is
                            // exactly the condition under which a readout would be a lie.
                            showScore = shell.sim.scores
                        )
                    }
                }
                // The menu and the board play the attract demo behind themselves, exactly
                // as a real cabinet does — but WAVE n is a player's readout, so the demo
                // stays silent about it.
                CabinetScreen.MENU, CabinetScreen.SCORES ->
                    r.drawPlayfield(canvas, shell.attractSim, showWaveBanner = false)
                CabinetScreen.OVER -> r.drawPlayfield(canvas, shell.sim)
            }
            // Read once and used twice below: the replay entry and a design decision's gate both
            // want it, and it is the same answer either way.
            val crystalReleased = persistence.isCrystalReleased()
            cabinetShellRenderer?.draw(
                canvas, shell,
                if (shell.screen == CabinetScreen.SCORES)
                    CabinetBezelRenderer.allPilots(persistence)
                else CabinetBezelRenderer.topFive(persistence),
                persistence.getCabinetCredits(),
                persistence.getYen() >= COIN_COST,
                // The price the button prints and the price takeCoin() charges are now
                // the same number rather than two that happen to match.
                COIN_COST,
                // The MENU's fourth entry. CabinetShell is pure and knows nothing of
                // persistence, so whether the crystal has been released is the host's own
                // knowledge, passed in the same way credits and canAfford already are.
                crystalReleased,
                // A design decision, the overlay's half. Same predicate as the shell's own
                // shouldStartReckoning lambda above, evaluated fresh every frame for the
                // same reason that one is: the debug menu can clear the twelfth pilot
                // without closing the cabinet, and the menu must be glitching by the time
                // the player looks back at it. Null shuts the whole effect off.
                if (CrystalReckoning.shouldEnter(persistence.allPilotsCleared(), crystalReleased))
                    System.currentTimeMillis() else null,
                persistence.getBestReleaseSeconds()
            )
            // Inside the CRT squeeze, like the buttons it rings — hitRects are in the shell's
            // own space, so a ring drawn outside the scale would sit beside them.
            cabinetShellRenderer?.let { focusRing.render(canvas, it.focusRegistry) }

            // Restored here (not deferred to finally) because the brightness-sag veil
            // below is a full-screen dim, not part of the squeezed tube content, and must
            // be drawn unsquashed on the normal path. finally still restores again below
            // as a safety net for the case this line is never reached.
            canvas.restoreToCount(saved)

            // Brightness sag: the picture is dim until the tube is up to voltage. The veil
            // only ever dims — it fades from black to nothing and never brightens past
            // the settled picture, so there is no overshoot in it.
            if (phase < 1f) {
                val veil = ((1f - phase) * 210f).toInt().coerceIn(0, 255)
                cabinetVeil.color = 0xFF000206.toInt()
                cabinetVeil.alpha = veil
                canvas.drawRect(0f, 0f, m.width, m.height, cabinetVeil)
            }

            // The debug button (and, when open, its menu) must reach over the cabinet overlay
            // too — this is a second, independent lock/draw/post cycle from render()'s normal
            // path above, so it needs its own copy of that block rather than sharing one. By
            // this point canvas.restoreToCount(saved) above has already undone the CRT squeeze,
            // so this draws in the same unscaled, raw-device-pixel space the cabinet itself
            // uses — the menu still needs its own renderScale scope, the button none at all.
            if (BuildConfig.DEBUG) {
                if (debugState.debugMenuOpen) {
                    canvas.save()
                    canvas.scale(renderScale, renderScale)
                    debugMenuRenderer.render(canvas, debugState)
                    canvas.restore()
                }
                if (!debugButtonLoaded) {
                    debugButton.load(persistence, width.toFloat(), height.toFloat())
                    debugButtonLoaded = true
                }
                debugButton.draw(canvas)
            }
        } finally {
            // Belt-and-suspenders restore: if a draw call above throws before the
            // restoreToCount in the try body is reached, that restore is skipped but this
            // still runs before the buffer is posted — so a throw mid-draw can no longer
            // post a frame with the vertical squash still applied. Matches render()'s own
            // restore-in-finally handling, two functions above, for the same reason. A
            // no-op on the normal path, since `saved` is already restored to by here.
            canvas.restoreToCount(saved)
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // Surface was destroyed mid-frame
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A finger anywhere in the hangar puts the mode back to touch, and the ring fades from
        // there. The hangar has its own gesture handling and never goes through TouchController,
        // which is the one place that used to mark this — so the ring outstayed the controller.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) InputModeState.markTouch()

        // Raw device pixels, and above the cabinet branch below: that branch returns
        // unconditionally whenever the overlay is open (openCabinet/closeCabinet always set
        // cabinetOpen and cabinetShell together), so anything below it is unreachable while
        // the overlay is up — the button has to come first to stay usable over the cabinet.
        //
        // Also gated on debugButtonLoaded, not just BuildConfig.DEBUG: until the first render()
        // pass positions it, cx/cy sit at their raw defaults (0f, 0f) — a circle that overlaps
        // real hangar hit targets in the top-left corner (the spin button among them). On a
        // device this window is unobservable — nothing is on screen to touch before the first
        // frame — but it is the whole story for a Robolectric test that drives onTouchEvent
        // without ever running a render pass, which is deliberate (view code isn't unit-tested
        // here; see TouchGestureAbandonTest's own doc comment).
        if (BuildConfig.DEBUG && debugButtonLoaded) {
            when (debugButton.onTouch(event, width.toFloat(), height.toFloat())) {
                DebugFloatingButton.Outcome.TAPPED -> {
                    debugState.debugMenuOpen = !debugState.debugMenuOpen
                    if (debugState.debugMenuOpen) DebugMirror.populate(debugState, persistence)
                    else debugButton.save(persistence)
                    return true
                }
                DebugFloatingButton.Outcome.CONSUMED -> {
                    // Persist only when a gesture actually moved the button — the same latch
                    // GameSurfaceView's own site uses, so a drag that ends by cancellation
                    // still saves and a still finger never triggers a write.
                    if (debugButton.consumeDirtyPosition()) debugButton.save(persistence)
                    return true
                }
                DebugFloatingButton.Outcome.IGNORED -> Unit
            }
            if (debugState.debugMenuOpen) {
                val result = debugMenuRenderer.handleTouch(event, debugState)
                if (result != null) {
                    // ARCADE_OPEN / ARCADE_PLAY and RECKONING_PHASE_* have no run to dispatch
                    // to — the hangar is already the bridge, straight through cabinetShell,
                    // with no onGameOver hop needed. CabinetDebugIntent stays for the
                    // GameSurfaceView side, which still needs that hop.
                    if (result == "ARCADE_OPEN" || result == "ARCADE_PLAY") {
                        debugState.debugMenuOpen = false
                        openCabinet()
                        if (result == "ARCADE_PLAY") {
                            // Bank the credit onPlay is about to spend, exactly as
                            // DebugActionDispatch's own ARCADE_PLAY branch does: the point of
                            // this button is not to be blocked by the coin. Without it the
                            // action silently did nothing at zero credits — and the hangar is
                            // now the natural place to press it.
                            persistence.addCabinetCredit()
                            cabinetShell?.onPlay()
                        }
                        return true
                    }
                    if (result.startsWith("RECKONING_PHASE_")) {
                        // Same parse GameSurfaceView's inline branch uses: a "_LAP2" suffix
                        // selects lap 2, stripped before the phase number is parsed. A missed
                        // suffix here would silently fall the phase through to 0.
                        debugState.debugMenuOpen = false
                        val rest = result.removePrefix("RECKONING_PHASE_")
                        val lap = if (rest.endsWith("_LAP2")) 2 else 1
                        val phase = rest.removeSuffix("_LAP2").toIntOrNull() ?: 0
                        openCabinet()
                        // isReplay once the ending has been consumed: winning a debug jump
                        // must not re-write crystal_released or re-fire the one-shot post-win
                        // bar chatter. A design decision, reached from the very ARCADE debug page
                        // a tester uses to get here.
                        cabinetShell?.startReckoning(phase, lap, persistence.isCrystalReleased())
                        return true
                    }
                    DebugActionDispatch.handle(result, debugState, persistence, debugHost)
                }
                return true
            }
        }

        // Cabinet touches are in raw device pixels (CabinetMetrics is built from the view's
        // own width/height, unscaled) — routed before ex/ey below apply the hangar's
        // design-unit renderScale, which the overlay doesn't use.
        // Volatile flag before the handle - see the note in update().
        if (state.cabinetOpen) {
            val shell = cabinetShell
            if (shell != null) return handleCabinetTouch(event, shell)
        }

        val ex = event.x / renderScale
        val ey = event.y / renderScale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ex
                touchStartY = ey
                lastTouchX = ex
                lastTouchY = ey
                lastTouchTime = event.eventTime
                isDragging = false
                gestureAbandoned = false
                activeSwipe = SwipeTarget.NONE
                shipDragPossible = false

                if (state.phase == HangarPhase.BROWSING) {
                    // Block interaction during crystal reveal animation
                    if (crystalRevealFlying()) return true
                    // A landscape panel's scrim — open on this page, or still fading closed
                    // wherever the page has gone (HangarState.visiblePanel) — covers everything
                    // beneath it exactly as the crystal-reveal flight does just above. Consumed
                    // here, before any of the room-specific branches below, so a hold or a drag
                    // can never reach through to a room the player cannot see: a press on the
                    // blank SHOP box used to buy an upgrade or start a 100¥ spin nobody could
                    // see, and a closing CREW panel left the launchpad's ship fully draggable
                    // underneath its scrim. Placed here,
                    // not copied into each branch below, so a room-interaction path added later
                    // inherits the refusal for free instead of needing its own copy of it.
                    if (panelScrimShown()) {
                        // ...except the panel's OWN contents, which are not underneath the scrim —
                        // they are what it exists to show. A shop tile is bought by holding it, so
                        // a press on one has to start the same fill in the panel as in the room, or
                        // landscape would be able to read the board but never buy from it.
                        beginPanelPress(ex, ey)
                        return true
                    }
                    // Any touch below walkway on shipyard page can drag the ship
                    val walkwayY = screenHeight * 0.60f
                    if (state.currentPage == 1 && ey > walkwayY &&
                        state.isShipUnlocked(state.selectedShipIndex)) {
                        shipDragPossible = true
                    }
                    state.pageVelocity = 0f
                    state.pageScrollOffset = 0f
                    // Check if spin button is being held. Must resolve against the rest position
                    // (pageScrollOffset == 0), same as the release path in handleStoreTap: its own
                    // reset above the switch statement already zeroes pageScrollOffset before it
                    // calls storeHitPoint(), so DOWN and UP have to agree on the same rest-position
                    // offset or a press during a post-swipe settle can hold against one room-local
                    // X and release against another.
                    val (spinX, spinY) = storeHitPoint(ex, ey)
                    if (state.currentPage == 2 && renderer.spinButtonRect.contains(spinX, spinY)) {
                        spinButtonHeld = true
                        // Press only. Auto-spin re-enters handleSlotSpin from updateBrowsing while
                        // the finger stays down, and a tap per spin would turn a held button into a
                        // continuous rattle.
                        buttonTap()
                    }
                    // Store tiles: the press starts a hold. Resolve against the rest position for
                    // the same reason the spin button does — DOWN and UP must agree on the offset
                    // or a press during a post-swipe settle hits a different tile than it releases
                    // on. Release under the threshold falls through to handleStoreTap as a tap.
                    if (state.currentPage == 2) {
                        val (rx, ry) = storeHitPoint(ex, ey)
                        for ((index, rect) in renderer.upgradeRects.withIndex()) {
                            if (rect.contains(rx, ry)) {
                                // Maxed and unaffordable tiles start no fill at all — the press
                                // falls through to a plain tap on release instead, which flips
                                // the card, and the card is what explains the cost or the cap.
                                if (canStartHold(index)) {
                                    storeHold.start(index)
                                    heldUpgradeIndex = index
                                }
                                break
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (state.phase == HangarPhase.BROWSING) {
                    if (!isDragging) {
                        val totalDx = abs(ex - touchStartX)
                        val totalDy = abs(ey - touchStartY)

                        // Slop comes from the platform so it scales with the screen — see
                        // HangarGestures for why the old raw 15f was the wrong shape entirely.
                        val shipDrag = HangarGestures.startsShipDrag(
                            totalDx, totalDy, swipeSlop, shipDragPossible)
                        val pageSwipe = HangarGestures.startsPageSwipe(totalDx, totalDy, swipeSlop)

                        if (shipDrag || pageSwipe) {
                            // A swipe is not a hold. Note this is now reached only by a gesture the
                            // page can actually perform: vertical drift on the store page commits
                            // to nothing and leaves the fill running.
                            cancelHold()
                            isDragging = true
                            activeSwipe = if (shipDrag) SwipeTarget.SHIP_DRAG else SwipeTarget.PAGE
                            if (shipDrag) state.isDraggingShip = true
                        } else if (storeHold.isActive) {
                            // Drift within the tile is free; leaving it is letting go of the
                            // button, which is what every platform control does.
                            val rect = renderer.upgradeRects.getOrNull(storeHold.index)
                            val (hx, hy) = storeHitPoint(ex, ey)
                            if (rect != null && !HangarGestures.holdSurvivesDrift(
                                    hx, hy, rect.left, rect.top, rect.right, rect.bottom)) {
                                cancelHold()
                            }
                        }
                    }

                    if (isDragging) {
                        val dx = ex - lastTouchX
                        val dy = ey - lastTouchY

                        when (activeSwipe) {
                            SwipeTarget.PAGE -> {
                                state.pageScrollOffset -= dx

                                // Add resistance at edges (page 0 left edge, page 2 right edge).
                                // During the intro cinematic the launchpad (page 1) is locked both
                                // ways — you may only arrive there from the bar.
                                if ((state.currentPage == 0 && state.pageScrollOffset < 0) ||
                                    (state.currentPage == 2 && state.pageScrollOffset > 0) ||
                                    (state.introCinematic && state.currentPage == 1)) {
                                    state.pageScrollOffset *= 0.3f
                                }

                                val touchDt = (event.eventTime - lastTouchTime).coerceAtLeast(1L).toFloat() / 1000f
                                state.pageVelocity = -dx / touchDt
                                lastTouchTime = event.eventTime
                            }
                            SwipeTarget.SHIP_DRAG -> {
                                state.shipDragY += dy
                                // Clamp: ship stops exactly at halo center, can't go above
                                val targetZoneY = screenHeight / 2f
                                state.shipDragY = state.shipDragY.coerceIn(
                                    targetZoneY,
                                    state.shipRestingY + 20f
                                )
                            }
                            else -> {}
                        }
                    }
                }

                lastTouchX = ex
                lastTouchY = ey
                return true
            }
            MotionEvent.ACTION_UP -> {
                // A press that outlived the tap window was an upgrade being started, not a tap.
                // Releasing it abandons the purchase and must NOT fall through to a flip: treating
                // the two as one event is exactly what made holding-then-letting-go still turn the
                // card over. The tail below cancels the hold, which arms the fill's fade, so the
                // player sees the attempt end rather than nothing happening.
                val abandonedPurchase = storeHold.isActive && !HoldToBuy.isTap(storeHold.heldSeconds)

                // gestureAbandoned, not just isDragging: an abandoned gesture has already cleared
                // isDragging, and without this it would be dispatched below as a tap.
                if (!isDragging && !gestureAbandoned && !abandonedPurchase) {
                    // Reset scroll offsets to prevent jitter on tap
                    if (shipDragPossible) {
                        // Was a tap on ship, not a drag — don't reset page scroll
                    } else {
                        state.pageScrollOffset = 0f
                        state.pageVelocity = 0f
                    }
                    handleTap(ex, ey)
                } else if (activeSwipe == SwipeTarget.PAGE) {
                    // A page swipe travels one screen, not one room.
                    val stride = RoomAnchor.stride(screenWidth, roomWidth, landscape)
                    val shouldSwitch = abs(state.pageVelocity) > pageVelocityThreshold ||
                            abs(state.pageScrollOffset) > stride * 0.25f

                    val oldPage = state.currentPage
                    var targetPage = oldPage
                    if (shouldSwitch) {
                        // Intro cinematic only permits the single forward hop bar(0) → launchpad(1).
                        val cinematicLocked = state.introCinematic
                        if (state.pageScrollOffset > 0 && oldPage < 2 &&
                            !(cinematicLocked && oldPage >= 1)) {
                            targetPage = oldPage + 1
                            state.pageScrollOffset -= stride
                        } else if (state.pageScrollOffset < 0 && oldPage > 0 && !cinematicLocked) {
                            targetPage = oldPage - 1
                            state.pageScrollOffset += stride
                        }
                    }
                    // setPageTarget is the only writer of currentPage here, and it runs BEFORE the
                    // sound block — the same rule navigateToPage states, for the same reason and
                    // then some. The page used to be written straight into `state.currentPage`
                    // above, which opened a window where currentPage already read as the new page
                    // while an open panel still belonged to the old one: `visiblePanel()` answers
                    // null there, `drawPanelLayer` returns early, and a frame landing in the
                    // window cuts the scrim outright — the instant disappearance the fade exists
                    // to prevent. Here that window was the wider of the two, because the sound
                    // block sat inside it and `SoundManager.playAmbient` builds a `MediaPlayer`
                    // synchronously: tens of milliseconds, several frames at 120Hz. The horizontal
                    // drag is the primary non-modal way out of a page with a panel open (spec
                    // a design decision), so this is the path that showed it. Pinned by `PanelFadeTest`'s
                    // "a swipe off a panel's page fades it out instead of blanking it mid-gesture".
                    //
                    // Pilot walks to the new page target as a side effect (no teleporting).
                    state.setPageTarget(targetPage)
                    // Play swipe sound and crossfade ambient on page change.
                    if (targetPage != oldPage) {
                        if (state.introCinematic) {
                            // The one allowed swipe (bar → launchpad): swell rises while drone fades out.
                            SoundManager.playIntroSwell(context)
                            SoundManager.stopAmbient(fadeOutMillis = 1200)
                        } else {
                            SoundManager.playSFX("sfx_ui_swipe")
                            SoundManager.playAmbient(getAmbientForPage(targetPage))
                        }
                    }
                    state.pageVelocity = 0f
                } else if (activeSwipe == SwipeTarget.SHIP_DRAG) {
                    // Check if ship is in the launch zone
                    val targetZoneY = screenHeight / 2f
                    val inZone = abs(state.shipDragY - targetZoneY) < 45f

                    stopHaloRumble()
                    if (inZone && canLaunchNow()) {
                        beginLaunch()
                    } else {
                        // Release → snap back to resting position
                        state.isDraggingShip = false
                    }
                }

                isDragging = false
                gestureAbandoned = false
                activeSwipe = SwipeTarget.NONE
                shipDragPossible = false
                spinButtonHeld = false
                cancelHold()
                // Safety net: the tile-match branch in handleStoreTap already consumes this on a
                // matching release, but a release that lands off every rect (or a drag that
                // starts only after a hold completed) would otherwise leave it set for a later,
                // unrelated tap on the same tile to consume instead.
                suppressFlipIndex = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // The OS can deliver CANCEL instead of UP mid-hold (a parent intercepting the
                // gesture, e.g.). Mirrors the ACTION_UP tail's resets, minus the tap dispatch —
                // a cancel must never buy or flip anything — plus the ACTION_UP SHIP_DRAG release
                // branch's stopHaloRumble()/isDraggingShip reset just above, which this needs too:
                // a cancel mid ship-drag is a release that skips the launch check, not a no-op.
                // Without both halves, the snap-back animation (gated on !isDraggingShip) never
                // fires, the ship hangs in mid-air, and the halo check keeps re-arming the rumble
                // — the phone vibrates continuously until the player drags the ship again.
                abandonGesture()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // A second finger touching down anywhere mid-gesture, then the first lifting,
                // delivers POINTER_UP rather than UP or CANCEL. This view tracks one logical
                // touch, so — same as CANCEL — treat any additional-finger transition as an
                // abandon rather than let a hold's clock keep ticking with only a phantom finger
                // on the tile it started on, which could otherwise complete a purchase (or a ship
                // launch) the player never asked for.
                abandonGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleCabinetTouch(event: MotionEvent, shell: CabinetShell): Boolean {
        // The tube is collapsing and the overlay is on its way out, but renderCabinet()
        // still runs — and rebuilding the button rects is what makes them hit targets. A
        // stray tap landing on PLAY in the ~150ms after EXIT used to take ¥100 and start a
        // run that the next frame's closeCabinet() threw away with the shell. The event is
        // consumed rather than passed down: the hangar underneath is not visible yet, and
        // a tap that lands on it through a dying overlay is not a tap the player aimed.
        if (cabinetPoweringDown) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shell.screen == CabinetScreen.PLAY) {
                    // The stick plants its origin here and reads zero until the finger
                    // actually travels, so pressing down can no longer move the ship.
                    // Double-tap is resolved on RELEASE instead — see ACTION_UP.
                    cabinetInput?.down(event.x, event.y)
                    return true
                }

                dispatchShellButton(cabinetShellRenderer?.hitTest(event.x, event.y), shell)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cabinetInput?.move(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                // A release that never left the dead zone is a tap. Two inside the
                // window pause the run — resolved here rather than on touch-down, so a
                // deliberate double-tap never drives the ship at all.
                val wasTap = cabinetInput?.up() ?: false
                if (wasTap && shell.screen == CabinetScreen.PLAY) {
                    val now = System.currentTimeMillis()
                    if (now - cabinetLastTapTime < DOUBLE_TAP_MS) {
                        shell.onPause()
                        cabinetLastTapTime = 0L
                    } else {
                        cabinetLastTapTime = now
                    }
                } else {
                    // A drag breaks the chain. Only taps used to touch this clock, so
                    // tap - drag - tap still read as a double-tap and paused the run
                    // mid-manoeuvre; the shipped code stamped on every ACTION_DOWN and
                    // got that property for free. Cleared rather than stamped, so the
                    // release that ends a drag cannot itself start a new pair.
                    cabinetLastTapTime = 0L
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cabinetInput?.cancel()
                return true
            }
        }
        return true
    }

    /** No credit banked: take the coin now so PLAY still works in one tap.
     *  @return true iff the coin was taken AND the run started. */
    private fun chargeAndPlay(shell: CabinetShell): Boolean {
        if (!takeCoin()) return false
        return shell.onPlay()
    }

    private fun chargeAndAgain(shell: CabinetShell): Boolean {
        if (!takeCoin()) return false
        return shell.onAgain()
    }

    private fun chargeAndReplay(shell: CabinetShell): Boolean {
        if (!takeCoin()) return false
        return shell.onReplay()
    }

    /**
     * Take 100Y and bank a credit.
     *
     * state.actualYen MUST be resynced here. Other handlers compute their cost against
     * it and write the result straight back to persistence, so leaving it stale-high
     * silently refunds the coin and can approve a purchase the real balance cannot
     * cover. Every other yen mutation in this file syncs the same way.
     *
     * @return true iff 100Y was available and has been converted into a credit.
     */
    private fun takeCoin(): Boolean {
        if (persistence.getYen() < COIN_COST) return false
        persistence.setYen(persistence.getYen() - COIN_COST)
        state.actualYen = persistence.getYen()
        persistence.addCabinetCredit()
        return true
    }

    /**
     * Reset every gesture-tracking field with no purchase, no launch, no flip — shared by
     * ACTION_CANCEL and ACTION_POINTER_UP, both of which must abandon whatever is in flight
     * rather than complete it. Mirrors the ACTION_UP tail's resets, plus the ACTION_UP SHIP_DRAG
     * release branch's stopHaloRumble()/isDraggingShip reset, minus the tap dispatch and the
     * launch check — an abandoned gesture must never buy, launch, or flip anything.
     */
    private fun abandonGesture() {
        cancelHold()
        suppressFlipIndex = -1
        gestureAbandoned = true
        isDragging = false
        activeSwipe = SwipeTarget.NONE
        shipDragPossible = false
        spinButtonHeld = false
        stopHaloRumble()
        stopHoldRumble()
        state.isDraggingShip = false
    }

    /**
     * Abandon the in-flight hold with no purchase. Every release path — a plain release, a
     * drag past slop, [abandonGesture], and the surface being destroyed — funnels through here
     * so the fill's exit fade starts from whatever width it had actually reached, rather than
     * each call site reimplementing "was there even a hold to cancel".
     */
    private fun cancelHold() {
        if (storeHold.isActive) {
            beginHoldExit(storeHold.index, storeHold.progress, success = false)
        }
        storeHold.cancel()
        heldUpgradeIndex = -1
    }

    /**
     * Whether tile [index] can still be bought — a maxed or unaffordable tile starts no fill.
     * The designed behaviour: maxed, unaffordable and the NG+ tile start no fill at all, and
     * tapping still flips them — a press that cannot buy falls through to a plain tap. The NG+
     * tile never reaches this — it is tracked separately as `crystalTileRect` and is never in
     * `renderer.upgradeRects` — so this only needs the same guards `handleUpgradeTap` applies at
     * purchase time, checked here too so a hold never starts on a tile it cannot complete.
     */
    private fun canStartHold(index: Int): Boolean {
        val ids = StoreUpgradeDefinitions.purchasableIds
        if (index !in ids.indices) return false
        val currentLevel = persistence.getUpgradeLevel(ids[index])
        if (currentLevel >= 5) return false
        return state.actualYen >= PersistenceManager.getUpgradeCost(currentLevel)
    }

    /**
     * Start the hold-fill's exit, so it is never seen to vanish. [progress] is the
     * width the fade holds through its decay: 1f (full) on a completed purchase, whatever the
     * fill had actually reached on an early release or a cancel. [success] tints the exit as a
     * brief completion flash rather than a plain fade. A no-op below zero width — nothing was
     * ever visible, so there is nothing to fade.
     */
    private fun beginHoldExit(index: Int, progress: Float, success: Boolean) {
        if (index < 0 || progress <= 0f) return
        state.storeHoldExitIndex = index
        state.storeHoldExitProgress = progress
        state.storeHoldExitAlpha = 1f
        state.storeHoldExitSuccess = success
        storeHoldExitTimer = STORE_HOLD_EXIT_DURATION
    }

    /** Whether the screen is rotated — the one place the touch side asks. */
    private val landscape: Boolean get() = RoomAnchor.isLandscape(screenWidth, screenHeight)

    /**
     * True exactly when [HangarRenderer.drawPanelLayer] is putting a scrim on screen this frame
     * — gated on [landscape] and the intro cinematic the same way that layer is, and asking
     * [HangarState.visiblePanel] the identical identity question it draws from. Two separate
     * derivations of "is a panel showing" is what let the input side drift from the draw side in
     * the first place — this is the one place either side asks.
     */
    private fun panelScrimShown(): Boolean =
        landscape && !state.introCinematic && state.visiblePanel() != null

    /**
     * The crystal orb is in flight and the screen belongs to it — no interaction, of any kind,
     * from any input device.
     *
     * One predicate rather than the three inline copies it replaces (the ACTION_DOWN gate,
     * [handlePanelTap], and now the focus pass), for the same reason [HangarState.visiblePanel]
     * exists: the moment two derivations of "is input refused" live side by side, one of them
     * starts answering a slightly different question.
     *
     * The phase comparison itself now lives on [HangarState.crystalRevealInFlight], because the
     * DRAW side has to ask it too — the room hands its Astro dot to
     * `HangarRenderer.drawCrystalReveal` for exactly these two phases. Same rule, one definition;
     * this stays as the input side's name for it.
     */
    private fun crystalRevealFlying(): Boolean = state.crystalRevealInFlight()

    /**
     * Whether the reveal has somewhere to fly to yet.
     *
     * The ordering this exists to settle. `HangarState.armCrystalRevealPanel` opens the SHOP
     * panel on the frame the store page settles, and the panel publishes the crystal tile's rect
     * when it DRAWS — so arming and launching in the same tick would sample [CrystalOrbPath]
     * against a rect that does not exist yet. The orb would be at its source for that one frame
     * either way (`position(0, …)` returns the source exactly, whatever the destination), so this
     * is not a visible bug today; it is one bad frame away from being one, and a destination that
     * is only correct because the first sample happens not to use it is not a destination.
     *
     * So the gate holds for exactly one frame in landscape: arm, let the panel layer publish,
     * then fly — ~8ms at 120Hz, against a 1.4s flight and a 0.15s panel fade that is long done
     * before the orb arrives. The launch sequence and the intro cinematic both stop the panel
     * drawing and both end by themselves, so the reveal simply resumes when they do.
     *
     * [HangarPhase.CODEX] is the one phase that suppresses the panel layer and does NOT end by
     * itself — it closes only on a tap or pad activate. It still cannot stall this gate: reaching
     * it needs the SLOT panel's hatch tapped five times, and arming force-closes SLOT onto SHOP on
     * every settled frame while GLOW is waiting for [revealDestinationReady] (see
     * `armCrystalRevealPanel`'s unconditional `togglePanel`), so that sequence can never
     * accumulate; and once ORB_TRAVEL/FLASH begin, every input that could open it is already
     * refused by [crystalRevealFlying]. So CODEX is unreachable for the whole window this gate
     * covers — and even granting a way in nobody has found, the player can always dismiss it
     * themselves, so there is still no stall.
     *
     * PORTRAIT IS UNTOUCHED, by the first disjunct alone: the board is in the room there, the
     * expression short-circuits before it looks at any draw state, and the reveal fires on
     * exactly the frame it always has.
     *
     * Asked of `state.landscape` rather than this view's own [landscape] deliberately, even though
     * the two always agree: `HangarState.armCrystalRevealPanel` — the half that OPENS the panel —
     * is pure and can only ask the state. Two predicates that disagreed by a frame during a
     * rotation would be a reveal that waits for a panel nobody opened.
     *
     * internal: a test seam, like [state] and [renderer] themselves. The ordering it encodes is
     * the whole point of this gate, and a private one could only be observed by driving the entire
     * tick.
     */
    internal fun revealDestinationReady(): Boolean =
        !state.landscape || !renderer.crystalTileRect.isEmpty

    /**
     * Screen X of the CURRENT room's left edge. The draw side's [HangarRenderer] page origin for
     * the page being shown, so a rect published room-local lands back where it was drawn.
     */
    private fun roomOriginX(): Float =
        RoomAnchor.pageOriginX(
            state.currentPage, state.currentPage, state.pageScrollOffset,
            screenWidth, roomWidth, landscape
        )

    /**
     * Touch X → the current room's local X.
     *
     * The bar and shop pages draw inside `canvas.translate(-xOffset, 0f)` and publish their tap
     * rects (codex book, upgrade tiles, spin button, hatch, paper, mute toggles) in that
     * room-local space, so every hit test against one of them goes through here. Below the gate
     * the room origin is 0 and the page is at rest whenever a tap is dispatched, so this is the
     * identity — phone hit testing is bit-for-bit unchanged.
     *
     * The shipyard page deliberately does NOT use this: its hit tests are all measured from
     * screenWidth / 2. The draw side (`HangarRenderer.drawShips`/`drawLaunchRail`/the room frame
     * on page 1) uses the same one width — `RoomAnchor.pageWidth(1, …)` — for both clipping and
     * centring, so the drawn ship's centre and the screen's centre are the same point. That is
     * true because the launchpad is the one page landscape leaves full-screen (`pageWidth(1, …)
     * == screenWidth` there), not merely because its origin is flush (`RoomAnchor.anchorX(1) ==
     * 0` makes the page's left edge flush; it says nothing about where its centre falls, and
     * before this file's companion fix the draw side used the narrower ROOM width here while the
     * hit test stayed at screenWidth / 2 — the two drifted apart under rotation on a device below
     * the sw600 gate).
     */
    private fun roomX(screenX: Float): Float =
        RoomAnchor.toRoomX(
            screenX, state.currentPage, state.pageScrollOffset,
            screenWidth, roomWidth, landscape
        )

    /**
     * The inverse of [roomX]: a room-local X back in screen space.
     *
     * Written down once rather than guessed per call site. Focus targets need it in both
     * directions — the published rect must be screen space so FocusNavigator can compare it
     * against the nav row, and the tap point must be screen space because the tap handlers
     * convert with roomX themselves and would otherwise convert twice.
     */
    private fun roomLocalToScreen(roomLocalX: Float): Float = roomLocalX + roomOriginX()

    /**
     * Whether the store's published rects — the upgrade tiles, the crystal tile and every rect the
     * machine publishes — are in SCREEN space rather than the shop room's local space.
     *
     * One question, asked in one place, answered by where the draw side actually put them:
     * `StorePageRenderer.draw` skips both the board and the machine in landscape (and retracts
     * their rects), so the only thing that can have published any of them there is a panel, and a
     * panel draws on the screen. In portrait the room is the only publisher. There is no third
     * case: with no panel open in landscape nothing is published at all, and an empty rect matches
     * no point in either space.
     *
     * Not `openPanel == SHOP`: that is true of the tiles and false of the machine, and the machine's
     * rects are the ones that spend 100¥.
     */
    private fun storeRectsInScreenSpace(): Boolean = landscape

    /**
     * The point a store hit test must use, in the space the store's rects were published in.
     *
     * Every store hit test crosses through here — ACTION_DOWN's spin-button and tile-hold checks,
     * ACTION_MOVE's drift check and [handleStoreTap] — so DOWN, MOVE and UP cannot disagree about
     * which space they are in. The store's hold-to-buy compares the same rects across all three,
     * and a helper applied to two of them would fill one tile and buy another.
     *
     * Y is returned unchanged: rooms tile horizontally, so no store rect has ever needed a vertical
     * transform. It travels as half of the point rather than being left to each call site to carry,
     * so "the store's hit point" is one value.
     *
     * internal: a test seam. The two spaces coincide on the shop page at rest — the page's own
     * origin is 0 in landscape and the scroll is zeroed before any tap is dispatched — so
     * `ShopPanelTest` drives them apart with a scroll offset to pin the rule rather than the
     * coincidence.
     */
    internal fun storeHitPoint(x: Float, y: Float): Pair<Float, Float> =
        if (storeRectsInScreenSpace()) x to y else roomX(x) to y

    /**
     * The inverse of [storeHitPoint]: a published store rect's X back in the space the tap handlers
     * take. The controller path has no real touch point, so it activates a target at its own rect's
     * centre and must undo exactly the transform [handleStoreTap] is about to apply.
     */
    private fun storeRectToTapX(rectX: Float): Float =
        if (storeRectsInScreenSpace()) rectX else roomLocalToScreen(rectX)

    private fun handleTap(x: Float, y: Float) {
        when (state.phase) {
            HangarPhase.BROWSING -> {
                // Check nav label taps FIRST: [CREW] [LAUNCH] [SHOP] at bottom. The nav row is
                // drawn ABOVE the panel scrim (HangarRenderer.drawPanelLayer's own doc: "leaving
                // the hangar must not require closing a panel first"), so it must stay reachable
                // whether a panel is open, mid-close, or absent — resolving it before
                // handlePanelTap is what makes a drawn-lit label actually hittable, rather than
                // being swallowed by the backdrop-closes catch-all or the closingPanel refusal
                // gate inside handlePanelTap. `CrewPanelTest` pins that the panel box and its
                // button stay clear of this tap band, so the two can never compete for the same
                // point — only which one gets first refusal of everything else.
                val labelY = HangarMetrics.navLabelY(screenHeight)
                // Nav labels are hidden during the intro cinematic — ignore their tap zone.
                if (!state.introCinematic &&
                    y > HangarMetrics.navBandTop(screenHeight) && y < labelY + 15f) {
                    val centerX = screenWidth / 2f
                    // Must match HangarRenderer.drawPageIndicator's content-anchored spacing,
                    // or the side labels' tap zones drift off the drawn labels on wide screens.
                    val spacing = layout.content.width * 0.25f
                    for (i in 0..2) {
                        val labelCenterX = centerX + (i - 1) * spacing
                        if (abs(x - labelCenterX) < spacing * 0.4f && i != state.currentPage) {
                            navigateToPage(i)
                            return
                        }
                    }
                }

                if (handlePanelTap(x, y)) return

                when (state.currentPage) {
                    0 -> handleBarTap(x, y)
                    1 -> handleShipyardTap(x, y)
                    2 -> handleStoreTap(x, y)
                }
            }
            HangarPhase.CODEX -> {
                // Tap anywhere closes codex
                state.phase = HangarPhase.BROWSING
            }
            else -> {}
        }
    }

    private fun navigateToPage(targetPage: Int) {
        val oldPage = state.currentPage
        state.pageScrollOffset = (oldPage - targetPage) *
            RoomAnchor.stride(screenWidth, roomWidth, landscape)
        state.pageVelocity = 0f
        if (state.introCinematic) {
            // The one allowed hop: the swell rises while the drone fades out. The same pair the
            // swipe release plays, so the intro sounds identical whichever way it is made.
            SoundManager.playIntroSwell(context)
            SoundManager.stopAmbient(fadeOutMillis = 1200)
        } else {
            SoundManager.playSFX("sfx_ui_swipe")
            SoundManager.playAmbient(getAmbientForPage(targetPage))
        }
        // setPageTarget is the only writer of currentPage here
        // — it used to be written directly above too, redundantly, which left a window
        // between that write and this call where currentPage already read as targetPage but an
        // open panel belonging to oldPage had not yet been handed to closingPanel. A render
        // frame landing in that window saw a panel neither page owned and cut its scrim for one
        // frame — the instant disappearance the fade exists to prevent. Deleting the redundant
        // write makes this call the one and only place currentPage changes, so that window
        // cannot exist.
        state.setPageTarget(targetPage)
    }

    /**
     * The landscape panel layer's share of a tap: its buttons, its items, and its backdrop.
     * Returns true when it consumed the tap.
     *
     * The panel publishes its rects in SCREEN space, so this bypasses [roomX] entirely rather
     * than converting a point that was never room-local. And it reads only what
     * [HangarRenderer.drawPanelLayer] actually drew this frame: a button that is not on screen
     * has no rect here, which is what makes portrait, the intro cinematic and a page that owns no
     * panel untappable without a second gate that could disagree with the drawn one.
     *
     * Resolved AFTER the nav row, matching the nav row's own draw order (drawn ABOVE the scrim —
     * see `HangarRenderer.drawPanelLayer`'s doc). A tap that lands on a lit nav label is claimed
     * by `handleTap` before this function ever runs, so the panel — open, closing, or shut — can
     * never swallow the way out of the hangar (it used to, because
     * this was resolved FIRST). The two only need to agree on tap ownership at all because the
     * panel's box stays clear of the nav row's tap band on every profile — `CrewPanelTest` pins
     * that clearance so the pair cannot silently start overlapping.
     *
     * Gated on `!landscape || state.introCinematic` up front, matching `HangarRenderer.
     * drawPanelLayer`'s own top gate exactly — see [HangarState.visiblePanel]'s doc for why the
     * rest of this reads that same predicate rather than a second one.
     */
    private fun handlePanelTap(x: Float, y: Float): Boolean {
        if (!landscape || state.introCinematic) return false
        // Refused (true), matching the closingPanel gate below rather than differing from it
        // (found in review): crystalRevealPhase's own doc calls this
        // "interaction" with no exception carved out for a tap that reaches here, but returning
        // false used to let it fall through to handleBarTap/handleShipyardTap/handleStoreTap on
        // the room below while the reveal was still playing.
        if (crystalRevealFlying()) return true
        // ONE read of the published layer, held for the whole resolution. Reading the renderer
        // field again further down would be a second sample that a frame boundary can fall
        // between, which is the same class of bug this snapshot exists to remove — a tap could
        // find a card in one read and no box in the next, and close the panel it just hit.
        val drawn = renderer.panelLayer
        // A button is reachable wherever it is drawn — home or tab — regardless of whether a
        // panel is currently visible; an empty list (a page with no buttons, or nothing drawn at
        // all) simply runs zero iterations.
        for ((panel, rect) in drawn.buttons) {
            if (rect.contains(x, y)) {
                togglePanel(panel)
                return true
            }
        }
        // What HangarRenderer.drawPanelLayer is actually drawing this frame — the identical
        // predicate, not a second question ("does this page publish any buttons?") that used to
        // disagree with it on a page that owns no panel of its own (the launchpad) while a
        // panel from a different page was still fading closed over it: nothing here refused the
        // tap, so it fell through to handleShipyardTap underneath a scrim plainly on screen
        // (found in review). Nothing visible means nothing to hit, so an ordinary
        // tap on a panel-less page still falls through to that page's own handler.
        val visible = state.visiblePanel() ?: return false
        // The panel is on its way out — still drawn (HangarRenderer.drawPanelLayer keeps
        // drawing HangarState.closingPanel through its own fade), but not a target the player
        // is aiming for. Mirrors handleCabinetTouch's cabinetPoweringDown gate elsewhere in
        // this file: the tap is consumed rather than let through to whatever the fading scrim
        // still covers — the room underneath included, on a page that owns no panel of its own.
        // The button loop above already ran, so pressing the panel's own button here still
        // reaches togglePanel and reverses the close — this only refuses card/backdrop taps, and
        // any tap on the room below, once the close has begun.
        if (state.closingPanel != null) return true

        val index = PanelGrid.indexAt(drawn.cards, x, y)
        if (index != null) {
            when (visible) {
                HangarPanels.Panel.CREW -> {
                    SoundManager.playSFX("sfx_ui_tap")
                    selectOrFlipPilot(index)
                }
                // The shop's two panels hold the store page's own controls, so the tap goes to the
                // store's own handler at the point it actually landed — one handler for both
                // placements, exactly as the crew panel shares selectOrFlipPilot with the room.
                // It resolves the tile (or the crystal tile, the hatch, a mute toggle, INSERT COIN,
                // the spin button) itself against the rects the panel just published, and plays its
                // own tap sound, so there is no second play here. SLOT publishes the machine as its
                // one item, so `index` is 0 and unused: a machine is not a grid.
                HangarPanels.Panel.SHOP, HangarPanels.Panel.SLOT -> handleStoreTap(x, y)
            }
            return true
        }
        // The SLOT panel publishes only the machine's own frame as its one hit-testable item
        // above (`renderer.panelCardRects`), but the mute toggles' drawn rects poke out past the
        // machine's left/right edges by design — StorePageRenderer.drawSlotMachineAt's
        // muteButtonRadius math puts about 11% of each button's width outside `machineLeft`/
        // `machineRight`. `PanelGrid.indexAt` above misses that sliver, so without this a press
        // there fell straight to the backdrop catch-all below and closed the panel instead of
        // muting. Tested directly here, ahead of the catch-all,
        // rather than published as extra `panelCardRects` entries: SLOT's "index is 0 and
        // unused" contract above stays true, and this reuses `handleStoreTap`'s own rect checks
        // instead of duplicating them.
        if (visible == HangarPanels.Panel.SLOT) {
            val (hx, hy) = storeHitPoint(x, y)
            if (state.audioMuteButtonRect?.contains(hx, hy) == true ||
                state.vibrationMuteButtonRect?.contains(hx, hy) == true
            ) {
                handleStoreTap(x, y)
                return true
            }
        }
        // Inside the panel's own box, but on none of its items: the padding round the grid, a
        // gutter between two cards, or the empty half-row a reflowed board leaves. Dead space —
        // consumed, and nothing happens.
        //
        // This used to fall through to the backdrop rule below and CLOSE the panel, which is what
        // the owner reported on 2026-09-20 as a pilot press sometimes shutting the roster instead
        // of selecting: the pilot grid's gutters are about 16 units wide and its padding 24, so a
        // thumb that landed slightly off a card dismissed the panel. The owner's rule is that only
        // the button, a tap OUTSIDE the panel, or a swipe closes it — so the box, not the cards,
        // is the boundary the backdrop rule is allowed to fire outside of.
        //
        // Read from the renderer rather than recomputed here for the same reason the cards are:
        // this is the box that was actually drawn, so the scrim a player sees and the region that
        // swallows a tap cannot drift apart.
        //
        // A NULL box here means the state holds a panel open that no frame has drawn yet — the
        // gap between `togglePanel` setting it and the next render publishing it. Refused rather
        // than closed: closing is what the renderer and the state disagreeing used to cost the
        // player, and a panel that is on its way IN is the last thing a tap should dismiss. It
        // costs at most one frame of an un-dismissable panel, against a panel that shuts itself.
        val box = drawn.box ?: return true
        if (box.contains(x, y)) return true
        // A tap on the dimmed backdrop closes — through the same fade a button press starts.
        togglePanel(visible)
        return true
    }

    /**
     * The press half of a tap on an open panel's contents — the counterpart of [handlePanelTap],
     * which owns the release.
     *
     * Only the shop's two panels have anything to press: a store tile is bought by HOLDING it and
     * the spin button auto-repeats while held, so both need the press, not just the release. The
     * crew panel has neither — a pilot card is a tap — so it does nothing here and its release path
     * is unchanged.
     *
     * Reached only from the ACTION_DOWN gate, i.e. only while [panelScrimShown] is true, so this
     * never competes with the room's own press handling below it. A panel already fading closed is
     * refused, mirroring [handlePanelTap]'s own closingPanel gate: what is on its way out is not
     * something the player is aiming at.
     */
    private fun beginPanelPress(x: Float, y: Float) {
        if (state.closingPanel != null) return
        val (hx, hy) = storeHitPoint(x, y)
        when (state.visiblePanel()) {
            HangarPanels.Panel.SHOP -> {
                for ((index, rect) in renderer.upgradeRects.withIndex()) {
                    if (rect.contains(hx, hy)) {
                        // Maxed and unaffordable tiles start no fill at all, exactly as in the
                        // room: the release then falls through to a plain tap, which flips the
                        // card that explains the cost or the cap.
                        if (canStartHold(index)) {
                            storeHold.start(index)
                            heldUpgradeIndex = index
                        }
                        break
                    }
                }
            }
            HangarPanels.Panel.SLOT -> {
                if (renderer.spinButtonRect.contains(hx, hy)) {
                    spinButtonHeld = true
                    // Press only — auto-spin re-enters handleSlotSpin from updateBrowsing while the
                    // finger stays down, and a tap per spin would be a continuous rattle.
                    buttonTap()
                }
            }
            else -> {}
        }
    }

    /**
     * Opens [panel], or begins closing it if it is the one already open — see
     * [HangarState.togglePanel] for the three-way logic (open / start closing / reverse a
     * close already in flight).
     */
    internal fun togglePanel(panel: HangarPanels.Panel) {
        state.togglePanel(panel)
        SoundManager.playSFX("sfx_ui_tap")
    }

    internal fun handleBarTap(x: Float, y: Float) {
        SoundManager.playSFX("sfx_ui_tap")

        // The bar page draws — and publishes its rects — in room-local space.
        val rx = roomX(x)

        // The codex book on the counter is set dressing only. The maintenance hatch on the slot
        // machine is the one way in (owner, 2026-08-10) — it is the secret the bar's own hints
        // point at, and a second door on the counter gave it away.

        renderer.getPilotGridIndex(rx, y)?.let { selectOrFlipPilot(it) }
    }

    /**
     * Select a pilot, or turn their card over if they are already the selected one.
     *
     * One definition for both placements of the grid: the in-room one the room draws in portrait,
     * and the landscape CREW panel's. The same idiom `activateShip` and `beginLaunch` follow —
     * the second caller shares the action rather than copying it.
     */
    private fun selectOrFlipPilot(pilotIndex: Int) {
        PilotDefinitions.getPilotByIndex(pilotIndex) ?: return
        if (!state.isPilotUnlocked(pilotIndex)) return
        if (pilotIndex == state.selectedPilotIndex) {
            // Already selected — turn the card over to show the passive, or turn it back if it is
            // already over. A second tap means "put it back", same as the store.
            state.togglePilotFlip(pilotIndex)
        } else {
            selectPilotAndStartWalk(pilotIndex)
        }
    }

    private fun selectPilotAndStartWalk(pilotIndex: Int) {
        val oldSelectedIndex = state.selectedPilotIndex
        // Reached only from handleBarTap, so the NPC's normalized x is the BAR's band: hand it to
        // the same page-0 derivation getPilotWorldTarget uses, or the pilot walker jumps to a
        // position outside the bar's own room — off its stride on a wide screen, and into the
        // starfield left of the right-anchored crew room on a rotated one.
        val npcWalker = state.npcWalkers.find { it.pilotIndex == pilotIndex }
        if (npcWalker != null) {
            state.pilotX = state.pilotWorldX(0, npcWalker.x)
        } else {
            state.pilotX = state.getPilotWorldTarget(state.currentPage)
        }
        state.pilotTargetX = state.getPilotWorldTarget(state.currentPage)
        state.pilotWalking = state.pilotX != state.pilotTargetX
        state.selectedPilotIndex = pilotIndex
        if (oldSelectedIndex != pilotIndex) {
            val oldPilot = PilotDefinitions.getPilotByIndex(oldSelectedIndex)
            if (oldPilot != null && state.isPilotUnlocked(oldSelectedIndex)) {
                state.pendingNPCAdds.add(WalkerNPC(
                    pilotIndex = oldSelectedIndex,
                    color = oldPilot.color,
                    x = 0.9f,
                    targetX = kotlin.random.Random.nextFloat() * 0.8f + 0.1f,
                    walking = true,
                    idleTimer = 0f
                ))
            }
            state.pendingNPCRemoves.add(pilotIndex)
        }
    }

    /**
     * One frame's worth of focus targets: the nav row, then whatever the current page is showing.
     *
     * Republished every frame, from the same pass that drew what they point at, so a rect can
     * never outlive the layout that produced it. The braces enclose the page content as well as
     * the nav row.
     *
     * internal, and lifted out of [render], because it is the one seam a focus test can reach:
     * Robolectric's `lockCanvas()` returns null, so `render()` itself cannot run in a test, and a
     * parity test that instead feeds hand-made rects to the publishers proves the publishers work
     * and says nothing about whether the VIEW publishes the right rects — which is exactly the
     * class of defect this seam exists to catch. `PanelFocusParityTest` draws a real frame with
     * `renderer.render` and then calls this, in the same order and on the same state production
     * does.
     */
    internal fun publishFocusPass() {
        hangarFocus.begin()
        HangarRenderer.publishNavTargets(
            hangarFocus, state.currentPage,
            screenWidth, screenHeight, layout.content.width,
            state.introCinematic
        ) { navigateToPage(it) }
        publishPageFocusTargets()
        hangarFocus.commit()
    }

    /**
     * Whichever page is showing publishes its own targets into the one hangar registry, from
     * inside the same begin()/commit() pass that published the nav row.
     */
    private fun publishPageFocusTargets() {
        // The landscape panel layer first: its buttons are drawn OVER the room and stay reachable
        // whatever the room is doing, and while a panel's scrim is up the room beneath it is as
        // unreachable to the pad as it is to a finger.
        if (publishPanelFocus()) return
        when (state.currentPage) {
            0 -> publishBarFocus()
            1 -> publishShipyardFocus()
            2 -> publishStoreFocus()
        }
    }

    /**
     * The landscape panel layer's share of the focus pass.
     *
     * Reads only what [HangarRenderer.drawPanelLayer] actually published this frame, exactly as
     * [handlePanelTap] does: portrait, the intro cinematic and a page that owns no panel all leave
     * `panelButtonRects` empty, so they publish nothing here by the same fact that makes them
     * untappable, rather than through a second gate that could disagree.
     *
     * Three states, matching [handlePanelTap]'s own three branches:
     *  - **shut** — the buttons alone, which is the pad's only way in. Before this the landscape
     *    crew page published nothing but the nav row: the room draws no grid there, so a pad user
     *    had no pilot targets AND no way to open the panel holding them.
     *  - **open** — the buttons plus the panel's contents.
     *  - **closing** — the buttons alone again. `handlePanelTap` refuses a closing panel's cards
     *    and its backdrop while its button still reverses the close, so the pad gets that same
     *    shape, and the ring falls back onto the button through [FocusRegistry.setDefault].
     *
     * @return true when a panel's scrim is on screen, in which case the caller skips the room's own
     *   publisher — asked through [panelScrimShown], the one predicate the ACTION_DOWN gate and the
     *   draw path already share, so the pad and the finger cannot drift about what is covered.
     */
    private fun publishPanelFocus(): Boolean {
        if (!landscape || state.introCinematic) return false
        val flying = crystalRevealFlying()
        val visible = state.visiblePanel()
        // Only a panel that is fully in play offers its contents — see the three states above.
        val open = visible?.takeIf { state.closingPanel == null && !flying }
        // CREW's grid exists ONLY in the panel in landscape, so the panel publishes it. The shop's
        // two panels instead hold the STORE's own controls, which the store's own publisher already
        // puts out in screen space (storeRectsInScreenSpace) under the `store:` ids the pad's
        // hold-to-buy reads off the focused id — republishing those as panel cards would stack two
        // targets on every tile and break buying from the board.
        // One read of the published layer, for the reason handlePanelTap takes one too: the cards
        // and the buttons below must come from the same frame as each other.
        val drawn = renderer.panelLayer
        val cards = if (open == HangarPanels.Panel.CREW) drawn.cards else emptyList()

        publishPanelTargets(
            hangarFocus, cards, drawn.buttons, enabled = !flying
        ) { x, y -> handlePanelTap(x, y) }

        val firstItemId = when (open) {
            HangarPanels.Panel.CREW -> panelCardId(0).takeIf { cards.isNotEmpty() }
            // SHOP/SLOT do NOT go through publishPanelTargets/handlePanelTap at all — this hands
            // their contents to the STORE's own publisher, with `onTap = handleStoreTap`, bypassing
            // handlePanelTap entirely. That is safe today only because these targets are published
            // NOT AT ALL in the refusing states: `open` above is null while the panel is closing or
            // the crystal reveal is flying, so this branch simply never runs then — non-publication,
            // not inheritance, is what keeps SHOP/SLOT in step with those gates. A gate added to
            // handlePanelTap later will cover panel:* cards and panelbtn:* buttons for free but will
            // silently miss the shop tiles; such a gate must be added here too.
            HangarPanels.Panel.SHOP, HangarPanels.Panel.SLOT -> publishStoreFocus()
            null -> null
        }

        // Where the ring goes when what it was holding disappears: the panel's own button. Opening
        // and closing both destroy targets, and without a default named here the ring would simply
        // go out — commit()'s fallback needs somewhere to fall TO. Null outside the landscape
        // layer, which is what it has always been.
        //
        // Filtered to THIS page's own buttons, because `visible` need not belong to it: a panel
        // fading shut is drawn wherever the player has gone (HangarState.closingPanel), so a CREW
        // panel closing over the shop page would otherwise name `panelbtn:CREW` — an id nothing
        // published there — and the fallback would find nothing at all.
        //
        // A page that owns no panel at all (the launchpad, `panelsOn(1) == emptyList()`) falls back
        // one level further, to that page's own nav label — published every frame by
        // `publishNavTargets` regardless of the panel layer, so it is always there to land on. Without
        // this, a panel fading shut over the launchpad (the only way this layer runs there at all)
        // left the default null and the ring simply went out for the ~0.15s the fade takes.
        val page = HangarPanels.panelsOn(state.currentPage)
        hangarFocus.setDefault(
            (visible?.takeIf { it in page } ?: page.firstOrNull())?.let { panelButtonId(it) }
                ?: "nav:${state.currentPage}"
        )
        // ...and opening moves it INTO the panel, on that one frame only. The button keeps its id
        // when it slides out to become the tab, so nothing about opening invalidates the focused id
        // and the fallback above cannot do this for us. Guarded on the identity CHANGING so a pad
        // player who deliberately walks the ring out to the nav row is not dragged back every
        // frame. Invisible to a touch player: FocusRingRenderer draws nothing outside
        // InputMode.DIRECTIONAL.
        if (visible != lastFocusedPanel) {
            lastFocusedPanel = visible
            if (firstItemId != null) hangarFocus.focusedId = firstItemId
        }
        return panelScrimShown()
    }

    /** @return the id of the first target published, for the ring to land on. */
    private fun publishStoreFocus(): String? {
        // The focus registry works in screen space, so room-local rects need the room's origin
        // added and panel rects — already screen space — need nothing. Same decision as
        // storeHitPoint, asked the same way, so the ring lands on what a finger would hit.
        val originX = if (storeRectsInScreenSpace()) 0f else roomOriginX()
        return StorePageRenderer.publishStoreTargets(
            hangarFocus,
            renderer.upgradeRects,
            renderer.storeButtonRects,
            state.audioMuteButtonRect,
            state.vibrationMuteButtonRect,
            // Nothing is published for the hatch while it is shut — the ring finding it is exactly
            // what gave the secret away. Once open, the paper sticking out is what a finger taps,
            // so it is what a pad reaches too.
            state.paperRect.takeIf { state.hatchOpen },
            originX
        ) { x, y -> handleStoreTap(x, y) }
    }

    private fun publishBarFocus() {
        // Landscape has no in-room grid at all — the CREW panel owns it, publishes it in SCREEN
        // space, and [publishPanelFocus] is what turns those rects into targets. Returning here is
        // not merely an optimisation over an empty list: everything below assumes the rects it is
        // handed were drawn inside the room's translate, and handing it the panel's is the defect
        // this task fixes (a room origin added to a screen-space rect, then activated through a
        // `getPilotGridIndex` that returns null in landscape).
        if (landscape) return
        // The bar draws in room-local space; the origin puts its rects back on the screen.
        val originX = roomOriginX()
        BarPageRenderer.publishPilotTargets(hangarFocus, renderer.pilotCardRects, originX) { x, y ->
            handleBarTap(x, y)
        }
    }

    private fun publishShipyardFocus() {
        HangarRenderer.publishShipyardTarget(
            hangarFocus,
            centerX = screenWidth / 2f,
            shipY = state.shipRestingY,
            hitSize = SHIP_HIT_SIZE
        ) { activateShip() }
    }

    /**
     * OK on the ship: buy it when it is not owned, fly it when it is.
     *
     * The purchase re-enters handleShipyardTap at the ship's own centre, so the yen check, the
     * purchase sound and the haptic knock are the tap path's rather than a second copy.
     */
    private fun activateShip() {
        if (state.padLaunchActive) return
        if (!state.isShipUnlocked(state.selectedShipIndex)) {
            handleShipyardTap(screenWidth / 2f, state.shipRestingY)
            return
        }
        if (canLaunchNow()) startPadLaunch()
    }

    /**
     * isDraggingShip is set for the whole rise: it stops the snap-back pulling the ship back down
     * every frame, and it is the same flag that arms the halo rumble under a finger.
     */
    private fun startPadLaunch() {
        state.padLaunchActive = true
        state.padLaunchTimer = 0f
        state.isDraggingShip = true
    }

    /** What the fifth tap on the panel does, reached from the sequence as well. */
    private fun openCodexHatch() {
        state.hatchOpen = true
        if (!state.codexDiscovered) {
            state.codexDiscovered = true
            persistence.setCodexDiscovered()
        }
    }

    /**
     * The carousel, from a controller. Re-enters the peek-ship tap zones, so the scroll
     * animation, the button tap and the corruption-aware visible list are the ones a finger gets
     * — including the silence at either end, where the tap path moves nothing.
     */
    private fun cycleShip(step: Int) {
        if (state.padLaunchActive) return
        val offset = renderer.shipSpacing / 2f + SHIP_HIT_SIZE
        handleShipyardTap(screenWidth / 2f + step * offset, state.shipRestingY)
    }

    /**
     * Whether a launch may start right now — the drag's own condition, minus the zone test.
     * For a focused launch the zone is not a test but the target itself.
     */
    private fun canLaunchNow(): Boolean = state.isReadyToLaunch() && !state.pilotWalking

    /**
     * The one place a launch begins, whether the ship was dragged into the halo or the pad was
     * activated from a controller. A pure extraction of what the drag release used to inline —
     * same statements, same order — so the animation, the sound and the intro-done write happen
     * identically either way.
     */
    private fun beginLaunch() {
        // Snap ship to center and launch
        state.shipDragY = screenHeight / 2f
        state.isDraggingShip = false
        state.phase = HangarPhase.LAUNCHING
        state.launchProgress = 0f
        state.launchPhase = 0
        SoundManager.playSFX("sfx_launch", 0.25f)
        SoundManager.startCombatMusicEarly(context)
        // First launch ever: the intro cinematic is over for good.
        if (state.introCinematic) {
            persistence.setIntroDone()
            state.introCinematic = false
        }
    }

    /**
     * The ships the carousel will actually stop on, in order.
     *
     * Lifted out of handleShipyardTap so the focus targets gate on the same list the tap path
     * walks — a controller that could reach a ship the carousel skips would desync the two.
     */
    internal fun visibleShipIndices(): List<Int> {
        val isCorrupted = StoryStateManager.isCorrupted(persistence)
        return (0 until ShipDefinitions.getShipCount()).filter { i ->
            val s = ShipDefinitions.getShipByIndex(i) ?: return@filter false
            if (isCorrupted && StoryStateManager.isShipDead(persistence, s.id)) return@filter false
            // Intro cinematic: only Scout is visible, so it's the only tappable ship.
            if (state.introCinematic && i != state.selectedShipIndex) return@filter false
            true
        }
    }

    private fun handleShipyardTap(x: Float, y: Float) {
        SoundManager.playSFX("sfx_ui_tap")

        // Ship taps — tap on peek ships to switch, tap on selected to purchase
        val shipY = state.shipRestingY
        val shipHitSize = SHIP_HIT_SIZE
        if (y > shipY - shipHitSize && y < shipY + shipHitSize) {
            val centerX = screenWidth / 2
            val spacing = renderer.shipSpacing

            // Build visible ship list — mirrors HangarRenderer; corruption = no dead ships
            val visibleShips = visibleShipIndices()
            val currentPos = visibleShips.indexOf(state.selectedShipIndex)
                .let { if (it < 0) 0 else it }

            // Tap left/right peek ship to swap. Both get the button tap: moving the carousel is a
            // control answering a press, same as the spin or audio buttons, and it feels the same
            // whether the ship you land on is owned or still locked — the swap happened either
            // way. Fired inside the guards, so a tap at either end of the list, where nothing
            // moves, stays silent.
            if (x < centerX - shipHitSize && currentPos > 0) {
                state.shipScrollOffset = -spacing
                state.selectedShipIndex = visibleShips[currentPos - 1]
                buttonTap()
                return
            }
            if (x > centerX + shipHitSize && currentPos < visibleShips.size - 1) {
                state.shipScrollOffset = spacing
                state.selectedShipIndex = visibleShips[currentPos + 1]
                buttonTap()
                return
            }
            // Tap center ship (purchase)
            if (x > centerX - shipHitSize && x < centerX + shipHitSize) {
                val ship = state.getSelectedShip()
                if (ship != null && !state.isShipUnlocked(state.selectedShipIndex)) {
                    if (state.canUnlockShip(state.selectedShipIndex) &&
                        state.actualYen >= ship.cost) {
                        persistence.addYen(-ship.cost)
                        persistence.unlockShip(ship.id)
                        state.actualYen = persistence.getYen()
                        telemetryManager.logPurchase("ship_purchase", ship.id, 0, ship.cost, state.actualYen)
                        SoundManager.playSFX("sfx_ui_purchase")
                        // The same knock a held upgrade gives. The gesture differs — the shipyard
                        // buys on a tap — but what happened to the wallet does not.
                        purchasePulse()
                    }
                }
                return
            }
        }
    }

    // internal: see the comment on `state`/`renderer` above — this is the test seam for the
    // suppression decision StoreHoldSuppressesFlipTest exercises.
    internal fun handleStoreTap(x: Float, y: Float) {
        // The shop page publishes its rects in room-local space in the room, and in screen space
        // once a landscape panel owns them — see storeHitPoint, which both this and the press
        // paths consult so they cannot drift apart.
        val (rx, ry) = storeHitPoint(x, y)

        // Mute toggle buttons (checked before generic tap sound)
        val audioRect = state.audioMuteButtonRect
        if (audioRect != null && audioRect.contains(rx, ry)) {
            // One button, four states: all → none → combat muted → music muted → all.
            state.audioMode = state.audioMode.next()
            persistence.setAudioMode(state.audioMode)
            SoundManager.applyAudioMode(state.audioMode)
            state.showReadoutMessage(state.audioMode.readoutLabel)
            // The confirmation tap is an effect like any other, so it simply goes quiet in the
            // states that silence effects. No guard needed: silence confirms itself by being silent.
            SoundManager.playSFX("sfx_ui_tap")
            // The haptic is the reason this button still answers in the states that silence it.
            buttonTap()
            return
        }

        val vibRect = state.vibrationMuteButtonRect
        if (vibRect != null && vibRect.contains(rx, ry)) {
            state.vibrationMuted = !state.vibrationMuted
            persistence.setVibrationMuted(state.vibrationMuted)
            isVibrationMuted = state.vibrationMuted
            state.showReadoutMessage(if (state.vibrationMuted) "VIBRATE OFF" else "VIBRATE ON")
            SoundManager.playSFX("sfx_ui_tap")
            // Only on the way back on. Turning vibration off and then buzzing to confirm it would
            // be the button disobeying itself; `buttonTap` would no-op anyway, since
            // isVibrationMuted is already updated above, but the intent is worth being explicit
            // about. This is the one button whose haptic is conditional.
            if (!state.vibrationMuted) buttonTap()
            return
        }

        SoundManager.playSFX("sfx_ui_tap")

        // Maintenance hatch tap (codex secret)
        val hatchRect = state.hatchRect
        if (hatchRect != null && hatchRect.contains(rx, ry) && !state.hatchOpen) {
            state.hatchTapCount++
            if (state.hatchTapCount >= 5) openCodexHatch()
            return
        }

        // Paper tap — open codex
        val paperRect = state.paperRect
        if (state.hatchOpen && paperRect != null && paperRect.contains(rx, ry)) {
            state.phase = HangarPhase.CODEX
            return
        }

        for ((index, rect) in renderer.upgradeRects.withIndex()) {
            if (rect.contains(rx, ry)) {
                // Tap reads, hold buys — but the finger is still down when a hold completes, so
                // the release that follows lands here on the very tile that was just bought.
                // suppressFlipIndex is consumed on read so it can't leak into a later, genuine
                // tap on the same tile. The unconditional sfx_ui_tap near the top of this
                // function already covers this call; no second play here.
                if (suppressFlipIndex == index) {
                    suppressFlipIndex = -1
                    return
                }
                state.toggleStoreCard(index, STORE_FLIP_DURATION)
                return
            }
        }

        // Tile 9 is deliberately absent from upgradeRects — it is tracked separately and cannot be
        // bought in any of its four states, which is what makes "not purchasable" structural rather
        // than a guard someone can delete. Only its two live faces turn over; both "?" states do
        // nothing, because the source requires the two mystery branches stay identical.
        // No second sfx_ui_tap here — same as the upgradeRects loop above, the unconditional play
        // near the top of this function already covers every tap that reaches this point.
        val crystalRect = renderer.crystalTileRect
        if (crystalRect.contains(rx, ry) && renderer.isCrystalTileRevealed(persistence, state)) {
            state.toggleStoreCard(CRYSTAL_TILE_INDEX, STORE_FLIP_DURATION)
            return
        }

        // INSERT COIN — Astro Loop only. Sits on the slot machine's old spin-button rect
        // (see HangarState.insertCoinRect), so this must be checked first or the tap falls
        // through to the spin-button branch below and spins a slot machine that isn't there.
        state.insertCoinRect?.let { rect ->
            if (rect.contains(rx, ry)) {
                if (persistence.getCabinetCredits() > 0 || takeCoin()) {
                    openCabinet()
                }
                return
            }
        }

        // Slot machine spin button
        if (renderer.spinButtonRect.contains(rx, ry)) {
            handleSlotSpin()
            return
        }
    }

    /**
     * The reels have all stopped: reveal the result and pay it out.
     *
     * internal: the test seam for the deferred payout. Everything the player is owed by a spin
     * lands here rather than at roll time, so the reels are never showing one thing while the
     * save already holds another.
     */
    internal fun completeSpin(now: Long) {
        state.isSpinning = false
        state.spinResultTime = now
        val isJackpot = state.reelValues[0] == StorePageRenderer.SYM_ROCKET &&
            state.reelValues[1] == StorePageRenderer.SYM_ROCKET &&
            state.reelValues[2] == StorePageRenderer.SYM_ROCKET
        if (isJackpot) {
            SoundManager.playSFX("sfx_slot_jackpot")
            spinButtonHeld = false  // Let jackpot animation play before resuming auto-spin
        }
        // The free upgrade the jackpot promised at roll time, handed over now the reels have
        // shown it. Read-to-clear so a later spin can never re-grant it.
        state.pendingSpinUpgradeId?.let { id ->
            state.pendingSpinUpgradeId = null
            synchronized(upgradeLock) {
                persistence.setUpgradeLevel(id, persistence.getUpgradeLevel(id) + 1)
            }
        }
        if (state.spinResultYen > 0) {
            if (!isJackpot) SoundManager.playSFX("sfx_slot_win")
            synchronized(upgradeLock) {
                val newYen = state.actualYen + state.spinResultYen
                persistence.setYen(newYen)
                state.actualYen = newYen
            }
        }
    }

    /**
     * @param roll the outcome draw. internal with a default so a test can force a jackpot
     *   without spinning until one turns up.
     */
    internal fun handleSlotSpin(roll: Float = kotlin.random.Random.nextFloat()) {
        if (state.isSpinning) return
        if (state.actualYen < 100) return

        synchronized(upgradeLock) {
            if (state.actualYen < 100) return
            // Deduct cost immediately
            val newYen = state.actualYen - 100
            persistence.setYen(newYen)
            state.actualYen = newYen
        }

        var outcome: Triple<Int, Int, String?> = Triple(-1, 0, null) // default: loss
        val rascalRigged = StoryStateManager.hasLoopedBefore(persistence)
                && persistence.isPilotUnlocked("pilot_rascal")
                && !StoryStateManager.isAstroLoop(persistence)  // Astro Loop is never rigged
        val isCorrupted = StoryStateManager.isCorrupted(persistence)

        // Chosen before the roll, not inside the jackpot branch: the jackpot's odds are derived
        // from what it would hand over. See SlotOdds — a 1,000 yen upgrade is a common win and a
        // 50,000 one is nearly never, which is what holds the house edge steady across a save.
        val pendingPrize = chooseRandomUpgrade()
        val prizeValue = if (pendingPrize == null) SlotOdds.JACKPOT_CASH_PAYOUT
            else PersistenceManager.getUpgradeCost(persistence.getUpgradeLevel(pendingPrize.first))

        // Note: isCorrupted branches below are dead — the if (!isCorrupted) guard above skips
        // the entire when block in corruption runs (outcome stays at the default loss Triple).
        // Thresholds kept here so the non-corruption paths remain readable in one place.
        val jackpotThreshold = when {
            isCorrupted -> 0.005f
            // Whiskers rides the machine, not the economy: a flat, generous rate until she joins.
            state.isWhiskersJackpotEligible() -> 0.10f
            else -> SlotOdds.jackpotThreshold(prizeValue, rascalRigged)
        }
        val diamondThreshold = if (isCorrupted) 0.02f else SlotOdds.diamondThreshold(rascalRigged)
        // Cash-tier band widths, straight from SlotOdds.CASH_TIERS — the same list SlotOddsTest
        // pins the house edge against — so the live machine can't quietly drift from the tested
        // numbers. Diamond is the one exception: its band width stays diamondThreshold above (it
        // widens when Rascal has rigged the machine), and only its payout is read from the tier
        // list. Order is diamond, star, yen, bolt, wrench, matching CASH_TIERS and the symbols.
        val starWidth = SlotOdds.CASH_TIERS[1].first
        val yenWidth = SlotOdds.CASH_TIERS[2].first
        val boltWidth = SlotOdds.CASH_TIERS[3].first
        val wrenchWidth = SlotOdds.CASH_TIERS[4].first
        if (!isCorrupted) when {
            roll < jackpotThreshold -> {
                // Jackpot — free random upgrade or, once all eight are maxed, cash
                if (pendingPrize != null) {
                    state.pendingSpinUpgradeId = pendingPrize.first
                    outcome = Triple(StorePageRenderer.SYM_ROCKET, 0, pendingPrize.second)
                } else {
                    outcome = Triple(StorePageRenderer.SYM_ROCKET, SlotOdds.JACKPOT_CASH_PAYOUT, null)
                }
                // Recruit Whiskers if eligible
                if (state.isWhiskersJackpotEligible()) {
                    val pilot = state.recruitNextPilot()
                    if (pilot != null) {
                        telemetryManager.logPurchase("pilot_jackpot", pilot.id, 0, 0, persistence.getYen())
                        val pilotIndex = PilotDefinitions.pilots.indexOf(pilot)
                        val npcRandom = kotlin.random.Random(System.currentTimeMillis())
                        state.pendingNPCAdds.add(WalkerNPC(
                            pilotIndex = pilotIndex,
                            color = pilot.color,
                            x = npcRandom.nextFloat() * 0.8f + 0.1f,
                            targetX = npcRandom.nextFloat() * 0.8f + 0.1f,
                            walking = false,
                            idleTimer = 1f
                        ))
                        chatSystem.onPilotRecruited(state, pilot.callsign)
                        SoundManager.playSFX("sfx_pilot_recruit", 0.25f)
                    }
                }
            }
            roll < jackpotThreshold + diamondThreshold ->
                outcome = Triple(StorePageRenderer.SYM_DIAMOND, SlotOdds.CASH_TIERS[0].second, null)
            roll < jackpotThreshold + diamondThreshold + starWidth ->
                outcome = Triple(StorePageRenderer.SYM_STAR, SlotOdds.CASH_TIERS[1].second, null)
            roll < jackpotThreshold + diamondThreshold + starWidth + yenWidth ->
                outcome = Triple(StorePageRenderer.SYM_YEN, SlotOdds.CASH_TIERS[2].second, null)
            roll < jackpotThreshold + diamondThreshold + starWidth + yenWidth + boltWidth ->
                outcome = Triple(StorePageRenderer.SYM_BOLT, SlotOdds.CASH_TIERS[3].second, null)
            roll < jackpotThreshold + diamondThreshold + starWidth + yenWidth + boltWidth + wrenchWidth ->
                outcome = Triple(StorePageRenderer.SYM_WRENCH, SlotOdds.CASH_TIERS[4].second, null)
            else -> outcome = Triple(-1, 0, null)
        }

        val (symbol, yenPayout, upgradeName) = outcome

        // Set reel values — all three show the winning symbol (or mixed for loss)
        if (symbol == -1) {
            // Mixed — random non-matching symbols
            val rng = kotlin.random.Random
            state.reelValues[0] = rng.nextInt(StorePageRenderer.SYMBOL_COUNT)
            state.reelValues[1] = rng.nextInt(StorePageRenderer.SYMBOL_COUNT)
            do {
                state.reelValues[2] = rng.nextInt(StorePageRenderer.SYMBOL_COUNT)
            } while (state.reelValues[0] == state.reelValues[1] && state.reelValues[1] == state.reelValues[2])
        } else {
            state.reelValues[0] = symbol
            state.reelValues[1] = symbol
            state.reelValues[2] = symbol
        }

        // Stagger stop times: left first, then middle, then right
        val now = System.currentTimeMillis()
        state.reelStopTimes[0] = now + 800L
        state.reelStopTimes[1] = now + 1100L
        state.reelStopTimes[2] = now + 1400L

        state.spinResultYen = yenPayout
        state.spinResultUpgrade = upgradeName
        state.spinResultSymbol = symbol
        state.spinResultTime = 0  // Set when animation completes
        state.isSpinning = true
        SoundManager.playSFX("sfx_slot_spin")
        persistence.incrementCasinoSpins()

        val symbolNames = state.reelValues.map { StorePageRenderer.getSymbolName(it) }
        telemetryManager.logCasinoSpin(symbolNames, yenPayout, state.actualYen)
    }

    /**
     * Pick the upgrade a jackpot will hand over, or null if all eight are maxed.
     *
     * Chooses only — `completeSpin` does the granting. A jackpot that picked *and* wrote here
     * would raise the tile's level while the reels were still turning, which is the machine
     * answering before it has finished asking.
     */
    private fun chooseRandomUpgrade(): Pair<String, String>? {
        val nonMaxed = SLOT_UPGRADES.filter { persistence.getUpgradeLevel(it.first) < 5 }
        if (nonMaxed.isEmpty()) return null
        return nonMaxed[kotlin.random.Random.nextInt(nonMaxed.size)]
    }

    private val upgradeLock = Any()

    /**
     * Buy one level of [index], the completion of a hold.
     *
     * `handleUpgradeTap` keeps the money handling it always had, including the maxed and
     * unaffordable guards, so a completed hold on a tile that cannot be bought is silently free.
     *
     * internal: see the comment on `state`/`renderer` above — this is the test seam for the
     * suppression decision StoreHoldSuppressesFlipTest exercises.
     */
    internal fun purchaseHeldUpgrade(index: Int) {
        if (index < 0) return
        handleUpgradeTap(index)
        // The finger is still down here — the ACTION_UP that follows falls through to
        // handleStoreTap on this same tile, since the press never crossed the drag slop. Record
        // it unconditionally (even if handleUpgradeTap's guards silently declined the purchase):
        // a completed 0.5s hold is not a tap either way, so its release must not flip the tile.
        suppressFlipIndex = index
        heldUpgradeIndex = -1
    }

    private fun handleUpgradeTap(index: Int) {
        val upgradeIds = listOf("health", "shields", "speed", "damage", "crit", "magnet", "yen_bonus", "salvage")

        // Time Crystal — 9th tile (index 8), auto-equipped when unlocked — no purchase needed
        if (index == 8) return

        if (index >= upgradeIds.size) return

        synchronized(upgradeLock) {
            val id = upgradeIds[index]
            val currentLevel = persistence.getUpgradeLevel(id)
            if (currentLevel >= 5) return  // Already maxed

            val cost = PersistenceManager.getUpgradeCost(currentLevel)
            if (state.actualYen >= cost) {
                val newYen = state.actualYen - cost
                persistence.setYen(newYen)
                persistence.setUpgradeLevel(id, currentLevel + 1)
                state.actualYen = newYen
                telemetryManager.logPurchase("store_upgrade", id, currentLevel + 1, cost, newYen)
                SoundManager.playSFX("sfx_ui_purchase")
            }
        }
    }

    private fun getAmbientForPage(page: Int): String = "bgm_${SoundManager.activeSet}_hangar"

    /** The thing to buzz: the pad being played on, or this device. */
    private fun haptic(): Vibrator = PadHaptics.target(vibrator)

    private fun startHaloRumble() {
        synchronized(hapticsLock) {
            if (hapticsShutDown || isVibrationMuted || isVibratingHalo) return
            isVibratingHalo = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                haptic().vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 50, 50), intArrayOf(0, 40, 0), 0
                ))
            } else {
                @Suppress("DEPRECATION")
                haptic().vibrate(longArrayOf(0, 50, 50), 0)
            }
        }
    }

    private fun stopHaloRumble() {
        synchronized(hapticsLock) {
            if (!isVibratingHalo) return
            isVibratingHalo = false
            PadHaptics.cancelAll(vibrator)
        }
    }

    /**
     * The hum under a filling store tile — deliberately fainter than the halo rumble.
     *
     * The halo announces a thing you have found; this one only confirms a thing you are already
     * watching happen, and it sits under a thumb resting on the tile for a full second. Amplitude
     * 18 against the halo's 40, and a slow 90/90 pulse rather than the halo's 50/50 flutter, so it
     * reads as the tile charging rather than as an alert.
     */
    private fun startHoldRumble() {
        synchronized(hapticsLock) {
            if (hapticsShutDown || isVibrationMuted || isVibratingHold) return
            isVibratingHold = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                haptic().vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, HAPTIC_HOLD_ON_MS, HAPTIC_HOLD_OFF_MS),
                    intArrayOf(0, HAPTIC_HOLD_AMPLITUDE, 0), 0
                ))
            } else {
                @Suppress("DEPRECATION")
                haptic().vibrate(longArrayOf(0, HAPTIC_HOLD_ON_MS, HAPTIC_HOLD_OFF_MS), 0)
            }
        }
    }

    /**
     * A control acknowledging a press — the feel of a small physical button.
     *
     * Deliberately not wired to the pilot or store card flips: turning a card over is reading, not
     * acting on anything, and a buzz on every browse would wear the gesture out.
     */
    private fun buttonTap() {
        synchronized(hapticsLock) {
            if (hapticsShutDown || isVibrationMuted) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                haptic().vibrate(VibrationEffect.createOneShot(HAPTIC_BUTTON_MS, HAPTIC_BUTTON_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                haptic().vibrate(HAPTIC_BUTTON_MS)
            }
        }
    }

    private fun stopHoldRumble() {
        synchronized(hapticsLock) {
            if (!isVibratingHold) return
            isVibratingHold = false
            PadHaptics.cancelAll(vibrator)
        }
    }

    /**
     * Silence the vibrator no matter what the flags believe.
     *
     * The per-rumble stops are flag-guarded, which is right while the view is live — but a flag can
     * be wrong, and at shutdown there is nothing left to correct it. This one always cancels.
     */
    private fun cancelAllHaptics() {
        synchronized(hapticsLock) {
            isVibratingHalo = false
            isVibratingHold = false
            PadHaptics.cancelAll(vibrator)
        }
    }

    /**
     * Money left the wallet: one short, definite knock, clearly above the hum it replaces.
     *
     * Shared by upgrades and ships, so a spend feels the same wherever the player makes it — the
     * shipyard buys on a tap rather than a hold, but the thing that happened is identical.
     */
    private fun purchasePulse() {
        if (isVibrationMuted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            haptic().vibrate(VibrationEffect.createOneShot(HAPTIC_PURCHASE_MS, HAPTIC_PURCHASE_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            haptic().vibrate(HAPTIC_PURCHASE_MS)
        }
    }

    private fun checkPilotRecruitment() {
        if (state.checkPilotUnlockCondition()) {
            val pilot = state.recruitNextPilot()
            if (pilot != null) {
                telemetryManager.logPurchase("pilot_unlock", pilot.id, 0, 0, persistence.getYen())
                val pilotIndex = PilotDefinitions.pilots.indexOf(pilot)
                val npcRandom = kotlin.random.Random(System.currentTimeMillis())
                state.pendingNPCAdds.add(WalkerNPC(
                    pilotIndex = pilotIndex,
                    color = pilot.color,
                    x = npcRandom.nextFloat() * 0.8f + 0.1f,
                    targetX = npcRandom.nextFloat() * 0.8f + 0.1f,
                    walking = false,
                    idleTimer = 1f
                ))
                chatSystem.onPilotRecruited(state, pilot.callsign)
                SoundManager.playSFX("sfx_pilot_recruit", 0.25f)
            }
        }
    }

    fun pause() {
        // Latch first, stop the thread, cancel last. The render thread starts rumbles; cancelling
        // before it is joined leaves the last frame free to start one that nothing will stop.
        hapticsShutDown = true
        running = false
        gameThread?.join()
        cancelAllHaptics()
    }

    fun resume() {
        if (!running) {
            hapticsShutDown = false
            running = true
            gameThread = Thread(this)
            gameThread?.start()
        }
    }

    /**
     * The Android back button, forwarded from MainActivity. Routes to pause rather than
     * quitting outright — a run in progress must never vanish on a button a player can
     * press by reflex. @return true iff the overlay consumed it.
     */
    fun onBackPressedFromActivity(): Boolean {
        val shell = cabinetShell ?: return false
        if (!state.cabinetOpen) return false
        shell.onBack()
        return true
    }

    fun addYenFromRun(amount: Int) {
        // The payout is already banked — MainActivity credits it on the game thread before
        // handing back here, so a failure during the return cannot cost the player the run.
        // This only syncs the display, which is what drives the count-up: displayedYen still
        // holds the pre-run balance, and updateYenDisplay lerps it up to actualYen over 3s.
        state.actualYen = persistence.getYen()
        persistence.incrementRunsSincePilotUnlock()
        if (!StoryStateManager.isCorrupted(persistence)) chatSystem.resetUsedLines()
        val pilotId = persistence.getSelectedPilotId()
        if (persistence.isFreshLoopStart()) {
            persistence.clearFreshLoopStart()
            chatSystem.onFirstLaunch(state)
        } else {
            chatSystem.onDeathReturn(state, pilotId, amount)
        }

        // Corruption: check if all crew are dead and crystal should unlock
        if (StoryStateManager.isCorrupted(persistence)) {
            if (StoryStateManager.shouldUnlockCrystal(persistence)) {
                persistence.setCrystalUnlocked(true)
            }

            // Remove dead pilots from NPC walkers
            val deadPilots = persistence.getDeadPilots()
            if (deadPilots.isNotEmpty()) {
                for (i in 0 until PilotDefinitions.getPilotCount()) {
                    val pilot = PilotDefinitions.getPilotByIndex(i) ?: continue
                    if (deadPilots.contains(pilot.id)) {
                        state.pendingNPCRemoves.add(i)
                    }
                }
            }

            // Crystal reveal: set up pending reveal when crystal unlocked and all crew dead
            if (persistence.isCrystalUnlocked() && StoryStateManager.allCrewDead(persistence)) {
                if (!persistence.isAwaitingCrystalReveal()) {
                    // First time all crew dead + crystal unlocked — set up reveal
                    persistence.setAwaitingCrystalReveal(true)
                }
                // Don't auto-select Astro. initCorruptionState() will handle the rest.
                state.selectedPilotIndex = -1
                val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
                if (specterIndex >= 0) state.selectedShipIndex = specterIndex
            } else {
                // If currently selected pilot/ship is now dead, find first available
                if (!state.isPilotUnlocked(state.selectedPilotIndex)) {
                    val firstAvailable = (0 until PilotDefinitions.getPilotCount()).firstOrNull { state.isPilotUnlocked(it) }
                    if (firstAvailable != null) state.selectedPilotIndex = firstAvailable
                }
                if (!state.isShipUnlocked(state.selectedShipIndex)) {
                    val firstAvailable = (0 until ShipDefinitions.ships.size).firstOrNull { state.isShipUnlocked(it) }
                    if (firstAvailable != null) state.selectedShipIndex = firstAvailable
                }
            }
        }

        checkPilotRecruitment()
    }

    fun resetForReturn(fadeFromWhite: Boolean = false) {
        state.phase = HangarPhase.BROWSING
        state.launchProgress = 0f
        state.launchPhase = 0
        // Reset scroll states
        state.pageScrollOffset = 0f
        state.pageVelocity = 0f
        // A landscape panel is cleared outright, not faded — a run has intervened (a launch, a
        // death, a return from combat), so there is no "page sliding away" for it to fade with;
        // the player is simply back, and a panel left open (or mid-close) from before the run
        // would otherwise reappear pinned at whatever alpha it last had, which a code review
        // caught: open CREW, launch, and it comes back already open at full alpha.
        // Mirrors applyScreenDimensions's own rotation-clear a few lines below in this file.
        state.openPanel = null
        state.closingPanel = null
        state.panelFade = 0f
        // Refresh the music set from current story stage
        SoundManager.activeSet = StoryStateManager.stageMusicSet(persistence)
        // Reset to bar page and start ambient
        state.currentPage = 0
        SoundManager.playAmbient(getAmbientForPage(0))
        val barTarget = state.getPilotWorldTarget(0)
        state.pilotX = barTarget
        state.pilotTargetX = barTarget
        state.pilotWalking = false

        // Reset slot machine result state
        state.spinResultYen = 0
        state.spinResultUpgrade = null
        state.pendingSpinUpgradeId = null
        state.spinResultSymbol = -1
        state.spinResultTime = 0

        // Reset ship drag
        state.isDraggingShip = false
        state.shipDragY = state.shipRestingY

        // Initialize corruption state if we're in the corruption phase
        // This rebuilds NPC walkers (filtering dead pilots) and auto-selects Astro+Specter
        if (StoryStateManager.isCorrupted(persistence)) {
            state.initCorruptionState(persistence)
            state.fadeFromBlackTimer = 3.0f
        } else if (StoryStateManager.isAstroLoop(persistence)) {
            if (persistence.isAstroLoopFirstEntry()) {
                // First entry: default to Astro + Specter
                val astroIndex = (0 until PilotDefinitions.getPilotCount()).firstOrNull { i ->
                    PilotDefinitions.getPilotByIndex(i)?.id == "pilot_astro"
                } ?: -1
                val specterIndex = (0 until ShipDefinitions.getShipCount()).firstOrNull { i ->
                    ShipDefinitions.getShipByIndex(i)?.id == "ship_white"
                } ?: -1
                if (astroIndex >= 0) state.selectedPilotIndex = astroIndex
                if (specterIndex >= 0) state.selectedShipIndex = specterIndex
            } else {
                // Later entries: restore last-flown selection
                val savedShipId = persistence.getSelectedShipId()
                val savedPilotId = persistence.getSelectedPilotId()
                state.selectedShipIndex = (0 until ShipDefinitions.getShipCount())
                    .firstOrNull { ShipDefinitions.getShipByIndex(it)?.id == savedShipId } ?: 0
                state.selectedPilotIndex = (0 until PilotDefinitions.getPilotCount())
                    .firstOrNull { PilotDefinitions.getPilotByIndex(it)?.id == savedPilotId } ?: 0
            }

            // Black fade-in whenever the game already faded itself to black before the
            // handoff (desert farewell on first entry). First entry
            // additionally fires TB's one-shot welcome.
            if (fadeFromWhite) {
                state.fadeFromBlackTimer = 2.0f
                if (persistence.isAstroLoopFirstEntry()) {
                    state.pendingTbWelcome = true
                    persistence.clearAstroLoopFirstEntry()
                }
            }

            // Rebuild the bar roster (mirrors the corruption branch above). Without this,
            // first entry inherits the empty end-of-corruption walker list and the revived
            // crew stays invisible until each pilot is cycled through the grid.
            state.rebuildNpcWalkers()
        } else {
            val savedShipId = persistence.getSelectedShipId()
            val savedPilotId = persistence.getSelectedPilotId()
            val shipIdx = (0 until ShipDefinitions.getShipCount())
                .firstOrNull { ShipDefinitions.getShipByIndex(it)?.id == savedShipId } ?: 0
            val pilotIdx = (0 until PilotDefinitions.getPilotCount())
                .firstOrNull { PilotDefinitions.getPilotByIndex(it)?.id == savedPilotId } ?: 0
            state.selectedShipIndex = shipIdx
            state.selectedPilotIndex = pilotIdx
            state.glitchTimer = 1.0f
        }

        // A debug request from the ARCADE page, delivered by riding onGameOver(0, false).
        // Consuming rather than leaving it pending is deliberate: a request that lingered
        // would fire on some later, unrelated return to the bar.
        val debugRequest = CabinetDebugIntent.consume()
        if (debugRequest != null) {
            openCabinet()
            when (debugRequest.action) {
                CabinetDebugIntent.Action.OPEN -> Unit
                CabinetDebugIntent.Action.PLAY -> cabinetShell?.onPlay()
                // Free, and it skips the walk, the coin and — for phases 1..5 — the
                // twelve-second opening. This is the whole reason the spec said to build the
                // debug route before the patterns.
                CabinetDebugIntent.Action.RECKONING ->
                    // isReplay when already consumed — see the note at the other jump site.
                    cabinetShell?.startReckoning(
                        debugRequest.phase, debugRequest.lap, persistence.isCrystalReleased()
                    )
            }
        }
    }
}
