package com.astroloop.game.hangar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.astroloop.game.core.LayoutRect
import com.astroloop.game.core.StoryStateManager
import com.astroloop.game.data.BandanaDefinitions
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.data.PilotDef
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.PilotHintCards
import com.astroloop.game.entity.Boss
import com.astroloop.game.render.FontManager
import com.astroloop.game.render.IconCache
import com.astroloop.game.render.TextWrap
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import java.util.concurrent.CopyOnWriteArrayList

class BarPageRenderer(
    private val textPaint: Paint,
    private val costPaint: Paint,
    private val persistence: PersistenceManager
) {
    var screenWidth = 0f
    var roomWidth = 0f

    /**
     * Whether the screen is rotated. The room keeps its portrait width either way — this says
     * whether it is one of the two that lean toward the shipyard rather than filling the screen.
     */
    var landscape = false

    var screenHeight = 0f
    var walkwayY = 0f
    var ceilingY = 0f
    var content: LayoutRect = LayoutRect(0f, 0f, 0f, 0f)

    val pilotCardRects = CopyOnWriteArrayList<RectF>()
    var codexBookRect = RectF()
    var corrupted = false
    var astroLoop = false
    var dressing: BarDressing = BarDressing.forStage(com.astroloop.game.core.StoryStage.NORMAL)

    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    private val signPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = FontManager.getBold()
        letterSpacing = 0.15f
    }
    private val signGlowPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = FontManager.getBold()
        letterSpacing = 0.15f
    }
    private val signLinePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    private val cardBgPaint = Paint().apply { style = Paint.Style.FILL }
    private val cardBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Put a card's frame back on top of a face that painted over it.
     *
     * The border is stroked once, before either face, and a stroke straddles its own path —
     * so the back face's fill, which covers the whole card rect, buried the inner half of it
     * and a turned-over card read with a visibly thinner frame than every card beside it.
     *
     * The store never had this: its faces draw their own border after their own background
     * (`StorePageRenderer.drawUpgradeBack`). The bar page draws one border up front for both
     * faces, so the back is the one that has to restore it.
     *
     * Only the back face calls this, and only unlocked pilots have one, so it carries that
     * branch's weight and colour rather than re-deriving the locked variants. Drawn at the
     * card's own [cardAlpha] and not scaled by the flip: the frame is the one part of the
     * card that does not turn over, and animating it would be a second bug in place of the
     * first.
     */
    private fun restrokeCardBorder(canvas: Canvas, rect: RectF, borderColor: Int, cardAlpha: Int) {
        cardBorderPaint.color = borderColor
        cardBorderPaint.alpha = cardAlpha
        cardBorderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, 4f, 4f, cardBorderPaint)
        cardBorderPaint.alpha = 255
    }

    private val swagWirePaint = Paint().apply { color = 0xFF443322.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true }
    private val swagBulbPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val swagPath = Path()
    private val stringLightColors = listOf(0xFFFFCC88.toInt())
    private val fairyLightColors = listOf(
        0xFFFF0066.toInt(), 0xFFFFAA00.toInt(), 0xFF00FF88.toInt(),
        0xFF00AAFF.toInt(), 0xFFFF00FF.toInt()
    )

    private val confettiPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val confettiColors = intArrayOf(
        0xFFFF0066.toInt(), 0xFFFFAA00.toInt(), 0xFF00FF88.toInt(), 0xFF00AAFF.toInt(), 0xFFFF00FF.toInt()
    )
    // (x fraction 0..1, yOffset px below the walkway rect, colorIndex) — seeded once.
    // Band is 5..9px below walkwayY: the walkway line (walkwayY..+4, drawn later by
    // HangarRenderer over the bar page) would otherwise occlude confetti placed on it,
    // and chat starts at walkwayY+10, so 5..9 is the clear floor strip.
    private val confettiSpots: List<Triple<Float, Float, Int>> = run {
        val r = java.util.Random(99)
        List(26) { i -> Triple(r.nextFloat(), 5f + r.nextFloat() * 4f, i % confettiColors.size) }
    }


    private val sitterFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val sitterLimbPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val sitterBandPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    lateinit var drawRoomFrame: (Canvas, Boolean, Boolean, Boolean) -> Unit
    lateinit var drawNPCWalkers: (Canvas, HangarState) -> Unit
    lateinit var drawCharacter: (Canvas, Float, Float, Int, Boolean, Float) -> Unit

    fun draw(canvas: Canvas, state: HangarState, xOffset: Float) {
        canvas.save()
        canvas.translate(-xOffset, 0f)
        // Clip to this room, exactly as the shipyard page does: nothing the bar draws may reach
        // into a neighbouring room. Below the gate the room is the whole screen, so the clip is
        // the screen and nothing is cut — defence in depth, not a layout change.
        canvas.clipRect(0f, 0f, HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth), screenHeight)

        drawNormalBar(canvas, state)

        canvas.restore()
    }

    private fun drawNormalBar(canvas: Canvas, state: HangarState) {
        // Room frame: ceiling + solid left wall + archway on right
        drawRoomFrame(canvas, true, true, false)

        // --- Pilot portrait grid (top half, 4 columns x 3 rows) ---
        pilotCardRects.clear()

        // In landscape the grid is not in the room: the screen is too short for it at true card
        // size, so the CREW panel owns it and HangarRenderer.drawPanelLayer calls drawPilotCards
        // below with the panel's own rects. What is left here is the counter strip under its
        // ceiling line, exactly as wide as it is in portrait.
        if (!landscape) {
            // Room-local: everything below is drawn inside this page's translate, where the room
            // spans 0..roomWidth. `content` is a SCREEN-space rect, so its X coordinates have to
            // cross into room space first or the grid lands a room-offset to the right of its own
            // room. Above the gate the room is the content column, so this is 12..roomWidth-12 —
            // 2px inside the counter's barLeft/barRight, which is the point of the feature. Below
            // the gate contentXInRoom is the identity and the grid stays exactly where it ships.
            // Vertical stays screen-space: rooms tile horizontally only.
            val bounds = GridGeometry.pilotGridBounds(content, roomWidth, screenWidth)
            drawPilotCards(
                canvas, state, GridGeometry.pilotCardRects(bounds, PilotDefinitions.getPilotCount())
            )
        }

        // Bar area with TB-26 bartender, codex book, and neon lamps
        drawBarArea(canvas, state)
        drawNPCWalkers(canvas, state)

        // Chat messages below walkway
        drawChatMessages(canvas, state)
    }

    /**
     * Draws the pilot cards into [rects], in whatever space the caller's canvas is in: room-local
     * for the in-room grid, screen space for the landscape CREW panel. One card renderer, two
     * placements — a second copy of this body is the three-copy pilot grid all over again.
     *
     * [pilotCardRects] is therefore "the cards as drawn", in the space they were drawn in: empty
     * in landscape with the panel shut, and screen-space while it is open. Its one consumer,
     * `HangarSurfaceView.publishBarFocus`, adds a room origin to it in portrait only; in landscape
     * it returns early, and the panel publishes its own screen-space targets.
     *
     * The card height is hoisted out of the loop from the first rect, exactly as the old
     * `GridGeometry.pilotCardSize(bounds).second` was hoisted: every card in a grid is the same
     * size, and the two expressions agree bit-for-bit on every device profile (verified — row 0's
     * top IS `bounds.top`, so `(top + cardH) - top` round-trips).
     *
     * Clears [pilotCardRects] itself rather than trusting [drawNormalBar]'s clear to have run
     * first: since an earlier fix, the landscape CREW panel can call this while it is
     * fading closed on a page other than the bar's own — `HangarRenderer.drawPanelLayer` draws a
     * closing panel regardless of the current page — and on those pages `drawNormalBar` is culled
     * (its room is off-screen) and never runs at all this frame. Without a self-clear, this call
     * would append onto whatever [pilotCardRects] already held instead of replacing it.
     */
    /**
     * The face a recruited pilot wears right now: the boss for corrupted Astro, the corrupted
     * portrait for the rest of a corrupted crew, the bandana once earned, the plain one otherwise.
     * One rule shared by the roster card and the landscape crew button, so the two cannot disagree.
     */
    fun unlockedPortrait(pilotId: String, state: HangarState): Bitmap? = when {
        corrupted && pilotId == "pilot_astro" -> IconCache.getPortraitBoss()
        corrupted -> IconCache.getCorruptedPortrait(pilotId) ?: IconCache.getPortrait(pilotId)
        state.persistence.hasBandana(pilotId) ->
            IconCache.getBandanaPortrait(pilotId) ?: IconCache.getPortrait(pilotId)
        else -> IconCache.getPortrait(pilotId)
    }

    /**
     * A recruited card's front-face text: the callsign, then the passive's name beneath it.
     * Shared by the roster and [drawPilotCardFront], so the two cannot drift apart.
     */
    private fun drawFrontLabels(
        canvas: Canvas, rect: RectF, pilot: PilotDef,
        stripTop: Float, stripH: Float, cardHeight: Float, alpha: Int
    ) {
        textPaint.textSize = (cardHeight * 0.13f).coerceIn(12f, 22f)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.alpha = alpha
        canvas.drawText(pilot.callsign, rect.centerX(), stripTop + stripH * 0.38f, textPaint)
        textPaint.alpha = 255

        // Passive name text only (no icon)
        val nameY = stripTop + stripH * 0.78f
        textPaint.textSize = (cardHeight * 0.13f).coerceIn(12f, 22f)
        textPaint.color = 0xFFAABBCC.toInt()
        textPaint.alpha = alpha
        // Corruption: Astro's TB-26 passive is gone — strike it through.
        textPaint.isStrikeThruText = corrupted && pilot.id == "pilot_astro"
        canvas.drawText(
            PassiveDefinitions.getDisplayName(pilot.startingPassiveId, pilot.id, astroLoop),
            rect.centerX(), nameY, textPaint
        )
        textPaint.isStrikeThruText = false
        textPaint.alpha = 255
    }

    /**
     * One recruited pilot's card, front face, at full brightness — exactly as the roster draws
     * it for the selected pilot: dark ground, pilot-coloured border (corruption red in a
     * corruption run), portrait in the top 65%, callsign and passive name in the strip. The
     * landscape crew button uses this to show who is flying.
     */
    fun drawPilotCardFront(canvas: Canvas, state: HangarState, pilotIndex: Int, lr: LayoutRect) {
        val pilot = PilotDefinitions.getPilotByIndex(pilotIndex) ?: return
        val rect = RectF(lr.left, lr.top, lr.right, lr.bottom)
        val cardHeight = lr.height

        cardBgPaint.color = 0xFF1A1A2E.toInt()
        cardBgPaint.alpha = 255
        canvas.drawRoundRect(rect, 4f, 4f, cardBgPaint)
        cardBorderPaint.color = if (corrupted) Boss.CORRUPTION_COLOR else pilot.color
        cardBorderPaint.alpha = 255
        cardBorderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, 4f, 4f, cardBorderPaint)

        val portraitHeight = cardHeight * 0.65f
        unlockedPortrait(pilot.id, state)?.let {
            val portraitRect = RectF(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.top + portraitHeight)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(it, null, portraitRect, bitmapPaint)
        }
        drawFrontLabels(
            canvas, rect, pilot, rect.top + portraitHeight, cardHeight - portraitHeight, cardHeight, 255
        )
    }

    fun drawPilotCards(canvas: Canvas, state: HangarState, rects: List<LayoutRect>) {
        pilotCardRects.clear()
        // Only cardHeight is used below — cardWidth fed the old cx/rect-width math, which
        // GridGeometry.pilotCardRects now does internally.
        val cardHeight = rects.firstOrNull()?.height ?: return

        for ((index, lr) in rects.withIndex()) {
            val pilot = PilotDefinitions.getPilotByIndex(index) ?: continue

            val rect = RectF(lr.left, lr.top, lr.right, lr.bottom)
            pilotCardRects.add(rect)

            val isUnlocked = state.isPilotUnlocked(index)
            val isDead = !isUnlocked && state.isPilotDeadInCorruption(index)
            val nextPilotIndex = state.getNextPilotIndex()
            val isNextToRecruit = (index == nextPilotIndex)

            // Dim non-selected cards (selected = 1.0, others lerp to 0.5)
            val cardFade = if (index < state.pilotCardFades.size) state.pilotCardFades[index] else 1f
            val cardAlpha = (cardFade * 255).toInt()

            // Background — always dark, portrait provides the color
            cardBgPaint.color = 0xFF1A1A2E.toInt()
            cardBgPaint.alpha = cardAlpha
            canvas.drawRoundRect(rect, 4f, 4f, cardBgPaint)
            cardBgPaint.alpha = 255

            // Border — unlocked pilots use their pilot color (corruption red in corruption runs)
            val borderColor = if (corrupted) Boss.CORRUPTION_COLOR else pilot.color
            when {
                isUnlocked -> {
                    cardBorderPaint.color = borderColor
                    cardBorderPaint.alpha = cardAlpha
                    cardBorderPaint.strokeWidth = 1.5f
                }
                isNextToRecruit -> {
                    // Same border as an unlocked crewmate — colour, alpha and weight. What
                    // marks this card as locked is the silhouette and the note inside it, not
                    // a dimmer frame around it.
                    cardBorderPaint.color = borderColor
                    cardBorderPaint.alpha = cardAlpha
                    cardBorderPaint.strokeWidth = 1.5f
                }
                else -> {
                    cardBorderPaint.color = 0xFF333344.toInt()
                    cardBorderPaint.alpha = cardAlpha
                    cardBorderPaint.strokeWidth = 1f
                }
            }
            canvas.drawRoundRect(rect, 4f, 4f, cardBorderPaint)
            cardBorderPaint.alpha = 255  // reset

            // Portrait — top 65% of card
            val portraitHeight = cardHeight * 0.65f
            val portraitRect = RectF(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.top + portraitHeight)

            // Compute flip state here so portrait alpha can reflect it
            val isFlipping = isUnlocked && (index == state.pilotFlipIndex)
            val flipProgress = if (isFlipping) state.pilotFlipProgress else 1f
            val showBack = isFlipping && state.pilotFlipShowBack

            val portraitBitmap = when {
                isUnlocked -> unlockedPortrait(pilot.id, state)
                isDead -> null
                else -> IconCache.getPortraitLocked()
            }
            if (portraitBitmap != null) {
                val baseAlpha = when {
                    isUnlocked      -> cardAlpha
                    // Carried on the silhouette itself rather than on the card's fade, so the
                    // card can dim exactly like any other unselected one. 180 at a 0.35 fade
                    // lands near 25% — the visibility that worked on device — where the
                    // original 80 gave 11% and read as absent. Still under an unlocked
                    // portrait's 35%, which is right: it is a shape, not a face. The
                    // further-off pilots stay at 40 so the tiering says which one is next.
                    isNextToRecruit -> (180 * cardFade).toInt()
                    else            -> (40 * cardFade).toInt()
                }
                bitmapPaint.alpha = when {
                    isFlipping && !showBack -> (baseAlpha * flipProgress).toInt()       // front fading out
                    isFlipping && showBack  -> (baseAlpha * (1f - flipProgress)).toInt() // portrait fading in under dissolving back
                    else -> baseAlpha
                }
                canvas.drawBitmap(portraitBitmap, null, portraitRect, bitmapPaint)
                bitmapPaint.alpha = 255
            }

            // Bottom strip text
            val stripTop = rect.top + portraitHeight
            val stripH = cardHeight - portraitHeight

            if (isUnlocked) {
                if (showBack) {
                    // Every alpha in this branch is scaled by cardAlpha, exactly as the front
                    // face is. It used to draw at a flat 255 * flipProgress, so a turned-over
                    // card ignored the grid's dimming: flip one, select somebody else, and it
                    // stayed at full brightness while every card around it faded.
                    if (corrupted && pilot.id == "pilot_astro") {
                        // TB-26 is dead — show muted memorial back
                        cardBgPaint.color = 0xFF252538.toInt()
                        cardBgPaint.alpha = (cardAlpha * flipProgress).toInt()
                        canvas.drawRoundRect(rect, 4f, 4f, cardBgPaint)
                        cardBgPaint.color = 0xFF1A1A2E.toInt()
                        restrokeCardBorder(canvas, rect, borderColor, cardAlpha)

                        val tb26Icon = IconCache.getPassiveIcon("tb26")
                        if (tb26Icon != null) {
                            val iconSize = (rect.width() * 0.45f).coerceAtMost(portraitHeight * 0.8f)
                            val iconLeft = rect.centerX() - iconSize / 2
                            val iconTop = rect.top + (portraitHeight - iconSize) / 2
                            val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                            bitmapPaint.alpha = (cardAlpha * 0.5f * flipProgress).toInt()  // 50% alpha
                            canvas.drawBitmap(tb26Icon, null, iconRect, bitmapPaint)
                            bitmapPaint.alpha = 255
                        }

                        textPaint.textSize = (cardHeight * 0.13f).coerceIn(12f, 22f)
                        textPaint.color = 0xFF666677.toInt()
                        textPaint.alpha = (cardAlpha * flipProgress).toInt()
                        canvas.drawText("TB-26", rect.centerX(), stripTop + stripH * 0.38f, textPaint)

                        textPaint.textSize = (cardHeight * 0.12f).coerceIn(13f, 22f)
                        textPaint.color = 0xFF994444.toInt()
                        canvas.drawText("He's gone.", rect.centerX(), stripTop + stripH * 0.78f, textPaint)
                        textPaint.alpha = 255
                    } else {
                        // Back face — cross-dissolves with portrait at animation end
                        cardBgPaint.color = 0xFF252538.toInt()
                        cardBgPaint.alpha = (cardAlpha * flipProgress).toInt()
                        canvas.drawRoundRect(rect, 4f, 4f, cardBgPaint)
                        cardBgPaint.color = 0xFF1A1A2E.toInt()
                        restrokeCardBorder(canvas, rect, borderColor, cardAlpha)

                        val effectivePassiveId = PassiveDefinitions.getEffectivePassiveId(pilot.startingPassiveId, pilot.id, astroLoop)
                        val passiveDef = PassiveDefinitions.getPassiveDef(effectivePassiveId)

                        // Passive icon — centered at 45% of card width (same proportion as upgrade card)
                        val passiveIcon = IconCache.getPassiveIcon(effectivePassiveId)
                        if (passiveIcon != null) {
                            val iconSize = (rect.width() * 0.45f).coerceAtMost(portraitHeight * 0.8f)
                            val iconLeft = rect.centerX() - iconSize / 2
                            val iconTop = rect.top + (portraitHeight - iconSize) / 2
                            val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                            bitmapPaint.alpha = (cardAlpha * flipProgress).toInt()
                            canvas.drawBitmap(passiveIcon, null, iconRect, bitmapPaint)
                            bitmapPaint.alpha = 255
                        }

                        // Passive name (white) — same size as callsign, no wrapping
                        textPaint.textSize = (cardHeight * 0.13f).coerceIn(12f, 22f)
                        textPaint.color = 0xFFFFFFFF.toInt()
                        textPaint.alpha = (cardAlpha * flipProgress).toInt()
                        canvas.drawText(
                            PassiveDefinitions.getDisplayName(pilot.startingPassiveId, pilot.id, astroLoop),
                            rect.centerX(), stripTop + stripH * 0.38f, textPaint
                        )

                        // Effect text (amber). TextWrap honours an explicit \n and balances a
                        // two-line break, so this card no longer carries its own splitter —
                        // the old one initialised splitIndex to 1 and only tested prefixes
                        // *short of* the full string, so copy that overflowed on its last word
                        // alone fell through the loop and put one word on line one. "Gain a
                        // 5th weapon slot" is a passive description and did exactly that.
                        val effectText = passiveDef?.description ?: ""
                        textPaint.textSize = (cardHeight * 0.12f).coerceIn(13f, 22f)
                        textPaint.color = 0xFFFFBB44.toInt()
                        textPaint.alpha = (cardAlpha * flipProgress).toInt()
                        val lineH = textPaint.textSize * 1.2f
                        // Clamped to two, which is all the strip has room for. The old code
                        // drew exactly parts[0] and parts[1] and so could never exceed it;
                        // wrapping re-breaks each \n segment, so a segment too wide for the
                        // cell would have turned a two-line description into three and pushed
                        // it out of the strip.
                        val effectLines = TextWrap.clamp(
                            TextWrap.wrap(effectText, rect.width() - 12f, textPaint::measureText), 2
                        )
                        // One line sits lower, centred in the strip on its own; two or more
                        // start higher so the block stays centred. Unchanged behaviour.
                        var effY = stripTop + stripH * (if (effectLines.size == 1) 0.78f else 0.72f)
                        for (line in effectLines) {
                            canvas.drawText(line, rect.centerX(), effY, textPaint)
                            effY += lineH
                        }
                        textPaint.alpha = 255
                    }
                } else {
                    // Front face
                    val frontAlpha = if (isFlipping) (cardAlpha * flipProgress).toInt() else cardAlpha
                    drawFrontLabels(canvas, rect, pilot, stripTop, stripH, cardHeight, frontAlpha)
                }
            } else if (isNextToRecruit) {
                // Once somebody has hinted about this pilot, the card stops being a mystery and
                // becomes the bar's note on them — a hint is spoken once, several runs before
                // the player can act on it, and this is where it stays. Null until then, and
                // null again from story loop 2, where the stated condition no longer governs.
                val note = PilotHintCards.cardFor(
                    pilotIndex = index,
                    hintedPilotIndex = persistence.getHintedPilotIndex(),
                    astroHinted = persistence.getAstroHintCount() >= 1,
                    hasLoopedBefore = StoryStateManager.hasLoopedBefore(persistence)
                )
                // Cross-fade rather than swap: the note arrives in the same beat the hint is
                // spoken, so both faces are drawn while the reveal runs — the "?" is seen to
                // go, which is what the "No instant disappearance" rule asks for. A note the
                // player already knew about has reveal 0 and draws outright.
                val noteAlpha = if (note == null) 0f else state.hintNoteAlpha()
                if (note == null || noteAlpha < 1f) {
                    // Same sizing rule as the store's mystery "?" — 0.25 of the cell, clamped to
                    // 16..28 — so the two mystery glyphs read as the same element. Both land on
                    // the 28 cap at normal card/tile sizes.
                    textPaint.textSize = (cardHeight * 0.25f).coerceIn(16f, 28f)
                    textPaint.color = 0xFF888888.toInt()
                    textPaint.alpha = (cardAlpha * (1f - noteAlpha)).toInt()
                    canvas.drawText("?", rect.centerX(), stripTop + stripH * 0.55f, textPaint)
                }
                if (note != null) {
                    // Set exactly like the passive name on an unlocked card — same size, same
                    // colour — so a locked card reads as the same kind of label rather than as
                    // fine print. Italic and quoted on top of that, because it is something
                    // written about the pilot rather than the game talking; FontManager carries
                    // no italic face, so the body face is skewed.
                    textPaint.textSize = (cardHeight * 0.13f).coerceIn(12f, 22f)
                    textPaint.color = 0xFFAABBCC.toInt()
                    // Exempt from the card fade, unlike every other label on this grid. Cards
                    // dim to 0.35 when they are not the selected one, and a locked card can
                    // never BE the selected one — so the note was permanently at 2.21:1, worse
                    // than the 2.29:1 this renderer already treated as a defect for the store's
                    // "?". At full alpha it is 8.68:1. The reveal still fades it in.
                    textPaint.alpha = (255 * noteAlpha).toInt()
                    // No skew: FontManager has no italic face, so the italic was a synthetic
                    // oblique of the upright font, which smears the stems and drops the hinting
                    // at the 12px this lands on. The quotation marks already say it is written.

                    // The note is allowed out of the bottom strip. A locked card has no callsign
                    // and no passive under it, and its portrait is a near-transparent
                    // silhouette, so the whole card is free — confining a two-line note to the
                    // strip's 35% is what forced it down to unreadable sizes before.
                    val lines = wrapNote(note, rect.width() * 0.9f, textPaint)
                    val lineHeight = textPaint.textSize * 1.2f
                    // Centre the block on the line the "?" used, so a card that gains a note
                    // does not jump.
                    var noteY = stripTop + stripH * 0.55f - (lines.size - 1) * lineHeight / 2f
                    for (line in lines) {
                        canvas.drawText(line, rect.centerX(), noteY, textPaint)
                        noteY += lineHeight
                    }
                }
                textPaint.alpha = 255
            }
            // Unknown: no text
        }
    }

    /**
     * Horizontal extent of the bar counter, and of everything hung off it — the chat column
     * included. Measured against the ROOM, not the screen: on large screens the room is the
     * content column and the pages tile at that width, so a room-relative counter lands 2px
     * outside the pilot grid on every device instead of only on phones.
     */
    private val barLeft: Float get() = 10f
    private val barRight: Float get() = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth) - 10f

    private fun drawChatMessages(canvas: Canvas, state: HangarState) {
        val lineHeight = 32f
        val chatFontSize = 24f
        val indicatorY = screenHeight * 0.95f
        val chatBottom = indicatorY - 30f
        val chatTop = walkwayY + 10f
        val maxVisible = ((chatBottom - chatTop) / lineHeight).toInt().coerceAtLeast(1)
        // The chat column hangs off the counter, anchoring to its edges rather than the pilot
        // grid. The counter's left and right bounds are measured against the room width, not
        // the screen; on large screens, each room is a narrow content column and the three rooms
        // tile edge to edge, so room-relative edges ensure consistent chat anchoring across all
        // devices. Both edges must come from the same rect—mixing a screen-relative coordinate
        // with a room-relative coordinate was the original bug that made lines simultaneously
        // too wide on the left and truncated early on the right.
        val chatLeft = barLeft
        val chatRight = barRight
        val messages = state.chatMessages.toList()
        val startIndex = (messages.size - maxVisible).coerceAtLeast(0)
        val visibleMessages = messages.subList(startIndex, messages.size)

        textPaint.textAlign = Paint.Align.LEFT
        for ((i, msg) in visibleMessages.withIndex()) {
            val msgY = chatTop + (i + 1) * lineHeight
            textPaint.textSize = chatFontSize
            textPaint.color = msg.color
            val callsignText = "[${msg.speaker}]: "
            canvas.drawText(callsignText, chatLeft + 6f, msgY, textPaint)
            val callsignWidth = textPaint.measureText(callsignText)
            textPaint.color = 0xFFCCCCCC.toInt()
            val textX = chatLeft + 6f + callsignWidth
            val fittedText = truncateToFit(msg.text, textPaint, chatRight - 6f - textX)
            canvas.drawText(fittedText, textX, msgY, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFFFFFFFF.toInt()
    }

    /** Trim a chat line with an ellipsis so it never runs past the chat column. */
    private fun truncateToFit(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        for (i in text.length - 1 downTo 1) {
            val truncated = text.substring(0, i) + "…"
            if (paint.measureText(truncated) <= maxWidth) return truncated
        }
        return "…"
    }

    /**
     * Draws the bar area static elements: shelf, bottles, counter, stools, codex book, neon lamps.
     * @param hasEvolutions Whether codex book should glow with evolution discovery
     */
    private fun drawBarElements(canvas: Canvas, swayMomentum: Float = 0f, hasEvolutions: Boolean = false) {
        val barTop = walkwayY - 25f
        val barBottom = walkwayY + 2f

        // Shelf behind bar (full width) — drawn first as back wall
        val shelfPaint = Paint().apply {
            color = 0xFF2A1A0A.toInt()
            style = Paint.Style.FILL
        }
        val shelfY = barTop - 18f
        canvas.drawRect(barLeft + 5f, shelfY, barRight - 5f, shelfY + 2f, shelfPaint)

        // Bottles on shelf (more bottles across full width)
        val bottlePaint = Paint().apply { style = Paint.Style.FILL }
        val bottleColors = listOf(
            0xFF00AA44.toInt(), 0xFFAA4400.toInt(), 0xFF4444AA.toInt(), 0xFFAA0044.toInt(),
            0xFF44AA88.toInt(), 0xFFAA8800.toInt(), 0xFF8844AA.toInt(), 0xFF00AAAA.toInt()
        )
        val bottleSpacing = (barRight - barLeft - 20f) / (bottleColors.size + 1)
        for ((bi, bColor) in bottleColors.withIndex()) {
            val bx = barLeft + 10f + bottleSpacing * (bi + 1)
            val by = shelfY - 1f
            bottlePaint.color = bColor
            bottlePaint.alpha = 160
            canvas.drawRoundRect(RectF(bx - 2f, by - 8f, bx + 2f, by), 1f, 1f, bottlePaint)
            canvas.drawRect(bx - 1f, by - 11f, bx + 1f, by - 8f, bottlePaint)
        }

        // Counter surface (dark wood-ish)
        val counterPaint = Paint().apply {
            color = 0xFF2A1A0A.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 3f, 3f, counterPaint)

        // Counter edge highlight
        val edgePaint = Paint().apply {
            color = 0xFF885522.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawLine(barLeft, barTop, barRight, barTop, edgePaint)

        // Bar stools in front of counter (above walkway line)
        val stoolPaint = Paint().apply {
            color = 0xFF3A2A1A.toInt()
            style = Paint.Style.FILL
        }
        val stoolLegPaint = Paint().apply {
            color = 0xFF555555.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val stoolRoomWidth = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)
        for (s in 1..HangarMetrics.STOOL_COUNT) {
            val sx = HangarMetrics.stoolCenterX(stoolRoomWidth, s)
            val seatY = barBottom - 6f
            canvas.drawRoundRect(RectF(sx - 5f, seatY, sx + 5f, seatY + 3f), 1f, 1f, stoolPaint)
            canvas.drawLine(sx, seatY + 3f, sx, barBottom, stoolLegPaint)
        }

        // Codex book on counter (right side)
        val bookX = barRight - 55f
        val bookY = barTop - 2f
        val bookW = 22f
        val bookH = 15f
        val bookRect = RectF(bookX - bookW / 2f, bookY - bookH, bookX + bookW / 2f, bookY)
        codexBookRect = bookRect

        val bookPaint = Paint().apply { style = Paint.Style.FILL }
        val bookLinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        if (hasEvolutions) {
            // Discovered evolutions — book glows faintly
            bookPaint.color = 0xFF2A2035.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookPaint)
            // Glow
            val time = System.currentTimeMillis()
            val glow = 0.4f + 0.3f * kotlin.math.sin(time / 800.0).toFloat()
            bookLinePaint.color = 0xFF8844AA.toInt()
            bookLinePaint.alpha = (glow * 200).toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookLinePaint)
            // Page lines
            bookLinePaint.strokeWidth = 0.5f
            bookLinePaint.alpha = (glow * 120).toInt()
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 4f, bookRect.right - 4f, bookRect.top + 4f, bookLinePaint)
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 7f, bookRect.right - 4f, bookRect.top + 7f, bookLinePaint)
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 10f, bookRect.right - 6f, bookRect.top + 10f, bookLinePaint)
            // Bookmark tab sticking out top
            bookPaint.color = 0xFF8844AA.toInt()
            bookPaint.alpha = (glow * 220).toInt()
            canvas.drawRect(bookRect.right - 6f, bookRect.top - 4f, bookRect.right - 3f, bookRect.top, bookPaint)
            bookPaint.alpha = 255
        } else {
            // No evolutions — dark/closed book
            bookPaint.color = 0xFF1A1A24.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookPaint)
            bookLinePaint.color = 0xFF333344.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookLinePaint)
        }

        // Neon lamps across full crew quarters width
        val lampPaint = Paint().apply { style = Paint.Style.FILL }
        val lampColors = listOf(
            0xFFFF0066.toInt(), 0xFF00FF88.toInt(), 0xFFFFAA00.toInt(), 0xFF00AAFF.toInt(),
            0xFFFF00FF.toInt(), 0xFF88FF00.toInt(), 0xFFFF8800.toInt()
        )
        val lampSpacing = (barRight - barLeft) / (lampColors.size + 1)

        val wirePaint = Paint().apply {
            color = 0xFF444444.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        for ((i, lampColor) in lampColors.withIndex()) {
            val swayAmount = (swayMomentum * 0.005f).coerceIn(-5f, 5f)
            val lx = barLeft + lampSpacing * (i + 1) + swayAmount * (i + 1) * 0.3f
            val ly = barTop - 25f

            // Lamp glow
            lampPaint.color = lampColor
            lampPaint.alpha = 40
            canvas.drawCircle(lx, ly, 8f, lampPaint)

            // Lamp bulb
            lampPaint.alpha = 200
            canvas.drawCircle(lx, ly, 2.5f, lampPaint)

            // Wire to ceiling
            val wireTopX = barLeft + lampSpacing * (i + 1)
            canvas.drawLine(lx, ly - 8f, wireTopX, ceilingY + 3f, wirePaint)
        }
    }

    /** Tucked legs, drawn BEFORE the seat so the seat hides the thighs (patron faces the bar). */
    private fun drawSitterLegs(canvas: Canvas, sx: Float, seatY: Float, color: Int) {
        sitterLimbPaint.color = color
        canvas.drawLine(sx - 1.5f, seatY + 1f, sx - 2f, seatY + 6f, sitterLimbPaint)
        canvas.drawLine(sx + 1.5f, seatY + 1f, sx + 2f, seatY + 6f, sitterLimbPaint)
    }

    /** Body on the seat with hands resting on the counter edge (barTop = seatY - 3). */
    private fun drawSitterBody(canvas: Canvas, sx: Float, seatY: Float, color: Int, bandanaColor: Int? = null) {
        sitterFillPaint.color = color
        canvas.drawCircle(sx, seatY - 4f, 5f, sitterFillPaint)
        sitterLimbPaint.color = color
        canvas.drawLine(sx - 4f, seatY - 5f, sx - 6f, seatY - 3f, sitterLimbPaint)
        canvas.drawLine(sx + 4f, seatY - 5f, sx + 6f, seatY - 3f, sitterLimbPaint)

        // Bandana, seen from behind: same chord-fit band as the walkers (head center
        // seatY-4, r=5 → band 2.4 above center) plus the knot's two tails hanging down.
        if (bandanaColor != null) {
            sitterBandPaint.color = bandanaColor
            val bandY = seatY - 6.4f
            canvas.drawLine(sx - 4.1f, bandY, sx + 4.1f, bandY, sitterBandPaint)
            canvas.drawLine(sx - 0.5f, bandY, sx - 2f, bandY + 3.5f, sitterBandPaint)
            canvas.drawLine(sx + 0.5f, bandY, sx + 1.5f, bandY + 3f, sitterBandPaint)
        }
    }

    private fun drawBarArea(canvas: Canvas, state: HangarState) {
        val swipeVelocity = state.swayMomentum
        val hasEvolutions = state.hasDiscoveredEvolutions
        val geom = BarGeometry(walkwayY)
        val barTop = geom.counterTop      // slim slab (was walkwayY - 25f)
        val barBottom = geom.counterBottom

        // Shelf behind bar (full width) — drawn first as back wall
        val shelfPaint = Paint().apply {
            color = 0xFF2A1A0A.toInt()
            style = Paint.Style.FILL
        }
        val shelfY = barTop - 18f
        canvas.drawRect(barLeft + 5f, shelfY, barRight - 5f, shelfY + 2f, shelfPaint)

        // Bottles on shelf (more bottles across full width)
        val bottlePaint = Paint().apply { style = Paint.Style.FILL }
        val bottleColors = listOf(
            0xFF00AA44.toInt(), 0xFFAA4400.toInt(), 0xFF4444AA.toInt(), 0xFFAA0044.toInt(),
            0xFF44AA88.toInt(), 0xFFAA8800.toInt(), 0xFF8844AA.toInt(), 0xFF00AAAA.toInt()
        )
        val bottleSpacing = (barRight - barLeft - 20f) / (bottleColors.size + 1)
        for ((bi, bColor) in bottleColors.withIndex()) {
            val bx = barLeft + 10f + bottleSpacing * (bi + 1)
            val by = shelfY - 1f
            bottlePaint.color = bColor
            bottlePaint.alpha = 160
            canvas.drawRoundRect(RectF(bx - 2f, by - 8f, bx + 2f, by), 1f, 1f, bottlePaint)
            canvas.drawRect(bx - 1f, by - 11f, bx + 1f, by - 8f, bottlePaint)
        }

        // TB-26 behind bar (dynamic position, pacing) — drawn before counter so legs are occluded
        // Skip TB-26 when corrupted (bar is empty)
        if (!corrupted) {
            val tb26X = state.tb26BarX
            val tb26Y = barTop + 2f  // Legs extend to y+5 = barTop+7, hidden behind counter (barTop to barBottom)
            drawCharacter(canvas, tb26X, tb26Y, 0xFF88AACC.toInt(), state.tb26BarMoving, 0f)

            // Cyan eye for TB-26 (droid detail)
            val eyePaint = Paint().apply {
                color = 0xFF00FFFF.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawCircle(tb26X + 1.5f, tb26Y - 7f, 1.5f, eyePaint)
        }

        // Counter surface (dark wood-ish) — occludes TB-26's legs
        val counterPaint = Paint().apply {
            color = 0xFF2A1A0A.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 3f, 3f, counterPaint)

        // Counter edge highlight
        val edgePaint = Paint().apply {
            color = 0xFF885522.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawLine(barLeft, barTop, barRight, barTop, edgePaint)

        // Bar stools in front of counter (above walkway line)
        val stoolPaint = Paint().apply {
            color = 0xFF3A2A1A.toInt()
            style = Paint.Style.FILL
        }
        val stoolLegPaint = Paint().apply {
            color = 0xFF555555.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val stoolRoomWidth = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)
        for (s in 1..HangarMetrics.STOOL_COUNT) {
            val sx = HangarMetrics.stoolCenterX(stoolRoomWidth, s)
            val seatY = geom.stoolSeatY
            val sitter = if (dressing.seatedCrew)
                state.npcWalkers.firstOrNull { it.seated && it.seatedStool == s } else null
            if (sitter != null) drawSitterLegs(canvas, sx, seatY, sitter.color)
            canvas.drawRoundRect(RectF(sx - 5f, seatY, sx + 5f, seatY + 3f), 1f, 1f, stoolPaint)
            canvas.drawLine(sx - 3f, seatY + 3f, sx - 5f, barBottom, stoolLegPaint)
            canvas.drawLine(sx + 3f, seatY + 3f, sx + 5f, barBottom, stoolLegPaint)
            if (sitter != null) {
                val sitterBandana = PilotDefinitions.getPilotByIndex(sitter.pilotIndex)
                    ?.takeIf { persistence.hasBandana(it.id) }
                    ?.let { BandanaDefinitions.accentColor(it.id) }
                drawSitterBody(canvas, sx, seatY, sitter.color, sitterBandana)
            }
        }

        // Beer sliding along counter surface (skip when corrupted — no TB-26 to slide beers)
        if (state.beerActive && !corrupted) {
            val beerAlpha = (state.beerFadeAlpha * 255).toInt().coerceIn(0, 255)
            val beerPaint = Paint().apply {
                color = 0xFFDDAA44.toInt()
                alpha = beerAlpha
                style = Paint.Style.FILL
            }
            val beerSurfaceY = geom.beerSurfaceY   // was barTop + 2f — the +2 sank the glass into the slab
            canvas.drawRect(state.beerX - 3f, beerSurfaceY - 6f, state.beerX + 3f, beerSurfaceY, beerPaint)
            // Foam top
            val foamPaint = Paint().apply {
                color = 0xFFEEDDBB.toInt()
                alpha = beerAlpha
                style = Paint.Style.FILL
            }
            canvas.drawRect(state.beerX - 3f, beerSurfaceY - 8f, state.beerX + 3f, beerSurfaceY - 6f, foamPaint)
        }

        // Codex book on counter (right side)
        val bookX = barRight - 55f
        val bookY = barTop - 2f
        val bookW = 22f
        val bookH = 15f
        val bookRect = RectF(bookX - bookW / 2f, bookY - bookH, bookX + bookW / 2f, bookY)
        codexBookRect = bookRect

        val bookPaint = Paint().apply { style = Paint.Style.FILL }
        val bookLinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        if (hasEvolutions) {
            // Discovered evolutions — book glows faintly
            bookPaint.color = 0xFF2A2035.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookPaint)
            // Glow
            val time = System.currentTimeMillis()
            val glow = 0.4f + 0.3f * kotlin.math.sin(time / 800.0).toFloat()
            bookLinePaint.color = 0xFF8844AA.toInt()
            bookLinePaint.alpha = (glow * 200).toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookLinePaint)
            // Page lines
            bookLinePaint.strokeWidth = 0.5f
            bookLinePaint.alpha = (glow * 120).toInt()
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 4f, bookRect.right - 4f, bookRect.top + 4f, bookLinePaint)
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 7f, bookRect.right - 4f, bookRect.top + 7f, bookLinePaint)
            canvas.drawLine(bookRect.left + 4f, bookRect.top + 10f, bookRect.right - 6f, bookRect.top + 10f, bookLinePaint)
            // Bookmark tab sticking out top
            bookPaint.color = 0xFF8844AA.toInt()
            bookPaint.alpha = (glow * 220).toInt()
            canvas.drawRect(bookRect.right - 6f, bookRect.top - 4f, bookRect.right - 3f, bookRect.top, bookPaint)
            bookPaint.alpha = 255
        } else {
            // No evolutions — dark/closed book
            bookPaint.color = 0xFF1A1A24.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookPaint)
            bookLinePaint.color = 0xFF333344.toInt()
            canvas.drawRoundRect(bookRect, 2f, 2f, bookLinePaint)
        }

        // Neon lamps across full crew quarters width
        val lampPaint = Paint().apply { style = Paint.Style.FILL }
        val lampColors = dressing.lampColors   // warm / corruption-red / rainbow per stage
        val lampSpacing = (barRight - barLeft) / (lampColors.size + 1)

        val wirePaint = Paint().apply {
            color = 0xFF444444.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        for ((i, lampColor) in lampColors.withIndex()) {
            val swayAmount = (swipeVelocity * 0.005f).coerceIn(-5f, 5f)
            val lx = barLeft + lampSpacing * (i + 1) + swayAmount * (i + 1) * 0.3f
            val ly = barTop - 25f

            // Lamp glow
            lampPaint.color = lampColor
            lampPaint.alpha = 40
            canvas.drawCircle(lx, ly, 8f, lampPaint)

            // Lamp bulb
            lampPaint.alpha = 200
            canvas.drawCircle(lx, ly, 2.5f, lampPaint)

            // Wire to ceiling
            val wireTopX = barLeft + lampSpacing * (i + 1)
            canvas.drawLine(lx, ly - 8f, wireTopX, ceilingY + 3f, wirePaint)
        }

        drawNeonBarSign(canvas, geom)

        if (dressing.stringLights) {
            drawSwagLights(canvas, stringLightColors, dipPx = 7f)  // warm, gentle sag
        }
        if (dressing.fairyLights) {
            drawSwagLights(canvas, fairyLightColors, dipPx = 9f)
        }

        if (dressing.confetti) {
            drawConfetti(canvas)
        }
    }

    /** Static confetti scattered on the floor band just below the walkway line. */
    private fun drawConfetti(canvas: Canvas) {
        val w = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth)
        for ((xf, yOff, ci) in confettiSpots) {
            confettiPaint.color = confettiColors[ci]
            confettiPaint.alpha = 170
            canvas.drawCircle(20f + xf * (w - 40f), walkwayY + yOff, 1.1f, confettiPaint)
        }
    }

    /** Two swag arcs across the bar back wall with bulbs. Anchored to the wall, stage-independent. */
    private fun drawSwagLights(canvas: Canvas, colors: List<Int>, dipPx: Float) {
        val left = 40f
        val right = HangarMetrics.effectiveRoomWidth(roomWidth, screenWidth) - 40f
        val mid = (left + right) / 2f
        drawOneSwag(canvas, left, mid, colors, dipPx)
        drawOneSwag(canvas, mid, right, colors, dipPx)
    }

    /** One swag arc (wire + bulbs) between points a and b. */
    private fun drawOneSwag(canvas: Canvas, a: Float, b: Float, colors: List<Int>, dipPx: Float) {
        val baseY = ceilingY + 5f
        val ctrlX = (a + b) / 2f
        val ctrlY = baseY + dipPx * 2f   // quadratic control for a ~dipPx sag at the middle
        swagPath.reset()
        swagPath.moveTo(a, baseY)
        swagPath.quadTo(ctrlX, ctrlY, b, baseY)
        canvas.drawPath(swagPath, swagWirePaint)
        var t = 0.1f
        var ci = 0
        while (t < 0.95f) {
            val inv = 1f - t
            val bx = inv * inv * a + 2f * inv * t * ctrlX + t * t * b
            val by = inv * inv * baseY + 2f * inv * t * ctrlY + t * t * baseY
            swagBulbPaint.color = colors[ci % colors.size]
            swagBulbPaint.alpha = 210
            canvas.drawCircle(bx, by, 2f, swagBulbPaint)
            t += 0.11f
            ci++
        }
    }

    /** Cyan neon "BAR" glyph, upper-left of the bar wall. STEADY breathes; BLINKING flickers. */
    private fun drawNeonBarSign(canvas: Canvas, geom: BarGeometry) {
        val cyan = 0xFF00FFFF.toInt()
        val x = 62f
        val y = geom.shelfY - 8f          // just above the shelf, clear of the lamp row and grid
        val size = 15f
        val dim = when (dressing.signMode) {
            SignMode.STEADY   -> 0.7f + 0.3f * kotlin.math.sin(System.currentTimeMillis() / 400.0).toFloat()
            SignMode.BLINKING -> SignFlicker.dim(System.currentTimeMillis())
        }.coerceIn(0f, 1f)

        // Glow: two enlarged, low-alpha passes behind the glyph (no BlurMaskFilter).
        signGlowPaint.color = cyan
        signGlowPaint.textSize = size * 1.35f
        signGlowPaint.alpha = (60 * dim).toInt()
        canvas.drawText("BAR", x, y, signGlowPaint)
        signGlowPaint.textSize = size * 1.15f
        signGlowPaint.alpha = (90 * dim).toInt()
        canvas.drawText("BAR", x, y, signGlowPaint)

        // Crisp glyph.
        signPaint.color = cyan
        signPaint.textSize = size
        signPaint.alpha = (230 * dim).toInt()
        canvas.drawText("BAR", x, y, signPaint)

        // Tube underline.
        val w = signPaint.measureText("BAR")
        signLinePaint.color = cyan
        signLinePaint.alpha = (90 * dim).toInt()
        canvas.drawLine(x - w / 2f, y + size * 0.45f, x + w / 2f, y + size * 0.45f, signLinePaint)
    }

    /**
     * Word wrap for a locked pilot's note — the card is a twelfth of the grid.
     *
     * Balanced on two lines like every other card in the game. `"Is looking for yen."` and
     * `"Wants every pilot and ship."` are short enough that greedy usually agreed, but the notes
     * sit in the narrowest cell here, so the one that does not agree is only a rewrite away.
     */
    private fun wrapNote(text: String, maxWidth: Float, paint: Paint): List<String> =
        TextWrap.wrap(text, maxWidth, paint::measureText)

    companion object {
        /**
         * The pilot cards, taken straight from the rects the renderer already publishes — so
         * focus and touch cannot disagree about which cards exist, including as cards reveal
         * over time.
         *
         * [originX] converts room-local to screen space, and both halves need it. The bar draws
         * inside its room's translate, so `pilotCardRects` are room-local; FocusNavigator
         * compares them against the nav row, which is not. And handleBarTap's first act is
         * roomX(x), so handing it a room-local x would convert a second time and land on the
         * wrong card. Below the gate the origin is zero and this is the identity — the phone
         * case is untouched.
         *
         * The action taps the card's own centre through the existing handleBarTap rather than
         * duplicating selection and flip-back logic that already works.
         */
        fun publishPilotTargets(
            focus: FocusRegistry,
            cardRects: List<RectF>,
            originX: Float,
            onTap: (Float, Float) -> Unit
        ) {
            for ((index, rect) in cardRects.withIndex()) {
                val screenRect = RectF(
                    rect.left + originX, rect.top,
                    rect.right + originX, rect.bottom
                )
                focus.add(FocusTarget("pilot:$index", screenRect) {
                    onTap(screenRect.centerX(), screenRect.centerY())
                })
            }
        }
    }
}
