package com.astroloop.game.lab

import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LabEditsTest {
    @After
    fun reset() = Tunables.resetAll()

    @Test
    fun `the first edit on Default makes a new active slot`() {
        val store = PresetStore(MapKeyValue())
        val p = LabEdits.set(store, KnobsWeapons.railgun.damage, 120f)
        assertNotEquals(PresetStore.DEFAULT_ID, p.id)
        assertEquals(p.id, store.activeId)
        assertEquals("My tuning", p.payload.title)
        assertEquals(mapOf("railgun.damage" to 120f), p.payload.values)
        assertEquals(120f, KnobsWeapons.railgun.damage.value)
    }

    @Test
    fun `later edits update the same slot and survive a reload`() {
        val kv = MapKeyValue()
        val store = PresetStore(kv)
        val first = LabEdits.set(store, KnobsWeapons.railgun.damage, 120f)
        LabEdits.set(store, KnobsWeapons.railgun.cooldown!!, 8f)
        val reloaded = PresetStore(kv).get(first.id)!!
        assertEquals(mapOf("railgun.damage" to 120f, "railgun.cooldown" to 8f), reloaded.payload.values)
    }

    @Test
    fun `resetting knobs removes them from the slot`() {
        val store = PresetStore(MapKeyValue())
        LabEdits.set(store, KnobsWeapons.railgun.damage, 120f)
        val p = LabEdits.resetKnobs(store, listOf(KnobsWeapons.railgun.damage))
        assertTrue(p.payload.values.isEmpty())
        assertEquals(80f, KnobsWeapons.railgun.damage.value)
    }

    @Test
    fun `resetting on Default with no diffs does not create a slot`() {
        val store = PresetStore(MapKeyValue())
        val before = store.all().size
        val p = LabEdits.resetKnobs(store, listOf(KnobsWeapons.railgun.damage))
        assertEquals(PresetStore.DEFAULT_ID, p.id)
        assertTrue(store.activeId == null || store.activeId == PresetStore.DEFAULT_ID)
        assertEquals(before, store.all().size)
    }

    @Test
    fun `an edit that changes nothing writes nothing`() {
        val store = PresetStore(MapKeyValue())
        val before = Tunables.version
        val p = LabEdits.set(store, KnobsWeapons.railgun.damage, KnobsWeapons.railgun.damage.value)
        assertEquals(PresetStore.DEFAULT_ID, p.id)
        assertEquals(before, Tunables.version)

        LabEdits.set(store, KnobsWeapons.railgun.damage, 120f)
        val v = Tunables.version
        LabEdits.set(store, KnobsWeapons.railgun.damage, 120f)
        assertEquals(v, Tunables.version)
    }

    @Test
    fun `resetting knobs already at default writes nothing`() {
        val store = PresetStore(MapKeyValue())
        val before = Tunables.version
        val p = LabEdits.resetKnobs(store, listOf(KnobsWeapons.railgun.damage))
        assertEquals(PresetStore.DEFAULT_ID, p.id)
        assertEquals(before, Tunables.version)
        assertEquals(PresetStore.DEFAULT_ID, store.active()!!.id)
    }

    @Test
    fun `live edits change the knob and save only on commit`() {
        val store = PresetStore(MapKeyValue())
        val k = com.astroloop.game.tuning.KnobsWeapons.pulseCannon.damage
        assertEquals(true, LabEdits.setLive(k, 16f))
        assertEquals(16f, k.value, 0f)
        assertEquals(null, store.active()!!.payload.values[k.id])
        LabEdits.commitLive(store)
        assertEquals(16f, store.active()!!.payload.values[k.id]!!, 0f)
        assertEquals(false, LabEdits.setLive(k, 16f))
    }
}
