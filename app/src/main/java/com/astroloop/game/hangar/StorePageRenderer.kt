package com.astroloop.game.hangar

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.astroloop.game.cabinet.CabinetBezelRenderer
import com.astroloop.game.cabinet.CabinetMarqueeDrift
import com.astroloop.game.cabinet.CabinetRenderer
import com.astroloop.game.cabinet.CabinetSim
import com.astroloop.game.core.AudioMode
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.CrystalCardBack
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.SlotOdds
import com.astroloop.game.data.StoreUpgradeDefinitions
import com.astroloop.game.render.CrystalPalette
import com.astroloop.game.render.FontManager
import com.astroloop.game.render.IconCache
import com.astroloop.game.render.TextWrap
import com.astroloop.game.system.CrystalReckoning
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class StorePageRenderer(
    private val persistence: PersistenceManager,
    private val textPaint: Paint,
    private val costPaint: Paint
) {
    var screenWidth = 0f

    /**
     * Width of this room in design units. Equals screenWidth below sw600dp in portrait; rotated,
     * it stays the device's PORTRAIT room width while the screen is much wider.
     */
    var roomWidth = 0f

    /**
     * Whether the screen is rotated. The room keeps its portrait width either way — this says
     * whether it is one of the two that lean toward the shipyard rather than filling the screen.
     */
    var landscape = false

    /** Room width with a fallback for the frame before dimensions arrive. */
    private val rw: Float get() = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)

    /**
     * The content column's left edge in ROOM-local units — this page draws inside its room's
     * translate, and `content` is a screen-space rect. Above the gate the room is the content
     * column, so this is 0; below the gate it is `content.left` unchanged, which is why nothing
     * moves on a phone.
     *
     * PORTRAIT ONLY, since the landscape panels: its only two readers — the board and the machine — are both
     * inside `if (!landscape)` now. That matters because `content` is the LANDSCAPE content rect
     * when the screen is rotated while `roomWidth` is the device's PORTRAIT room width, and mixing
     * the two put the in-room board a few hundred units off its own room. Rotated, nothing reads
     * this at all; the panels measure against the portrait-shaped layout instead.
     *
     * In portrait with a SIDE cutout this is `content.left` including the cutout's offset, so the
     * board sits centred in the content column rather than in the room — 48.2 design units right
     * of room centre on a 128px left cutout. That is content-anchored layout doing its job, and
     * the draw and the hit test both come through this one getter, so they agree.
     */
    private val contentLeftInRoom: Float get() = HangarMetrics.contentXInRoom(content.left, roomWidth, screenWidth)

    var screenHeight = 0f
    var walkwayY = 0f
    var ceilingY = 0f
    var content: LayoutRect = LayoutRect(0f, 0f, 0f, 0f)

    val upgradeRects = CopyOnWriteArrayList<RectF>()
    val storeButtonRects = CopyOnWriteArrayList<RectF>()
    // Written every frame on the game thread (draw(), below) and read from the UI thread on every
    // tap (HangarSurfaceView.handleStoreTap, for the tile-9 hit test) — @Volatile so the touch
    // handler is guaranteed to see the current rect rather than a stale or torn one.
    @Volatile var crystalTileRect = RectF()

    /**
     * Where this room actually drew corrupted Astro's dot, in ROOM-LOCAL space, or `NaN` when it
     * drew none. Rewritten on every [draw], which is every frame this room is on screen.
     *
     * A seam, in the same spirit as `HangarRenderer.drawnSelectedShipX` and for the same reason:
     * the dot is handed to `HangarRenderer.drawCrystalReveal` for the duration of the flight, and
     * the two failure modes of that handover — the room keeping its copy (the dot on screen twice
     * at two alphas, once under the panel scrim and once over it) and the reveal never picking it
     * up (a corkscrew that starts at nothing) — are both invisible to a test that can only see
     * that the phases advanced. Reading NaN/not-NaN off both sides is what tells them apart.
     *
     * It also pins the handover's POSITION in portrait: the point the reveal draws at, mapped
     * back through the store page's own origin, must be this exact point.
     *
     * Not `@Volatile`, unlike [crystalTileRect] just above: that field is read by the touch
     * handler on the UI thread, a genuine cross-thread reader this one has none of — the only
     * reader is `CrystalRevealPanelTest`, on the same thread as the render pass under Robolectric,
     * exactly like `HangarRenderer.drawnSelectedShipX` / `drawnRevealSrcX,Y` / `drawnRevealDstX,Y`
     * (matching the convention those seams already set rather than the
     * other way around).
     */
    var drawnAstroDotX: Float = Float.NaN
        private set
    var drawnAstroDotY: Float = Float.NaN
        private set

    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    private val crystalDescPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = FontManager.getRegular()
    }

    // Shared across the upgrade grid — color/alpha mutated per-tile before each draw, same
    // convention as textPaint/costPaint/crystalDescPaint above. Promoted out of drawStorePage's
    // local scope so drawUpgradeFront/drawUpgradeBack (private methods) can reach them too.
    private val tileBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val pipPaint = Paint().apply { style = Paint.Style.FILL }

    lateinit var drawRoomFrame: (Canvas, Boolean, Boolean, Boolean) -> Unit

    /**
     * [bezelSim]/[bezelRenderer] are the store page's own attract demo — see
     * HangarSurfaceView's bezelSim doc comment. [marqueeDrift] is the marquee plate's
     * ambient rock drift, owned the same way. All threaded through as parameters, the
     * same way [state] itself flows from HangarRenderer.render() down to here, rather
     * than living on StorePageRenderer as fields: this class only ever draws what it's
     * handed, and these demos' lifecycles belong to HangarSurfaceView, not to a renderer.
     */
    fun draw(
        canvas: Canvas,
        state: HangarState,
        xOffset: Float,
        bezelSim: CabinetSim? = null,
        bezelRenderer: CabinetRenderer? = null,
        marqueeDrift: CabinetMarqueeDrift? = null
    ) {
        canvas.save()
        canvas.translate(-xOffset, 0f)
        // Clip to this room, exactly as the shipyard page does: nothing the shop draws may reach
        // into a neighbouring room. Below the gate the room is the whole screen, so the clip is
        // the screen and nothing is cut — defence in depth, not a layout change.
        canvas.clipRect(0f, 0f, rw, screenHeight)

        // Room frame: ceiling + archway on left + solid right wall
        drawRoomFrame(canvas, true, false, true)
        drawStoreDecoration(canvas)

        upgradeRects.clear()

        // --- 3x3 black market upgrade grid ---
        // In landscape the board is not in the room: the screen is too short for it at true tile
        // size, so the SHOP panel owns it and HangarRenderer.drawPanelLayer calls drawUpgradeTiles
        // below with the panel's own rects. Everything else the room holds — the decoration, the
        // walkway props, Astro's dot — stays exactly where it is in both orientations.
        if (!landscape) {
            // Room-local (see contentLeftInRoom): this page draws inside its room's translate, so a
            // screen-space content.left would push the grid a room-offset to the right of its room.
            val gridBounds = GridGeometry.storeGridBounds(content, contentLeftInRoom, walkwayY)
            drawUpgradeTiles(
                canvas, state,
                GridGeometry.storeTileRects(gridBounds, GridGeometry.STORE_COLS * GridGeometry.STORE_ROWS)
            )
        } else {
            // Nothing drawn, nothing hit-testable — see clearRoomStoreRects.
            crystalTileRect = RectF()
        }

        // Miniature slot machine position on store walkway (always at base position).
        // Read side of the walker band: HangarState.pilotWorldX(2, 0.1f) puts the pilot at page
        // 2's origin plus `margin + 0.1f * walkable`, i.e. exactly here once this page's own
        // translate has supplied that origin. Measured against the ROOM, like the write side,
        // which measures page 2 against RoomAnchor.pageWidth(2) — the room width in either
        // orientation. Below the gate in portrait roomWidth is the screen width, so this is the
        // shipped `pilotScreenWidth` formula unchanged.
        val margin = rw * 0.1f
        val walkable = rw * 0.8f
        val storeTargetX = margin + 0.1f * walkable
        if (StoryStateManager.isAstroLoop(persistence)) {
            CabinetBezelRenderer.drawWalkwayMini(canvas, storeTargetX, walkwayY)
        } else {
            drawMiniSlotMachine(canvas, storeTargetX, walkwayY)
        }

        // Corrupted Astro at slot machine (auto-gambling during corruption)
        drawnAstroDotX = Float.NaN
        drawnAstroDotY = Float.NaN
        if (state.astroAtSlotMachine) {
            val astroX = storeTargetX
            val astroY = walkwayY - 8f
            val time = System.currentTimeMillis()

            // Darkened Astro dot (Astro is red 0xFFDD3333, corrupted = 50% brightness)
            //
            // Handed to HangarRenderer.drawCrystalReveal for the flight: the orb has to read as
            // leaving HIM, and in landscape the room sits under the panel's scrim while the orb
            // flies above it. Drawn in both places it would be on screen twice at two alphas, so
            // the room stands down rather than the reveal drawing a second copy. Only the dot
            // moves up — the mini machine and the shield aura below stay behind the scrim, since
            // they are the room's furniture and not part of the moment.
            if (!state.crystalRevealInFlight()) {
                val astroDotPaint = Paint().apply {
                    color = StoryStateManager.corruptColor(0xFFDD3333.toInt())
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(astroX, astroY - 6f, 5f, astroDotPaint)
                drawnAstroDotX = astroX
                drawnAstroDotY = astroY - 6f
            }

            // Shield aura glow when crystal not yet unlocked or during reveal glow phase
            if (!persistence.isCrystalUnlocked() || state.crystalRevealPhase == HangarState.CrystalRevealPhase.GLOW) {
                val pulse = (0.4f + 0.6f * ((sin((time % 10000L) / 1000.0) + 1f) / 2f)).toFloat()
                val glowPaint = Paint().apply {
                    // color set per-ring below (MID outer / ICE inner)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                // Outer ring (shield boundary)
                glowPaint.color = CrystalPalette.MID
                glowPaint.alpha = (pulse * 120).toInt()
                canvas.drawCircle(astroX, astroY - 6f, 10f + pulse * 2f, glowPaint)
                // Inner ring (brighter)
                glowPaint.color = CrystalPalette.ICE
                glowPaint.alpha = (pulse * 200).toInt()
                glowPaint.strokeWidth = 1.5f
                canvas.drawCircle(astroX, astroY - 6f, 7f + pulse * 1f, glowPaint)
            }

            // The corkscrew and the burst used to be here. They moved to
            // HangarRenderer.drawCrystalReveal, above the panel layer: in landscape the tile they
            // fly to belongs to the SHOP panel and the walkway they leave belongs to the room, so
            // the flight crosses two layers and can only be drawn from the one that owns both.
        }

        // Slot machine below walkway — in landscape it belongs to the SLOT panel, exactly as the
        // board belongs to the SHOP panel above.
        if (!landscape) {
            val gridBounds = GridGeometry.storeGridBounds(content, contentLeftInRoom, walkwayY)
            drawSlotMachineAt(
                canvas, state, GridGeometry.machineFrame(gridBounds, walkwayY, screenHeight),
                bezelSim, bezelRenderer, marqueeDrift
            )
        } else {
            clearRoomStoreRects(state)
        }

        // Reset paints
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        canvas.restore()
    }

    /**
     * Draws the nine upgrade tiles into [rects], in whatever space the caller's canvas is in:
     * room-local for the in-room board, screen space for the landscape SHOP panel. One tile
     * renderer, two placements — a second copy of this body would be the three-copy pilot grid all
     * over again (see GridGeometry's own doc).
     *
     * [upgradeRects] is therefore "the tiles as drawn", in the space they were drawn in: empty in
     * landscape with the panel shut, and screen space while it is open. `HangarSurfaceView.
     * storeHitPoint` is the one place that decides which of those two spaces a hit test is in.
     *
     * Clears [upgradeRects] itself rather than trusting [draw]'s clear to have run first: the panel
     * can call this on a frame where the shop room was culled off-screen and [draw] never ran at
     * all — the same reason `BarPageRenderer.drawPilotCards` self-clears.
     *
     * The tile size is taken from the rects rather than re-derived: every tile in the board is a
     * square of the same side, and `GridGeometry.storeTileRects` builds them from
     * `GridGeometry.storeTileSize`, so the two agree bit-for-bit on every device profile (verified
     * across the spec's landscape profiles, cutout and no cutout).
     *
     * [rects] carries one rect per tile in `StoreUpgradeDefinitions.tiles` order — that order IS
     * the layout, as it has always been — so both callers pass all nine.
     */
    fun drawUpgradeTiles(canvas: Canvas, state: HangarState, rects: List<LayoutRect>) {
        upgradeRects.clear()
        crystalTileRect = RectF()
        val tileSize = rects.firstOrNull()?.width ?: return

        val tileBgPaint = Paint().apply {
            color = 0xFF1A1A2E.toInt()
            style = Paint.Style.FILL
        }

        // Tiles live in StoreUpgradeDefinitions: the order below IS the grid layout, since each
        // tile's row and column come from its index.
        val tiles = StoreUpgradeDefinitions.tiles

        for ((index, tile) in tiles.withIndex()) {
            val lr = rects[index]
            val rect = RectF(lr.left, lr.top, lr.right, lr.bottom)

            // Only add purchasable tiles to upgradeRects
            if (tile.isNgPlus) {
                // Crystal tile — auto-unlocks, no purchase needed
                crystalTileRect = RectF(rect)
                if (StoryStateManager.isAstroLoop(persistence)) {
                    // Emergency Shield — auto-equipped; mirrors the Time Crystal tile
                    canvas.drawRoundRect(rect, 6f, 6f, tileBgPaint)
                    if (state.storeFlipShowBack(index)) {
                        drawShieldBack(canvas, rect, state.storeFlipProgress(index), tileSize)
                    } else {
                        tileBorderPaint.color = CrystalPalette.MID   // icy cyan border
                        canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)

                        // Icon — top ~33%
                        val shieldBitmap = IconCache.getStoreIcon("emergency_shield")
                        if (shieldBitmap != null) {
                            val iconSize = tileSize * 0.33f
                            val iconLeft = rect.centerX() - iconSize / 2f
                            val iconTop = rect.top + tileSize * 0.05f
                            canvas.drawBitmap(shieldBitmap, null,
                                RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize), bitmapPaint)
                        }

                        // Name
                        textPaint.textSize = (tileSize * 0.17f).coerceIn(14f, 24f)
                        textPaint.color = CrystalPalette.ICE
                        canvas.drawText("Emergency Shield", rect.centerX(), rect.top + tileSize * 0.52f, textPaint)

                        // Description — aligns with the Time Crystal description row
                        crystalDescPaint.textSize = (tileSize * 0.16f).coerceIn(13f, 22f)
                        crystalDescPaint.color = CrystalPalette.DEEP
                        canvas.drawText("Survive a lethal hit", rect.centerX(), rect.top + tileSize * 0.62f, crystalDescPaint)

                        // Auto-equipped — aligns with the EQUIPPED row on the Time Crystal tile
                        costPaint.textSize = (tileSize * 0.16f).coerceIn(13f, 22f)
                        costPaint.color = 0xFF44FF44.toInt()
                        canvas.drawText("EQUIPPED", rect.centerX(), rect.top + tileSize * 0.88f, costPaint)
                    }
                    continue
                }
            } else {
                upgradeRects.add(rect)
            }

            // Tile background
            canvas.drawRoundRect(rect, 6f, 6f, tileBgPaint)

            if (tile.isNgPlus) {
                val corrupted = StoryStateManager.isCorrupted(persistence)
                val crystalUnlocked = persistence.isCrystalUnlocked()

                if (!corrupted) {
                    // Not corrupted — mystery tile
                    tileBorderPaint.color = 0xFF444444.toInt()
                    canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)
                    textPaint.textSize = (tileSize * 0.25f).coerceIn(16f, 28f)
                    // The same grey as the tile effect/description row ("+10% yen"). The shipped
                    // 0xFF555555 measured 2.29:1 on this background, under the 3:1 floor; this is
                    // 7.06:1. Both mystery branches must stay identical or the tile visibly
                    // shifts when the story state flips.
                    textPaint.color = 0xFFAAAAAA.toInt()
                    canvas.drawText("?", rect.centerX(), rect.centerY() + tileSize * 0.05f, textPaint)
                } else if (!crystalUnlocked || state.awaitingCrystalReveal) {
                    // Corrupted but crystal not unlocked — unknown tile (matches non-corrupted "?" style)
                    tileBorderPaint.color = 0xFF444444.toInt()
                    canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)
                    textPaint.textSize = (tileSize * 0.25f).coerceIn(16f, 28f)
                    // The same grey as the tile effect/description row ("+10% yen"). The shipped
                    // 0xFF555555 measured 2.29:1 on this background, under the 3:1 floor; this is
                    // 7.06:1. Both mystery branches must stay identical or the tile visibly
                    // shifts when the story state flips.
                    textPaint.color = 0xFFAAAAAA.toInt()
                    canvas.drawText("?", rect.centerX(), rect.centerY() + tileSize * 0.05f, textPaint)
                } else if (state.storeFlipShowBack(index)) {
                    // Corrupted + crystal unlocked, flipped — AGAIN, tiled
                    drawCrystalBack(canvas, rect, state.storeFlipProgress(index), tileSize)
                } else {
                    // Corrupted + crystal unlocked — show Time Crystal tile (auto-equipped)
                    tileBorderPaint.color = CrystalPalette.MID   // icy cyan border
                    canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)

                    // Crystal icon bitmap
                    val crystalBitmap = IconCache.getStoreIcon(null)
                    if (crystalBitmap != null) {
                        val iconSize = tileSize * 0.33f
                        val iconLeft = rect.centerX() - iconSize / 2f
                        val iconTop = rect.top + tileSize * 0.05f
                        canvas.drawBitmap(crystalBitmap, null,
                            RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize), bitmapPaint)
                    }

                    // Name
                    textPaint.textSize = (tileSize * 0.17f).coerceIn(14f, 24f)
                    textPaint.color = CrystalPalette.ICE
                    canvas.drawText("TIME CRYSTAL", rect.centerX(), rect.top + tileSize * 0.52f, textPaint)

                    // Description — aligns with effect text row on regular tiles
                    crystalDescPaint.textSize = (tileSize * 0.16f).coerceIn(13f, 22f)
                    crystalDescPaint.color = CrystalPalette.DEEP
                    canvas.drawText("Astro's fate", rect.centerX(), rect.top + tileSize * 0.62f, crystalDescPaint)

                    // Auto-equipped — aligns with MAX/cost row on regular tiles
                    costPaint.textSize = (tileSize * 0.16f).coerceIn(13f, 22f)
                    costPaint.color = 0xFF44FF44.toInt()
                    canvas.drawText("EQUIPPED", rect.centerX(), rect.top + tileSize * 0.88f, costPaint)
                }
            } else {
                // Hold-to-buy fill, drawn BEFORE the faces so it reads as the tile's background
                // filling rather than a wash over the words: the copy has to stay readable while
                // it rises. Then a short exit (a completion flash or a fade) rather than
                // vanishing in one frame.
                if (index == state.storeHoldIndex && state.storeHoldProgress > 0f) {
                    drawHoldFill(canvas, rect, state.storeHoldProgress, alpha = 1f, flash = false)
                } else if (index == state.storeHoldExitIndex && state.storeHoldExitAlpha > 0f) {
                    drawHoldFill(
                        canvas, rect, state.storeHoldExitProgress,
                        alpha = state.storeHoldExitAlpha, flash = state.storeHoldExitSuccess
                    )
                }

                val flipAlpha = state.storeFlipProgress(index)
                val currentLevel = persistence.getUpgradeLevel(tile.id!!)
                if (state.storeFlipShowBack(index)) {
                    drawUpgradeBack(canvas, rect, tile, currentLevel, flipAlpha, tileSize)
                } else {
                    // A card mid-peek is fading its front out; one at rest draws at full alpha.
                    val frontAlpha = if (state.isStoreCardFlipped(index)) flipAlpha else 1f
                    drawUpgradeFront(canvas, rect, tile, currentLevel, frontAlpha, tileSize)
                }
            }
        }
    }

    /**
     * Whether tile 9 is showing a real face rather than a `?`.
     *
     * Mirrors the branch structure in `drawStorePage` exactly. Both mystery branches must answer
     * false together — a tile that flips in one and not the other would visibly differ across a
     * story-state change, which the source explicitly forbids.
     */
    fun isCrystalTileRevealed(persistence: PersistenceManager, state: HangarState): Boolean {
        if (StoryStateManager.isAstroLoop(persistence)) return true
        if (!StoryStateManager.isCorrupted(persistence)) return false
        return persistence.isCrystalUnlocked() && !state.awaitingCrystalReveal
    }

    /**
     * The hold-to-buy fill along a tile's foot — a live sweep while [alpha] is 1, or a decaying
     * exit (a completion [flash] or a plain fade) once the hold has ended.
     *
     * Clipped to the tile's own rounded shape rather than drawn as a plain rect: the tile is a
     * 6f-radius round rect, and a square-cornered fill flush with the bottom edge shows nubs
     * poking past the rounded corners.
     */
    /**
     * The hold-to-buy fill: the tile's whole background flooding from the bottom up.
     *
     * Owner, 2026-08-09 — the first cut was a thin gold bar sweeping left to right along the foot
     * of the tile, which was easy to miss and told the player nothing about how much longer to
     * hold. Filling the box itself makes the progress the size of the thing being bought.
     *
     * **The colour is chosen to be seen through.** A bright or warm wash would sit on top of the
     * tile's gold cost row and white name and bleach them; a deep, cool tint darkens the tile
     * enough to show a hard waterline against the unfilled part while leaving light text readable
     * on both sides of it. This is drawn *under* the faces for the same reason. The two constants
     * below are the tuning knobs if it still reads badly on a device — S2 covers it.
     */
    private fun drawHoldFill(canvas: Canvas, rect: RectF, progress: Float, alpha: Float, flash: Boolean) {
        val filled = rect.height() * progress.coerceIn(0f, 1f)
        val fillTop = rect.bottom - filled

        pipPaint.color = if (flash) HOLD_FILL_FLASH_COLOR else HOLD_FILL_COLOR
        pipPaint.alpha = ((if (flash) 210 else HOLD_FILL_ALPHA) * alpha.coerceIn(0f, 1f))
            .toInt().coerceIn(0, 255)
        canvas.save()
        // Clip to the rising waterline, then paint the tile's own rounded shape so the fill picks
        // up the bottom corners instead of showing square nubs outside them.
        canvas.clipRect(rect.left, fillTop, rect.right, rect.bottom)
        canvas.drawRoundRect(rect, 6f, 6f, pipPaint)
        canvas.restore()
        pipPaint.alpha = 255
    }

    /**
     * A normal tile's front: icon, name, effect line, level pips and the cost row.
     *
     * [alpha] scales every paint so the front can cross-fade out when the card flips. At rest it is
     * 1f and this draws exactly what it always drew.
     */
    private fun drawUpgradeFront(
        canvas: Canvas,
        rect: RectF,
        tile: StoreUpgradeDefinitions.UpgradeTile,
        currentLevel: Int,
        alpha: Float,
        tileSize: Float
    ) {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        val maxLevel = 5

        // Border
        tileBorderPaint.color = 0xFFCC8844.toInt()
        tileBorderPaint.alpha = a
        canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)
        tileBorderPaint.alpha = 255

        // Icon — top ~33% of tile
        val iconBitmap = IconCache.getStoreIcon(tile.id)
        if (iconBitmap != null) {
            val iconSize = tileSize * 0.33f
            val iconLeft = rect.centerX() - iconSize / 2f
            val iconTop = rect.top + tileSize * 0.05f
            // ITEM 24: always full alpha, no dimming on max
            bitmapPaint.alpha = a
            canvas.drawBitmap(
                iconBitmap, null,
                RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize),
                bitmapPaint
            )
            bitmapPaint.alpha = 255
        }

        // Name — ~50% from top (ITEM 25: font size ~21% larger: 0.14 -> 0.17, coerceIn 12/18 -> 14/22)
        textPaint.textSize = (tileSize * 0.17f).coerceIn(14f, 24f)
        // ITEM 24: always white, no gray when maxed
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.alpha = a
        canvas.drawText(tile.name, rect.centerX(), rect.top + tileSize * 0.52f, textPaint)
        textPaint.alpha = 255

        // Effect text — ~62% from top (ITEM 4: new effect description row)
        val effectTextPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = FontManager.getRegular()
            textSize = (tileSize * 0.16f).coerceIn(13f, 22f)
            color = 0xFFAAAAAA.toInt()
        }
        effectTextPaint.alpha = a
        canvas.drawText(tile.effect, rect.centerX(), rect.top + tileSize * 0.62f, effectTextPaint)

        // Level pips — ~73% from top (ITEM 25: pip radius ~20% larger: 0.03->0.036, coerceIn 3/5 -> 3.6/6)
        val pipRadius = (tileSize * 0.036f).coerceIn(3.6f, 6f)
        val pipSpacing = pipRadius * 3.2f
        val pipsWidth = (maxLevel - 1) * pipSpacing
        val pipStartX = rect.centerX() - pipsWidth / 2f
        val pipY = rect.top + tileSize * 0.73f

        for (p in 0 until maxLevel) {
            val px = pipStartX + p * pipSpacing
            if (p < currentLevel) {
                pipPaint.color = 0xFFCC8844.toInt()
                pipPaint.style = android.graphics.Paint.Style.FILL
            } else {
                pipPaint.color = 0xFF333344.toInt()
                pipPaint.style = android.graphics.Paint.Style.FILL
            }
            pipPaint.alpha = a
            canvas.drawCircle(px, pipY, pipRadius, pipPaint)
        }
        pipPaint.alpha = 255

        drawCostRow(canvas, rect, currentLevel, maxLevel, a, tileSize)
    }

    /**
     * The cost, or MAX once there is nothing left to buy.
     *
     * Drawn identically on both faces from `StoreBackLayout.PRICE_BASELINE`, so turning a card over
     * does not move the figure. It is the one thing on the front that survives the flip, which is
     * the point: the back is where the player decides, and the decision needs the price.
     */
    private fun drawCostRow(
        canvas: Canvas,
        rect: RectF,
        currentLevel: Int,
        maxLevel: Int,
        a: Int,
        tileSize: Float
    ) {
        costPaint.textSize = StoreTextSizes.frontBody(tileSize)
        costPaint.alpha = a
        val y = rect.top + tileSize * StoreBackLayout.PRICE_BASELINE
        if (currentLevel >= maxLevel) {
            costPaint.color = 0xFF44FF44.toInt()
            canvas.drawText("MAX", rect.centerX(), y, costPaint)
        } else {
            costPaint.color = 0xFFFFD700.toInt()
            canvas.drawText(
                GameConfig.formatYen(PersistenceManager.getUpgradeCost(currentLevel)),
                rect.centerX(), y, costPaint
            )
        }
        costPaint.alpha = 255
    }

    /**
     * The card back: what the player has accumulated, what one more level buys, and one plain
     * sentence of what the thing does.
     *
     * The cost row is drawn here too, at the same baseline the front uses, so the figure does not
     * move when the card turns — a player reading the back to decide whether to buy should not have
     * to turn it over again to find the price. "Next" is omitted at max, where the cost row shows
     * MAX instead.
     */
    private fun drawUpgradeBack(
        canvas: Canvas,
        rect: RectF,
        tile: StoreUpgradeDefinitions.UpgradeTile,
        level: Int,
        alpha: Float,
        tileSize: Float
    ) {
        val a = (255 * alpha).toInt().coerceIn(0, 255)

        tileBorderPaint.color = 0xFFCC8844.toInt()
        tileBorderPaint.alpha = a
        canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)

        // Every size here comes from StoreTextSizes, which holds the rule that the back is never
        // set smaller than the front — a rule about the resolved size, not the scale factor,
        // because the coerceIn caps are what actually bind on an ordinary phone.
        textPaint.textSize = StoreTextSizes.backTitle(tileSize)
        textPaint.color = 0xFFCC8844.toInt()
        textPaint.alpha = a
        canvas.drawText("${tile.name}  $level/5", rect.centerX(), rect.top + tileSize * 0.14f, textPaint)

        // Measure the description's wrap first: how many lines it takes is what decides whether
        // everything fits, and StoreBackLayout needs the count to choose the leading and whether
        // the next-level block survives.
        crystalDescPaint.textSize = StoreTextSizes.backDetail(tileSize)
        val detailLines = wrapToWidth(tile.detail, rect.width() * 0.88f, crystalDescPaint)
        val effectLines = tile.effectsAt(level)
        val nextLines = if (level < 5) tile.nextDeltas() else emptyList()
        val plan = StoreBackLayout.plan(tileSize, effectLines.size, nextLines.size, detailLines.size)
        val lineHeight = plan.lineStep

        var y = rect.top + tileSize * StoreBackLayout.TOP

        costPaint.textSize = StoreTextSizes.backEffect(tileSize)
        costPaint.color = 0xFFFFD700.toInt()
        costPaint.alpha = a
        for (line in effectLines) {
            canvas.drawText(line, rect.centerX(), y, costPaint)
            y += lineHeight
        }

        if (plan.showNext) {
            y += lineHeight * StoreBackLayout.BLOCK_GAP
            crystalDescPaint.textSize = StoreTextSizes.backNext(tileSize)
            crystalDescPaint.color = 0xFFAAAAAA.toInt()
            crystalDescPaint.alpha = a
            for (line in nextLines) {
                canvas.drawText("next $line", rect.centerX(), y, crystalDescPaint)
                y += lineHeight
            }
        }

        y += lineHeight * StoreBackLayout.BLOCK_GAP
        crystalDescPaint.textSize = StoreTextSizes.backDetail(tileSize)
        crystalDescPaint.color = 0xFFDDDDDD.toInt()
        crystalDescPaint.alpha = a
        for (line in detailLines) {
            canvas.drawText(line, rect.centerX(), y, crystalDescPaint)
            y += lineHeight * StoreBackLayout.DETAIL_LEADING
        }

        // The one thing that survives the flip. StoreBackLayout.BOTTOM_LIMIT is set to keep the
        // content above this row, so it can be drawn last without measuring against it here.
        drawCostRow(canvas, rect, level, maxLevel = 5, a = a, tileSize = tileSize)

        tileBorderPaint.alpha = 255
        textPaint.alpha = 255
        costPaint.alpha = 255
        crystalDescPaint.alpha = 255
    }

    /**
     * Word wrap against a measured pixel width — the tile is a third of the screen.
     *
     * Two-line descriptions are balanced rather than greedy, so a card back never reads
     * "Everything you fire hits" over a lone "harder." See [TextWrap].
     */
    private fun wrapToWidth(text: String, maxWidth: Float, paint: Paint): List<String> =
        TextWrap.wrap(text, maxWidth, paint::measureText)

    /**
     * AGAIN, repeated until the tile is full.
     *
     * Plain `drawText` per row, clipped to the tile. The row is sized off the *narrowest* letter so
     * it always over-runs the tile rather than leaving a ragged right edge, and the clip takes care
     * of the overhang.
     */
    private fun drawCrystalBack(canvas: Canvas, rect: RectF, alpha: Float, tileSize: Float) {
        val a = (255 * alpha).toInt().coerceIn(0, 255)

        tileBorderPaint.color = CrystalPalette.MID
        tileBorderPaint.alpha = a
        canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)

        textPaint.textSize = (tileSize * 0.10f).coerceIn(8f, 14f)
        textPaint.color = CrystalPalette.ICE
        textPaint.alpha = a
        textPaint.textAlign = Paint.Align.LEFT

        val narrowest = textPaint.measureText("I").coerceAtLeast(1f)
        val columns = (rect.width() / narrowest).toInt().coerceAtLeast(4)
        val lineHeight = textPaint.textSize * 1.15f
        val rows = ((rect.height() * 0.80f) / lineHeight).toInt().coerceAtLeast(4)
        val line = CrystalCardBack.row(columns)

        var y = rect.top + (rect.height() - rows * lineHeight) / 2f + lineHeight

        canvas.save()
        canvas.clipRect(rect)
        repeat(rows) {
            canvas.drawText(line, rect.left, y, textPaint)
            y += lineHeight
        }
        canvas.restore()

        // Reset before returning — textPaint and tileBorderPaint are shared across every tile in
        // the grid; a leaked alpha or a left-behind LEFT align would dim or misalign whatever this
        // frame draws with them next, same convention as drawUpgradeFront/drawUpgradeBack.
        tileBorderPaint.alpha = 255
        textPaint.alpha = 255
        textPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * The shield's back exists to correct its own front.
     *
     * "Survive a lethal hit" reads as *fight on*, and that is not what happens: in Astro Loop mode
     * `handlePlayerDeath` routes to `startRetreat()`, which sets health to 1, makes the ship
     * invulnerable, flies it off the bottom of the screen and saves with `includeDeath = false`.
     * The run ends — but the player leaves alive, keeps the takings, and no death is recorded.
     *
     * The first line is the paradox: in Astro Loop the boss ship never existed, because the crystal
     * was never picked up. The plating is salvage from a branch that was erased, which plays
     * against the Time Crystal's back three states earlier insisting it has already happened.
     */
    private fun drawShieldBack(canvas: Canvas, rect: RectF, alpha: Float, tileSize: Float) {
        val a = (255 * alpha).toInt().coerceIn(0, 255)

        tileBorderPaint.color = CrystalPalette.MID
        tileBorderPaint.alpha = a
        canvas.drawRoundRect(rect, 6f, 6f, tileBorderPaint)

        // Same type scale as every other card back (StoreTextSizes), rather than a private set of
        // fractions that ran a third smaller. This face was the only one in the grid the player
        // had to lean in for.
        textPaint.textSize = StoreTextSizes.backTitle(tileSize)
        textPaint.color = CrystalPalette.ICE
        textPaint.alpha = a
        canvas.drawText("EMERGENCY SHIELD", rect.centerX(), rect.top + tileSize * 0.15f, textPaint)

        // Status line — parallel to the green "EQUIPPED" the front shows, but styled like the
        // front's own subtitle row (crystalDescPaint + CrystalPalette.DEEP, same as "Survive a
        // lethal hit") rather than as body prose. The front's green is a purchase-affordance
        // color used for MAX/EQUIPPED states elsewhere in this grid; the back never buys
        // anything (design spec: "purchase stays a front-face gesture, and the back is purely
        // for reading"), so reusing it here would borrow a vocabulary this face doesn't use.
        // Sits in the gap already reserved between the title and the body start below — the
        // body's own start position and rhythm are untouched by this line.
        crystalDescPaint.textSize = StoreTextSizes.backDetail(tileSize)
        crystalDescPaint.color = CrystalPalette.DEEP
        crystalDescPaint.alpha = a
        canvas.drawText("Always equipped", rect.centerX(), rect.top + tileSize * 0.27f, crystalDescPaint)

        crystalDescPaint.textSize = StoreTextSizes.backDetail(tileSize)
        crystalDescPaint.color = 0xFFDDDDDD.toInt()
        crystalDescPaint.alpha = a

        // Title 0.15, status 0.27, body 0.40 — the two gaps opened from 0.07/0.10 on 2026-08-11.
        // They were set when this face ran 14px type; at the shared 22px they had the name, the
        // status and the prose reading as one block.
        var y = rect.top + tileSize * 0.40f
        // Leading off the *type*, not the tile. As a tile fraction it collapsed on small screens:
        // backDetail caps at 22px, so a 222px tile kept full-size text on 0.11 x 222 = 24px rows,
        // and the descenders ran into the line below.
        val lineHeight = StoreTextSizes.backDetail(tileSize) * SHIELD_BACK_LEADING
        for (paragraph in SHIELD_BACK_BODY) {
            if (paragraph.isEmpty()) { y += lineHeight * 0.5f; continue }
            for (line in wrapToWidth(paragraph, rect.width() * 0.88f, crystalDescPaint)) {
                canvas.drawText(line, rect.centerX(), y, crystalDescPaint)
                y += lineHeight
            }
        }

        // Reset before returning — same shared-Paint convention as drawCrystalBack above.
        tileBorderPaint.alpha = 255
        textPaint.alpha = 255
        crystalDescPaint.alpha = 255
    }

    private fun drawStoreDecoration(canvas: Canvas) {
        val roomTop = ceilingY + 3f
        val roomBottom = walkwayY
        val time = System.currentTimeMillis()

        val fillPaint = Paint().apply { style = Paint.Style.FILL }
        val linePaint = Paint().apply {
            color = 0xFF555566.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val accentPaint = Paint().apply { style = Paint.Style.FILL }

        // =============================================
        // LEFT SIDE — Stacked crates (~35% width)
        // =============================================
        val crateLeft = 10f
        val crateRight = rw * 0.32f

        // Large crate (bottom)
        fillPaint.color = 0xFF1A1A28.toInt()
        val bigCrate = RectF(crateLeft, roomBottom - 35f, crateLeft + 45f, roomBottom - 2f)
        canvas.drawRect(bigCrate, fillPaint)
        linePaint.color = 0xFF3A3A4A.toInt()
        linePaint.strokeWidth = 1f
        canvas.drawRect(bigCrate, linePaint)
        // Cross slats
        canvas.drawLine(bigCrate.left, bigCrate.top, bigCrate.right, bigCrate.bottom, linePaint)
        canvas.drawLine(bigCrate.right, bigCrate.top, bigCrate.left, bigCrate.bottom, linePaint)

        // Medium crate (stacked on top, offset)
        fillPaint.color = 0xFF1E1E2C.toInt()
        val medCrate = RectF(crateLeft + 5f, roomBottom - 60f, crateLeft + 40f, roomBottom - 35f)
        canvas.drawRect(medCrate, fillPaint)
        linePaint.color = 0xFF3A3A4A.toInt()
        canvas.drawRect(medCrate, linePaint)

        // Small open crate (right of big one) with interior glow
        fillPaint.color = 0xFF181828.toInt()
        val openCrate = RectF(crateLeft + 50f, roomBottom - 28f, crateRight, roomBottom - 2f)
        canvas.drawRect(openCrate, fillPaint)
        linePaint.color = 0xFF3A3A4A.toInt()
        canvas.drawRect(openCrate, linePaint)
        // Faint glow inside (contraband)
        val glowPulse = 0.4f + 0.3f * sin(time / 1200.0).toFloat()
        accentPaint.color = 0xFFCC8844.toInt()
        accentPaint.alpha = (glowPulse * 40).toInt()
        canvas.drawRect(openCrate.left + 3f, openCrate.top + 3f, openCrate.right - 3f, openCrate.bottom - 3f, accentPaint)

        // Tall crate at far left (background)
        fillPaint.color = 0xFF161626.toInt()
        val tallCrate = RectF(crateLeft, roomTop + 20f, crateLeft + 22f, roomBottom - 35f)
        canvas.drawRect(tallCrate, fillPaint)
        linePaint.color = 0xFF2A2A3A.toInt()
        canvas.drawRect(tallCrate, linePaint)

        // =============================================
        // CENTER — Bare bulb on wire
        // =============================================
        val centerX = rw * 0.5f

        // Wire from ceiling
        linePaint.color = 0xFF444444.toInt()
        linePaint.strokeWidth = 1f
        canvas.drawLine(centerX, roomTop, centerX, roomTop + 18f, linePaint)

        // Bulb — irregular flicker (dying tube effect)
        val flickerSeed = (time / 80).toInt()
        val flickerHash = (flickerSeed * 2654435761L).toInt()
        val flicker = if ((flickerHash % 17) < 2) 0.2f  // Brief dark flickers
            else 0.65f + 0.35f * sin(time / 300.0).toFloat()

        // Bulb shape (small circle)
        accentPaint.color = 0xFFDDAA55.toInt()
        accentPaint.alpha = (flicker * 220).toInt()
        canvas.drawCircle(centerX, roomTop + 22f, 3f, accentPaint)

        // Light cone below bulb
        accentPaint.alpha = (flicker * 15).toInt()
        val coneHalfWidth = 40f
        val coneBottom = roomBottom - 10f
        val path = android.graphics.Path().apply {
            moveTo(centerX - 4f, roomTop + 24f)
            lineTo(centerX - coneHalfWidth, coneBottom)
            lineTo(centerX + coneHalfWidth, coneBottom)
            lineTo(centerX + 4f, roomTop + 24f)
            close()
        }
        canvas.drawPath(path, accentPaint)

        // =============================================
        // Loose cables from ceiling
        // =============================================
        linePaint.color = 0xFF333344.toInt()
        linePaint.strokeWidth = 1f
        val sway1 = sin(time / 2000.0).toFloat() * 3f
        val sway2 = sin(time / 2500.0 + 1.0).toFloat() * 4f

        // Cable 1
        val cable1X = rw * 0.25f
        canvas.drawLine(cable1X, roomTop, cable1X + sway1, roomTop + 25f, linePaint)
        canvas.drawLine(cable1X + sway1, roomTop + 25f, cable1X + sway1 * 1.5f, roomTop + 40f, linePaint)

        // Cable 2
        val cable2X = rw * 0.72f
        canvas.drawLine(cable2X, roomTop, cable2X + sway2, roomTop + 30f, linePaint)
        canvas.drawLine(cable2X + sway2, roomTop + 30f, cable2X + sway2 * 1.3f, roomTop + 50f, linePaint)

        // =============================================
        // RIGHT SIDE — Tarp over shelves
        // =============================================
        val tarpLeft = rw * 0.68f
        val tarpRight = rw - 10f

        // Shelves behind tarp (partially visible)
        fillPaint.color = 0xFF1A1A28.toInt()
        canvas.drawRect(tarpLeft + 5f, roomTop + 15f, tarpRight - 3f, roomTop + 17f, fillPaint)
        canvas.drawRect(tarpLeft + 5f, roomTop + 40f, tarpRight - 3f, roomTop + 42f, fillPaint)

        // Tarp (rough triangular drape)
        fillPaint.color = 0xFF2A2820.toInt()
        fillPaint.alpha = 180
        val tarpPath = android.graphics.Path().apply {
            moveTo(tarpRight - 2f, roomTop + 8f)
            lineTo(tarpRight - 2f, roomBottom - 15f)
            lineTo(tarpLeft + 15f, roomBottom - 8f)
            lineTo(tarpLeft + 8f, roomTop + 30f)
            close()
        }
        canvas.drawPath(tarpPath, fillPaint)
        fillPaint.alpha = 255
        // Tarp edge
        linePaint.color = 0xFF3A3830.toInt()
        linePaint.strokeWidth = 1f
        canvas.drawLine(tarpRight - 2f, roomTop + 8f, tarpLeft + 8f, roomTop + 30f, linePaint)
        canvas.drawLine(tarpLeft + 8f, roomTop + 30f, tarpLeft + 15f, roomBottom - 8f, linePaint)

        // =============================================
        // Sign — "BLACK MARKET"
        // =============================================
        val signX = rw * 0.5f
        val signY = roomTop + 12f
        // BLACK MARKET — crooked, flickering red neon
        canvas.save()
        canvas.rotate(-2f, signX, signY)
        textPaint.textSize = 16f
        // Irregular neon flicker
        val neonFlicker = if ((flickerHash % 23) < 3) 0.3f
            else 0.7f + 0.3f * sin(time / 400.0).toFloat()
        textPaint.color = 0xFFCC4422.toInt()
        textPaint.alpha = (neonFlicker * 255).toInt()
        canvas.drawText("BLACK MARKET", signX, signY, textPaint)
        // Neon glow
        accentPaint.color = 0xFFCC4422.toInt()
        accentPaint.alpha = (neonFlicker * 25).toInt()
        canvas.drawRoundRect(RectF(signX - 60f, signY - 14f, signX + 60f, signY + 5f), 3f, 3f, accentPaint)
        canvas.restore()

        // =============================================
        // Floor details near walkway
        // =============================================
        accentPaint.color = 0xFF333340.toInt()
        accentPaint.alpha = 255
        val floorRandom = Random(42)
        // Scattered bolts
        for (b in 0 until 8) {
            val bx = 20f + floorRandom.nextFloat() * (rw - 40f)
            val by = roomBottom - 4f - floorRandom.nextFloat() * 6f
            canvas.drawCircle(bx, by, 0.8f + floorRandom.nextFloat() * 0.5f, accentPaint)
        }
        // Spilled canister
        fillPaint.color = 0xFF2A2A35.toInt()
        val canX = rw * 0.42f
        canvas.drawRoundRect(RectF(canX, roomBottom - 6f, canX + 8f, roomBottom - 2f), 1f, 1f, fillPaint)
        // Scuff marks
        linePaint.color = 0xFF252535.toInt()
        linePaint.strokeWidth = 2f
        canvas.drawLine(rw * 0.55f, roomBottom - 2f, rw * 0.60f, roomBottom - 3f, linePaint)
        canvas.drawLine(rw * 0.20f, roomBottom - 1f, rw * 0.24f, roomBottom - 3f, linePaint)

        // Reset paint state
        textPaint.alpha = 255
        textPaint.color = 0xFFFFFFFF.toInt()
    }

    // Slot machine symbol IDs
    companion object {
        /**
         * Every interactive control on the shop page, as focus targets.
         *
         * Optional rects are nullable because they genuinely come and go — the hatch only exists
         * once drawn, and the mute buttons live on state rather than in a list. A null publishes
         * nothing rather than an empty rect a player could land on.
         *
         * [originX] converts room-local to screen space, the same seam the bar page has: these
         * rects are recorded inside the page's translate, while FocusNavigator and handleStoreTap
         * both work in screen space. Below the gate it is zero and this is the identity.
         *
         * Actions tap the control's own centre through handleStoreTap, so the audio mode cycle,
         * the vibration toggle and the purchase rules are all the existing code, reached the
         * existing way.
         *
         * **The hatch is never published.** It is an unmarked panel that TB-26 only hints at
         * sideways, and a ring landing on it drew brackets around the secret — the finding is the
         * puzzle. A pad opens it with the directional sequence instead. Once open, the paper
         * sticking out is published, because that is what a finger taps to read the codex.
         *
         * @return the id of the first control published, or null if the page had none. The
         *   landscape SHOP/SLOT panels use it to put the ring on their first item when they open,
         *   without a second copy of the id scheme above to drift from this one.
         */
        fun publishStoreTargets(
            focus: FocusRegistry,
            upgradeRects: List<RectF>,
            buttonRects: List<RectF>,
            audioRect: RectF?,
            vibrationRect: RectF?,
            paperRect: RectF?,
            originX: Float,
            onTap: (Float, Float) -> Unit
        ): String? {
            var firstId: String? = null
            fun add(id: String, rect: RectF) {
                val screenRect = RectF(
                    rect.left + originX, rect.top,
                    rect.right + originX, rect.bottom
                )
                focus.add(FocusTarget(id, screenRect) {
                    onTap(screenRect.centerX(), screenRect.centerY())
                })
                if (firstId == null) firstId = id
            }
            for ((i, r) in upgradeRects.withIndex()) add("store:upgrade:$i", r)
            for ((i, r) in buttonRects.withIndex()) add("store:button:$i", r)
            audioRect?.let { add("store:audio", it) }
            vibrationRect?.let { add("store:vibration", it) }
            paperRect?.let { add("store:paper", it) }
            return firstId
        }

        /**
         * The Emergency Shield's card back, in two paragraphs with a blank line between.
         *
         * **Shortened when the type grew.** This face used to set its body at `0.10f` of the tile
         * capped at 14px while every other back used `backDetail`'s `0.16f` capped at 22px — the
         * one card in the grid the player had to lean in for (owner, 2026-08-11). Matching the
         * others costs roughly half again as much width per character, and S10 already had this
         * back within about one line of clipping, so the copy came down 26% to pay for it.
         *
         * What survived the cut is what the card is for: the flavour line, and the correction to
         * the front's "Survive a lethal hit" — which reads as *fight on*, where the code actually
         * calls `startRetreat()` and the run ends with you alive. "Withdraw" is the game's own
         * word for that.
         *
         * [SHIELD_BACK_BUDGET] guards the total; the device check is S10.
         */
        val SHIELD_BACK_BODY = listOf(
            "Salvaged from a ship that never was.",
            "",
            "When the hull fails, you withdraw alive."
        )

        /**
         * Baseline-to-baseline step on the shield's body, as a multiple of the **type size**.
         *
         * A multiple of the type rather than of the tile, which is what it used to be. `backDetail`
         * caps at 22px, so on a 222px tile the old `0.11 × tileSize` gave 24px rows to 22px text
         * and the descenders ran into the line below. Anchored to the type, the rows cannot
         * collapse under it whatever the tile does.
         */
        const val SHIELD_BACK_LEADING = 1.25f

        /**
         * Total characters the shield back's body may carry.
         *
         * Calibrated for a real phone tile (~342px on a 1080-wide screen), where the body runs
         * from `0.40` of the tile to the `0.94` floor: about 185px against a 27.5px step, so five
         * wrapped lines plus the blank paragraph. 76 characters currently wraps to four there.
         *
         * **Small tiles are the open question, not this number.** The type is capped, so a narrow
         * tile keeps 22px text in a narrower column: measured, 342px and 303px both wrap the copy
         * to four lines and end at 0.76 and 0.81, while a 240px tile wraps the second paragraph to
         * three, makes five, and runs to 1.03 — off the bottom. The turn is somewhere near 280px.
         * It wants a look on the narrowest screen to hand; the lever if it clips is the copy, down
         * 26% from where it started.
         */
        const val SHIELD_BACK_BUDGET = 80

        /**
         * The rising hold-to-buy fill: a deep, cool teal, deliberately not the store's amber.
         *
         * It has to be legible *through* — the tile's gold cost row and white name stay on top of
         * it — so it darkens and cools rather than brightening. Warm or bright washes bleach the
         * gold; this holds a visible waterline against the unfilled background while leaving text
         * readable above and below it.
         */
        private val HOLD_FILL_COLOR = 0xFF2E7D8A.toInt()
        /** Partial, so the tile beneath still reads as the tile. */
        private const val HOLD_FILL_ALPHA = 150
        /** The completion flash — brighter and lighter, held only for the exit fade. */
        private val HOLD_FILL_FLASH_COLOR = 0xFF7FE3D4.toInt()

        const val SYM_YEN = 0
        const val SYM_STAR = 1
        const val SYM_DIAMOND = 2
        const val SYM_ROCKET = 3
        const val SYM_BOLT = 4
        const val SYM_WRENCH = 5
        const val SYMBOL_COUNT = 6

        private val SYMBOL_NAMES = arrayOf("yen", "star", "diamond", "rocket", "bolt", "gear")
        fun getSymbolName(index: Int): String = SYMBOL_NAMES.getOrElse(index) { "?" }

        /**
         * The PAYOUTS panel, derived from [SlotOdds.CASH_TIERS] instead of written out.
         *
         * These numbers were hardcoded and went stale when the casino rebalance (#23) changed every
         * cash payout: the panel advertised 2000/700/100/75/50 while the machine actually paid
         * 750/300/100/50/20. Deriving them means the sign over the machine cannot lie about it
         * again. Order matters and is load-bearing — CASH_TIERS runs diamond, star, yen, bolt,
         * wrench, and [PAYOUT_SYMBOLS] lists those same five after the jackpot, so row i of one
         * describes row i of the other. SlotPayoutPanelTest pins that pairing; reordering
         * CASH_TIERS without reordering here fails it rather than quietly mislabelling the panel.
         */
        val PAYOUT_SYMBOLS = listOf(SYM_ROCKET, SYM_DIAMOND, SYM_STAR, SYM_YEN, SYM_BOLT, SYM_WRENCH)

        /** Labels for [PAYOUT_SYMBOLS]. The jackpot's prize varies, so it reads BONUS, not a number. */
        val PAYOUT_VALUES: List<String> = listOf("BONUS") + SlotOdds.CASH_TIERS.map { it.second.toString() }
    }

    var spinButtonRect = RectF()

    /**
     * Retract every tap rect the room's machine publishes, for the frames it draws no machine.
     *
     * Nothing drawn means nothing hit-testable. Without this the machine's rects would simply keep
     * whatever values the last draw left on them — the portrait ones from before a rotation, or the
     * SLOT panel's screen-space ones after it closed — and `handleStoreTap` would still match them:
     * a press on empty starfield spending 100¥ on a spin nobody can see, which is the bug a code review
     * found behind the panel scrim, one layer further out.
     *
     * Ordering: the room draws first and the panel layer second, within one frame, so a SLOT panel
     * open over this room clears here and republishes moments later in the same pass.
     *
     * `state.cabinetScreenRect` / `cabinetMarqueeRect` are deliberately NOT cleared: they are sizes
     * for the attract sims, not hit targets, and `HangarSurfaceView.update` rebuilds off them only
     * when the size changes.
     *
     * This only runs from inside [draw]'s landscape branch, so its "nothing drawn, nothing
     * hit-testable" guarantee is only as good as [drawPageContent]'s own cull check that decides
     * whether [draw] runs at all this frame — a store room scrolled
     * far enough off-screen to fail that check skips this clear along with everything else `draw`
     * would have done, and the rects above simply keep their last published values. Every profile
     * this task and its predecessors have exercised keeps the store room inside that window
     * whenever it matters, but a future caller relying on this method alone, off a cull window
     * that has grown less forgiving, should not assume more than that.
     */
    private fun clearRoomStoreRects(state: HangarState) {
        storeButtonRects.clear()
        spinButtonRect = RectF()
        state.hatchRect = null
        state.paperRect = null
        state.audioMuteButtonRect = null
        state.vibrationMuteButtonRect = null
        state.insertCoinRect = null
    }

    private fun drawMiniSlotMachine(canvas: Canvas, pilotX: Float, walkwayY: Float) {
        val fillPaint = Paint().apply { style = Paint.Style.FILL }
        val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        // Sized relative to the pilot character (~2x pilot height)
        val machineW = 18f
        val machineH = 32f
        val machineCX = pilotX
        val machineBottom = walkwayY
        val machineTop = machineBottom - machineH
        val left = machineCX - machineW / 2f
        val right = machineCX + machineW / 2f

        // Cabinet body
        fillPaint.color = 0xFF22223A.toInt()
        canvas.drawRoundRect(RectF(left, machineTop, right, machineBottom), 3f, 3f, fillPaint)
        linePaint.color = 0xFF665544.toInt()
        canvas.drawRoundRect(RectF(left, machineTop, right, machineBottom), 3f, 3f, linePaint)

        // Three tiny reel rectangles
        val reelW = machineW * 0.22f
        val reelH = machineH * 0.25f
        val reelY = machineTop + machineH * 0.2f
        val reelGap = (machineW - reelW * 3) / 4f
        fillPaint.color = 0xFF0A0A16.toInt()
        for (r in 0 until 3) {
            val rx = left + reelGap + r * (reelW + reelGap)
            canvas.drawRect(rx, reelY, rx + reelW, reelY + reelH, fillPaint)
        }

        // Faint screen glow below reels (the CRT)
        fillPaint.color = 0xFFCC8844.toInt()
        fillPaint.alpha = 35
        val glowTop = reelY + reelH + machineH * 0.06f
        val glowBottom = glowTop + machineH * 0.15f
        canvas.drawRoundRect(RectF(left + machineW * 0.12f, glowTop, right - machineW * 0.12f, glowBottom), 1f, 1f, fillPaint)
        fillPaint.alpha = 255
    }

    /**
     * The machine — the slot machine, or the BELT RUN cabinet in Astro Loop — drawn into [frame],
     * in whatever space the caller's canvas is in: room-local under the room's walkway, screen
     * space inside the landscape SLOT panel. Same contract as [drawUpgradeTiles].
     *
     * The frame is the caller's because the two placements measure it differently: the room hangs
     * it off its own grid's bounds, while the panel hands it the PORTRAIT frame at the panel's own
     * position — the machine is the size it is in portrait whichever way the device is held.
     *
     * Every rect this publishes for the tap path — [spinButtonRect], [storeButtonRects],
     * `state.hatchRect`, `state.paperRect`, the two mute toggles and `state.insertCoinRect` — is in
     * that same space, which is why the room clears them all ([clearRoomStoreRects]) on the frames
     * it draws no machine at all.
     */
    fun drawSlotMachineAt(
        canvas: Canvas,
        state: HangarState,
        frame: LayoutRect,
        bezelSim: CabinetSim?,
        bezelRenderer: CabinetRenderer?,
        marqueeDrift: CabinetMarqueeDrift? = null
    ) {
        // The slot machine and BELT RUN share this method's geometry exactly (see
        // CabinetBezelRenderer's doc comment) — this flag only ever changes what is drawn
        // inside each zone below, never where a zone's rect is computed.
        val isCabinet = StoryStateManager.isAstroLoop(persistence)
        storeButtonRects.clear()

        val time = System.currentTimeMillis()

        // A design decision: once the gate is open the crystal is taking the machine over, and it
        // has to be visible from the walkway — before the 100Y door, before PLAY. Until this
        // existed, nothing on the cabinet told a cleared board from an uncleared one, so a
        // player who had just cleared their twelfth pilot pressed PLAY and fell into the
        // ending with no warning — the undiscoverable-finale complaint in a new place.
        //
        // Read through CrystalReckoning rather than re-derived from the score table, so the
        // machine glitches under exactly the condition PLAY branches on. Null means the gate
        // is shut and every surface below early-outs, drawing precisely what it drew before.
        //
        // The `isCabinet &&` short-circuits, so the slot machine performs no extra read at
        // all. The cabinet's own per-frame persistence read is the idiom already in use —
        // HangarSurfaceView.renderCabinet reads twelve arcade scores plus three flags every
        // frame the overlay is up.
        val takeoverMs = if (isCabinet && CrystalReckoning.shouldEnter(
                persistence.allPilotsCleared(), persistence.isCrystalReleased()
            )
        ) time else null
        val machineTop = frame.top
        val machineBottom = frame.bottom
        val machineHeight = frame.height
        val machineLeft = frame.left
        val machineRight = frame.right  // machine width == grid width, content-centered

        // Right panel (payout table) — scales with the machine, not the full screen,
        // so its proportion stays constant across aspect ratios.
        val rightPanelWidth = frame.width * 0.15f

        // Left gap for maintenance hatch panel
        val machineWidth = machineRight - machineLeft
        val leftGap = machineWidth * 0.12f
        val firstReelLeft = machineLeft + leftGap

        // Reel area boundaries
        val reelAreaRight = machineRight - rightPanelWidth - 5f
        val reelAreaLeft = firstReelLeft - 10f

        // Three reels
        val reelGap = 8f
        val reelAreaTop = machineTop + 10f
        val reelAreaBottom = machineTop + machineHeight * 0.55f
        val reelTotalWidth = reelAreaRight - 10f - firstReelLeft
        val reelWidth = (reelTotalWidth - reelGap * 2) / 3f
        val reelHeight = reelAreaBottom - reelAreaTop

        val fillPaint = Paint().apply { style = Paint.Style.FILL }
        val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        // Ambient glow behind machine
        fillPaint.color = 0xFFCC8844.toInt()
        fillPaint.alpha = 12
        canvas.drawRoundRect(RectF(machineLeft - 10f, machineTop - 5f, machineRight + 10f, machineBottom + 5f), 10f, 10f, fillPaint)
        fillPaint.alpha = 255

        // Machine body
        fillPaint.color = 0xFF1A1A2E.toInt()
        canvas.drawRoundRect(RectF(machineLeft, machineTop, machineRight, machineBottom), 6f, 6f, fillPaint)
        linePaint.color = 0xFF4A3A2A.toInt()
        canvas.drawRoundRect(RectF(machineLeft, machineTop, machineRight, machineBottom), 6f, 6f, linePaint)

        if (isCabinet) {
            // Exactly the three reels' own span. reelAreaLeft/Right are padded outwards
            // and reach into the maintenance hatch on one side and the score board on the
            // other, which clipped both.
            val screenRect = RectF(firstReelLeft, reelAreaTop, firstReelLeft + reelTotalWidth, reelAreaBottom)
            // Published for HangarSurfaceView.update() to build the bezel's own CabinetSim/
            // CabinetRenderer at this rect's size — see HangarState.cabinetScreenRect.
            state.cabinetScreenRect = screenRect
            CabinetBezelRenderer.drawScreen(canvas, screenRect, bezelSim, bezelRenderer, takeoverMs)
        } else {
            for (r in 0 until 3) {
                val rx = firstReelLeft + r * (reelWidth + reelGap)
                val ry = reelAreaTop
                val reelRect = RectF(rx, ry, rx + reelWidth, ry + reelHeight)

                // Reel background
                fillPaint.color = 0xFF0E0E1A.toInt()
                canvas.drawRoundRect(reelRect, 4f, 4f, fillPaint)

                // Reel border
                linePaint.color = 0xFF3A3A4A.toInt()
                linePaint.strokeWidth = 1f
                canvas.drawRoundRect(reelRect, 4f, 4f, linePaint)

                // Draw symbol
                val symbolIndex: Int
                val isSpinning = state.isSpinning && time < state.reelStopTimes[r]

                if (isSpinning) {
                    // Spinning — cycle through symbols rapidly
                    val elapsed = time - (state.reelStopTimes[r] - 1500L) // ~1.5s spin time
                    val cycleSpeed = 80L // ms per symbol change
                    symbolIndex = ((elapsed / cycleSpeed) % SYMBOL_COUNT).toInt()
                } else if (state.isSpinning || state.spinResultTime > 0) {
                    // Stopped or post-spin — show final value
                    symbolIndex = state.reelValues[r]
                } else {
                    // Idle — show a default face
                    symbolIndex = listOf(SYM_STAR, SYM_YEN, SYM_DIAMOND)[r]
                }

                val symCenterX = rx + reelWidth / 2f
                val symCenterY = ry + reelHeight / 2f
                drawSymbol(canvas, symbolIndex, symCenterX, symCenterY, reelHeight * 0.3f, isSpinning)
            }
        }

        // Pay line (horizontal across all reels). Slot machine only - in Astro Loop
        // this rect is the cabinet's CRT, and a reel-window artifact bisecting a
        // vector game screen reads as a rendering bug.
        if (!isCabinet) {
            linePaint.color = 0xFFCC8844.toInt()
            linePaint.strokeWidth = 1f
            linePaint.alpha = 120
            val payLineY = reelAreaTop + reelHeight / 2f
            canvas.drawLine(reelAreaLeft + 5f, payLineY, reelAreaRight - 5f, payLineY, linePaint)
            linePaint.alpha = 255
        }

        // --- Below reels: Result Screen (top) + Spin Button (bottom) ---
        // Three equal gaps: reels→result, result→button, button→machine bottom
        val belowReelsSpace = machineBottom - reelAreaBottom
        val boxHeight = machineHeight * 0.12f
        val reelCenterX = (reelAreaLeft + reelAreaRight) / 2f
        val buttonWidth = (reelAreaRight - reelAreaLeft) * 0.80f
        val totalBoxSpace = boxHeight * 2
        val gap = (belowReelsSpace - totalBoxSpace) / 3f

        val resultTop = reelAreaBottom + gap
        val resultBottom = resultTop + boxHeight
        val resultLeft = reelCenterX - buttonWidth / 2f
        val resultRight = reelCenterX + buttonWidth / 2f
        val resultRect = RectF(resultLeft, resultTop, resultRight, resultBottom)

        val buttonTop = resultBottom + gap
        val buttonBottom = buttonTop + boxHeight
        val buttonRect = RectF(resultLeft, buttonTop, resultRight, buttonBottom)

        spinButtonRect = buttonRect
        storeButtonRects.add(buttonRect)

        // --- CRT Result Screen ---
        // Hoisted out of the branch below: the spin button's label (unconditional, further down)
        // sizes its text off this same value, so both paths need it in scope.
        val fontSize = (boxHeight * 0.38f).coerceIn(12f, 20f)

        if (isCabinet) {
            // The marquee. message carries the vibration/audio toggle feedback (same
            // 3-second window the amber-TTF readout used below) and takes the whole
            // plate when present; otherwise CabinetBezelRenderer.drawMarquee falls back
            // to the machine's own name, which is the point of this replacing the old
            // FLYING/idle readout below — a player can finally read BELT RUN without
            // paying the 100Y door to the overlay.
            val messageAge = time - state.readoutMessageTime
            val message = state.readoutMessage?.takeIf {
                state.readoutMessageTime > 0 && messageAge < 3000L
            }
            // Published for HangarSurfaceView.update() to build the marquee's own
            // CabinetMarqueeDrift at this rect's size — see HangarState.cabinetMarqueeRect.
            state.cabinetMarqueeRect = resultRect
            CabinetBezelRenderer.drawMarquee(
                canvas, resultRect, message, bezelRenderer, marqueeDrift, takeoverMs
            )
        } else {
        // Bezel
        fillPaint.color = 0xFF2A2A35.toInt()
        canvas.drawRoundRect(RectF(resultLeft - 3f, resultTop - 3f, resultRight + 3f, resultBottom + 3f), 5f, 5f, fillPaint)

        // Screen background with amber tint
        fillPaint.color = 0xFF121218.toInt()
        canvas.drawRoundRect(resultRect, 4f, 4f, fillPaint)

        // Scanlines
        linePaint.color = 0xFF000000.toInt()
        linePaint.alpha = 20
        linePaint.strokeWidth = 1f
        var scanY = resultTop + 2f
        while (scanY < resultBottom) {
            canvas.drawLine(resultLeft + 2f, scanY, resultRight - 2f, scanY, linePaint)
            scanY += 3f
        }
        linePaint.alpha = 255

        // Result content
        val resultAge = time - state.spinResultTime
        val symbolSize = boxHeight * 0.55f

        val resultDuration = when {
            state.spinResultUpgrade != null -> 5000L
            state.spinResultSymbol == SYM_ROCKET -> 4000L
            else -> 3000L
        }
        val fadeDuration = 1000L

        // Button feedback takes the readout outright. Drawn before the spin-result branch on
        // purpose: the ordering IS the rule, so there is no condition to keep in sync.
        val messageAge = time - state.readoutMessageTime
        val message = state.readoutMessage
        if (message != null && state.readoutMessageTime > 0 && messageAge < 3000L) {
            val messageFade =
                if (messageAge > 3000L - fadeDuration) ((3000L - messageAge) / fadeDuration.toFloat()) else 1f
            textPaint.textSize = fontSize
            // Unused elsewhere on this screen, where white means a good win, grey a poor one and
            // pulsing gold a jackpot. This is the machine talking about itself, not an outcome —
            // and it is the amber the buttons themselves are lit in.
            textPaint.color = 0xFFCC8844.toInt()
            textPaint.alpha = (messageFade * 255).toInt()
            // No slot symbol, so the text is plainly centred and has the full box width.
            canvas.drawText(message, resultRect.centerX(), resultRect.centerY() + fontSize * 0.15f, textPaint)
            textPaint.alpha = 255
        } else if (state.spinResultTime > 0 && resultAge < resultDuration) {
            val fadeAlpha = if (resultAge > resultDuration - fadeDuration) ((resultDuration - resultAge) / fadeDuration.toFloat()) else 1f

            if (state.spinResultUpgrade != null) {
                // Jackpot — pulsing amber glow + rocket + scrolling text
                val pulse = (0.7f + 0.3f * sin(resultAge * 0.008f).toFloat())
                fillPaint.color = 0xFFFFAA22.toInt()
                fillPaint.alpha = (fadeAlpha * pulse * 40).toInt()
                canvas.drawRoundRect(RectF(resultLeft - 6f, resultTop - 6f, resultRight + 6f, resultBottom + 6f), 8f, 8f, fillPaint)
                fillPaint.alpha = 255

                drawSymbol(canvas, SYM_ROCKET, resultLeft + symbolSize * 0.8f, resultRect.centerY(), symbolSize, false)
                textPaint.textSize = fontSize
                textPaint.color = 0xFFFFAA22.toInt()
                textPaint.alpha = (fadeAlpha * 255).toInt()

                // Scroll from "JACKPOT!" to upgrade name
                val textX = resultRect.centerX() + symbolSize * 0.2f
                val textY = resultRect.centerY() + fontSize * 0.15f
                if (resultAge < 1500L) {
                    canvas.drawText("JACKPOT!", textX, textY, textPaint)
                } else if (resultAge < 2000L) {
                    // Scroll transition — slide up
                    val t = (resultAge - 1500L) / 500f
                    val offset = t * fontSize * 1.2f
                    textPaint.alpha = ((1f - t) * fadeAlpha * 255).toInt()
                    canvas.drawText("JACKPOT!", textX, textY - offset, textPaint)
                    textPaint.alpha = (t * fadeAlpha * 255).toInt()
                    canvas.drawText(state.spinResultUpgrade!!, textX, textY - offset + fontSize * 1.2f, textPaint)
                } else {
                    canvas.drawText(state.spinResultUpgrade!!, textX, textY, textPaint)
                }

                // Full-screen flash
                if (resultAge < 300L) {
                    fillPaint.color = 0xFFFFAA22.toInt()
                    fillPaint.alpha = ((1f - resultAge / 300f) * 30).toInt()
                    canvas.drawRect(0f, 0f, screenWidth, screenHeight, fillPaint)
                    fillPaint.alpha = 255
                }
            } else if (state.spinResultSymbol == SYM_ROCKET) {
                // Jackpot fallback — all upgrades maxed, gives 10k yen, shows rocket (not diamond)
                val pulse = (0.7f + 0.3f * sin(resultAge * 0.008f).toFloat())
                fillPaint.color = 0xFFFFAA22.toInt()
                fillPaint.alpha = (fadeAlpha * pulse * 40).toInt()
                canvas.drawRoundRect(RectF(resultLeft - 6f, resultTop - 6f, resultRight + 6f, resultBottom + 6f), 8f, 8f, fillPaint)
                fillPaint.alpha = 255

                drawSymbol(canvas, SYM_ROCKET, resultLeft + symbolSize * 0.8f, resultRect.centerY(), symbolSize, false)
                textPaint.textSize = fontSize
                textPaint.color = 0xFFFFAA22.toInt()
                textPaint.alpha = (fadeAlpha * 255).toInt()
                val textX = resultRect.centerX() + symbolSize * 0.2f
                val textY = resultRect.centerY() + fontSize * 0.15f
                if (resultAge < 1500L) {
                    canvas.drawText("JACKPOT!", textX, textY, textPaint)
                } else {
                    canvas.drawText("+${state.spinResultYen}\u00A5", textX, textY, textPaint)
                }

                // Full-screen flash
                if (resultAge < 300L) {
                    fillPaint.color = 0xFFFFAA22.toInt()
                    fillPaint.alpha = ((1f - resultAge / 300f) * 30).toInt()
                    canvas.drawRect(0f, 0f, screenWidth, screenHeight, fillPaint)
                    fillPaint.alpha = 255
                }
            } else if (state.spinResultSymbol == SYM_DIAMOND) {
                // Diamond win — strong cyan glow
                fillPaint.color = 0xFF44DDAA.toInt()
                fillPaint.alpha = (fadeAlpha * 35).toInt()
                canvas.drawRoundRect(RectF(resultLeft - 5f, resultTop - 5f, resultRight + 5f, resultBottom + 5f), 7f, 7f, fillPaint)
                fillPaint.alpha = 255

                drawSymbol(canvas, SYM_DIAMOND, resultLeft + symbolSize * 0.8f, resultRect.centerY(), symbolSize, false)
                textPaint.textSize = fontSize
                textPaint.color = 0xFF44DDAA.toInt()
                textPaint.alpha = (fadeAlpha * 255).toInt()
                canvas.drawText("+${state.spinResultYen}\u00A5", resultRect.centerX() + symbolSize * 0.2f, resultRect.centerY() + fontSize * 0.15f, textPaint)
            } else if (state.spinResultSymbol == SYM_STAR) {
                // Star win — subtle gold glow
                fillPaint.color = 0xFFFFD700.toInt()
                fillPaint.alpha = (fadeAlpha * 20).toInt()
                canvas.drawRoundRect(RectF(resultLeft - 3f, resultTop - 3f, resultRight + 3f, resultBottom + 3f), 6f, 6f, fillPaint)
                fillPaint.alpha = 255

                drawSymbol(canvas, SYM_STAR, resultLeft + symbolSize * 0.8f, resultRect.centerY(), symbolSize, false)
                textPaint.textSize = fontSize
                textPaint.color = 0xFFFFD700.toInt()
                textPaint.alpha = (fadeAlpha * 255).toInt()
                canvas.drawText("+${state.spinResultYen}\u00A5", resultRect.centerX() + symbolSize * 0.2f, resultRect.centerY() + fontSize * 0.15f, textPaint)
            } else if (state.spinResultYen > 0) {
                // Small win (yen, bolt, wrench) — dim gray or white, no glow.
                //
                // The symbol is the one the spin rolled, not one recovered from the payout. This
                // whole block used to map 100/75/50 back to a symbol, and #23 changed the payouts
                // out from under it: a 750 diamond took the star branch, a 300 star and a 50 bolt
                // both came out as the wrench. The reels showed the truth while the result box
                // beside them disagreed. handleSlotSpin already stores the symbol — read it.
                val matchedSymbol = if (state.spinResultSymbol >= 0) state.spinResultSymbol else SYM_WRENCH
                val textColor = if (state.spinResultYen >= 100) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()

                drawSymbol(canvas, matchedSymbol, resultLeft + symbolSize * 0.8f, resultRect.centerY(), symbolSize, false)
                textPaint.textSize = fontSize
                textPaint.color = textColor
                textPaint.alpha = (fadeAlpha * 255).toInt()
                canvas.drawText("+${state.spinResultYen}\u00A5", resultRect.centerX() + symbolSize * 0.2f, resultRect.centerY() + fontSize * 0.15f, textPaint)
            } else {
                // Mixed — "No luck" dim gray
                textPaint.textSize = fontSize
                textPaint.color = 0xFF555555.toInt()
                textPaint.alpha = (fadeAlpha * 200).toInt()
                canvas.drawText("No luck", resultRect.centerX(), resultRect.centerY() + fontSize * 0.15f, textPaint)
            }
            textPaint.alpha = 255
        } else {
            // Idle state — faint static flicker
            val flicker = kotlin.random.Random.nextFloat()
            if (flicker < 0.03f) {
                fillPaint.color = 0xFF181820.toInt()
                fillPaint.alpha = 40
                val flickerY = resultTop + kotlin.random.Random.nextFloat() * (resultBottom - resultTop)
                canvas.drawRect(resultLeft + 2f, flickerY, resultRight - 2f, flickerY + 2f, fillPaint)
                fillPaint.alpha = 255
            }
        }
        }

        // --- Spin Button ---
        // A credit already banked is spendable whatever the wallet says — you can walk
        // out of the cabinet without playing, and then the coin is already in the machine.
        // isCabinet-gated, so the slot machine's own enabled state is unchanged.
        val hasBankedCredit = isCabinet && persistence.getCabinetCredits() > 0
        val canSpin = !state.isSpinning && (state.actualYen >= 100 || hasBankedCredit)
        fillPaint.color = if (canSpin) 0xFF2A2A40.toInt() else 0xFF1A1A24.toInt()
        canvas.drawRoundRect(buttonRect, 4f, 4f, fillPaint)
        linePaint.color = if (canSpin) 0xFFCC8844.toInt() else 0xFF333344.toInt()
        linePaint.strokeWidth = 1.5f
        canvas.drawRoundRect(buttonRect, 4f, 4f, linePaint)

        textPaint.textSize = fontSize
        textPaint.color = if (canSpin) 0xFFFFDD88.toInt() else 0xFF555555.toInt()
        val buttonLabel = if (isCabinet) {
            when {
                // Holding a credit, pressing this costs nothing — the tap handler checks
                // credits before it ever reaches takeCoin(). Saying INSERT COIN here was a
                // lie that read as a double charge: you pressed expecting to pay, then saw
                // CREDIT 1 and concluded the second coin had vanished.
                //
                // START, not PRESS START: a cabinet prints START on the button face and
                // lets the SCREEN say "press start". Writing the verb on the thing you
                // press is the tell that the phrase came off the wrong surface.
                hasBankedCredit -> "START"
                state.actualYen < 100 && !state.isSpinning -> "NO \u00A5"
                else -> "INSERT COIN \u2014 100\u00A5"
            }
        } else {
            if (state.actualYen < 100 && !state.isSpinning) "NO \u00A5" else "SPIN \u2014 100\u00A5"
        }
        canvas.drawText(buttonLabel, buttonRect.centerX(), buttonRect.centerY() + fontSize * 0.35f, textPaint)
        state.insertCoinRect = if (isCabinet) RectF(buttonRect) else null

        // --- Payout table (right panel) ---
        val payoutLeft = machineRight - rightPanelWidth + 4f
        val payoutRight = machineRight - 4f
        val payoutTop = reelAreaTop
        val payoutCenterX = (payoutLeft + payoutRight) / 2f
        val lineH = reelHeight / 7f

        val payoutFontSize = (boxHeight * 0.38f).coerceIn(12f, 20f)

        val payoutTitlePaint = Paint().apply {
            textSize = payoutFontSize
            typeface = FontManager.getRegular()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            // Set here rather than only at the call site below, so the Astro Loop board —
            // which draws through this same Paint without touching its color — inherits a
            // legible amber instead of Paint's default black.
            color = 0xFFAA8855.toInt()
        }

        // Payout lines: 3 small symbols on left, value on right
        val payoutValuePaint = Paint().apply {
            textSize = payoutFontSize
            typeface = FontManager.getRegular()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
            // Same reasoning as payoutTitlePaint above.
            color = 0xFF888877.toInt()
        }

        if (isCabinet) {
            // CENTRED between the CRT's right edge and the cabinet's, rather than hung off
            // the right edge. A device pass: "a little off center" — and it was, by 2.5px:
            // payoutLeft sits 9px right of reelAreaRight while payoutRight sits 4px left of
            // machineRight.
            //
            // Computed HERE rather than by moving payoutLeft/payoutRight, because those two
            // are shared with the slot machine's payout table in the else branch below, and
            // that layout must stay byte-identical.
            val boardWidth = payoutRight - payoutLeft
            val boardLeft = reelAreaRight + ((machineRight - reelAreaRight) - boardWidth) / 2f
            CabinetBezelRenderer.drawBoard(
                canvas, persistence, boardLeft, boardLeft + boardWidth, payoutTop, lineH,
                payoutTitlePaint, payoutValuePaint, bezelRenderer, takeoverMs
            )
        } else {
            // Title
            payoutTitlePaint.color = 0xFFAA8855.toInt()
            canvas.drawText("PAYOUTS", payoutCenterX, payoutTop + lineH * 0.7f, payoutTitlePaint)

            val payoutSymbols = PAYOUT_SYMBOLS
            val payoutValues = PAYOUT_VALUES
            // Reserve room for the widest value text ("BONUS" is wider than any number,
            // and wider still under Exo 2) so the 3-symbol cluster can never clip it.
            // The cluster spans 3.6 * symbol size at 1.3x spacing; shrink the symbols
            // only if the panel is too narrow, otherwise keep their normal size.
            val maxValueWidth = payoutValues.maxOf { payoutValuePaint.measureText(it) }
            val symbolsLeft = payoutLeft + 4f
            val symbolsAvail = (payoutRight - 2f - maxValueWidth - 6f) - symbolsLeft
            val payoutSymSize = (lineH * 0.35f).coerceAtMost((symbolsAvail / 3.6f).coerceAtLeast(1f))
            val symbolSpacing = payoutSymSize * 1.3f
            for (i in payoutSymbols.indices) {
                val py = payoutTop + lineH * (i + 1.7f)
                // Draw 3 symbols side by side, starting from left
                val symbolsStartX = symbolsLeft + payoutSymSize * 0.5f
                for (s in 0 until 3) {
                    val sx = symbolsStartX + s * symbolSpacing
                    drawSymbol(canvas, payoutSymbols[i], sx, py - payoutFontSize * 0.3f, payoutSymSize, false)
                }
                payoutValuePaint.color = 0xFF888877.toInt()
                canvas.drawText(payoutValues[i], payoutRight - 2f, py, payoutValuePaint)
            }
        }

        // --- Maintenance hatch (left panel) — centered in gap between machineLeft and firstReelLeft ---
        val hatchCenterX = (machineLeft + firstReelLeft) / 2f
        val hatchW = (firstReelLeft - machineLeft) - 8f
        val hatchH = machineHeight * 0.30f
        val hatchCenterY = (reelAreaTop + reelAreaBottom) / 2f
        val hatchTopY = hatchCenterY - hatchH / 2f
        val hatchLeft = hatchCenterX - hatchW / 2f
        val hatchR = RectF(hatchLeft, hatchTopY, hatchLeft + hatchW, hatchTopY + hatchH)
        state.hatchRect = hatchR

        // Panel background
        fillPaint.color = 0xFF151525.toInt()
        canvas.drawRoundRect(hatchR, 3f, 3f, fillPaint)

        // Panel border — subtle seams
        linePaint.color = 0xFF2A2A3A.toInt()
        linePaint.strokeWidth = 1f
        canvas.drawRoundRect(hatchR, 3f, 3f, linePaint)

        // Small screws at corners
        fillPaint.color = 0xFF333344.toInt()
        val sr = 2f
        canvas.drawCircle(hatchLeft + 5f, hatchTopY + 5f, sr, fillPaint)
        canvas.drawCircle(hatchLeft + hatchW - 5f, hatchTopY + 5f, sr, fillPaint)
        canvas.drawCircle(hatchLeft + 5f, hatchTopY + hatchH - 5f, sr, fillPaint)
        canvas.drawCircle(hatchLeft + hatchW - 5f, hatchTopY + hatchH - 5f, sr, fillPaint)

        if (state.hatchOpen) {
            // Hatch ajar — draw slightly offset/open
            fillPaint.color = 0xFF0E0E1A.toInt()
            val openRect = RectF(hatchLeft - 2f, hatchTopY + 2f, hatchLeft + hatchW - 4f, hatchTopY + hatchH - 2f)
            canvas.drawRoundRect(openRect, 3f, 3f, fillPaint)

            // Paper peeking out — extends beyond hatch for a hastily-stashed look
            fillPaint.color = 0xFFCCBB99.toInt()
            val paperTopY = hatchTopY + hatchH * 0.3f - 3f
            val paperBottomY = hatchTopY + hatchH * 0.7f
            val paperLeftX = hatchLeft + 3f
            val paperRightX = hatchLeft + hatchW - 6f + 2f

            // Rotate paper ~6 degrees for a crooked, hastily-stashed look
            canvas.save()
            val paperCenterX = (paperLeftX + paperRightX) / 2f
            val paperCenterY = (paperTopY + paperBottomY) / 2f
            canvas.rotate(6f, paperCenterX, paperCenterY)

            canvas.drawRect(paperLeftX, paperTopY, paperRightX, paperBottomY, fillPaint)

            // Paper border
            linePaint.color = 0xFF998866.toInt()
            canvas.drawRect(paperLeftX, paperTopY, paperRightX, paperBottomY, linePaint)

            // Dog-ear fold on top-right corner
            val foldSize = (paperRightX - paperLeftX) * 0.2f
            val foldPath = Path().apply {
                moveTo(paperRightX - foldSize, paperTopY)  // Start of fold on top edge
                lineTo(paperRightX, paperTopY)              // Top-right corner
                lineTo(paperRightX, paperTopY + foldSize)   // Down the right edge
                close()
            }
            // Fold shadow (slightly darker than paper)
            fillPaint.color = 0xFFBBAA88.toInt()
            canvas.drawPath(foldPath, fillPaint)

            // Text hint on paper
            val tinyPaint = Paint().apply {
                textSize = 18f
                typeface = FontManager.getRegular()
                color = 0xFF554433.toInt()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("CODEX", (paperLeftX + paperRightX) / 2f, (paperTopY + paperBottomY) / 2f + 3f, tinyPaint)

            canvas.restore()

            // Store paper rect for touch — expanded to account for rotation
            state.paperRect = RectF(paperLeftX - 5f, paperTopY - 5f, paperRightX + 5f, paperBottomY + 5f)
        } else {
            state.paperRect = null
        }

        // --- Mute toggle buttons (bottom corners of machine body) ---
        val muteButtonRadius = machineHeight * 0.07f
        val muteButtonMargin = muteButtonRadius + 8f

        // spinButtonRect is assigned when the spin button is drawn earlier in this drawSlotMachine pass
        val spinCenterY = spinButtonRect.centerY()

        // Bottom-left: vibration mute
        val vibBtnCx = machineLeft + muteButtonMargin
        val vibBtnCy = spinCenterY
        state.vibrationMuteButtonRect = RectF(
            vibBtnCx - muteButtonRadius * 1.5f, vibBtnCy - muteButtonRadius * 1.5f,
            vibBtnCx + muteButtonRadius * 1.5f, vibBtnCy + muteButtonRadius * 1.5f
        )

        // Bottom-right: audio mute
        val audBtnCx = machineRight - muteButtonMargin
        val audBtnCy = spinCenterY
        state.audioMuteButtonRect = RectF(
            audBtnCx - muteButtonRadius * 1.5f, audBtnCy - muteButtonRadius * 1.5f,
            audBtnCx + muteButtonRadius * 1.5f, audBtnCy + muteButtonRadius * 1.5f
        )

        drawMuteButton(canvas, vibBtnCx, vibBtnCy, muteButtonRadius, state.vibrationMuted, isAudio = false)
        drawAudioButton(canvas, audBtnCx, audBtnCy, muteButtonRadius, state.audioMode)

        // Reset paint state
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.alpha = 255
    }

    private fun drawSymbol(canvas: Canvas, symbol: Int, cx: Float, cy: Float, size: Float, spinning: Boolean) {
        val bitmap = IconCache.getSlotSymbol(symbol) ?: return
        val half = size / 2f
        bitmapPaint.alpha = if (spinning) 180 else 255
        canvas.drawBitmap(bitmap, null, RectF(cx - half, cy - half, cx + half, cy + half), bitmapPaint)
        bitmapPaint.alpha = 255
    }

    /**
     * The audio button, which cycles four states rather than toggling two.
     *
     * One speaker cannot show two independent channels, so the speaker is the button and the two
     * things around it are the channels: the **wave arc means music**, the **burst means effects**.
     * Each is drawn only while that channel is audible, so all four states read as a distinct
     * shape. The full strikethrough is reserved for silence, where it still means what it always
     * meant.
     *
     * This is the part of the feature a device has to confirm. If the four states do not read at a
     * glance, the agreed fallback is separate buttons rather than an ambiguous glyph —
     * discoverability is already a theme of this release.
     */
    private fun drawAudioButton(canvas: Canvas, cx: Float, cy: Float, radius: Float, mode: AudioMode) {
        val silent = mode.everythingSilenced
        val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val linePaint = Paint().apply {
            style = Paint.Style.STROKE; isAntiAlias = true; strokeWidth = 1.5f
        }

        fillPaint.color = if (silent) 0xFF0E0E1A.toInt() else 0xFF2A2018.toInt()
        canvas.drawCircle(cx, cy, radius, fillPaint)
        linePaint.color = if (silent) 0xFF2A2A3A.toInt() else 0xFFCC8844.toInt()
        canvas.drawCircle(cx, cy, radius, linePaint)

        val iconSize = radius * 0.85f
        linePaint.color = if (silent) 0xFF444444.toInt() else 0xFFCC8844.toInt()
        linePaint.strokeWidth = 2.5f

        // Speaker body — always present, since the button is always the audio button.
        val left = cx - iconSize * 0.5f
        val right = cx + iconSize * 0.1f
        val top = cy - iconSize * 0.3f
        val bottom = cy + iconSize * 0.3f
        canvas.drawRect(left, top + iconSize * 0.15f, left + iconSize * 0.25f, bottom - iconSize * 0.15f, linePaint)
        val path = Path()
        path.moveTo(left + iconSize * 0.25f, top + iconSize * 0.15f)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(left + iconSize * 0.25f, bottom - iconSize * 0.15f)
        canvas.drawPath(path, linePaint)

        // Wave arc — the music channel.
        if (!mode.musicSilenced) {
            val waveX = right + iconSize * 0.2f
            canvas.drawArc(
                RectF(waveX - iconSize * 0.2f, cy - iconSize * 0.25f,
                      waveX + iconSize * 0.2f, cy + iconSize * 0.25f),
                -45f, 90f, false, linePaint
            )
        }

        // Burst — the effects channel: three short rays where the noise would be.
        if (!mode.effectsSilenced) {
            val burstX = right + iconSize * 0.55f
            linePaint.strokeWidth = 2f
            for (angleDeg in listOf(-40f, 0f, 40f)) {
                val rad = Math.toRadians(angleDeg.toDouble())
                val inner = iconSize * 0.12f
                val outer = iconSize * 0.30f
                canvas.drawLine(
                    burstX + (cos(rad) * inner).toFloat(), cy + (sin(rad) * inner).toFloat(),
                    burstX + (cos(rad) * outer).toFloat(), cy + (sin(rad) * outer).toFloat(),
                    linePaint
                )
            }
        }

        // Strikethrough is reserved for actual silence.
        if (silent) {
            linePaint.color = 0xFFAA3333.toInt()
            linePaint.strokeWidth = 3f
            canvas.drawLine(
                cx - radius * 0.6f, cy + radius * 0.6f,
                cx + radius * 0.6f, cy - radius * 0.6f,
                linePaint
            )
        }
    }

    private fun drawMuteButton(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        isMuted: Boolean, isAudio: Boolean
    ) {
        val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val linePaint = Paint().apply {
            style = Paint.Style.STROKE; isAntiAlias = true; strokeWidth = 1.5f
        }

        // Button base (recessed circle)
        fillPaint.color = if (isMuted) 0xFF0E0E1A.toInt() else 0xFF2A2018.toInt()
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // Button rim
        linePaint.color = if (isMuted) 0xFF2A2A3A.toInt() else 0xFFCC8844.toInt()
        canvas.drawCircle(cx, cy, radius, linePaint)

        // Icon
        val iconSize = radius * 0.85f
        linePaint.color = if (isMuted) 0xFF444444.toInt() else 0xFFCC8844.toInt()
        linePaint.strokeWidth = 2.5f

        if (isAudio) {
            // Speaker icon
            val left = cx - iconSize * 0.4f
            val right = cx + iconSize * 0.2f
            val top = cy - iconSize * 0.3f
            val bottom = cy + iconSize * 0.3f
            canvas.drawRect(left, top + iconSize * 0.15f, left + iconSize * 0.25f, bottom - iconSize * 0.15f, linePaint)
            val path = Path()
            path.moveTo(left + iconSize * 0.25f, top + iconSize * 0.15f)
            path.lineTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left + iconSize * 0.25f, bottom - iconSize * 0.15f)
            canvas.drawPath(path, linePaint)
            if (!isMuted) {
                val waveX = right + iconSize * 0.15f
                canvas.drawArc(
                    RectF(waveX - iconSize * 0.2f, cy - iconSize * 0.25f,
                          waveX + iconSize * 0.2f, cy + iconSize * 0.25f),
                    -45f, 90f, false, linePaint
                )
            }
        } else {
            // Vibration icon: three arcs
            for (i in 0 until 3) {
                val arcRadius = iconSize * (0.2f + i * 0.2f)
                canvas.drawArc(
                    RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius),
                    200f, 140f, false, linePaint
                )
            }
        }

        // Strikethrough when muted
        if (isMuted) {
            linePaint.color = 0xFFAA3333.toInt()
            linePaint.strokeWidth = 3f
            canvas.drawLine(
                cx - radius * 0.6f, cy + radius * 0.6f,
                cx + radius * 0.6f, cy - radius * 0.6f,
                linePaint
            )
        }
    }
}
