package com.astroloop.game

import android.content.Intent
import com.astroloop.game.tuning.RunLoadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectLoadoutTest {
    private val loadout = RunLoadout("ship_blue", "pilot_astro", listOf("pulse_cannon" to 1), listOf("tb26" to 1), 0)
    private fun intent(raw: String?) = Intent().apply { if (raw != null) putExtra(MainActivity.EXTRA_LOADOUT, raw) }

    @Test
    fun `the game ignores a loadout extra entirely`() {
        assertEquals(DirectLoadout.None, directLoadoutFrom(intent(loadout.encode()), isLab = false))
        assertEquals(DirectLoadout.None, directLoadoutFrom(intent("garbage"), isLab = false))
    }

    @Test
    fun `the lab reads a valid loadout`() {
        val r = directLoadoutFrom(intent(loadout.encode()), isLab = true)
        assertEquals(loadout.shipId, (r as DirectLoadout.Run).loadout.shipId)
    }

    @Test
    fun `the lab rejects an undecodable loadout and treats no extra as none`() {
        assertEquals(DirectLoadout.Invalid, directLoadoutFrom(intent("garbage"), isLab = true))
        assertEquals(DirectLoadout.None, directLoadoutFrom(intent(null), isLab = true))
        assertEquals(DirectLoadout.None, directLoadoutFrom(null, isLab = true))
    }
}
