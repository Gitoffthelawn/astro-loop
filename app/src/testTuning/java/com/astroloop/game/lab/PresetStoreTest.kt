package com.astroloop.game.lab

import org.junit.Assert.*
import org.junit.Test

class PresetStoreTest {

    private fun store() = PresetStore(MapKeyValue())
    private val payload = TuningPayload("1.5-lab.1", "Saw x2", "", mapOf("energy_saw.disc_radius_l5" to 110f))

    @Test
    fun `a new store has only the default slot, active`() {
        val s = store()
        assertEquals(listOf("default"), s.all().map { it.id })
        assertEquals("default", s.active()!!.id)
        assertTrue(s.active()!!.payload.values.isEmpty())
    }

    @Test
    fun `create, persist, reload`() {
        val kv = MapKeyValue()
        val created = PresetStore(kv).create(payload)
        val reloaded = PresetStore(kv)
        assertEquals(payload, reloaded.get(created.id)!!.payload)
        assertEquals(listOf("default", created.id), reloaded.all().map { it.id })
    }

    @Test
    fun `duplicate branches with the original as parent`() {
        val s = store()
        val a = s.create(payload)
        val b = s.duplicate(a.id)!!
        assertNotEquals(a.id, b.id)
        assertEquals(ShareCode.hash(a.payload), b.payload.parent)
        assertEquals(a.payload.values, b.payload.values)
    }

    @Test
    fun `default cannot be deleted and deleting the active slot falls back to default`() {
        val s = store()
        s.delete("default")
        assertNotNull(s.get("default"))
        val a = s.create(payload)
        s.activeId = a.id
        s.delete(a.id)
        assertEquals("default", s.active()!!.id)
    }

    @Test
    fun `a slot that no longer parses is skipped, not fatal`() {
        val kv = MapKeyValue()
        val s = PresetStore(kv)
        val a = s.create(payload)
        kv.put("preset.${a.id}", "garbage")
        assertNull(PresetStore(kv).get(a.id))
        assertEquals("default", PresetStore(kv).active()!!.id)
    }

    @Test
    fun `rename keeps values and hash`() {
        val s = store()
        val a = s.create(payload)
        val renamed = s.rename(a.id, "Saw twice as big")!!
        assertEquals("Saw twice as big", renamed.payload.title)
        assertEquals(ShareCode.hash(a.payload), ShareCode.hash(renamed.payload))
        assertNull(s.rename(PresetStore.DEFAULT_ID, "x"))
    }

    @Test
    fun `renaming to a blank title keeps the old title`() {
        val s = store()
        val a = s.create(payload)
        assertEquals("Saw x2", s.rename(a.id, "   ")!!.payload.title)
        val blank = s.create(payload.copy(title = ""))
        assertEquals("Untitled", s.rename(blank.id, "")!!.payload.title)
    }

    @Test
    fun `broken slots are listed with their raw text and an active pointer to one is cleared`() {
        val kv = MapKeyValue()
        val s = PresetStore(kv)
        val a = s.create(payload)
        s.activeId = a.id
        kv.put("preset.${a.id}", "garbage")
        val fresh = PresetStore(kv)
        assertEquals(listOf(a.id to "garbage"), fresh.broken())
        assertEquals(PresetStore.DEFAULT_ID, fresh.active()!!.id)
        assertEquals(PresetStore.DEFAULT_ID, fresh.activeId)
    }
}
