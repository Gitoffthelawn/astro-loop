package com.astroloop.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.GameState
import com.astroloop.game.core.ScreenLayout
import com.astroloop.game.data.PersistenceManager
import com.astroloop.game.entity.EntityPool
import com.astroloop.game.entity.PowerUp
import com.astroloop.game.system.UpgradeOption
import com.astroloop.game.system.UpgradeSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parity test. Focus and touch are two paths to the same actions by design, and this is
 * what stops them drifting: every published FocusTarget must resolve, at its own centre, to
 * the same card index the touch hit-test would return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpgradeFocusParityTest {

    private lateinit var renderer: UpgradeSelectionRenderer
    private lateinit var state: GameState
    private lateinit var upgradeSystem: UpgradeSystem

    @Before
    fun setup() {
        PersistenceManager(ApplicationProvider.getApplicationContext()).resetAllProgress()
        renderer = UpgradeSelectionRenderer()
        renderer.initialize(ScreenLayout.compute(1080f, 2400f))
        state = GameState()
        upgradeSystem = UpgradeSystem(EntityPool({ PowerUp() }, 20))
        upgradeSystem.unlockedWeaponIds = setOf("pulse_cannon", "railgun", "scatter_shot")
    }

    private fun renderOnce(): List<UpgradeOption> {
        val options = upgradeSystem.generateUpgradeOptions(state)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        renderer.render(Canvas(bitmap), options, state, upgradeSystem)
        return options
    }

    @Test
    fun `every card publishes a focus target`() {
        val options = renderOnce()

        assertEquals(options.size, renderer.focusRegistry.targets().size)
    }

    @Test
    fun `each focus target resolves to the same card the touch path would pick`() {
        renderOnce()

        for (target in renderer.focusRegistry.targets()) {
            val expectedIndex = target.id.substringAfter("card:").toInt()
            val touchIndex = renderer.getSelectedOption(
                target.rect.centerX(),
                target.rect.centerY()
            )
            assertEquals(
                "focus target ${target.id} and the touch hit-test disagree",
                expectedIndex,
                touchIndex
            )
        }
    }

    @Test
    fun `the registry defaults focus to the first card`() {
        renderOnce()

        assertEquals("card:0", renderer.focusRegistry.focusedId)
    }

    @Test
    fun `a re-render does not duplicate targets`() {
        renderOnce()
        val first = renderer.focusRegistry.targets().size
        renderOnce()

        assertTrue("targets must be rebuilt, not appended", renderer.focusRegistry.targets().size == first)
    }

    @Test
    fun `activating a target reports the card index it was published for`() {
        // The registry fires onActivate; this is what carries the index to applySelectedUpgrade,
        // so a target that fires the wrong index would apply the wrong upgrade.
        renderOnce()
        var fired = -1
        renderer.onCardActivated = { index -> fired = index }

        renderer.focusRegistry.focusedId = "card:1"
        assertTrue(renderer.focusRegistry.activate())

        assertEquals(1, fired)
    }
}
