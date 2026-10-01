package com.astroloop.game.lab

import android.content.Context
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class LabIconsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every weapon, passive, pilot and ship has an icon`() {
        for (w in com.astroloop.game.data.WeaponDefinitions.weapons + com.astroloop.game.data.WeaponDefinitions.evolutions)
            assertNotNull(w.id, LabIcons.weapon(ctx, w.id))
        for (p in com.astroloop.game.data.PassiveDefinitions.passives) assertNotNull(p.id, LabIcons.passive(ctx, p.id))
        for (p in com.astroloop.game.data.PilotDefinitions.pilots) assertNotNull(p.id, LabIcons.pilot(ctx, p.id))
        for (s in com.astroloop.game.data.ShipDefinitions.ships) assertNotNull(s.id, LabIcons.ship(ctx, s.id))
        assertNotNull(LabIcons.passive(ctx, "drone"))
        assertNull(LabIcons.forKnobGroup(ctx, "asteroid"))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `ship icons are drawn, not blank`() {
        val bmp = (LabIcons.ship(ctx, "ship_blue") as android.graphics.drawable.BitmapDrawable).bitmap
        var lit = 0
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) lit++
        assertTrue("ship bitmap is empty", lit > 0)
    }

    @Test
    fun `label puts the icon before or after the name`() {
        val icon = LabIcons.weapon(ctx, "railgun")
        val after = LabIcons.label("Railgun", icon, iconFirst = false)
        assertEquals("Railgun ${LabIcons.MARK}", after.toString())
        assertEquals(1, (after as Spanned).getSpans(0, after.length, ImageSpan::class.java).size)
        val before = LabIcons.label("Railgun", icon, iconFirst = true)
        assertEquals("${LabIcons.MARK} Railgun", before.toString())
        assertEquals("Railgun", LabIcons.label("Railgun", null, iconFirst = true).toString())
    }
}
