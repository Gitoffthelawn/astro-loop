package com.astroloop.game.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the knob refactor changed no number the game computes.
 *
 * The golden files were recorded on the tree before any knob existed. Regenerate them ONLY on a
 * tree whose behaviour is known-good, with `GOLDEN_UPDATE=1` in the environment — regenerating to
 * make a red test green defeats the point of the test.
 */
class GoldenBehaviourTest {

    @Test
    fun `weapons match the recorded golden`() = check("weapons.golden", GoldenSnapshot.weapons())

    @Test
    fun `world matches the recorded golden`() = check("world.golden", GoldenSnapshot.world())

    private fun check(name: String, actual: String) {
        val file = File("src/test/resources/tuning/$name")
        if (System.getenv("GOLDEN_UPDATE") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        assertTrue("missing $name — record it with GOLDEN_UPDATE=1 on a known-good tree", file.exists())
        assertEquals(file.readText(), actual)
    }
}
