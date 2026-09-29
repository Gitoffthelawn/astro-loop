package com.astroloop.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.data.WeaponDefinitions
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import com.astroloop.game.system.UpgradeOption
import com.astroloop.game.system.UpgradeSystem
import kotlin.math.sin

class UpgradeSelectionRenderer {

    private val titlePaint = Paint().apply {
        isAntiAlias = true
        color = GameConfig.COLOR_HUD
        textSize = 48f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val cardPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = GameConfig.COLOR_HUD
    }

    // ITEM 5: All card borders are now white — single white border paint
    private val whiteBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFFFFFFF.toInt()
    }

    private val cardFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xFF111122.toInt()
    }

    // SHARED, MUTABLE PAINTS — every draw must set every property it depends on.
    //
    // These are reused across all three card renderers and mutated in place by both the card
    // code and fitTextSize(), which drives textSize down to 14f. Three cards are drawn per
    // selection screen, so a draw that reads a property it never sets renders differently
    // depending on what was drawn beside it. That is not a hypothetical: the evolution card's
    // ingredient line inherited its size and colour from the previous card for exactly this
    // reason, and looked wrong in a way nobody could pin down.
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = GameConfig.COLOR_HUD
        textSize = 28f  // Increased from 24f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val smallTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFAAAAAA.toInt()
        textSize = 22f  // Increased from 18f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val newBadgePaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF00FF00.toInt()
        textSize = 16f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val levelPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFF00.toInt()
        textSize = 24f  // Increased from 20f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val evolutionPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFF44FF.toInt()  // Purple/magenta for evolution
        textSize = 16f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val evolutionBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFFF44FF.toInt()
    }

    private val goldBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFFFFD700.toInt() // Gold
    }

    private val goldFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xFF1A1A0A.toInt() // Dark gold tint
    }

    private val evolutionHeaderPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFD700.toInt() // Gold
        textSize = 20f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFD700.toInt()
        textSize = 24f
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    /** The grey every card description is drawn in. Named because three sites set it. */
    private val DESCRIPTION_GREY = 0xFFCCCCCC.toInt()

    private val dimOverlayPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    private var layout: ScreenLayout = ScreenLayout.compute(GameConfig.DESIGN_WIDTH, GameConfig.DESIGN_HEIGHT)

    val cardRects = mutableListOf<RectF>()

    /**
     * The icon size the last card actually drew at, `NaN` before the first render — a seam.
     *
     * Every card on a screen shares one size, so one field says it. Without it the portrait cap
     * is only reachable through private helpers, and a call site that stopped applying it would
     * fail nothing: the pure functions would still return the right numbers to nobody.
     */
    internal var drawnCardIconSize: Float = Float.NaN
        private set

    /** The same, for the evolution card's own icon. */
    internal var drawnEvolutionIconSize: Float = Float.NaN
        private set

    /**
     * Published alongside `cardRects`, not instead of it. Touch keeps its own hit-testing —
     * the two are deliberately parallel, and UpgradeFocusParityTest asserts they agree at
     * every target's own centre.
     */
    val focusRegistry = FocusRegistry()

    /** Set by GameSurfaceView so an activated card takes the same path a tap takes. */
    var onCardActivated: ((Int) -> Unit)? = null

    companion object {
        /** A card's share of the content rect. Read by the layout and by the portrait cap. */
        const val CARD_W_FRACTION = 0.30f
        const val CARD_H_FRACTION = 0.42f

        /**
         * An icon's size inside an upgrade card, measured against the card's SHORT side.
         *
         * Portrait cards are 288x900, where the short side IS the width, so this is exactly the
         * `rect.width() * 0.45f` that shipped. Landscape cards are 643x403, where 45% of the width
         * is a 289px icon in a 403px card and the name, level badge and description have nowhere
         * left to go.
         *
         * The short side alone is not enough, though — see [portraitCardIconSize]. A landscape
         * card's short side is its HEIGHT, and 45% of 403 is still 181 against portrait's 124, so
         * the icon grew by half on rotation. Callers cap this against the portrait size.
         */
        fun cardIconSize(width: Float, height: Float): Float = minOf(width, height) * 0.45f

        /** The evolved weapon's icon — the same rule at the evolution card's smaller weight. */
        fun evolutionIconSize(width: Float, height: Float): Float = minOf(width, height) * 0.25f

        /** The inline "→ evolves into" icon beside the evolution note, at its own weight. */
        fun inlineIconSize(width: Float): Float = width * 0.12f
    }

    fun initialize(layout: ScreenLayout) {
        this.layout = layout
        portraitLayout = DesignSpace.portraitShaped(layout)
    }

    /**
     * The device's own PORTRAIT design space, whichever way it is held — the same seam the hangar
     * uses to keep an object's size off the orientation.
     */
    private var portraitLayout: ScreenLayout =
        ScreenLayout.compute(GameConfig.DESIGN_WIDTH, GameConfig.DESIGN_HEIGHT)

    /**
     * The card this device would lay out in PORTRAIT, whichever way it is held.
     *
     * Not the card actually drawn: a landscape card is a different SHAPE (643x403 against
     * 288x900) because the cards fan across a wide screen, and that is fine — what may not change
     * with the orientation is how big the things INSIDE it are drawn. Owner, 2026-09-20: "I find
     * the icons for weapon upgrades a tad big in landscape mode."
     */
    private fun portraitCardSize(): Pair<Float, Float> =
        portraitLayout.content.width * CARD_W_FRACTION to
            portraitLayout.content.height * CARD_H_FRACTION

    /**
     * The cap every icon in a card is held to: what it would be on this device in portrait.
     *
     * In portrait this is an IDENTITY rather than a reconstruction — `portraitShaped` returns the
     * layout unchanged there, so the expression below is the same one the card itself is built
     * from and the cap is bit-for-bit the size that already shipped. That is the whole safety
     * argument, and it is the same one `DesignSpace.portraitShaped` makes for the hangar.
     */
    private fun portraitCardIconSize(): Float {
        val (w, h) = portraitCardSize()
        return cardIconSize(w, h)
    }

    private fun portraitEvolutionIconSize(): Float {
        val (w, h) = portraitCardSize()
        return evolutionIconSize(w, h)
    }

    fun render(canvas: Canvas, options: List<UpgradeOption>, state: GameState, upgradeSystem: UpgradeSystem? = null) {
        val content = layout.content
        // Dim background (full screen)
        canvas.drawColor(0xAA000000.toInt())

        // Title
        val title = if (state.luckyStarAnimating) "LUCKY STAR!" else "CHOOSE UPGRADE"
        canvas.drawText(title, content.centerX, content.top + content.height * 0.12f, titlePaint)

        // Calculate card layout - bigger cards with comfortable spacing
        val cardWidth = content.width * CARD_W_FRACTION
        val cardHeight = content.height * CARD_H_FRACTION
        val numCards = options.size
        val spacing = content.width * 0.025f  // Tighter spacing since cards are bigger
        val totalCardsWidth = cardWidth * numCards + spacing * (numCards - 1)
        val startX = content.left + (content.width - totalCardsWidth) / 2
        val cardY = content.top + content.height * 0.22f

        cardRects.clear()
        focusRegistry.begin()

        for ((index, option) in options.withIndex()) {
            val cardX = startX + index * (cardWidth + spacing)
            val rect = RectF(cardX, cardY, cardX + cardWidth, cardY + cardHeight)
            cardRects.add(rect)
            focusRegistry.add(FocusTarget("card:$index", rect) { onCardActivated?.invoke(index) })

            when {
                option.isEvolution -> {
                    // Render special evolution card
                    renderEvolutionCard(canvas, rect, option, state)
                }
                else -> {
                    renderCard(canvas, rect, option, state, null)
                }
            }
        }

        focusRegistry.setDefault("card:0")
        focusRegistry.commit()
        if (focusRegistry.focusedId == null) focusRegistry.focusedId = "card:0"

        // Lucky Star: every card but the lit one is dimmed, at one weight, throughout.
        //
        // The losers used to FADE to that weight across the whole hold, and the effect was the
        // opposite of what it was for. They are already dimmed while the highlight is bouncing,
        // so at the moment the bounce settled the ramp restarted from zero — the nine cards
        // that just lost snapped back to full brightness and then took the entire 1.25s to
        // sink again, which is precisely the window the hold exists to make readable. Owner,
        // playtesting: dim them right away so it is obvious what won.
        //
        // The winner is exempt once the bounce has settled on it, and until then it is
        // whichever card the highlight is passing over. That is the only thing the two phases
        // now differ by.
        if (state.luckyStarAnimating) {
            val lit =
                if (state.luckyStarDimming) state.luckyStarSelectedIndex
                else state.luckyStarCurrentHighlight
            for ((index, rect) in cardRects.withIndex()) {
                if (index == lit) continue
                dimOverlayPaint.alpha = 0x99
                canvas.drawRect(rect, dimOverlayPaint)
            }
        }

    }

    // ITEM 5: Returns white border paint for all non-evolution cards
    private fun getUpgradeBorderPaint(option: UpgradeOption, state: GameState): Paint {
        return whiteBorderPaint
    }

    private fun renderCard(canvas: Canvas, rect: RectF, option: UpgradeOption, state: GameState, evolutionId: String? = null) {
        // Card background
        canvas.drawRect(rect, cardFillPaint)

        // Use special border if this triggers evolution, otherwise use white border
        if (evolutionId != null) {
            canvas.drawRect(rect, evolutionBorderPaint)
        } else {
            canvas.drawRect(rect, getUpgradeBorderPaint(option, state))
        }

        val centerX = rect.centerX()
        val cardPadding = 24f

        // TB-26 stacks 2-5 display as Combat Drone (they add green combat drones, not another TB-26).
        // Astro's Autonomous Ace shows the TB-26-X icon (pilot-aware).
        val displayId = when {
            !option.isWeapon && option.id == "tb26" && state.getPassiveStacks("tb26") >= 1 -> "combat_drone"
            option.isWeapon -> WeaponDefinitions.getWeaponIconId(option.id, state.activePilotId, state.astroLoopMode)
            else -> option.id
        }

        // --- ICON (larger, centered at top) ---
        val iconSize = minOf(cardIconSize(rect.width(), rect.height()), portraitCardIconSize())
        drawnCardIconSize = iconSize // seam — see this field's own doc
        val iconY = rect.top + cardPadding + iconSize / 2 + 10f

        cardPaint.style = Paint.Style.STROKE
        IconRenderer.drawIcon(canvas, displayId, option.isWeapon, centerX, iconY, iconSize, cardPaint)

        var y = iconY + iconSize / 2 + 28f

        // --- NAME (larger, prominent) ---
        val name = if (option.isWeapon) {
            WeaponDefinitions.getWeaponDisplayName(option.id)
        } else {
            PassiveDefinitions.getDisplayName(displayId, state.activePilotId, state.astroLoopMode)
        }
        fitTextSize(name, rect.width() - 20f, textPaint, 28f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = GameConfig.COLOR_HUD
        canvas.drawText(name, centerX, y, textPaint)

        y += 32f

        // --- LEVEL BADGE ---
        val currentLevel = if (option.isWeapon) {
            state.getWeaponLevel(option.id)
        } else {
            state.getPassiveStacks(option.id)
        }
        val maxLevel = if (option.isWeapon) GameConfig.WEAPON_MAX_LEVEL else GameConfig.PASSIVE_MAX_STACKS

        levelPaint.textSize = 24f
        levelPaint.textAlign = Paint.Align.CENTER
        if (currentLevel == 0) {
            newBadgePaint.textSize = 22f
            canvas.drawText("NEW", centerX, y, newBadgePaint)
        } else {
            val nextLevel = currentLevel + 1
            if (nextLevel > maxLevel) {
                levelPaint.color = 0xFFFFD700.toInt() // Gold for MAX
                canvas.drawText("MAX", centerX, y, levelPaint)
                levelPaint.color = 0xFFFFFF00.toInt()
            } else {
                canvas.drawText("Lv.$currentLevel > Lv.$nextLevel", centerX, y, levelPaint)
            }
        }

        y += 36f

        // --- CONTENT: Description (NEW) or Bonus (upgrade) ---
        smallTextPaint.textAlign = Paint.Align.CENTER
        smallTextPaint.textSize = 22f
        smallTextPaint.color = DESCRIPTION_GREY

        if (currentLevel == 0) {
            // NEW item: Show description
            val description = if (option.isWeapon) {
                WeaponDefinitions.getWeaponDef(option.id)?.description ?: ""
            } else {
                PassiveDefinitions.getPassiveDef(displayId)?.description ?: ""
            }
            val lines = wrapText(description, rect.width() - 24f, smallTextPaint)
            for (line in TextWrap.clamp(lines, 3)) {
                fitTextSize(line, rect.width() - 24f, smallTextPaint, 22f)
                canvas.drawText(line, centerX, y, smallTextPaint)
                y += 34f
            }

        } else {
            // UPGRADE: Just show the bonus (no current stats)
            levelPaint.textSize = 24f
            levelPaint.color = 0xFF44FF44.toInt()

            if (option.isWeapon) {
                val weaponDef = WeaponDefinitions.getWeaponDef(option.id)
                if (weaponDef != null) {
                    val nextLevel = currentLevel + 1
                    if (nextLevel <= maxLevel) {
                        val levelBonus = weaponDef.getLevelDescription(nextLevel)
                        val effectLines = splitEffectText(levelBonus)
                        for ((lineIndex, line) in effectLines.take(3).withIndex()) {
                            val prefix = if (lineIndex == 0) "↑ " else "  "
                            val lineText = "$prefix${line.trim()}"
                            fitTextSize(lineText, rect.width() - 30f, levelPaint, 24f)
                            canvas.drawText(lineText, centerX, y, levelPaint)
                            y += 36f
                        }
                    }
                }
            } else {
                val passiveDef = PassiveDefinitions.getPassiveDef(option.id)
                if (passiveDef != null) {
                    val effect = passiveDef.effectPerStack
                    val effectLines = splitEffectText(effect)
                    for ((lineIndex, line) in effectLines.take(3).withIndex()) {
                        val prefix = if (lineIndex == 0) "↑ " else "  "
                        val lineText = "$prefix${line.trim()}"
                        fitTextSize(lineText, rect.width() - 30f, levelPaint, 24f)
                        canvas.drawText(lineText, centerX, y, levelPaint)
                        y += 36f
                    }
                }
            }
            levelPaint.color = 0xFFFFFF00.toInt()
        }

        // --- EVOLUTION INDICATOR (if applicable, at bottom) ---
        if (evolutionId != null) {
            val evolvedName = WeaponDefinitions.getEvolutionDisplayName(evolutionId, state.activePilotId)

            // Position at bottom of card
            val evolveY = rect.bottom - 50f

            // Small icon
            val smallIconSize = inlineIconSize(minOf(rect.width(), portraitCardSize().first))
            cardPaint.color = 0xFFFF44FF.toInt()
            IconRenderer.drawIcon(canvas, WeaponDefinitions.getWeaponIconId(evolutionId, state.activePilotId, state.astroLoopMode), true, centerX - 50f, evolveY, smallIconSize, cardPaint)
            cardPaint.color = GameConfig.COLOR_HUD

            // "→ Storm Cannon" next to icon
            evolutionPaint.textSize = 18f
            evolutionPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("→ $evolvedName", centerX - 30f, evolveY + 6f, evolutionPaint)
            evolutionPaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun renderEvolutionCard(canvas: Canvas, rect: RectF, option: UpgradeOption, state: GameState) {
        // Special golden background for evolution cards
        canvas.drawRect(rect, goldFillPaint)

        // Draw animated glow effect
        val time = System.currentTimeMillis() / 100f
        val pulseAlpha = (0.3f + 0.2f * sin(time * 0.5f)).coerceIn(0f, 1f)
        goldBorderPaint.alpha = (pulseAlpha * 255).toInt()
        goldBorderPaint.strokeWidth = 6f
        canvas.drawRect(rect, goldBorderPaint)
        goldBorderPaint.alpha = 255
        goldBorderPaint.strokeWidth = 3f
        canvas.drawRect(rect, goldBorderPaint)

        val centerX = rect.centerX()
        var y = rect.top + 30f

        // "EVOLUTION!" header with gold color
        evolutionHeaderPaint.textSize = 24f
        canvas.drawText("EVOLUTION!", centerX, y, evolutionHeaderPaint)

        y += 35f

        // The two ingredients, drawn identically — same paint, same size, same colour.
        //
        // The passive used to be drawn with smallTextPaint while the weapon used textPaint, which
        // made them differ in size and colour. Worse, nothing set smallTextPaint's size or colour
        // before this draw — only its alignment — and that paint is shared and mutated all over
        // this file (the description pass below drives it down to 14f via fitTextSize). So the
        // passive rendered at whatever the previously drawn card happened to leave behind, and
        // three cards are drawn per screen. Reading "SOLAR STORM + PHOENIX CORE" as one recipe
        // requires both halves to look like they belong to it.
        textPaint.textSize = 18f
        textPaint.color = GameConfig.COLOR_HUD

        val baseName = WeaponDefinitions.getWeaponDisplayName(option.baseWeaponId ?: "")
        canvas.drawText(baseName, centerX, y, textPaint)

        y += 25f

        val passiveName = PassiveDefinitions.getDisplayName(option.requiredPassiveId ?: "", state.activePilotId, state.astroLoopMode)
        canvas.drawText("+ $passiveName", centerX, y, textPaint)

        y += 30f

        // Arrow pointing down
        arrowPaint.textSize = 32f
        canvas.drawText("↓", centerX, y, arrowPaint)

        y += 35f

        // Evolved weapon name (gold and larger, pilot-aware for Autonomous Ace / TB-26-X)
        val evolvedWeaponDef = WeaponDefinitions.getWeaponDef(option.id)
        val evolvedName = WeaponDefinitions.getEvolutionDisplayName(option.id, state.activePilotId)
        evolutionHeaderPaint.textSize = 28f
        canvas.drawText(evolvedName, centerX, y, evolutionHeaderPaint)

        y += 30f

        // Icon area for evolved weapon
        val iconSize =
            minOf(evolutionIconSize(rect.width(), rect.height()), portraitEvolutionIconSize())
        drawnEvolutionIconSize = iconSize // seam — see drawnCardIconSize's own doc
        cardPaint.style = Paint.Style.STROKE
        cardPaint.color = 0xFFFFD700.toInt() // Gold
        IconRenderer.drawIcon(canvas, WeaponDefinitions.getWeaponIconId(option.id, state.activePilotId, state.astroLoopMode), true, centerX, y + iconSize / 2, iconSize, cardPaint)
        cardPaint.color = GameConfig.COLOR_HUD

        y += iconSize + 25f

        // Evolved weapon description
        val description = evolvedWeaponDef?.description ?: ""
        smallTextPaint.textAlign = Paint.Align.CENTER
        // Reset the size before measuring. fitTextSize below mutates it — down to 14f — and the
        // paint is shared, so wrapping against whatever the last card left behind made the line
        // breaks depend on what had been drawn previously.
        smallTextPaint.textSize = 22f
        // And the colour, for the same reason: this card inherited 0xFFCCCCCC when an ordinary
        // card had been drawn first and its own constructed 0xFFAAAAAA when it had not.
        smallTextPaint.color = DESCRIPTION_GREY
        val lines = wrapText(description, rect.width() - 30f, smallTextPaint)
        for (line in TextWrap.clamp(lines, 2)) {
            fitTextSize(line, rect.width() - 30f, smallTextPaint, 22f)
            canvas.drawText(line, centerX, y, smallTextPaint)
            y += 26f
        }
    }


    private fun fitTextSize(text: String, maxWidth: Float, paint: Paint, maxSize: Float, minSize: Float = 14f): Float {
        paint.textSize = maxSize
        if (paint.measureText(text) <= maxWidth) return maxSize
        var size = maxSize
        while (paint.measureText(text) > maxWidth && size > minSize) {
            size -= 1f
            paint.textSize = size
        }
        return size
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> =
        TextWrap.wrap(text, maxWidth, paint::measureText)

    private fun splitEffectText(text: String): List<String> {
        // Split effect text by newline, comma, "and", or ampersand for separate lines
        return text.split(Regex("\n|,\\s*|\\s+and\\s+|\\s*&\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun getSelectedOption(x: Float, y: Float): Int {
        for ((index, rect) in cardRects.withIndex()) {
            if (rect.contains(x, y)) {
                return index
            }
        }
        return -1
    }
}
