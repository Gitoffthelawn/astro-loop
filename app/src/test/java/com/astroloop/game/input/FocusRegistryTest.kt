package com.astroloop.game.input

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FocusRegistryTest {

    private lateinit var registry: FocusRegistry
    private var fired: String? = null

    private fun target(id: String, left: Float, enabled: Boolean = true) =
        FocusTarget(id, RectF(left, 0f, left + 50f, 50f), enabled) { fired = id }

    @Before
    fun setup() {
        registry = FocusRegistry()
        fired = null
    }

    @Test
    fun `commit replaces the previous pass`() {
        registry.begin()
        registry.add(target("a", 0f))
        registry.commit()

        registry.begin()
        registry.add(target("b", 0f))
        registry.commit()

        assertEquals(listOf("b"), registry.targets().map { it.id })
    }

    @Test
    fun `focus survives a rebuild by id, not by index`() {
        registry.begin()
        registry.add(target("a", 0f))
        registry.add(target("b", 60f))
        registry.commit()
        registry.focusedId = "b"

        // "a" disappears — a dead ship filtered out during corruption, say. If focus were
        // held by index, it would silently slide onto a different control.
        registry.begin()
        registry.add(target("b", 0f))
        registry.commit()

        assertEquals("b", registry.focusedId)
    }

    @Test
    fun `focus falls back to the default when its target disappears`() {
        registry.begin()
        registry.add(target("a", 0f))
        registry.add(target("b", 60f))
        registry.commit()
        registry.setDefault("a")
        registry.focusedId = "b"

        registry.begin()
        registry.add(target("a", 0f))
        registry.commit()

        assertEquals("a", registry.focusedId)
    }

    @Test
    fun `activate fires the focused target's action`() {
        registry.begin()
        registry.add(target("a", 0f))
        registry.commit()
        registry.focusedId = "a"

        assertTrue(registry.activate())
        assertEquals("a", fired)
    }

    @Test
    fun `activate does nothing when the focused target is disabled`() {
        registry.begin()
        registry.add(target("a", 0f, enabled = false))
        registry.commit()
        registry.focusedId = "a"

        assertFalse(registry.activate())
        assertNull(fired)
    }

    @Test
    fun `activate does nothing with no focus`() {
        registry.begin()
        registry.add(target("a", 0f))
        registry.commit()

        assertFalse(registry.activate())
        assertNull(fired)
    }
}
