package com.astroloop.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.astroloop.game.core.DebugActionDispatch
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.GameState
import com.astroloop.game.data.WeaponDefinitions
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.data.DesertDefinitions

class DebugMenuRenderer {
    private val bgPaint = Paint().apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 30f
        isAntiAlias = true
        typeface = FontManager.getBold()
        textAlign = Paint.Align.CENTER
    }
    private val btnFillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val btnStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val btnTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f
        isAntiAlias = true
        typeface = FontManager.getBold()
        textAlign = Paint.Align.CENTER
    }
    private val btnNumPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 18f
        isAntiAlias = true
        typeface = FontManager.getBold()
        textAlign = Paint.Align.CENTER
    }
    private val closeBtnPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val closeStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val closeTextPaint = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 20f
        isAntiAlias = true
        typeface = FontManager.getBold()
        textAlign = Paint.Align.CENTER
    }
    private val infoPaint = Paint().apply {
        color = 0xFF888888.toInt()
        textSize = 14f
        isAntiAlias = true
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val starPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = 0xFF8899BB.toInt()
    }
    private var screenWidth = 0f
    private var screenHeight = 0f

    // Page 0: Weapons & Passives
    private val weaponRects = Array(12) { RectF() }
    private val passiveRects = Array(PassiveDefinitions.getAllPassives().size) { RectF() }

    // Page 1: Evolutions & Resets
    private val evolutionRects = Array(12) { RectF() }
    private var resetSmallRect = RectF()
    private var resetBigRect = RectF()
    private var dieRect = RectF()

    // Page 3: Flak Designs (active designs: 35, 36, 38 — randomly selected at runtime)
    var debugFlakAge: Float = 0f
    private val flakCellRects = Array(3) { RectF() }
    private val flakActiveDesigns = intArrayOf(35, 36, 38)

    // Page 5: Black Market Designs
    private val BLACK_MARKET_PAGE = 5
    private var blackMarketScrollY = 0f
    private var blackMarketLastY = 0f
    private var debugStars: List<FloatArray>? = null   // [x, y, r] per star

    private fun drawDebugStarfield(canvas: Canvas) {
        val stars = debugStars ?: run {
            val rnd = java.util.Random(7)
            val list = ArrayList<FloatArray>(120)
            repeat(120) {
                list.add(floatArrayOf(
                    rnd.nextFloat() * screenWidth,
                    rnd.nextFloat() * screenHeight,
                    0.6f + rnd.nextFloat() * 1.6f
                ))
            }
            debugStars = list
            list
        }
        for (s in stars) canvas.drawCircle(s[0], s[1], s[2], starPaint)
    }

    // Page 2: Story Debug
    private var bossNowRect = RectF()
    private var setCorruptRect = RectF()
    private var killPilotRect = RectF()
    private var killAllRect = RectF()
    private var unlockCrystalRect = RectF()
    private var resetStoryRect = RectF()
    private var grantBandRect = RectF()
    private var clrBandRect = RectF()
    // Desert debug buttons
    private var playDesertRect = RectF()
    private var playDesertP2Rect = RectF()
    private var desertCrystalRect = RectF()
    private var astroLoopRect = RectF()
    private var setDesertFlagsRect = RectF()
    private var clrDesertRect = RectF()
    private var loopRect1 = RectF()
    private var loopRect2 = RectF()
    private var loopRect3 = RectF()

    // Page 6: Arcade (BELT RUN cabinet debug)
    private var arcadeOpenRect = RectF()
    private var arcadePlayRect = RectF()
    private var arcadeCreditsRect = RectF()
    private var arcadeClearPilotRect = RectF()
    private var arcadeClearAllRect = RectF()
    private var arcadeResetRect = RectF()
    // Index 0 is the authored opening; 1..5 are the five patterns.
    private val arcadePhaseRects = Array(6) { RectF() }
    // Same five patterns, landed on at lap 2 instead of lap 1 — the escalation was
    // otherwise reachable only by surviving a full 75-second lap first. Index i is
    // pattern i+1 (there is no lap-2 "OPEN").
    private val arcadeLap2PhaseRects = Array(5) { RectF() }

    private var closeButtonRect = RectF()

    var renderScale: Float = 1f

    /**
     * False when the menu is hosted by the hangar, where there is no live run.
     *
     * Pages 0 and 1 are the only ones that read run state — `renderWeaponsPassivesPage`,
     * `renderEvolutionsResetsPage` and `handleWeaponsPassivesTouch`. Everything else reads the
     * `debug*` persistence mirrors, which the hangar populates itself.
     *
     * Those two pages draw dimmed and inert rather than being hidden. Labelled, not hidden: it is
     * the treatment already used for the dimmed ARCADE buttons.
     */
    var runContext: Boolean = true

    // Swipe tracking
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isSwiping = false

    private val marginX = 16f
    private val buttonGap = 8f

    private val instantMaxPassives = setOf("glass_cannon", "phoenix_core", "duplicator_core", "extra_weapon_slot")

    fun initialize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    fun render(canvas: Canvas, state: GameState) {
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, bgPaint)

        if (!runContext && (state.debugMenuPage == 0 || state.debugMenuPage == 1)) {
            drawUnavailablePage(canvas)
            drawPageDots(canvas, state.debugMenuPage)
            drawCloseButton(canvas)
            return
        }

        when (state.debugMenuPage) {
            0 -> renderWeaponsPassivesPage(canvas, state)
            1 -> renderEvolutionsResetsPage(canvas, state)
            2 -> renderPhase4Page(canvas, state)
            3 -> renderFlakDesignsPage(canvas, state)
            4 -> drawThrusterPage(canvas)
            5 -> renderBlackMarketPage(canvas, state)
            6 -> renderArcadePage(canvas, state)
        }

        drawPageDots(canvas, state.debugMenuPage)
        drawCloseButton(canvas)
    }

    /** The two run-only pages, shown from the hangar. Visible, named, and inert. */
    private fun drawUnavailablePage(canvas: Canvas) {
        var y = 60f
        canvas.drawText("NOT AVAILABLE HERE", screenWidth / 2f, y, titlePaint)
        y += 90f
        canvas.drawText("Weapons, passives and evolutions", screenWidth / 2f, y, infoPaint)
        y += 44f
        canvas.drawText("need a live run. Launch, then use", screenWidth / 2f, y, infoPaint)
        y += 44f
        canvas.drawText("the button from inside the game.", screenWidth / 2f, y, infoPaint)
    }

    // =======================================================================
    // Page 0: Weapons & Passives
    // =======================================================================

    private fun renderWeaponsPassivesPage(canvas: Canvas, state: GameState) {
        var y = 60f

        canvas.drawText("WEAPONS & PASSIVES", screenWidth / 2f, y, titlePaint)
        y += 40f

        val sectionCols = 3
        val sectionRows = 4
        val gridWidth = screenWidth - marginX * 2
        val sectionHeight = (screenHeight - y - 140f) / 2f
        val buttonWidth = (gridWidth - (sectionCols - 1) * buttonGap) / sectionCols
        val buttonHeight = (sectionHeight - (sectionRows - 1) * buttonGap - 24f) / sectionRows

        infoPaint.color = 0xFF888888.toInt()
        canvas.drawText("WEAPONS", screenWidth / 2f, y, infoPaint)
        y += 20f

        val baseWeapons = WeaponDefinitions.getBaseWeapons()
        for (i in baseWeapons.indices) {
            val row = i / sectionCols
            val col = i % sectionCols
            val bx = marginX + col * (buttonWidth + buttonGap)
            val by = y + row * (buttonHeight + buttonGap)
            weaponRects[i] = RectF(bx, by, bx + buttonWidth, by + buttonHeight)

            val level = state.getWeaponLevel(baseWeapons[i].id)
            val hasIt = level > 0

            btnFillPaint.color = if (hasIt) 0xFF224488.toInt() else 0xFF222222.toInt()
            canvas.drawRoundRect(weaponRects[i], 6f, 6f, btnFillPaint)

            btnStrokePaint.color = if (hasIt) 0xFF4488FF.toInt() else 0xFF444444.toInt()
            canvas.drawRoundRect(weaponRects[i], 6f, 6f, btnStrokePaint)

            btnTextPaint.color = if (hasIt) 0xFFFFFFFF.toInt() else 0xFF777777.toInt()
            btnTextPaint.textSize = 11f
            canvas.drawText(baseWeapons[i].name, bx + buttonWidth / 2f, by + buttonHeight * 0.45f, btnTextPaint)

            btnNumPaint.color = if (hasIt) 0xFF88BBFF.toInt() else 0xFF555555.toInt()
            canvas.drawText("L$level", bx + buttonWidth / 2f, by + buttonHeight * 0.80f, btnNumPaint)
        }
        btnTextPaint.textSize = 13f

        y += sectionHeight + 10f
        infoPaint.color = 0xFF888888.toInt()
        canvas.drawText("PASSIVES", screenWidth / 2f, y, infoPaint)
        y += 20f

        val passives = PassiveDefinitions.getAllPassives()
        for (i in passives.indices) {
            val row = i / sectionCols
            val col = i % sectionCols
            val bx = marginX + col * (buttonWidth + buttonGap)
            val by = y + row * (buttonHeight + buttonGap)
            passiveRects[i] = RectF(bx, by, bx + buttonWidth, by + buttonHeight)

            val stacks = state.getPassiveStacks(passives[i].id)
            val hasIt = stacks > 0

            btnFillPaint.color = if (hasIt) 0xFF226633.toInt() else 0xFF222222.toInt()
            canvas.drawRoundRect(passiveRects[i], 6f, 6f, btnFillPaint)

            btnStrokePaint.color = if (hasIt) 0xFF44CC66.toInt() else 0xFF444444.toInt()
            canvas.drawRoundRect(passiveRects[i], 6f, 6f, btnStrokePaint)

            btnTextPaint.color = if (hasIt) 0xFFFFFFFF.toInt() else 0xFF777777.toInt()
            btnTextPaint.textSize = 11f
            canvas.drawText(passives[i].name, bx + buttonWidth / 2f, by + buttonHeight * 0.45f, btnTextPaint)

            btnNumPaint.color = if (hasIt) 0xFF88FFaa.toInt() else 0xFF555555.toInt()
            canvas.drawText("x$stacks", bx + buttonWidth / 2f, by + buttonHeight * 0.80f, btnNumPaint)
        }
        btnTextPaint.textSize = 13f
    }

    // =======================================================================
    // Page 1: Evolutions & Resets
    // =======================================================================

    private fun renderEvolutionsResetsPage(canvas: Canvas, state: GameState) {
        var y = 60f

        canvas.drawText("EVOLUTIONS & RESETS", screenWidth / 2f, y, titlePaint)
        y += 40f

        val sectionCols = 3
        val sectionRows = 4
        val gridWidth = screenWidth - marginX * 2
        val buttonWidth = (gridWidth - (sectionCols - 1) * buttonGap) / sectionCols
        val evoSectionHeight = screenHeight * 0.55f
        val buttonHeight = (evoSectionHeight - (sectionRows - 1) * buttonGap - 24f) / sectionRows

        infoPaint.color = 0xFF888888.toInt()
        canvas.drawText("EVOLUTIONS", screenWidth / 2f, y, infoPaint)
        y += 20f

        val baseWeapons = WeaponDefinitions.getBaseWeapons()
        for (i in baseWeapons.indices) {
            val weapon = baseWeapons[i]
            val evolvedId = weapon.evolutionWeaponId ?: continue
            val evolvedDef = WeaponDefinitions.getWeaponDef(evolvedId)

            val row = i / sectionCols
            val col = i % sectionCols
            val bx = marginX + col * (buttonWidth + buttonGap)
            val by = y + row * (buttonHeight + buttonGap)
            evolutionRects[i] = RectF(bx, by, bx + buttonWidth, by + buttonHeight)

            val hasIt = state.hasEvolution(evolvedId)

            btnFillPaint.color = if (hasIt) 0xFF664400.toInt() else 0xFF222222.toInt()
            canvas.drawRoundRect(evolutionRects[i], 6f, 6f, btnFillPaint)

            btnStrokePaint.color = if (hasIt) 0xFFFF8800.toInt() else 0xFF444444.toInt()
            canvas.drawRoundRect(evolutionRects[i], 6f, 6f, btnStrokePaint)

            val name = evolvedDef?.name ?: evolvedId
            btnTextPaint.color = if (hasIt) 0xFFFFFFFF.toInt() else 0xFF777777.toInt()
            btnTextPaint.textSize = 11f
            canvas.drawText(name, bx + buttonWidth / 2f, by + buttonHeight * 0.40f, btnTextPaint)

            infoPaint.color = if (hasIt) 0xFFFFCC88.toInt() else 0xFF555555.toInt()
            canvas.drawText(weapon.name, bx + buttonWidth / 2f, by + buttonHeight * 0.72f, infoPaint)
        }
        btnTextPaint.textSize = 13f

        y += evoSectionHeight + 20f

        val resetBtnWidth = screenWidth * 0.28f
        val resetBtnHeight = 60f
        val gap = 12f
        val totalResetWidth = resetBtnWidth * 3 + gap * 2
        val startX = (screenWidth - totalResetWidth) / 2f

        resetSmallRect = RectF(startX, y, startX + resetBtnWidth, y + resetBtnHeight)
        btnFillPaint.color = 0xFF442222.toInt()
        canvas.drawRoundRect(resetSmallRect, 8f, 8f, btnFillPaint)
        btnStrokePaint.color = 0xFFAA4444.toInt()
        canvas.drawRoundRect(resetSmallRect, 8f, 8f, btnStrokePaint)
        closeTextPaint.color = 0xFFFFAAAA.toInt()
        closeTextPaint.textSize = 16f
        canvas.drawText("RESET + \u00A5100", resetSmallRect.centerX(), resetSmallRect.centerY() + 6f, closeTextPaint)

        val bigX = startX + resetBtnWidth + gap
        resetBigRect = RectF(bigX, y, bigX + resetBtnWidth, y + resetBtnHeight)
        btnFillPaint.color = 0xFF224422.toInt()
        canvas.drawRoundRect(resetBigRect, 8f, 8f, btnFillPaint)
        btnStrokePaint.color = 0xFF44AA44.toInt()
        canvas.drawRoundRect(resetBigRect, 8f, 8f, btnStrokePaint)
        closeTextPaint.color = 0xFFAAFFAA.toInt()
        canvas.drawText("RICH RESET", resetBigRect.centerX(), resetBigRect.centerY() + 6f, closeTextPaint)

        val dieX = bigX + resetBtnWidth + gap
        dieRect = RectF(dieX, y, dieX + resetBtnWidth, y + resetBtnHeight)
        btnFillPaint.color = 0xFF441111.toInt()
        canvas.drawRoundRect(dieRect, 8f, 8f, btnFillPaint)
        btnStrokePaint.color = 0xFFFF2222.toInt()
        canvas.drawRoundRect(dieRect, 8f, 8f, btnStrokePaint)
        closeTextPaint.color = 0xFFFF4444.toInt()
        canvas.drawText("DIE", dieRect.centerX(), dieRect.centerY() + 6f, closeTextPaint)

        closeTextPaint.color = 0xFFCCCCCC.toInt()
        closeTextPaint.textSize = 20f
    }

    // =======================================================================
    // Page 2: Story Debug
    // =======================================================================

    private fun renderPhase4Page(canvas: Canvas, state: GameState) {
        var y = 60f

        canvas.drawText("STORY DEBUG", screenWidth / 2f, y, titlePaint)
        y += 24f

        infoPaint.color = 0xFF888888.toInt()
        val phaseName = when (state.debugStoryPhase) { 0 -> "NORMAL"; 1 -> "CORRUPT"; 2 -> "ASTRO"; else -> "?" }
        canvas.drawText("Stage: $phaseName | Dead: ${state.debugDeadPilotCount}/11 | Crystal: ${if (state.debugCrystalUnlocked) "Y" else "N"}", screenWidth / 2f, y, infoPaint)
        y += 16f
        canvas.drawText("Loop: ${state.debugStoryLoop}", screenWidth / 2f, y, infoPaint)
        y += 14f
        // Desert info line
        val desertStatus = when {
            state.debugDesertGoodEnding -> "good"
            state.debugDesertCompleted -> "done"
            else -> "none"
        }
        canvas.drawText("Desert:$desertStatus", screenWidth / 2f, y, infoPaint)
        y += 24f

        val btnWidth = screenWidth * 0.42f
        val btnHeight = 42f
        val gap = 8f
        val leftX = (screenWidth - btnWidth * 2 - gap) / 2f
        val rightX = leftX + btnWidth + gap

        // Row 1: BOSS NOW + SET CORRUPT
        bossNowRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        if (runContext) {
            drawPhase4Button(canvas, bossNowRect, "BOSS NOW", "Skip to 9:59",
                0xFF442244.toInt(), 0xFFAA44AA.toInt())
        } else {
            drawPhase4Button(canvas, bossNowRect, "BOSS NOW", "Needs a run",
                0xFF1a1a1a.toInt(), 0xFF333333.toInt())
        }

        setCorruptRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, setCorruptRect, "SET CORRUPT", "storyPhase = 1", 0xFF442222.toInt(), 0xFFAA2222.toInt())

        y += btnHeight + gap

        // Row 3: KILL PILOT + KILL ALL
        killPilotRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, killPilotRect, "KILL PILOT", "Next alive pilot", 0xFF443322.toInt(), 0xFFAA6644.toInt())

        killAllRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, killAllRect, "KILL ALL", "All 11 pilots", 0xFF442211.toInt(), 0xFFAA4422.toInt())

        y += btnHeight + gap

        // Row 4: BUY CRYSTAL + RESET STORY
        unlockCrystalRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, unlockCrystalRect, "BUY CRYSTAL", "Unlock + purchase", 0xFF224444.toInt(), 0xFF44AAAA.toInt())

        resetStoryRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, resetStoryRect, "RESET STORY", "Clear all story", 0xFF224422.toInt(), 0xFF44AA44.toInt())

        y += btnHeight + gap

        // Row 5: CLR DESERT
        clrDesertRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, clrDesertRect, "CLR DESERT", "Clear all desert", 0xFF442222.toInt(), 0xFFAA4444.toInt())

        y += btnHeight + gap

        // Row 6: GRANT BAND + CLR BAND
        grantBandRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, grantBandRect, "GRANT BAND", "All 12 bandanas", 0xFF223344.toInt(), 0xFF4488CC.toInt())

        clrBandRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, clrBandRect, "CLR BAND", "Clear bandanas", 0xFF332222.toInt(), 0xFFCC6666.toInt())

        y += btnHeight + gap

        // --- Desert section ---
        infoPaint.color = 0xFFAA8844.toInt()
        canvas.drawText("DESERT", screenWidth / 2f, y + 10f, infoPaint)
        y += 18f

        // Row 6: DESERT + DESERT P2
        playDesertRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        if (runContext) {
            drawPhase4Button(canvas, playDesertRect, "DESERT", "Start phase 0",
                0xFF443311.toInt(), 0xFFAA8833.toInt())
        } else {
            drawPhase4Button(canvas, playDesertRect, "DESERT", "Needs a run",
                0xFF1a1a1a.toInt(), 0xFF333333.toInt())
        }

        playDesertP2Rect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        if (runContext) {
            drawPhase4Button(canvas, playDesertP2Rect, "DESERT P2", "Escalation",
                0xFF443311.toInt(), 0xFFAA6622.toInt())
        } else {
            drawPhase4Button(canvas, playDesertP2Rect, "DESERT P2", "Needs a run",
                0xFF1a1a1a.toInt(), 0xFF333333.toInt())
        }

        y += btnHeight + gap

        // Row 7: CRYSTAL + DST FLAGS
        desertCrystalRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        if (runContext) {
            drawPhase4Button(canvas, desertCrystalRect, "CRYSTAL", "Crystal phase",
                0xFF224444.toInt(), 0xFF44AACC.toInt())
        } else {
            drawPhase4Button(canvas, desertCrystalRect, "CRYSTAL", "Needs a run",
                0xFF1a1a1a.toInt(), 0xFF333333.toInt())
        }

        setDesertFlagsRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, setDesertFlagsRect, "DST FLAGS", "Done + good ending", 0xFF443322.toInt(), 0xFFAA8844.toInt())

        y += btnHeight + gap

        // Row 9: ASTRO LOOP. Left-hand slot only — every row here fills left first, and the
        // ROUNDS button that used to share it went with the reckoning round counter.
        astroLoopRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        val astroLabel = if (state.debugAstroLoopMode) "ASTRO: ON" else "ASTRO: OFF"
        val astroSub = if (state.debugAstroLoopMode) "Tap to clear" else "Tap to set"
        val astroFill = if (state.debugAstroLoopMode) 0xFF224444.toInt() else 0xFF1a1a1a.toInt()
        val astroStroke = if (state.debugAstroLoopMode) 0xFF44AAAA.toInt() else 0xFF333333.toInt()
        drawPhase4Button(canvas, astroLoopRect, astroLabel, astroSub, astroFill, astroStroke)

        y += btnHeight + gap

        // Row 10: LOOP 1 / LOOP 2 / LOOP 3
        val thirdWidth = (rightX + btnWidth - leftX) / 3f - gap / 2f
        loopRect1 = RectF(leftX, y, leftX + thirdWidth, y + btnHeight)
        loopRect2 = RectF(leftX + thirdWidth + gap / 2f, y, leftX + thirdWidth * 2 + gap / 2f, y + btnHeight)
        loopRect3 = RectF(leftX + thirdWidth * 2 + gap, y, rightX + btnWidth, y + btnHeight)
        val loop1Fill = if (state.debugStoryLoop == 1) 0xFF224422.toInt() else 0xFF222244.toInt()
        val loop1Stroke = if (state.debugStoryLoop == 1) 0xFF44AA44.toInt() else 0xFF4444AA.toInt()
        val loop2Fill = if (state.debugStoryLoop == 2) 0xFF224422.toInt() else 0xFF222244.toInt()
        val loop2Stroke = if (state.debugStoryLoop == 2) 0xFF44AA44.toInt() else 0xFF4444AA.toInt()
        val loop3Fill = if (state.debugStoryLoop == 3) 0xFF224422.toInt() else 0xFF222244.toInt()
        val loop3Stroke = if (state.debugStoryLoop == 3) 0xFF44AA44.toInt() else 0xFF4444AA.toInt()
        drawPhase4Button(canvas, loopRect1, "LOOP 1", "First run", loop1Fill, loop1Stroke)
        drawPhase4Button(canvas, loopRect2, "LOOP 2", "Stop option on", loop2Fill, loop2Stroke)
        drawPhase4Button(canvas, loopRect3, "LOOP 3", "Nudge loop", loop3Fill, loop3Stroke)
    }

    private fun drawPhase4Button(canvas: Canvas, rect: RectF, title: String, subtitle: String, fillColor: Int, strokeColor: Int) {
        btnFillPaint.color = fillColor
        canvas.drawRoundRect(rect, 8f, 8f, btnFillPaint)
        btnStrokePaint.color = strokeColor
        canvas.drawRoundRect(rect, 8f, 8f, btnStrokePaint)
        closeTextPaint.color = 0xFFFFFFFF.toInt()
        closeTextPaint.textSize = 16f
        canvas.drawText(title, rect.centerX(), rect.centerY() - 2f, closeTextPaint)
        infoPaint.color = 0xFF999999.toInt()
        canvas.drawText(subtitle, rect.centerX(), rect.centerY() + 16f, infoPaint)
        closeTextPaint.color = 0xFFCCCCCC.toInt()
        closeTextPaint.textSize = 20f
    }

    // =======================================================================
    // Page 6: Arcade (BELT RUN cabinet debug)
    // =======================================================================

    private fun renderArcadePage(canvas: Canvas, state: GameState) {
        var y = 60f

        canvas.drawText("ARCADE", screenWidth / 2f, y, titlePaint)
        y += 40f

        val btnWidth = screenWidth * 0.42f
        val btnHeight = 50f
        val gap = 8f
        val leftX = (screenWidth - btnWidth * 2 - gap) / 2f
        val rightX = leftX + btnWidth + gap

        // Live as of stage 2. The route is CabinetDebugIntent riding onGameOver(0, false),
        // which is how RESET_SMALL/RESET_BIG/SET_CORRUPT already reach the hangar. Stage 1
        // drew these dimmed because no bridge existed; one does now.
        arcadeOpenRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadeOpenRect, "OPEN CABINET", "Skip the walk", 0xFF223344.toInt(), 0xFF4488CC.toInt())

        arcadePlayRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadePlayRect, "PLAY NOW", "Run - or the ending if cleared", 0xFF223344.toInt(), 0xFF4488CC.toInt())

        y += btnHeight + gap

        arcadeCreditsRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadeCreditsRect, "+10 CREDITS", "Bank ten credits", 0xFF223344.toInt(), 0xFF4488CC.toInt())

        arcadeClearPilotRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadeClearPilotRect, "CLEAR PILOT", "Selected pilot best = 999", 0xFF224422.toInt(), 0xFF44AA44.toInt())

        y += btnHeight + gap

        arcadeClearAllRect = RectF(leftX, y, leftX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadeClearAllRect, "CLEAR ALL 12", "Opens the stage-3 gate", 0xFF223344.toInt(), 0xFF4488CC.toInt())

        arcadeResetRect = RectF(rightX, y, rightX + btnWidth, y + btnHeight)
        drawPhase4Button(canvas, arcadeResetRect, "RESET ARCADE", "Wipe scores + credits", 0xFF332222.toInt(), 0xFFCC6666.toInt())

        y += btnHeight + gap * 2f

        canvas.drawText("RECKONING", screenWidth / 2f, y, titlePaint)
        y += 28f

        // Six small buttons: 0 plays the authored opening, 1..5 drop into that phase.
        // This is the action the design says to build before the patterns — a
        // pattern reachable only by surviving the four before it gets tuned twice a day.
        val phaseW = (screenWidth * 0.86f - gap * 5f) / 6f
        val phaseX0 = screenWidth * 0.07f
        for (i in 0 until 6) {
            val px = phaseX0 + i * (phaseW + gap)
            arcadePhaseRects[i] = RectF(px, y, px + phaseW, y + btnHeight)
            drawPhase4Button(
                canvas, arcadePhaseRects[i],
                if (i == 0) "OPEN" else "P$i",
                "", 0xFF332244.toInt(), 0xFFAA66CC.toInt()
            )
        }

        y += btnHeight + gap

        // The same five patterns, landed on at lap 2 instead of lap 1: the debug jump
        // hard-reset lap to 1, which made the escalation reachable only by surviving a
        // full 75-second lap in a fight nobody had confirmed was survivable. Aligned
        // under P1..P5 above (there is no lap-2 "OPEN") so the grid reads as one column
        // per pattern across both laps.
        for (i in 1..5) {
            val px = phaseX0 + i * (phaseW + gap)
            arcadeLap2PhaseRects[i - 1] = RectF(px, y, px + phaseW, y + btnHeight)
            drawPhase4Button(
                canvas, arcadeLap2PhaseRects[i - 1],
                "P$i'", "Lap 2", 0xFF223344.toInt(), 0xFF66AACC.toInt()
            )
        }
    }

    // =======================================================================
    // Page 3: Flak Designs (designs 20–39)
    // =======================================================================

    private val flakHighlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFFFF44.toInt()
        isAntiAlias = true
    }
    private val flakCellBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF111122.toInt()
    }
    private val flakCellStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF334455.toInt()
    }
    private val flakIndexPaint = Paint().apply {
        color = 0xFF888888.toInt()
        textSize = 10f
        isAntiAlias = true
        typeface = FontManager.getRegular()
        textAlign = Paint.Align.CENTER
    }

    private fun renderFlakDesignsPage(canvas: Canvas, state: GameState) {
        var y = 60f

        canvas.drawText("FLAK DESIGNS", screenWidth / 2f, y, titlePaint)
        y += 24f

        infoPaint.color = 0xFF888888.toInt()
        canvas.drawText("Randomly selected: #35, #36, #38", screenWidth / 2f, y, infoPaint)
        y += 22f

        // 3 cells in a single row
        val cols = 3
        val gridLeft = marginX
        val gridRight = screenWidth - marginX
        val gridBottom = screenHeight - 120f
        val cellW = (gridRight - gridLeft - (cols - 1) * 4f) / cols
        val cellH = gridBottom - y
        val previewRadius = (cellW.coerceAtMost(cellH) * 0.38f)

        for (i in 0 until 3) {
            val cellX = gridLeft + i * (cellW + 4f)
            val cellY = y
            flakCellRects[i] = RectF(cellX, cellY, cellX + cellW, cellY + cellH)

            val designIndex = flakActiveDesigns[i]

            canvas.drawRoundRect(flakCellRects[i], 4f, 4f, flakCellBgPaint)
            canvas.drawRoundRect(flakCellRects[i], 4f, 4f, flakCellStrokePaint)

            val cx = cellX + cellW / 2f
            val cy = cellY + cellH * 0.48f

            canvas.save()
            canvas.clipRect(cellX + 1f, cellY + 1f, cellX + cellW - 1f, cellY + cellH - 1f)
            FlakDesigns.render(canvas, designIndex, cx, cy, previewRadius, debugFlakAge)
            canvas.restore()

            flakIndexPaint.color = 0xFF666677.toInt()
            canvas.drawText("#$designIndex", cx, cellY + cellH - 4f, flakIndexPaint)
        }
    }

    private fun handleFlakDesignsTouch(ex: Float, ey: Float, state: GameState): String? {
        return null
    }

    // =======================================================================
    // Page 4: Thruster Designs
    // =======================================================================

    private fun drawThrusterPage(canvas: Canvas) {
        val cols = 4; val rows = 5
        val topPad = 80f; val botPad = 120f
        val cellW = screenWidth / cols.toFloat()
        val cellH = (screenHeight - topPad - botPad) / rows.toFloat()

        canvas.drawText("THRUSTER DESIGNS", screenWidth / 2f, 55f, titlePaint)

        val designs: List<(Canvas, Float, Float, Float, ShapeRenderer) -> Unit> = listOf(
            { c, cx, cy, s, sr -> ThrusterDesigns.design01_current(c, cx, cy, s, sr) },
            ThrusterDesigns::design02_narrowJet,
            ThrusterDesigns::design03_wideFan,
            ThrusterDesigns::design04_diamond,
            ThrusterDesigns::design05_doubleCone,
            ThrusterDesigns::design06_stepped,
            ThrusterDesigns::design07_ionicBlue,
            ThrusterDesigns::design08_plasmaPulse,
            ThrusterDesigns::design09_afterburner,
            ThrusterDesigns::design10_wave,
            ThrusterDesigns::design11_splitFork,
            { c, cx, cy, s, sr -> ThrusterDesigns.design12_diffuseCloud(c, cx, cy, s, sr) },
            ThrusterDesigns::design13_cometTail,
            ThrusterDesigns::design14_arrowhead,
            ThrusterDesigns::design15_starBurst,
            ThrusterDesigns::design16_pulseRing,
            ThrusterDesigns::design17_chevron,
            ThrusterDesigns::design18_spiral,
            ThrusterDesigns::design19_flare,
            ThrusterDesigns::design20_crystal
        )

        val labelPaint = Paint().apply {
            color = 0xFF888888.toInt()
            textSize = 11f
            textAlign = Paint.Align.CENTER
            typeface = FontManager.getRegular()
            isAntiAlias = true
        }
        val names = listOf(
            "1 CURRENT","2 NARROW JET","3 WIDE FAN","4 DIAMOND",
            "5 DBL CONE","6 STEPPED","7 IONIC","8 PLASMA",
            "9 AFTERBURN","10 WAVE","11 FORK","12 CLOUD",
            "13 COMET","14 ARROWHEAD","15 STARBURST","16 PULSE RING",
            "17 CHEVRON","18 SPIRAL","19 FLARE","20 CRYSTAL"
        )

        val cellBgPaint = Paint().apply { color = 0x22FFFFFF.toInt(); style = Paint.Style.FILL }
        val shipPaint = Paint().apply {
            color = 0xFF888888.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }

        for (i in 0 until 20) {
            val col = i % cols
            val row = i / cols
            val cx = cellW * col + cellW * 0.65f
            val cy = topPad + cellH * row + cellH * 0.5f
            val shipSize = minOf(cellW, cellH) * 0.18f

            // Cell background
            val cellLeft = cellW * col + 4f
            val cellTop = topPad + cellH * row + 4f
            canvas.drawRoundRect(
                android.graphics.RectF(cellLeft, cellTop, cellLeft + cellW - 8f, cellTop + cellH - 8f),
                8f, 8f, cellBgPaint)

            // Ship body (triangle pointing right)
            val path = android.graphics.Path().apply {
                moveTo(cx + shipSize, cy)
                lineTo(cx - shipSize * 0.7f, cy - shipSize * 0.6f)
                lineTo(cx - shipSize * 0.4f, cy)
                lineTo(cx - shipSize * 0.7f, cy + shipSize * 0.6f)
                close()
            }
            canvas.drawPath(path, shipPaint)

            // Thruster at nozzle (left indent of ship)
            val nozzleX = cx - shipSize * 0.4f
            val nozzleY = cy
            designs[i](canvas, nozzleX, nozzleY, shipSize, shapeRenderer)

            // Label
            val labelY = cellTop + cellH - 6f
            canvas.drawText(names[i], cx, labelY, labelPaint)
        }
    }

    // =======================================================================
    // Page 5: Black Market Designs
    // =======================================================================

    private fun renderBlackMarketPage(canvas: Canvas, state: GameState) {
        canvas.drawText("BLACK MARKET", screenWidth / 2f, 55f, titlePaint)

        // Starfield backdrop (designs are transparent, so stars show through them)
        drawDebugStarfield(canvas)

        val listTop = 78f
        val listBottom = screenHeight - 120f
        val bandH = listBottom - listTop          // one design fills the viewport
        val maxScroll = (BlackMarketDesigns.COUNT * bandH - (listBottom - listTop)).coerceAtLeast(0f)
        blackMarketScrollY = blackMarketScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, screenWidth, listBottom)
        canvas.translate(0f, -blackMarketScrollY)

        for (i in 0 until BlackMarketDesigns.COUNT) {
            val top = listTop + i * bandH
            // cull bands fully outside the viewport
            if (top + bandH < listTop + blackMarketScrollY || top > listTop + blackMarketScrollY + bandH) continue
            val bounds = RectF(marginX, top, screenWidth - marginX, top + bandH)
            BlackMarketDesigns.render(canvas, i, bounds)
            BlackMarketDesigns.drawMiniSlot(canvas, bounds)
            infoPaint.color = 0xFFCCCCDD.toInt()
            canvas.drawText("#${i + 1}  ${BlackMarketDesigns.NAMES[i]}", bounds.centerX(), top + 16f, infoPaint)
        }
        canvas.restore()
    }

    // =======================================================================
    // Shared UI
    // =======================================================================

    private val PAGE_COUNT = 7
    private val shapeRenderer = ShapeRenderer()

    private fun drawPageDots(canvas: Canvas, currentPage: Int) {
        val dotRadius = 6f
        val dotGap = 20f
        val totalWidth = PAGE_COUNT * dotRadius * 2 + (PAGE_COUNT - 1) * dotGap
        val startX = (screenWidth - totalWidth) / 2f + dotRadius
        val dotY = screenHeight - 100f

        for (i in 0 until PAGE_COUNT) {
            dotPaint.color = if (i == currentPage) 0xFFFFFFFF.toInt() else 0xFF555555.toInt()
            canvas.drawCircle(startX + i * (dotRadius * 2 + dotGap), dotY, dotRadius, dotPaint)
        }
    }

    private fun drawCloseButton(canvas: Canvas) {
        val closeBtnWidth = 200f
        val closeBtnHeight = 48f
        val closeBtnX = (screenWidth - closeBtnWidth) / 2f
        val closeBtnY = screenHeight - 60f
        closeButtonRect = RectF(closeBtnX, closeBtnY, closeBtnX + closeBtnWidth, closeBtnY + closeBtnHeight)

        closeBtnPaint.color = 0x44888888.toInt()
        canvas.drawRoundRect(closeButtonRect, 8f, 8f, closeBtnPaint)
        closeStrokePaint.color = 0xFF888888.toInt()
        canvas.drawRoundRect(closeButtonRect, 8f, 8f, closeStrokePaint)
        canvas.drawText("CLOSE", closeButtonRect.centerX(), closeButtonRect.centerY() + 7f, closeTextPaint)
    }

    // =======================================================================
    // Touch handling
    // =======================================================================

    fun handleTouch(event: MotionEvent, state: GameState): String? {
        val ex = event.x / renderScale
        val ey = event.y / renderScale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ex
                swipeStartY = ey
                isSwiping = false
                // Tuning scroll
                blackMarketLastY = ey
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ex - swipeStartX
                if (kotlin.math.abs(dx) > 50f) {
                    isSwiping = true
                }
                if (state.debugMenuPage == BLACK_MARKET_PAGE && !isSwiping) {
                    blackMarketScrollY += (blackMarketLastY - ey)
                    blackMarketLastY = ey
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ex - swipeStartX
                if (isSwiping && kotlin.math.abs(dx) > 50f) {
                    if (dx < 0 && state.debugMenuPage < PAGE_COUNT - 1) {
                        state.debugMenuPage++
                    } else if (dx > 0 && state.debugMenuPage > 0) {
                        state.debugMenuPage--
                    }
                    isSwiping = false
                    return null
                }

                return handleTapForPage(ex, ey, state)
            }
        }
        return null
    }

    private fun handleTapForPage(ex: Float, ey: Float, state: GameState): String? {
        if (closeButtonRect.contains(ex, ey)) {
            state.debugMenuOpen = false
            return "CLOSE"
        }

        if (!runContext && (state.debugMenuPage == 0 || state.debugMenuPage == 1)) {
            // Swipes between pages still work; nothing on these two pages is a target.
            return null
        }

        when (state.debugMenuPage) {
            0 -> return handleWeaponsPassivesTouch(ex, ey, state)
            1 -> return handleEvolutionsResetsTouch(ex, ey, state)
            2 -> return handlePhase4Touch(ex, ey, state)
            3 -> return handleFlakDesignsTouch(ex, ey, state)
            6 -> return handleArcadeTouch(ex, ey, state)
        }
        return null
    }

    private fun handleWeaponsPassivesTouch(ex: Float, ey: Float, state: GameState): String? {
        val baseWeapons = WeaponDefinitions.getBaseWeapons()

        for (i in baseWeapons.indices) {
            if (weaponRects[i].contains(ex, ey)) {
                val weaponId = baseWeapons[i].id
                val currentLevel = state.getWeaponLevel(weaponId)
                if (currentLevel >= GameConfig.WEAPON_MAX_LEVEL) {
                    state.weaponLevels.remove(weaponId)
                } else if (currentLevel == 0) {
                    state.weaponLevels[weaponId] = 1
                } else {
                    state.weaponLevels[weaponId] = currentLevel + 1
                }
                return "WEAPON_TOGGLE"
            }
        }

        val passives = PassiveDefinitions.getAllPassives()
        for (i in passives.indices) {
            if (passiveRects[i].contains(ex, ey)) {
                val passiveId = passives[i].id
                val currentStacks = state.getPassiveStacks(passiveId)

                if (instantMaxPassives.contains(passiveId)) {
                    if (currentStacks > 0) {
                        state.passiveStacks.remove(passiveId)
                    } else {
                        state.passiveStacks[passiveId] = GameConfig.PASSIVE_MAX_STACKS
                    }
                } else {
                    if (currentStacks >= GameConfig.PASSIVE_MAX_STACKS) {
                        state.passiveStacks.remove(passiveId)
                    } else if (currentStacks == 0) {
                        state.passiveStacks[passiveId] = 1
                    } else {
                        state.passiveStacks[passiveId] = currentStacks + 1
                    }
                }
                state.recalculateStats()
                return "PASSIVE_TOGGLE"
            }
        }
        return null
    }

    private fun handleEvolutionsResetsTouch(ex: Float, ey: Float, state: GameState): String? {
        val baseWeapons = WeaponDefinitions.getBaseWeapons()

        for (i in baseWeapons.indices) {
            if (evolutionRects[i].contains(ex, ey)) {
                val weapon = baseWeapons[i]
                val evolvedId = weapon.evolutionWeaponId ?: return null
                return "EVOLVE:${weapon.id}:$evolvedId"
            }
        }

        if (resetSmallRect.contains(ex, ey)) {
            return "RESET_SMALL"
        }
        if (resetBigRect.contains(ex, ey)) {
            return "RESET_BIG"
        }
        if (dieRect.contains(ex, ey)) {
            return "INSTANT_DEATH"
        }

        return null
    }

    private fun handlePhase4Touch(ex: Float, ey: Float, state: GameState): String? {
        val action = phase4Action(ex, ey) ?: return null
        // Same predicate the draw side used to decide whether to dim the row (Step 2), so a
        // row and its handler cannot disagree about which seven need a live run.
        if (!runContext && DebugActionDispatch.isRunOnly(action)) return null
        return action
    }

    private fun phase4Action(ex: Float, ey: Float): String? {
        if (bossNowRect.contains(ex, ey)) return "BOSS_NOW"
        if (setCorruptRect.contains(ex, ey)) return "SET_CORRUPT"
        if (killPilotRect.contains(ex, ey)) return "KILL_PILOT"
        if (killAllRect.contains(ex, ey)) return "KILL_ALL"
        if (unlockCrystalRect.contains(ex, ey)) return "BUY_CRYSTAL"
        if (resetStoryRect.contains(ex, ey)) return "RESET_STORY"
        if (grantBandRect.contains(ex, ey)) return "GRANT_BANDANAS"
        if (clrBandRect.contains(ex, ey)) return "CLEAR_BANDANAS"
        // Desert buttons
        if (playDesertRect.contains(ex, ey)) return "PLAY_DESERT"
        if (playDesertP2Rect.contains(ex, ey)) return "PLAY_DESERT_P2"
        if (desertCrystalRect.contains(ex, ey)) return "DESERT_CRYSTAL"
        if (astroLoopRect.contains(ex, ey)) return "TOGGLE_ASTRO_LOOP"
        if (setDesertFlagsRect.contains(ex, ey)) return "SET_DESERT_FLAGS"
        if (clrDesertRect.contains(ex, ey)) return "CLR_DESERT"
        if (loopRect1.contains(ex, ey)) return "SET_LOOP_1"
        if (loopRect2.contains(ex, ey)) return "SET_LOOP_2"
        if (loopRect3.contains(ex, ey)) return "SET_LOOP_3"
        return null
    }

    // =======================================================================
    // Page 6: Arcade (BELT RUN cabinet debug)
    // =======================================================================

    private fun handleArcadeTouch(ex: Float, ey: Float, state: GameState): String? {
        if (arcadeOpenRect.contains(ex, ey)) return "ARCADE_OPEN"
        if (arcadePlayRect.contains(ex, ey)) return "ARCADE_PLAY"
        for (i in 0 until 6) {
            if (arcadePhaseRects[i].contains(ex, ey)) return "RECKONING_PHASE_$i"
        }
        for (i in arcadeLap2PhaseRects.indices) {
            // i is pattern (i + 1); GameSurfaceView strips the "_LAP2" suffix and reads
            // lap from it, so this is the same action string family, not a new one.
            if (arcadeLap2PhaseRects[i].contains(ex, ey)) return "RECKONING_PHASE_${i + 1}_LAP2"
        }
        if (arcadeCreditsRect.contains(ex, ey)) return "ARCADE_CREDITS"
        if (arcadeClearPilotRect.contains(ex, ey)) return "ARCADE_CLEAR_PILOT"
        if (arcadeClearAllRect.contains(ex, ey)) return "ARCADE_CLEAR_ALL"
        if (arcadeResetRect.contains(ex, ey)) return "ARCADE_RESET"
        return null
    }

    fun handleTap(x: Float, y: Float, state: GameState): String? {
        return null
    }
}
