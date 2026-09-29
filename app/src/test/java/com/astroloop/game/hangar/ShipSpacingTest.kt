package com.astroloop.game.hangar

import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.data.PersistenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShipSpacingTest {

    private fun spacingFor(physW: Float, physH: Float): Float {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val m = DesignSpace.metricsFor(physW, physH)
        val portrait = DesignSpace.portraitShaped(m.layout)
        val renderer = HangarRenderer(PersistenceManager(ctx))
        renderer.initialize(m.layout, HangarMetrics.roomWidth(portrait.width, portrait.content.width, 427))
        return renderer.shipSpacing
    }

    @Test
    fun `ships sit the same distance apart whichever way the device is held`() {
        assertEquals(spacingFor(1280f, 2844f), spacingFor(2844f, 1280f), 0.001f)
    }

    @Test
    fun `a rotated screen therefore shows more of the fleet, not the same three spread out`() {
        val spacing = spacingFor(2844f, 1280f)
        val screenW = DesignSpace.metricsFor(2844f, 1280f).width
        assertTrue("more than six ships should fall on screen", screenW / spacing > 6f)
    }
}
