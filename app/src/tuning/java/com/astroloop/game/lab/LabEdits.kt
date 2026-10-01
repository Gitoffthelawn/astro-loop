package com.astroloop.game.lab

import com.astroloop.game.tuning.Knob
import com.astroloop.game.tuning.Tunables

/** Knob edits made in the Lab, written straight into the active tuning slot so they persist. */
object LabEdits {
    const val NEW_TITLE = "My tuning"

    fun set(store: PresetStore, knob: Knob, value: Float): Preset {
        if (knob.normalise(value) == knob.raw) return store.active()!!
        Tunables.set(knob, value)
        return commit(store)
    }

    /** Moves [knob] without saving, for a held button; true if it changed. */
    fun setLive(knob: Knob, value: Float): Boolean {
        if (knob.normalise(value) == knob.raw) return false
        Tunables.set(knob, value)
        return true
    }

    /** Saves whatever the knobs hold now into the active tuning. */
    fun commitLive(store: PresetStore): Preset = commit(store)

    fun resetKnobs(store: PresetStore, knobs: List<Knob>): Preset {
        val changed = knobs.filter { !it.isDefault }
        if (changed.isEmpty()) return store.active()!!
        for (k in changed) Tunables.set(k, k.defaultRaw)
        return commit(store)
    }

    private fun commit(store: PresetStore): Preset {
        val active = store.active()!!
        val values = Tunables.diffs()
        if (active.id == PresetStore.DEFAULT_ID && values.isEmpty()) return active
        val saved = if (active.id == PresetStore.DEFAULT_ID) {
            store.create(TuningPayload(LabBuild.BASE, NEW_TITLE, ShareCode.hash(active.payload), values)).also {
                store.activeId = it.id
            }
        } else {
            active.copy(payload = active.payload.copy(base = LabBuild.BASE, values = values)).also { store.save(it) }
        }
        LabSession.adoptEdited(saved)
        return saved
    }
}
