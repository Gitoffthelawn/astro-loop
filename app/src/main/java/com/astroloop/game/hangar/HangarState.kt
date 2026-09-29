package com.astroloop.game.hangar

import android.graphics.RectF
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDef
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.PilotUnlockType
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.data.StoreUpgradeDefinitions
import com.astroloop.game.core.AudioMode
import com.astroloop.game.core.StoryStateManager
import java.util.concurrent.CopyOnWriteArrayList

enum class HangarPhase {
    BROWSING,           // Normal state, scrolling ships/pilots
    LAUNCHING,          // Launch sequence playing
    CODEX               // Viewing codex
}

data class ChatMessage(
    val speaker: String,
    val text: String,
    val color: Int
)

data class WalkerNPC(
    val pilotIndex: Int,
    val color: Int,
    var x: Float,          // Normalized 0..1 within bar walkway
    var targetX: Float,
    var walking: Boolean,
    var idleTimer: Float,  // Seconds until picking new target
    var armRaiseTimer: Float = 0f,
    var seated: Boolean = false,      // parked and sitting at a stool (ASTRO_LOOP)
    var seatedStool: Int = -1,        // stool index 2/3/5/7 when seated, else -1
    var pendingStool: Int = -1        // stool index this walker is en route to, else -1
)

class HangarState(internal val persistence: PersistenceManager) {

    companion object {
        /** Long enough to be seen as a change, short enough not to be an animation. */
        const val HINT_NOTE_REVEAL_SECONDS = 0.8f

        /**
         * Open the panel the crystal reveal is about to fly into.
         *
         * In landscape the store's 3x3 board is not in the room at all — the SHOP panel owns it
         * (see `HangarRenderer.drawPanelLayer`), and that panel is shut by default. The reveal is
         * a scripted beat that fires itself and blocks both launching and selecting Astro until
         * it finishes, so a destination behind a closed door is a player stuck with no idea why.
         * The gate therefore opens it.
         *
         * Pure and static so the rule is testable without a SurfaceView, and routed through
         * [togglePanel] rather than assigning [openPanel] directly so the panel's own invariants
         * (at most one of open/closing, and when the fade may restart) hold for a panel the game
         * opened exactly as they do for one the player opened. The `!= SHOP` guard is what stops
         * that toggle from CLOSING a board the player already had up.
         *
         * A no-op in portrait, where the board is in the room and there is no panel to open.
         */
        fun armCrystalRevealPanel(state: HangarState) {
            if (!state.landscape) return
            if (state.openPanel == HangarPanels.Panel.SHOP) return
            state.togglePanel(HangarPanels.Panel.SHOP)
        }
    }

    @Volatile var phase: HangarPhase = HangarPhase.BROWSING

    // --- Page navigation ---
    @Volatile var currentPage: Int = 1            // 0=bar, 1=shipyard, 2=store
    @Volatile var pageScrollOffset: Float = 0f    // Pixel offset for smooth page swiping
    @Volatile var pageVelocity: Float = 0f        // For momentum page swiping
    @Volatile var swayMomentum: Float = 0f         // Decaying momentum for lamp sway effect

    // --- Ship selection (shipyard page) ---
    @Volatile var selectedShipIndex: Int = 0
    @Volatile var shipScrollOffset: Float = 0f   // Pixel offset for smooth ship switching

    // --- Drag-to-launch ---
    @Volatile var shipDragY: Float = 0f           // Current Y of ship being dragged
    @Volatile var isDraggingShip: Boolean = false
    @Volatile var shipRestingY: Float = 0f        // Resting position below walkway

    // A controller launch: the ship rises into the halo on its own, then holds there for a beat.
    @Volatile var padLaunchActive: Boolean = false
    @Volatile var padLaunchTimer: Float = 0f

    // --- Pilot selection (bar page grid) ---
    @Volatile var selectedPilotIndex: Int = 0
    @Volatile var pendingRecruitPilotIndex: Int = -1
    val pilotCardFades: FloatArray = FloatArray(12) { 1f }  // Per-card alpha (1=full, dim otherwise)
    // Pilot card flip animation (tap selected pilot to reveal passive effect)
    @Volatile var pilotFlipIndex: Int = -1
    @Volatile var pilotFlipTimer: Float = 0f
    @Volatile var pilotFlipProgress: Float = 0f
    @Volatile var pilotFlipShowBack: Boolean = false
    // No scroll needed — tappable grid on bar page

    /**
     * Seconds left on each store tile's flip, indexed by its position in
     * `StoreUpgradeDefinitions.tiles`. Zero means the tile is showing its front.
     *
     * **One clock per tile, not one clock.** The first cut kept a single index, so turning over a
     * second card snapped the first back to its front with no fade — which broke the rule that
     * nothing on screen may simply vanish and, more to the point, made the back useless for what it
     * exists to do: comparing two upgrades before spending. Owner, 2026-08-09: "no need for flipped
     * pages to flip back when you select another upgrade."
     *
     * A plain array rather than volatile scalars, matching `pilotCardFades` above: written by the
     * UI thread on tap, advanced and read by the game thread. A torn read costs one frame of a
     * fade, never a purchase.
     */
    private val storeFlipTimers = FloatArray(StoreUpgradeDefinitions.tiles.size)

    // Hold-to-buy, mirrored out of HoldToBuy each frame so the renderer can draw the fill without
    // reaching into the view's input state.
    @Volatile var storeHoldIndex: Int = -1
    @Volatile var storeHoldProgress: Float = 0f

    // Hold-fill exit — a completion flash or an early-release fade, so the fill drawn above never
    // simply vanishes — nothing on screen may go without a visible exit. HoldToBuy stays pure and
    // zeroes its own progress the instant it cancels or completes; HangarSurfaceView tracks the
    // decay and mirrors it here the same way it mirrors storeHoldIndex/Progress above.
    @Volatile var storeHoldExitIndex: Int = -1
    @Volatile var storeHoldExitProgress: Float = 0f  // fill width to hold through the fade, 0..1
    @Volatile var storeHoldExitAlpha: Float = 0f      // 1 = fully visible, decaying to 0
    @Volatile var storeHoldExitSuccess: Boolean = false // true = completion flash, false = plain fade

    /**
     * Show [index]'s back for [duration] seconds, leaving every other turned-over card alone.
     *
     * Re-tapping a card that is already showing its back restarts its peek rather than ending it —
     * there is deliberately no dismiss gesture, so a player rereading a card should not have to
     * wait it out.
     */
    fun flipStoreCard(index: Int, duration: Float) {
        if (index !in storeFlipTimers.indices) return
        storeFlipTimers[index] = duration
    }

    /**
     * A tap on [index]: turn it over if it is showing its front, turn it back if it is not.
     *
     * Owner, 2026-08-09. A second tap always means "put it back" — including one that lands during
     * the opening fade, where the front is technically still the visible face. Guessing at intent
     * from how far through the animation the player happened to tap would be worse than a rule they
     * can hold in their head.
     */
    fun toggleStoreCard(index: Int, duration: Float) {
        if (index !in storeFlipTimers.indices) return
        if (isStoreCardFlipped(index)) closeStoreCard(index) else flipStoreCard(index, duration)
    }

    /**
     * Dismiss [index] through its closing fade.
     *
     * Pulled down to one fade leg rather than zeroed: zeroing would make the back vanish between
     * frames, and this peek cross-fades in precisely because nothing may. A card already
     * inside its last leg is left alone rather than restarted.
     */
    fun closeStoreCard(index: Int) {
        if (index !in storeFlipTimers.indices) return
        storeFlipTimers[index] =
            storeFlipTimers[index].coerceAtMost(HangarSurfaceView.STORE_FLIP_FADE)
    }

    /**
     * A tap on pilot [index]'s card: turn it over, turn it back, or move the peek to a new card.
     *
     * The bar grid shows one back at a time — unlike the store, where comparing two upgrades is the
     * point — so tapping a different pilot moves the peek rather than adding to it.
     */
    fun togglePilotFlip(index: Int) {
        if (pilotFlipIndex == index && pilotFlipTimer > 0f) {
            pilotFlipTimer = pilotFlipTimer.coerceAtMost(HangarSurfaceView.PILOT_FLIP_FADE)
            return
        }
        pilotFlipIndex = index
        pilotFlipTimer = HangarSurfaceView.PILOT_FLIP_DURATION
        pilotFlipProgress = 1f    // start fully visible; 0f caused a one-frame invisible flash
        pilotFlipShowBack = false
    }

    /**
     * Seconds left of the cross-fade that turns a locked pilot's `?` into the bar's note on them.
     *
     * Armed the moment the hint is spoken, which is the moment the player is watching the bar — so
     * without it the `?` would simply cease to exist mid-conversation. Nothing on screen may go
     * without a visible exit, and that covers UI elements as much as ships and rocks.
     *
     * **Not persisted, deliberately.** On a later launch the note is already known and should just
     * be there; replaying the reveal every time the bar opens would make a one-off moment into a
     * recurring animation.
     */
    @Volatile var hintNoteReveal: Float = 0f

    /** Start the `?`-to-note cross-fade. */
    fun beginHintNoteReveal() {
        hintNoteReveal = HINT_NOTE_REVEAL_SECONDS
    }

    /** Tick the reveal. Safe to call every frame whether or not one is running. */
    fun advanceHintNoteReveal(deltaTime: Float) {
        if (hintNoteReveal > 0f) hintNoteReveal = (hintNoteReveal - deltaTime).coerceAtLeast(0f)
    }

    /** How visible the note is: 0 at the start of the reveal, 1 once it is done or never ran. */
    fun hintNoteAlpha(): Float =
        if (hintNoteReveal <= 0f) 1f
        else (1f - hintNoteReveal / HINT_NOTE_REVEAL_SECONDS).coerceIn(0f, 1f)

    /** Tick every turned-over card's clock. Called once per frame from the game thread. */
    fun advanceStoreFlips(deltaTime: Float) {
        for (i in storeFlipTimers.indices) {
            if (storeFlipTimers[i] > 0f) {
                storeFlipTimers[i] = (storeFlipTimers[i] - deltaTime).coerceAtLeast(0f)
            }
        }
    }

    /** Whether [index] is mid-peek — either face may be on screen, see [storeFlipShowBack]. */
    fun isStoreCardFlipped(index: Int): Boolean =
        index in storeFlipTimers.indices && storeFlipTimers[index] > 0f

    /**
     * Whether [index] has finished turning and is showing its back.
     *
     * The cycle is the pilot card's: fade the front out over one leg, hold the back, fade it out
     * again. So this is false for the first leg even though the peek has started.
     */
    fun storeFlipShowBack(index: Int): Boolean {
        if (!isStoreCardFlipped(index)) return false
        val elapsed = HangarSurfaceView.STORE_FLIP_DURATION - storeFlipTimers[index]
        return elapsed >= HangarSurfaceView.STORE_FLIP_FADE
    }

    /** Alpha of whatever face [index] is currently showing: 1 fully visible, 0 invisible. */
    fun storeFlipProgress(index: Int): Float {
        if (!isStoreCardFlipped(index)) return 0f
        val remaining = storeFlipTimers[index]
        val elapsed = HangarSurfaceView.STORE_FLIP_DURATION - remaining
        val fade = HangarSurfaceView.STORE_FLIP_FADE
        return when {
            elapsed < fade -> 1f - elapsed / fade   // front fading out
            remaining < fade -> remaining / fade    // back fading out
            else -> 1f                              // back fully visible
        }
    }

    // --- Pilot walker (world-space pixel coordinates spanning 3 pages) ---
    @Volatile var pilotX: Float = 0f              // World X position in pixels
    @Volatile var pilotTargetX: Float = 0f
    @Volatile var pilotWalking: Boolean = false
    var pilotScreenWidth: Float = 0f              // Set before initialize()

    /**
     * The screen's height in design units.
     *
     * Paired with [pilotScreenWidth] and set at the SAME three HangarSurfaceView sites. Miss one
     * and [landscape] reads false on a rotated screen, which sends the walker to a room that is
     * not where it is drawn.
     */
    var pilotScreenHeight: Float = 0f

    /**
     * Whether the screen is rotated. The walker's world space is anchored per page when it is.
     *
     * An unset height is treated as "not known yet" and answers portrait, the same convention
     * [HangarMetrics.effectiveRoomWidth] gives an unset [roomWidth]. Without it a state carrying a
     * width but no height — which is every frame between the two assignments, and several test
     * fixtures — would read `width > 0f` and so LANDSCAPE, the side that moves things. Portrait is
     * the side with no behaviour change, so it is the safe answer to a question we cannot yet
     * answer.
     */
    val landscape: Boolean
        get() = pilotScreenHeight > 0f && RoomAnchor.isLandscape(pilotScreenWidth, pilotScreenHeight)

    // Width of one hangar room in design units. In portrait this is also the page stride; in
    // landscape the stride is a full screen and the room keeps this — its PORTRAIT — width.
    // Set alongside pilotScreenWidth before initialize(). Equals pilotScreenWidth below sw600dp.
    var roomWidth: Float = 0f

    // --- NPC walkers (bar page) ---
    var npcWalkers: CopyOnWriteArrayList<WalkerNPC> = CopyOnWriteArrayList()
    val pendingNPCAdds: MutableList<WalkerNPC> = java.util.Collections.synchronizedList(mutableListOf())
    val pendingNPCRemoves: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

    // --- Seated crew (bar stools, ASTRO_LOOP only) ---
    private val SEATABLE_STOOLS = listOf(2, 3, 5, 7)

    /** Lowest stool in the seatable set not present in [occupied]; -1 if all taken. */
    fun lowestFreeStool(occupied: Set<Int>): Int =
        SEATABLE_STOOLS.firstOrNull { it !in occupied } ?: -1

    /** Screen x of stool [stool] mapped into the walker's normalized 0..1 band. */
    fun stoolNormalizedX(stool: Int): Float {
        val w = HangarMetrics.effectiveRoomWidth(roomWidth, pilotScreenWidth)
        val stoolScreenX = HangarMetrics.stoolCenterX(w, stool)
        return (stoolScreenX - w * 0.1f) / (w * 0.8f)
    }

    // --- Chat log (bar page) ---
    val chatMessages: CopyOnWriteArrayList<ChatMessage> = CopyOnWriteArrayList()
    @Volatile var chatTimer: Float = 0f

    fun addChatMessage(speaker: String, text: String, color: Int) {
        val recent = chatMessages.takeLast(10)
        if (recent.none { it.text == text }) {
            chatMessages.add(ChatMessage(speaker, text, color))
            while (chatMessages.size > 20) chatMessages.removeAt(0)
        }
    }

    // --- Conversation queue ---
    var activeConversation: List<ChatMessage>? = null
    var conversationLineIndex: Int = 0
    var conversationLineTimer: Float = 0f
    var conversationCooldown: Float = 15f  // Start with 15s delay before first conversation
    // Cooldown applied when the CURRENT conversation finishes. Scripted bursts (the post-run
    // return, reckoning chatter, a recruitment) drop this to a short tail so the bar doesn't
    // go silent for a full CONVERSATION_COOLDOWN right after the scripted lines land.
    // Reset to the default every time a conversation ends.
    var conversationEndCooldown: Float = ChatSystem.CONVERSATION_COOLDOWN

    // --- Discovered evolutions (for codex book in bar) ---
    @Volatile var hasDiscoveredEvolutions: Boolean = false

    // --- Slot machine (store page) ---
    @Volatile var isSpinning: Boolean = false
    var reelValues: IntArray = IntArray(3)          // Target symbol index per reel
    var reelStopTimes: LongArray = LongArray(3)     // Stagger timestamps
    @Volatile var spinResultYen: Int = 0            // Payout amount for display
    @Volatile var spinResultUpgrade: String? = null  // Upgrade name if jackpot
    // The upgrade a jackpot has promised but not yet handed over. Chosen when the roll happens so
    // the reels and the save can never disagree about which one it was; written when they stop, so
    // the tile does not gain a level before the third reel has landed on it.
    @Volatile var pendingSpinUpgradeId: String? = null
    @Volatile var spinResultSymbol: Int = -1         // Symbol that landed (SYM_* constant)
    @Volatile var spinResultTime: Long = 0          // When result landed (for fade-out)
    @Volatile var reelPhases: FloatArray = FloatArray(3) // Animation scroll offset per reel

    // --- TB-26 bartender bar movement ---
    @Volatile var tb26BarX: Float = 0f
    @Volatile var tb26BarTargetX: Float = 0f
    @Volatile var tb26BarMoving: Boolean = false
    @Volatile var tb26BarPauseTimer: Float = 0f

    // --- Beer sliding ---
    @Volatile var beerActive: Boolean = false
    @Volatile var beerX: Float = 0f
    @Volatile var beerTargetX: Float = 0f
    @Volatile var beerTimer: Float = 0f
    @Volatile var beerInterval: Float = 12f
    @Volatile var beerTargetPilotIndex: Int = -1
    @Volatile var beerFading: Boolean = false
    @Volatile var beerFadeAlpha: Float = 1f

    // --- Astro hints (TB-26 hints after all non-Astro pilots recruited) ---
    @Volatile var astroHintCount: Int = 0
    @Volatile var astroHinted: Boolean = false
    @Volatile var allEvolutionsHinted: Boolean = false

    // --- Pilot hint tracking (guaranteed first show) ---
    var hintShownForPilotIndex: Int = -1

    // --- Corrupted Astro at slot machine ---
    @Volatile var astroAtSlotMachine: Boolean = false
    var astroAutoSpinTimer: Float = 0f
    var astroAutoSpinInterval: Float = 5f + kotlin.random.Random.nextFloat() * 3f

    // --- Crystal reveal animation (store page, after 11th corrupted pilot dies) ---
    enum class CrystalRevealPhase { NONE, GLOW, ORB_TRAVEL, FLASH, DONE }
    @Volatile var crystalRevealPhase = CrystalRevealPhase.NONE
    var crystalRevealTimer = 0f
    @Volatile var awaitingCrystalReveal = false

    /**
     * The reveal's two MOVING phases — the orb in the air and the burst on the tile.
     *
     * The one definition of "the reveal is in flight", shared by everything that has to agree
     * about it: `HangarSurfaceView.crystalRevealFlying` (which gates every input path), the room
     * (which hands its Astro dot over for the duration — see `StorePageRenderer.draw`) and
     * `HangarRenderer.drawCrystalReveal` (which picks it up). [GLOW] is deliberately not in here:
     * nothing is moving yet, nothing is refused, and the room still owns the dot.
     */
    fun crystalRevealInFlight(): Boolean =
        crystalRevealPhase == CrystalRevealPhase.ORB_TRAVEL ||
            crystalRevealPhase == CrystalRevealPhase.FLASH

    // --- Codex secret (maintenance hatch on slot machine) ---
    @Volatile var codexDiscovered: Boolean = false
    @Volatile var codexHintGiven: Boolean = false
    @Volatile var hatchTapCount: Int = 0

    // How far into the codex sequence a controller has got. Not persisted: the secret is the
    // sequence, not the progress, and a half-entered run means nothing across launches.
    @Volatile var codexSequenceProgress: Int = 0
    @Volatile var hatchOpen: Boolean = false
    @Volatile var showCodex: Boolean = false
    var hatchRect: RectF? = null
    var paperRect: RectF? = null
    var audioMode: AudioMode = AudioMode.ALL
    var vibrationMuted: Boolean = false
    var audioMuteButtonRect: RectF? = null
    var vibrationMuteButtonRect: RectF? = null

    // --- BELT RUN cabinet (Astro Loop only) ---
    /** True while the full-screen cabinet overlay is up; the hangar pauses beneath it. */
    @Volatile var cabinetOpen: Boolean = false

    /**
     * The landscape panel that is open, or null. Landscape only — panels do not exist in
     * portrait, and the state is never persisted: an orientation change closes it
     * (`HangarSurfaceView.applyScreenDimensions`).
     *
     * "Open" means the panel is the one accepting card/backdrop taps — set the instant its
     * button is pressed, cleared the instant a close begins. What keeps the panel on screen
     * after that is [closingPanel], not this field.
     *
     * Read on the render thread and written on the UI thread, like [cabinetOpen] beside it.
     */
    @Volatile var openPanel: HangarPanels.Panel? = null

    /**
     * The panel sliding shut, or null.
     *
     * [togglePanel] moves a panel here the instant a tap closes it, rather than dropping it
     * straight to nothing — closing sets [openPanel] to null, and without somewhere else to
     * keep the identity, the panel would vanish between frames instead of fading out, which
     * the house rule against instant disappearance forbids. `HangarRenderer.drawPanelLayer`
     * keeps drawing whichever of [openPanel] / [closingPanel] is set; only the alpha differs.
     * A closing panel is drawn regardless of which page is current — see [setPageTarget],
     * which is what can hand it a page it no longer belongs to.
     *
     * Cleared by [advancePanelFade] once [panelFade] reaches zero — or immediately, by
     * [togglePanel], if the SAME panel is reopened before that happens, which hands the
     * identity back to [openPanel] and lets the fade resume from wherever it was instead of
     * restarting or jumping straight to open.
     *
     * At most one of [openPanel] / [closingPanel] is non-null at a time. Volatile because it
     * is written from two different threads: [togglePanel] (UI thread, on a tap or a page
     * change) sets it, and [advancePanelFade] (the update/render thread, every frame) clears
     * it once the fade completes. The race between them is benign — at worst a tap and a tick
     * land the same frame and one write wins, costing at most a frame of fade, never a stuck
     * or duplicated panel.
     */
    @Volatile var closingPanel: HangarPanels.Panel? = null

    /**
     * 0 (nothing on screen) .. 1 (fully open): how far into its open/close fade the panel
     * layer is. Belongs to whichever of [openPanel] / [closingPanel] is currently set — never
     * both, so there is never a question of whose fade this is. [advancePanelFade] ticks it
     * every frame from the hangar's ordinary update, never from the draw pass; the renderer
     * only reads it, for the scrim alpha and a `saveLayerAlpha` around the panel box and its
     * contents.
     *
     * Written from two threads, like [closingPanel]: the UI thread resets it to 0 in
     * [togglePanel] when the visible panel identity changes outright (switching straight to a
     * different panel, or a page change carrying the old one into [closingPanel]), and the
     * update/render thread advances it every frame in [advancePanelFade]. Same benign race as
     * [closingPanel]: the two writers never fight over the SAME frame's meaning, only over
     * which frame's tick a fresh tap lands on.
     */
    @Volatile var panelFade: Float = 0f

    /**
     * Press [panel]'s button — or, from [setPageTarget], leave the page an open panel belongs
     * to (which calls this with that SAME panel to start its close).
     *
     * Three cases: start closing it if it is the one already open ([openPanel] to null, the
     * identity moving to [closingPanel] rather than disappearing); if it is the very panel
     * already mid-close, hand the identity straight back to [openPanel] without touching
     * [panelFade] — so pressing the button again during a fade-out reverses the motion from
     * wherever it is instead of restarting it or snapping straight to fully open; or, open it
     * from cold or from a genuinely different panel.
     *
     * The state rule: the visible panel identity changing
     * for any reason OTHER than reversing the same panel resets the fade. The first two cases
     * above are exactly "the same panel, reversing" — closing what was open, or reopening what
     * was closing — so neither touches [panelFade]. The third case is everything else: a
     * button press for a panel that is neither the one open nor the one closing. That abandons
     * whatever was mid-close outright (only one identity is tracked at a time, and the
     * newly-pressed one is what the player is looking at now) AND resets [panelFade] to 0, so
     * the new panel opens from scratch instead of inheriting a stale fade value left over from
     * whatever was fading a moment ago — the bug this rule exists to close.
     */
    fun togglePanel(panel: HangarPanels.Panel) {
        when {
            openPanel == panel -> {
                closingPanel = panel
                openPanel = null
            }
            closingPanel == panel -> {
                openPanel = panel
                closingPanel = null
            }
            else -> {
                // Whichever identity was on screen a moment ago, open or mid-close — the "at
                // most one of openPanel/closingPanel is set" invariant the two cases above rely
                // on means at most one of these is non-null.
                val previous = openPanel ?: closingPanel

                // openPanel set before closingPanel cleared: a render frame caught between the
                // two would otherwise see neither set and the panel would blink out for a
                // frame.
                openPanel = panel
                closingPanel = null

                // A later review (correcting an earlier fix, which reset
                // the fade unconditionally here): a page change makes the old panel irrelevant,
                // so THAT switch resets — but it never reaches this branch at all, since
                // setPageTarget always calls this with the SAME panel that was open, which is
                // case one above. What DOES land here on an unchanged page is a button press for
                // a genuinely different panel while one is already up — SHOP -> SLOT, both live
                // on page 2. That keeps the same box and the same shared scrim; only the CONTENT
                // changes. Resetting unconditionally made that box and scrim vanish in one frame
                // the instant the second button was pressed, mid-fade — the exact instant
                // disappearance the fade exists to prevent — so only a switch that also changes
                // which page the panel belongs to resets; a same-page swap carries the fade
                // forward instead.
                if (previous == null || HangarPanels.pageOf(previous) != HangarPanels.pageOf(panel)) {
                    panelFade = 0f
                }
            }
        }
    }

    /**
     * The panel [HangarRenderer.drawPanelLayer] is actually drawing this frame, in identity
     * terms — or null when it draws none. The single predicate both the draw path and the input
     * gate consult: before this, `drawPanelLayer` derived it
     * inline while `HangarSurfaceView`'s input handling asked a DIFFERENT question ("does this
     * page publish any buttons?"), and those two questions agree everywhere except a closing
     * panel drawn over a page — the launchpad — that owns no buttons of its own. That drift is
     * what let a tap and a drag reach the launchpad through a scrim that was plainly on screen.
     *
     * An OPEN panel counts only on the page that owns it — the crew page's panel is not open on
     * the shop page. A CLOSING one counts regardless of page: it is already on its way out no
     * matter which page is current (see [closingPanel]'s own doc), and [advancePanelFade]
     * guarantees it clears itself within [HangarSurfaceView.PANEL_FADE_SECONDS] either way.
     */
    fun visiblePanel(): HangarPanels.Panel? =
        openPanel?.takeIf { it in HangarPanels.panelsOn(currentPage) } ?: closingPanel

    /**
     * Advance the panel open/close fade by [deltaTime]. Called once per frame from the
     * hangar's ordinary update, alongside [advanceStoreFlips] and [advanceHintNoteReveal] —
     * ticking belongs there, not in the draw pass.
     *
     * A no-op whenever neither [openPanel] nor [closingPanel] is set, which is always true
     * outside landscape: panels only ever open via [togglePanel], reached from
     * `HangarSurfaceView.handlePanelTap`, which itself refuses to run outside landscape. So
     * this costs one comparison per frame in portrait and changes nothing.
     */
    fun advancePanelFade(deltaTime: Float) {
        val rate = deltaTime / HangarSurfaceView.PANEL_FADE_SECONDS
        when {
            openPanel != null -> panelFade = (panelFade + rate).coerceAtMost(1f)
            closingPanel != null -> {
                panelFade = (panelFade - rate).coerceAtLeast(0f)
                if (panelFade <= 0f) closingPanel = null
            }
        }
    }

    /** The INSERT COIN button. Occupies the slot machine's old spin-button rect. */
    var insertCoinRect: RectF? = null

    /**
     * The CRT rect StorePageRenderer computes each frame for the bezel's attract demo.
     * Published here so HangarSurfaceView.update() — which owns the bezel's CabinetSim/
     * CabinetRenderer and has no other way to learn the rect's size — can build them
     * lazily at bezel scale once it's known.
     */
    @Volatile var cabinetScreenRect: RectF? = null

    /**
     * The marquee plate's rect, computed each frame by StorePageRenderer the same way
     * [cabinetScreenRect] is. Published here so HangarSurfaceView.update() — which owns
     * the marquee's ambient drift and has no other way to learn the plate's size — can
     * build/rebuild it lazily at plate scale. See [cabinetScreenRect]'s own doc.
     */
    @Volatile var cabinetMarqueeRect: RectF? = null

    // --- Slot readout messages (audio / vibration button feedback) ---
    // The buttons flank the machine's CRT readout, so the readout is what tells the player which
    // state a press just arrived at. A message takes the screen outright: see showReadoutMessage.
    @Volatile var readoutMessage: String? = null
    @Volatile var readoutMessageTime: Long = 0L

    /**
     * Put [text] on the slot machine's readout for the next three seconds.
     *
     * Clears any spin result at the same time. The message is drawn ahead of a result anyway, so
     * without this a jackpot interrupted early would reappear for its remainder once the message
     * faded, which reads as a glitch. The payout is already banked by the time either is drawn.
     */
    fun showReadoutMessage(text: String) {
        readoutMessage = text
        readoutMessageTime = System.currentTimeMillis()
        spinResultTime = 0L
    }

    // --- Fade from black (corruption death return) ---
    @Volatile var fadeFromBlackTimer: Float = 0f
    @Volatile var glitchTimer: Float = 0f

    // --- Desert farewell → Astro Loop first entry ---
    @Volatile var pendingTbWelcome: Boolean = false

    // --- First-launch intro cinematic ---
    @Volatile var introCinematic: Boolean = false   // True only during the first-ever launch intro
    @Volatile var introTitleTimer: Float = 0f        // Seconds the ASTRO LOOP title has been fading in
    // Seconds the player has sat on the bar page without moving to the launchpad.
    // Deliberately NOT persisted: introCinematic is derived from isIntroDone(), which is only
    // written when the ship is actually launched, so a player who kills the app on the bar
    // replays the whole intro — and a persisted latch would make that second attempt quieter
    // than the first for no reason a player could understand.
    @Volatile var introHintTimer: Float = 0f
    @Volatile var introHintDone: Boolean = false     // The swipe hint is a one-shot

    // --- Launch sequence ---
    @Volatile var launchProgress: Float = 0f
    @Volatile var launchPhase: Int = 0

    // --- Yen display (for animation) ---
    @Volatile var displayedYen: Int = 0
    @Volatile var actualYen: Int = 0
    private var yenAnimStartValue: Int = 0
    private var yenAnimTargetValue: Int = 0
    private var yenAnimStartTime: Long = 0L
    private val yenAnimDuration: Float = 3f

    fun updatePilotWalker(deltaTime: Float) {
        val walkWidth = HangarMetrics.effectiveRoomWidth(roomWidth, pilotScreenWidth)
        val baseSpeed = walkWidth * 0.70f  // Pixels per second
        val walkSpeed = when {
            // Intro cinematic: deliberately slow so the walk reads as a cinematic beat (~3.4s)
            introCinematic -> walkWidth * 0.20f
            StoryStateManager.isCorrupted(persistence) -> baseSpeed * 0.5f
            else -> baseSpeed
        }
        if (pilotWalking) {
            val diff = pilotTargetX - pilotX
            if (kotlin.math.abs(diff) < walkSpeed * deltaTime) {
                pilotX = pilotTargetX
                pilotWalking = false
            } else {
                pilotX += if (diff > 0) walkSpeed * deltaTime else -walkSpeed * deltaTime
            }
        }
    }

    fun updateNPCWalkers(deltaTime: Float) {
        // Drain pending changes from UI thread before iterating
        if (pendingNPCAdds.isNotEmpty()) {
            npcWalkers.addAll(pendingNPCAdds)
            pendingNPCAdds.clear()
        }
        if (pendingNPCRemoves.isNotEmpty()) {
            npcWalkers.removeAll { it.pilotIndex in pendingNPCRemoves }
            pendingNPCRemoves.clear()
        }

        val baseNpcSpeed = 0.105f
        val corruptedNow = StoryStateManager.isCorrupted(persistence)
        val seatingNow = BarDressing.forStage(StoryStateManager.stage(persistence)).seatedCrew
        val walkSpeed = if (corruptedNow) baseNpcSpeed * 0.5f else baseNpcSpeed
        for (npc in npcWalkers) {
            if (!seatingNow) {
                // No seating this stage (corruption); clear any stale seated state.
                npc.seated = false; npc.seatedStool = -1; npc.pendingStool = -1
            }
            if (npc.walking) {
                val diff = npc.targetX - npc.x
                if (kotlin.math.abs(diff) < walkSpeed * deltaTime) {
                    npc.x = npc.targetX
                    npc.walking = false
                    npc.idleTimer = kotlin.random.Random.nextFloat() * 3f + 1.5f
                    if (npc.pendingStool >= 0) {           // arrived at a stool → sit
                        npc.seated = true
                        npc.seatedStool = npc.pendingStool
                        npc.pendingStool = -1
                    }
                } else {
                    npc.x += if (diff > 0) walkSpeed * deltaTime else -walkSpeed * deltaTime
                }
            } else {
                npc.idleTimer -= deltaTime
                if (npc.idleTimer <= 0f) {
                    // Choosing a new destination: stand up and clear this walker's claim first.
                    npc.seated = false
                    npc.seatedStool = -1
                    npc.pendingStool = -1
                    val stool = if (seatingNow) {
                        val occupied = npcWalkers.mapNotNull { other ->
                            when {
                                other === npc -> null
                                other.seated -> other.seatedStool
                                other.pendingStool >= 0 -> other.pendingStool
                                else -> null
                            }
                        }.toSet()
                        lowestFreeStool(occupied)
                    } else -1
                    if (stool >= 0) {
                        npc.pendingStool = stool
                        npc.targetX = stoolNormalizedX(stool)
                    } else {
                        npc.targetX = kotlin.random.Random.nextFloat() * 0.8f + 0.1f
                    }
                    npc.walking = true
                }
            }
        }
    }

    /**
     * World X of a point [fraction] (0..1) along [page]'s walkable band.
     *
     * The one derivation of the walker's world space. Pages step by the stride and then lean
     * toward the shipyard by [RoomAnchor.anchorX], and the band is measured against the page's
     * DRAWN width, not the stride: in landscape a page slot is a full screen while the crew and
     * shop rooms are only portrait-wide, so measuring against the stride would walk the pilot out
     * of his own room and into starfield. In portrait `anchorX` is the centring term the shipped
     * code folded into its stride and `pageWidth` IS the stride, so this is that arithmetic
     * unchanged.
     *
     * `drawNPCWalkers` maps the same 0.1 .. 0.9 band room-locally inside the page's own translate,
     * which lands on the same screen point because room-local plus page origin is world minus
     * viewport.
     */
    fun pilotWorldX(page: Int, fraction: Float): Float {
        val stride = RoomAnchor.stride(pilotScreenWidth, roomWidth, landscape)
        val pageW = RoomAnchor.pageWidth(page, pilotScreenWidth, roomWidth, landscape)
        val base = page * stride + RoomAnchor.anchorX(page, pilotScreenWidth, roomWidth, landscape)
        return base + pageW * 0.1f + fraction * (pageW * 0.8f)
    }

    /** World X target for each page (pilot stands near the archway connecting to shipyard) */
    fun getPilotWorldTarget(page: Int): Float = when (page) {
        0 -> pilotWorldX(0, 0.9f)                          // Right side of bar
        1 -> pilotWorldX(1, 0.5f)                          // Center of shipyard
        2 -> {
            val base = pilotWorldX(2, 0.1f)                // Left side of store
            if (astroAtSlotMachine) base - 35f else base
        }
        else -> pilotWorldX(1, 0.5f)
    }

    /**
     * The store walkway's slot machine, in world space — page 2's target without the walker's
     * own offset. The crystal reveal needs this exact point, and computed it a second time by
     * hand before this existed.
     */
    fun slotMachineWorldX(): Float = pilotWorldX(2, 0.1f)

    /**
     * Move to [page] — and, if [page] does not own the currently OPEN panel, start closing it
     * rather than leaving it pinned open on a page that no longer shows it.
     *
     * This is the page-change half of the panel state rule: a page
     * change is exactly a case of the visible panel identity changing (from "open on the old
     * page" to "not present on the new one"), so the fade must not simply freeze — [togglePanel]
     * is reused here for the same reason `HangarSurfaceView.togglePanel` reuses it for a button
     * press, so there is one definition of "start closing [openPanel]", not two. A panel that
     * already belongs to [page] — including the common case of staying put — is left alone; a
     * panel that is already [closingPanel] is left to keep closing, wherever it is headed, since
     * `HangarRenderer.drawPanelLayer` draws a closing panel regardless of the current page.
     */
    fun setPageTarget(page: Int) {
        openPanel?.let { if (it !in HangarPanels.panelsOn(page)) togglePanel(it) }
        currentPage = page
        pilotTargetX = getPilotWorldTarget(page)
        if (pilotTargetX != pilotX) {
            pilotWalking = true
        }
    }

    fun updateYenDisplay(deltaTime: Float) {
        if (displayedYen != actualYen) {
            if (yenAnimStartTime == 0L || actualYen != yenAnimTargetValue) {
                // Start new animation (or restart if target changed mid-animation)
                yenAnimStartValue = displayedYen
                yenAnimTargetValue = actualYen
                yenAnimStartTime = System.currentTimeMillis()
            }
            val elapsed = (System.currentTimeMillis() - yenAnimStartTime) / 1000f
            val progress = (elapsed / yenAnimDuration).coerceIn(0f, 1f)
            val totalDiff = actualYen - yenAnimStartValue
            displayedYen = yenAnimStartValue + (totalDiff * progress).toInt()
            if (progress >= 1f) {
                displayedYen = actualYen
                yenAnimStartTime = 0L
            }
        }
    }

    fun initialize() {
        // First-launch intro cinematic is active until the first launch ever commits.
        introCinematic = !persistence.isIntroDone()
        introTitleTimer = 0f

        // Load from persistence
        val savedShipId = persistence.getSelectedShipId()
        selectedShipIndex = ShipDefinitions.ships.indexOfFirst { it.id == savedShipId }.coerceAtLeast(0)

        val savedPilotId = persistence.getSelectedPilotId()
        selectedPilotIndex = PilotDefinitions.pilots.indexOfFirst { it.id == savedPilotId }.coerceAtLeast(0)

        // Corruption state: auto-select Astro+Specter when crystal unlocked and all crew dead
        if (StoryStateManager.isCorrupted(persistence)) {
            if (persistence.isCrystalUnlocked() && StoryStateManager.allCrewDead(persistence)) {
                if (persistence.isAwaitingCrystalReveal()) {
                    // Crystal reveal not yet played — no pilot, Specter on carousel
                    selectedPilotIndex = -1
                    awaitingCrystalReveal = true
                    crystalRevealPhase = CrystalRevealPhase.GLOW
                    val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
                    if (specterIndex >= 0) selectedShipIndex = specterIndex
                } else {
                    val astroIndex = PilotDefinitions.pilots.indexOfFirst { it.id == "pilot_astro" }
                    val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
                    if (astroIndex >= 0) selectedPilotIndex = astroIndex
                    if (specterIndex >= 0) selectedShipIndex = specterIndex
                }
            } else {
                // If currently selected pilot/ship is dead, find first available
                if (!isPilotUnlocked(selectedPilotIndex)) {
                    val firstAvailable = (0 until PilotDefinitions.getPilotCount()).firstOrNull { isPilotUnlocked(it) }
                    if (firstAvailable != null) selectedPilotIndex = firstAvailable
                }
                if (!isShipUnlocked(selectedShipIndex)) {
                    val firstAvailable = (0 until ShipDefinitions.ships.size).firstOrNull { isShipUnlocked(it) }
                    if (firstAvailable != null) selectedShipIndex = firstAvailable
                }
            }
        }

        // Astro Loop Mode: default to Astro + Specter only on first entry;
        // otherwise keep the saved (last-flown) selection loaded above.
        if (StoryStateManager.isAstroLoop(persistence) && persistence.isAstroLoopFirstEntry()) {
            val astroIndex = PilotDefinitions.pilots.indexOfFirst { it.id == "pilot_astro" }
            val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
            if (astroIndex >= 0) selectedPilotIndex = astroIndex
            if (specterIndex >= 0) selectedShipIndex = specterIndex
            // Boot-path twin of resetForReturn's fadeFromWhite branch: if the app was
            // restarted between the timeline shift and the hangar, TB's "Welcome back."
            // must still fire — and the first-entry flag is consumed so it fires once.
            pendingTbWelcome = true
            persistence.clearAstroLoopFirstEntry()
        }

        // Initialize NPC walkers for all unlocked pilots except selected
        rebuildNpcWalkers()

        actualYen = persistence.getYen()
        displayedYen = actualYen

        hasDiscoveredEvolutions = persistence.getDiscoveredEvolutions().isNotEmpty()

        // Initialize TB-26 bartender position at center of counter
        tb26BarX = HangarMetrics.effectiveRoomWidth(roomWidth, pilotScreenWidth) / 2f
        tb26BarTargetX = tb26BarX
        tb26BarMoving = false
        tb26BarPauseTimer = 1f + kotlin.random.Random.nextFloat() * 1.5f

        // Load codex discovery state
        codexDiscovered = persistence.isCodexDiscovered()
        hatchOpen = persistence.isCodexDiscovered()  // Stay open if already discovered
        codexHintGiven = persistence.isCodexHintGiven()

        // Load mute state
        audioMode = persistence.getAudioMode()
        vibrationMuted = persistence.isVibrationMuted()

        // Load Astro hint state
        astroHintCount = persistence.getAstroHintCount()
        astroHinted = persistence.isAstroHinted()
        allEvolutionsHinted = persistence.isAllEvolutionsHinted()

        // Astro hangs at slot machine in corruption (before all crew are dead, or during crystal reveal)
        astroAtSlotMachine = StoryStateManager.isCorrupted(persistence) &&
            (!StoryStateManager.allCrewDead(persistence) || awaitingCrystalReveal)

        // Start page: the bar during the intro cinematic, otherwise the shipyard.
        // Forcing the bar here keeps the opening robust whenever initialize() re-runs on
        // a full view rebuild (config change / process death). An ordinary pause/resume
        // does not re-run initialize(), so it leaves an in-progress cinematic in place.
        val startPage = if (introCinematic) 0 else 1
        currentPage = startPage
        pilotX = getPilotWorldTarget(startPage)
        pilotTargetX = pilotX
        pilotWalking = false
    }

    fun getSelectedShip() = ShipDefinitions.getShipByIndex(selectedShipIndex)
    fun getSelectedPilot() = PilotDefinitions.getPilotByIndex(selectedPilotIndex)

    fun isShipUnlocked(index: Int): Boolean {
        val ship = ShipDefinitions.getShipByIndex(index) ?: return false
        if (!persistence.isShipUnlocked(ship.id)) return false
        // Corruption gating: dead ships and Specter (until crystal unlocked)
        if (StoryStateManager.isCorrupted(persistence)) {
            if (ship.id == "ship_white" && !persistence.isCrystalUnlocked()) return false
            if (StoryStateManager.isShipDead(persistence, ship.id)) return false
        }
        return true
    }

    fun canUnlockShip(index: Int): Boolean {
        // First ship is always unlockable (starter ship)
        if (index == 0) return true
        // In corruption, Specter is gated by crystal, not purchase
        val ship = ShipDefinitions.getShipByIndex(index)
        if (ship != null && StoryStateManager.isCorrupted(persistence) && ship.id == "ship_white" && !persistence.isCrystalUnlocked()) return false
        // Can only unlock if previous ship is unlocked (sequential)
        return isShipUnlocked(index - 1)
    }

    fun isPilotUnlocked(index: Int): Boolean {
        val pilot = PilotDefinitions.getPilotByIndex(index) ?: return false
        if (!persistence.isPilotUnlocked(pilot.id)) return false
        // Corruption gating: dead pilots and Astro (until crystal unlocked)
        if (StoryStateManager.isCorrupted(persistence)) {
            if (pilot.id == "pilot_astro" && (!persistence.isCrystalUnlocked() || awaitingCrystalReveal)) return false
            if (StoryStateManager.isPilotDead(persistence, pilot.id)) return false
        }
        return true
    }

    fun isPilotDeadInCorruption(index: Int): Boolean {
        val pilot = PilotDefinitions.getPilotByIndex(index) ?: return false
        if (!StoryStateManager.isCorrupted(persistence)) return false
        if (!persistence.isPilotUnlocked(pilot.id)) return false
        return StoryStateManager.isPilotDead(persistence, pilot.id)
    }

    fun canUnlockPilot(index: Int): Boolean {
        if (index == 0) return true
        if (index != persistence.getNextPilotIndex()) return false
        return isPilotUnlocked(index - 1)
    }

    fun checkPilotUnlockCondition(): Boolean {
        val nextIndex = persistence.getNextPilotIndex()
        if (nextIndex >= PilotDefinitions.getPilotCount()) return false

        val pilot = PilotDefinitions.getPilotByIndex(nextIndex) ?: return false

        // Post-desert: one pilot per run, except Astro keeps ALL_OTHERS + 2-run cooldown
        if (StoryStateManager.hasLoopedBefore(persistence)) {
            if (pilot.unlockType != PilotUnlockType.ALL_OTHERS) {
                return persistence.getRunsSincePilotUnlock() >= 1
            }
        }

        val runsSinceUnlock = persistence.getRunsSincePilotUnlock()
        if (runsSinceUnlock < 2) return false

        if (runsSinceUnlock >= 17
            && pilot.unlockType != PilotUnlockType.ALL_OTHERS
            && pilot.unlockType != PilotUnlockType.JACKPOT) return true
        return when (pilot.unlockType) {
            PilotUnlockType.FREE -> true
            PilotUnlockType.TOTAL_YEN_EARNED -> persistence.getTotalYenEarned() >= pilot.unlockThreshold
            PilotUnlockType.TOTAL_DAMAGE_TAKEN -> persistence.getTotalDamageTaken() >= pilot.unlockThreshold
            PilotUnlockType.SURVIVE_SECONDS -> persistence.getBestSurvivalSeconds() >= pilot.unlockThreshold
            PilotUnlockType.KILL_STREAK -> persistence.getBestKillStreak() >= pilot.unlockThreshold
            PilotUnlockType.TOTAL_DEATHS -> persistence.getTotalDeaths() >= pilot.unlockThreshold
            PilotUnlockType.KILLS_IN_SINGLE_RUN -> persistence.getBestSingleRunKills() >= pilot.unlockThreshold
            PilotUnlockType.WEAPONS_DISCOVERED -> persistence.getWeaponsDiscovered().size >= pilot.unlockThreshold
            PilotUnlockType.EVOLUTIONS_DISCOVERED -> persistence.getDiscoveredEvolutions().size >= pilot.unlockThreshold
            PilotUnlockType.TOTAL_KILLS -> persistence.getTotalKills() >= pilot.unlockThreshold
            PilotUnlockType.JACKPOT -> false  // Unlocked via slot machine jackpot
            PilotUnlockType.CONTINUOUS_FLIGHT_SECONDS -> persistence.getBestContinuousFlightSeconds() >= pilot.unlockThreshold
            PilotUnlockType.ALL_OTHERS -> {
                for (i in 0 until PilotDefinitions.getPilotCount() - 1) {
                    if (!isPilotUnlocked(i)) return false
                }
                for (i in 0 until ShipDefinitions.getShipCount()) {
                    if (!isShipUnlocked(i)) return false
                }
                true
            }
        }
    }

    fun recruitNextPilot(): PilotDef? {
        val nextIndex = persistence.getNextPilotIndex()
        val pilot = PilotDefinitions.getPilotByIndex(nextIndex) ?: return null
        persistence.unlockPilot(pilot.id)
        persistence.setNextPilotIndex(nextIndex + 1)
        persistence.resetRunsSincePilotUnlock()
        return pilot
    }

    fun isWhiskersJackpotEligible(): Boolean {
        val nextIndex = persistence.getNextPilotIndex()
        val pilot = PilotDefinitions.getPilotByIndex(nextIndex) ?: return false
        if (pilot.unlockType != PilotUnlockType.JACKPOT) return false
        return persistence.getRunsSincePilotUnlock() >= 1
    }

    fun shouldShowHints(): Boolean {
        val nextIndex = persistence.getNextPilotIndex()
        if (nextIndex >= PilotDefinitions.getPilotCount()) return false
        return persistence.getRunsSincePilotUnlock() >= 1
    }

    fun getNextPilotIndex(): Int = persistence.getNextPilotIndex()

    fun isReadyToLaunch(): Boolean {
        return isShipUnlocked(selectedShipIndex) && isPilotUnlocked(selectedPilotIndex)
    }

    /**
     * (Re)build the walker roster from currently unlocked pilots, minus the selected
     * one (corruption also filters dead pilots and Astro). Stale pending walker
     * mutations are dropped alongside the list they were meant for. Must be called
     * with selectedPilotIndex already final. Runs at initialize(), on every
     * corruption return (via initCorruptionState) and on every astro-loop return —
     * without the astro-loop rebuild, the empty end-of-corruption roster leaks into
     * the freshly-revived bar and pilots only reappear as the player cycles them.
     */
    fun rebuildNpcWalkers() {
        val newWalkers = mutableListOf<WalkerNPC>()
        val npcRandom = kotlin.random.Random(System.currentTimeMillis())
        val isCorrupted = StoryStateManager.isCorrupted(persistence)
        for (i in 0 until PilotDefinitions.getPilotCount()) {
            if (i != selectedPilotIndex && isPilotUnlocked(i)) {
                val pilot = PilotDefinitions.getPilotByIndex(i) ?: continue
                // Skip dead pilots in corruption state
                if (isCorrupted && StoryStateManager.isPilotDead(persistence, pilot.id)) continue
                // Skip Astro in corruption (he's at the slot machine / unavailable until crystal)
                if (isCorrupted && pilot.id == "pilot_astro") continue
                newWalkers.add(WalkerNPC(
                    pilotIndex = i,
                    color = pilot.color,
                    x = npcRandom.nextFloat() * 0.8f + 0.1f,
                    targetX = npcRandom.nextFloat() * 0.8f + 0.1f,
                    walking = false,
                    idleTimer = npcRandom.nextFloat() * 2.5f + 1f
                ))
            }
        }
        npcWalkers = CopyOnWriteArrayList(newWalkers)
        pendingNPCAdds.clear()
        pendingNPCRemoves.clear()
    }

    /**
     * Reinitialize hangar state for corruption phase.
     * Called on return from the boss-victory run (first time corruption activates)
     * and on subsequent corruption-phase returns.
     * Rebuilds NPC walkers to exclude dead pilots and auto-selects Astro+Specter
     * when the crystal is unlocked and all crew are dead.
     */
    fun initCorruptionState(persistence: PersistenceManager) {
        // Astro hangs at slot machine in corruption (before all crew are dead, or during crystal reveal)
        astroAtSlotMachine = !StoryStateManager.allCrewDead(persistence) || persistence.isAwaitingCrystalReveal()

        // Auto-select Astro+Specter when crystal unlocked and all crew dead
        if (persistence.isCrystalUnlocked() && StoryStateManager.allCrewDead(persistence)) {
            if (persistence.isAwaitingCrystalReveal()) {
                // Crystal reveal not yet played — no pilot, Specter on carousel
                selectedPilotIndex = -1
                awaitingCrystalReveal = true
                crystalRevealPhase = CrystalRevealPhase.GLOW
                val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
                if (specterIndex >= 0) selectedShipIndex = specterIndex
            } else {
                // Reveal already played — select Astro+Specter normally
                val astroIndex = PilotDefinitions.pilots.indexOfFirst { it.id == "pilot_astro" }
                val specterIndex = ShipDefinitions.ships.indexOfFirst { it.id == "ship_white" }
                if (astroIndex >= 0) selectedPilotIndex = astroIndex
                if (specterIndex >= 0) selectedShipIndex = specterIndex
            }
        } else {
            // If currently selected pilot/ship is now dead, find first available
            if (!isPilotUnlocked(selectedPilotIndex)) {
                val firstAvailable = (0 until PilotDefinitions.getPilotCount()).firstOrNull { isPilotUnlocked(it) }
                if (firstAvailable != null) selectedPilotIndex = firstAvailable
            }
            if (!isShipUnlocked(selectedShipIndex)) {
                val firstAvailable = (0 until ShipDefinitions.ships.size).firstOrNull { isShipUnlocked(it) }
                if (firstAvailable != null) selectedShipIndex = firstAvailable
            }
        }

        // After selection is final — dead pilots and Astro filtered out by isPilotUnlocked()
        rebuildNpcWalkers()
    }

    fun saveAstroHintState() {
        persistence.setAstroHintCount(astroHintCount)
        persistence.setAstroHinted(astroHinted)
    }

    fun saveSelection() {
        getSelectedShip()?.let { persistence.setSelectedShipId(it.id) }
        getSelectedPilot()?.let { persistence.setSelectedPilotId(it.id) }
    }
}
