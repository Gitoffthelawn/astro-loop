package com.astroloop.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameState
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.PowerUp
import com.astroloop.game.system.UpgradeSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An upgrade card's icon is the same size whichever way the device is held.
 *
 * The owner, 2026-09-20: "I find the icons for weapon upgrades a tad big in landscape mode."
 *
 * The card itself legitimately changes SHAPE on rotation — 288x900 in portrait, 643x403 once the
 * content rect transposes, because three cards fan across a wide screen — and the icon was sized
 * from that card. Against the card's short side that is 124 units in portrait and 181 in
 * landscape: half as big again, on the screen the player sees at every single level-up.
 *
 * The rule is the hangar's golden rule, one layer over: an object's size may not depend on which
 * way the device is held. Only the arrangement may.
 *
 * Driven through a real render and read back from the renderer's own seam, not by calling the
 * pure helpers: the helpers returning the right number to a call site that has stopped asking
 * them is exactly the shape of bug this project keeps finding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpgradeIconOrientationTest {

    private lateinit var state: GameState
    private lateinit var upgradeSystem: UpgradeSystem

    @Before
    fun setup() {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        state = GameState()
        upgradeSystem = UpgradeSystem(EntityPool({ PowerUp() }, 20))
        upgradeSystem.unlockedWeaponIds = setOf("pulse_cannon", "railgun", "scatter_shot")
    }

    /** One render at a physical size, returning the icon size it actually drew. */
    private fun drawnIconSize(physW: Float, physH: Float): Float {
        val m = DesignSpace.metricsFor(physW, physH)
        val renderer = UpgradeSelectionRenderer()
        renderer.initialize(m.layout)
        val options = upgradeSystem.generateUpgradeOptions(state)
        val bitmap = Bitmap.createBitmap(
            physW.toInt().coerceAtLeast(1), physH.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        renderer.render(Canvas(bitmap), options, state, upgradeSystem)
        return renderer.drawnCardIconSize
    }

    /** The card a given content rect produces, as the renderer lays it out. */
    private fun cardOf(layout: ScreenLayout): Pair<Float, Float> =
        layout.content.width * UpgradeSelectionRenderer.CARD_W_FRACTION to
            layout.content.height * UpgradeSelectionRenderer.CARD_H_FRACTION

    // A Pixel 9 Pro, both ways up.
    private val portraitPx = 1280f to 2844f
    private val landscapePx = 2844f to 1280f

    @Test
    fun `portrait draws exactly the icon it always has`() {
        val layout = DesignSpace.metricsFor(portraitPx.first, portraitPx.second).layout
        val (w, h) = cardOf(layout)
        assertEquals(
            "portrait must be bit-identical to the shipped rule",
            UpgradeSelectionRenderer.cardIconSize(w, h),
            drawnIconSize(portraitPx.first, portraitPx.second),
            0f
        )
    }

    /** The owner's report, as the rule it breaks. */
    @Test
    fun `rotating the device does not change the icon's size`() {
        assertEquals(
            "the same device, held the other way, must draw the same icon",
            drawnIconSize(portraitPx.first, portraitPx.second),
            drawnIconSize(landscapePx.first, landscapePx.second),
            0.01f
        )
    }

    /**
     * ...and that it is the CAP doing the work, not a coincidence. Without it the landscape card
     * drives the icon to 45% of its own short side, which is half as big again.
     */
    @Test
    fun `the landscape card would have drawn a bigger icon on its own`() {
        val layout = DesignSpace.metricsFor(landscapePx.first, landscapePx.second).layout
        val (w, h) = cardOf(layout)
        val uncapped = UpgradeSelectionRenderer.cardIconSize(w, h)
        val drawn = drawnIconSize(landscapePx.first, landscapePx.second)

        assertTrue(
            "the cap must actually bind: uncapped $uncapped vs drawn $drawn",
            uncapped > drawn + 1f
        )
        // The numbers the owner was looking at, so a change to either fraction shows up here.
        // A Pixel 9 Pro with no cutout in the fixture: its portrait content column is 959.8 wide,
        // giving a 287.9 card and a 129.6 icon. (With the 128px cutout it is 916.8 / 275.0 /
        // 123.8 — the icon tracks the device, which is the point; what it may not track is the
        // orientation, and `rotating the device does not change the icon's size` pins that.)
        assertEquals("the uncapped landscape icon", 181.44f, uncapped, 0.01f)
        assertEquals("what it draws now", 129.6f, drawn, 0.01f)
    }

    @Test
    fun `the cap never makes an icon bigger than its own card would`() {
        // A short, wide screen: the card gets shorter still, and the card's own rule must win.
        for ((w, h) in listOf(2844f to 1280f, 3840f to 2160f, 1920f to 1080f, 2560f to 1600f)) {
            val layout = DesignSpace.metricsFor(w, h).layout
            val (cw, ch) = cardOf(layout)
            val ownRule = UpgradeSelectionRenderer.cardIconSize(cw, ch)
            val drawn = drawnIconSize(w, h)
            assertTrue(
                "${w.toInt()}x${h.toInt()}: drawn $drawn must never exceed the card's own $ownRule",
                drawn <= ownRule + 0.01f
            )
        }
    }

    /**
     * The cap is a portrait IDENTITY, not a reconstruction — `DesignSpace.portraitShaped` returns
     * the layout unchanged in portrait, so the capped expression is the same one the card is
     * built from. Asserted across portrait profiles at zero tolerance, because "portrait cannot
     * change" is this work's standing constraint.
     */
    @Test
    fun `no portrait profile moves at all`() {
        for ((w, h) in listOf(1280f to 2844f, 1080f to 2400f, 1440f to 3120f, 1600f to 2560f)) {
            val layout = DesignSpace.metricsFor(w, h).layout
            val (cw, ch) = cardOf(layout)
            assertEquals(
                "${w.toInt()}x${h.toInt()}",
                UpgradeSelectionRenderer.cardIconSize(cw, ch), drawnIconSize(w, h), 0f
            )
        }
    }
}
