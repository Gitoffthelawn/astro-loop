package com.astroloop.game.lab

import com.astroloop.game.tuning.RunLoadout
import com.astroloop.game.tuning.RunSummary
import org.junit.Assert.*
import org.junit.Test

class RunReportTest {

    private val summary = RunSummary(
        shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
        survivedSeconds = 702.4f, cause = "asteroid.volatile",
        finalWeapons = linkedMapOf("railgun" to 5, "oblivion_beam" to 5),
        finalPassives = linkedMapOf("glass_cannon" to 5),
        damageByWeapon = mapOf("oblivion_beam" to 130022f, "railgun" to 48210f),
        damageTakenBy = mapOf("asteroid.rock" to 812f),
        asteroidsDestroyed = 1843, avgFps = 118.4f, tunedMidRunAtSeconds = 192f, loadout = null,
    )
    private val loadout = RunLoadout("ship_blue", "pilot_astro", listOf("railgun" to 5), listOf("glass_cannon" to 5, "cryo_field" to 3), 0)

    @Test
    fun `report has the fixed keys in order`() {
        val text = RunReport.format(summary.copy(loadout = loadout), "1.5-lab.1", "ALT1:abc", "7be01d", emptyList())
        val keys = text.lines().filter { it.isNotBlank() }.map { it.substringBefore(": ") }
        assertEquals(
            listOf(
                "astro-loop-lab-report", "build", "code", "code-hash", "ship", "pilot", "loadout", "final",
                "start-minute", "survived", "cause", "tuned-mid-run", "avg-fps", "low-fps",
                "damage.oblivion_beam", "damage.railgun", "damage-taken.asteroid.rock", "asteroids-destroyed",
            ),
            keys,
        )
    }

    @Test
    fun `values are formatted for people and parse back`() {
        val parsed = RunReport.parse(RunReport.format(summary.copy(loadout = loadout), "1.5-lab.1", "ALT1:abc", "7be01d", emptyList()))
        assertEquals("1", parsed["astro-loop-lab-report"])
        assertEquals("railgun L5, glass_cannon 5, cryo_field 3", parsed["loadout"])
        assertEquals("railgun L5, oblivion_beam L5, glass_cannon 5", parsed["final"])
        assertEquals("11:42", parsed["survived"])
        assertEquals("03:12", parsed["tuned-mid-run"])
        assertEquals("118", parsed["avg-fps"])
        assertEquals("48210 (4118/min)", parsed["damage.railgun"])
    }

    @Test
    fun `a clean run says no, and errors are listed`() {
        val text = RunReport.format(summary.copy(tunedMidRunAtSeconds = null), "b", "c", "h", listOf("IllegalStateException: boom", "  at X.y(X.kt:3)"))
        val parsed = RunReport.parse(text)
        assertEquals("no", parsed["tuned-mid-run"])
        assertEquals("IllegalStateException: boom", parsed["error.1"])
        assertEquals("at X.y(X.kt:3)", parsed["error.2"])
    }

    @Test
    fun `parse ignores lines it does not know`() {
        assertEquals(mapOf("a" to "1"), RunReport.parse("hello there\na: 1\n: nothing\n"))
    }

    @Test
    fun `slow devices are marked`() {
        fun low(fps: Float) = RunReport.parse(RunReport.format(summary.copy(avgFps = fps), "b", "c", "h", emptyList()))["low-fps"]
        assertEquals("yes", low(29.9f))
        assertEquals("no", low(30f))
        assertEquals("no", low(118f))
    }

    @Test
    fun `NaN avgFps produces avg-fps 0 without throwing`() {
        val text = RunReport.format(summary.copy(avgFps = Float.NaN), "b", "c", "h", emptyList())
        val parsed = RunReport.parse(text)
        assertEquals("0", parsed["avg-fps"])
    }

    @Test
    fun `NaN damage produces damage-0 without throwing`() {
        val text = RunReport.format(summary.copy(damageByWeapon = mapOf("railgun" to Float.NaN)), "b", "c", "h", emptyList())
        val parsed = RunReport.parse(text)
        assertEquals("0 (0/min)", parsed["damage.railgun"])
    }

    @Test
    fun `source id with special chars produces sanitised key`() {
        val text = RunReport.format(summary.copy(damageTakenBy = mapOf("weird: id\nx" to 123f)), "b", "c", "h", emptyList())
        val lines = text.lines().filter { it.startsWith("damage-taken.") }
        assertEquals(1, lines.size)
        assertEquals("damage-taken.weird__id_x: 123", lines[0])
    }

    @Test
    fun `empty error string produces dash`() {
        val text = RunReport.format(summary, "b", "c", "h", listOf(""))
        val parsed = RunReport.parse(text)
        assertEquals("-", parsed["error.1"])
    }

    @Test
    fun `CRLF input parses correctly`() {
        val text = "a: 1\r\nb: 2\r\n"
        val parsed = RunReport.parse(text)
        assertEquals(mapOf("a" to "1", "b" to "2"), parsed)
    }

    @Test
    fun `under a minute flown prints n-a per minute`() {
        val short = summary.copy(survivedSeconds = 40f, startMinute = 0)
        val parsed = RunReport.parse(RunReport.format(short, "b", "c", "h", emptyList()))
        assertEquals("48210 (n/a)", parsed["damage.railgun"])
    }
}
