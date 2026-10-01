package com.astroloop.game.lab

import com.astroloop.game.tuning.Tunables
import org.junit.Assert.*
import org.junit.Test

class EditorModelTest {
    private val knobs get() = Tunables.all.filterNot { it.group.startsWith("Test/") }

    @Test
    fun `in this run comes first and holds the run's weapons, their bases, and passives`() {
        val rows = EditorModel.rows(knobs, listOf("storm_cannon"), listOf("tb26"), "")
        val first = rows.first() as EditorRow.Header
        assertEquals(EditorModel.IN_THIS_RUN, first.title)
        val ids = first.knobIds
        assertTrue("storm_cannon.damage" in ids)
        assertTrue("pulse_cannon.cooldown" in ids)       // the evolution's rate follows its base
        assertTrue("drone.damage" in ids)                 // tb26 means drones
        assertFalse("railgun.damage" in ids)
    }

    @Test
    fun `every knob appears once outside the run group`() {
        val rows = EditorModel.rows(knobs, emptyList(), emptyList(), "")
        val ids = rows.filterIsInstance<EditorRow.KnobRow>().map { it.knob.id }
        assertEquals(knobs.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(rows.none { it is EditorRow.Header && it.title == EditorModel.IN_THIS_RUN })
    }

    @Test
    fun `search matches id, label and group, case-insensitively`() {
        val rows = EditorModel.rows(knobs, emptyList(), emptyList(), "PIERCE")
        val ids = rows.filterIsInstance<EditorRow.KnobRow>().map { it.knob.id }
        assertTrue("railgun.pierce" in ids)
        val byId = knobs.associateBy { it.id }
        assertTrue(ids.all { id ->
            val k = byId.getValue(id)
            listOf(k.id, k.label, k.group).any { "pierce" in it.lowercase() }
        })
    }
}
