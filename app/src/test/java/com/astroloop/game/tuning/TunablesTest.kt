package com.astroloop.game.tuning

import com.astroloop.game.core.BeatClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Knobs that exist only in the test JVM, under a group no real knob uses. */
object TestKnobs {
    private val g = KnobGroup("Test/Registry", "test")
    val speed = g.float("speed", "Speed", 100f)
    val count = g.int("count", "Count", 3, min = 1, max = 10)
    val cooldown = g.sixteenths("cooldown", "Cooldown", 4)
    val rate = g.evoRate("rate", "Rate", 0.5f)
    val perLevel = g.floatsPerLevel("radius", "Radius", floatArrayOf(4f, 5.5f, 7f, 8.5f, 10f))
}

class TunablesTest {

    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `knobs start at their defaults`() {
        assertEquals(100f, TestKnobs.speed.value)
        assertEquals(3, TestKnobs.count.value)
        assertTrue(TestKnobs.speed.isDefault)
    }

    @Test
    fun `non-finite values store the default on every knob type`() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (knob in listOf<Knob>(TestKnobs.speed, TestKnobs.count, TestKnobs.cooldown)) {
            for (b in bad) {
                Tunables.set(knob, knob.maxRaw)
                assertEquals("${knob.id} <- $b", knob.defaultRaw, Tunables.set(knob, b))
                assertEquals(knob.defaultRaw, knob.raw)
            }
        }
    }

    @Test
    fun `evolution rate takes the default for NaN and snaps infinities to an end`() {
        Tunables.set(TestKnobs.rate, 1f)
        assertEquals(0.5f, Tunables.set(TestKnobs.rate, Float.NaN))
        assertEquals(1f, Tunables.set(TestKnobs.rate, Float.POSITIVE_INFINITY))
        assertEquals(0.5f, Tunables.set(TestKnobs.rate, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `applySlot with a NaN leaves the knob at its default`() {
        Tunables.applySlot(mapOf("test.speed" to Float.NaN))
        assertEquals(100f, TestKnobs.speed.value)
    }

    @Test
    fun `float knobs clamp to their range`() {
        assertEquals(1000f, Tunables.set(TestKnobs.speed, 5000f))
        assertEquals(10f, Tunables.set(TestKnobs.speed, 0f))
    }

    @Test
    fun `int knobs round and clamp`() {
        Tunables.set(TestKnobs.count, 4.6f)
        assertEquals(5, TestKnobs.count.value)
        Tunables.set(TestKnobs.count, 99f)
        assertEquals(10, TestKnobs.count.value)
    }

    @Test
    fun `a sixteenth is a quarter of a beat at 120 BPM`() {
        assertEquals(BeatClock(120f).subdivisionMs(0.25f) / 1000f, SixteenthsKnob.SIXTEENTH_SECONDS)
        assertEquals(0.5f, TestKnobs.cooldown.seconds)
        Tunables.set(TestKnobs.cooldown, 3f)
        assertEquals(0.375f, TestKnobs.cooldown.seconds)
    }

    @Test
    fun `cooldowns cannot leave 1 to 32 sixteenths`() {
        Tunables.set(TestKnobs.cooldown, 0f)
        assertEquals(1, TestKnobs.cooldown.value)
        Tunables.set(TestKnobs.cooldown, 64f)
        assertEquals(32, TestKnobs.cooldown.value)
    }

    @Test
    fun `evolution rate snaps to 1x or half`() {
        Tunables.set(TestKnobs.rate, 0.9f)
        assertEquals(1f, TestKnobs.rate.factor)
        Tunables.set(TestKnobs.rate, 0.6f)
        assertEquals(0.5f, TestKnobs.rate.factor)
    }

    @Test
    fun `per-level knobs read by level and clamp past the top`() {
        assertEquals(4f, TestKnobs.perLevel.forLevel(1))
        assertEquals(10f, TestKnobs.perLevel.forLevel(5))
        assertEquals(10f, TestKnobs.perLevel.forLevel(9))
        assertEquals("test.radius_l3", TestKnobs.perLevel[2].id)
    }

    @Test
    fun `applySlot resets everything then applies the diffs`() {
        Tunables.set(TestKnobs.count, 7f)
        val result = Tunables.applySlot(mapOf("test.speed" to 200f, "test.nope" to 1f, "test.count" to 99f))
        assertEquals(200f, TestKnobs.speed.value)
        assertEquals(10, TestKnobs.count.value)             // clamped, not left at 7
        assertEquals(listOf("test.nope"), result.unknownIds)
        assertEquals(listOf("test.count"), result.clampedIds)
    }

    @Test
    fun `diffs lists only knobs away from default`() {
        Tunables.set(TestKnobs.speed, 150f)
        assertEquals(mapOf("test.speed" to 150f), Tunables.diffs().filterKeys { it.startsWith("test.") })
    }

    @Test
    fun `aliases resolve renamed knobs`() {
        Tunables.alias("test.old_speed", "test.speed")
        assertSame(TestKnobs.speed, Tunables.find("test.old_speed"))
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertNotNull(TestKnobs.speed) // registered
        assertThrows(IllegalArgumentException::class.java) {
            KnobGroup("Test/Registry", "test").float("speed", "Speed again", 1f)
        }
    }

    @Test
    fun `a default outside its range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatKnob("test.bad", "Test/Registry", "Bad", 5f, 10f, 20f, Applies.LIVE)
        }
    }

    @Test
    fun `every write bumps the version`() {
        val before = Tunables.version
        Tunables.set(TestKnobs.speed, 120f)
        assertTrue(Tunables.version > before)
    }
}
