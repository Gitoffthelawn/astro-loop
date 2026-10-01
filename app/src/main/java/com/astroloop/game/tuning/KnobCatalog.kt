package com.astroloop.game.tuning

/**
 * The objects that declare knobs. Kotlin initialises an object on first use, and a knob exists in
 * the registry only once its object has initialised — so anything that lists every knob goes
 * through [load] first. Add each new declaration object to the list.
 */
internal object KnobCatalog {
    private val declarations: List<Any> by lazy { listOf<Any>(KnobsWeapons, KnobsPassives, KnobsAsteroids, KnobsField, KnobsDrops) }

    fun load() {
        declarations.size
    }
}
