package com.astroloop.game.input

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The focusable targets of one screen, rebuilt every layout pass.
 *
 * Built on the render thread and read by input on the UI thread, so the committed list is a
 * CopyOnWriteArrayList — the same choice BarPageRenderer and StorePageRenderer already make
 * for their published rects.
 */
class FocusRegistry {

    private val pending = mutableListOf<FocusTarget>()
    private val committed = CopyOnWriteArrayList<FocusTarget>()

    private var defaultId: String? = null

    @Volatile
    var focusedId: String? = null

    fun begin() {
        pending.clear()
    }

    fun add(target: FocusTarget) {
        pending.add(target)
    }

    fun commit() {
        committed.clear()
        committed.addAll(pending)
        pending.clear()
        // The focused target can vanish between passes — a ship filtered out during
        // corruption, a pilot card not yet revealed. Fall back rather than hold a dangling id.
        val current = focusedId
        if (current != null && committed.none { it.id == current }) {
            focusedId = defaultId?.takeIf { id -> committed.any { it.id == id } }
        }
    }

    fun targets(): List<FocusTarget> = committed

    fun byId(id: String): FocusTarget? = committed.firstOrNull { it.id == id }

    fun setDefault(id: String?) {
        defaultId = id
    }

    fun isEmpty(): Boolean = committed.isEmpty()

    /** @return true if a target was actually fired. */
    fun activate(): Boolean {
        val target = focusedId?.let { byId(it) } ?: return false
        if (!target.enabled) return false
        target.onActivate()
        return true
    }
}
