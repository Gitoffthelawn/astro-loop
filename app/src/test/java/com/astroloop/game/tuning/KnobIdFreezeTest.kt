package com.astroloop.game.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Knob ids are a public format once a Lab build ships: share codes name them. This fails when an
 * id, default or range changes, so the change is made on purpose — rename with Tunables.alias,
 * and change a default only as part of a balance pass.
 */
class KnobIdFreezeTest {
    @Test
    fun `knob ids, defaults and ranges match the recorded list`() {
        val actual = Tunables.all
            .filterNot { it.group.startsWith("Test/") }
            .sortedBy { it.id }
            .joinToString("\n", postfix = "\n") { "${it.id} default=${it.defaultRaw} min=${it.minRaw} max=${it.maxRaw} ${it::class.simpleName} ${it.applies} \"${it.group}\"" }
        val file = File("src/test/resources/tuning/knob-ids.golden")
        if (System.getenv("GOLDEN_UPDATE") == "1") {
            file.writeText(actual)
            return
        }
        assertTrue("missing knob-ids.golden — record it with GOLDEN_UPDATE=1", file.exists())
        assertEquals(file.readText(), actual)
    }

    @Test
    fun `ids are lowercase scope-dot-key`() {
        for (k in Tunables.all.filterNot { it.group.startsWith("Test/") }) {
            assertTrue(k.id, Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$").matches(k.id))
        }
    }
}
