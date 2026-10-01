package com.astroloop.game.tuning

/**
 * Every knob in the game, by id.
 *
 * The normal game never writes here, so every knob keeps its default and behaviour is exactly the
 * shipped one. The Lab writes a tuning in before a run and whenever the player edits one.
 */
object Tunables {
    private val byId = LinkedHashMap<String, Knob>()
    private val aliases = HashMap<String, String>()

    /** Bumped on every write, so a consumer can cache a derived value and notice when it goes stale. */
    @Volatile
    var version: Int = 0
        private set

    fun <K : Knob> register(knob: K): K {
        synchronized(byId) {
            require(knob.id !in byId && knob.id !in aliases) { "duplicate knob id ${knob.id}" }
            byId[knob.id] = knob
        }
        return knob
    }

    /** Keeps an old share code importable after a knob is renamed. */
    fun alias(oldId: String, newId: String) {
        synchronized(byId) { aliases[oldId] = newId }
    }

    val all: List<Knob>
        get() {
            KnobCatalog.load()
            return synchronized(byId) { byId.values.toList() }
        }

    fun find(id: String): Knob? {
        KnobCatalog.load()
        return synchronized(byId) { byId[id] ?: aliases[id]?.let { byId[it] } }
    }

    /** Writes [value] normalised to the knob's range and lattice; returns what was stored. */
    fun set(knob: Knob, value: Float): Float {
        val v = knob.normalise(value)
        knob.raw = v
        version++
        return v
    }

    fun resetAll() {
        for (k in all) k.raw = k.defaultRaw
        version++
    }

    /** Resets every knob, then applies [diffs] (id → value). Reports what it skipped or clamped. */
    fun applySlot(diffs: Map<String, Float>): ApplyResult {
        resetAll()
        val unknown = mutableListOf<String>()
        val clamped = mutableListOf<String>()
        for ((id, value) in diffs) {
            val knob = find(id)
            if (knob == null) {
                unknown += id
                continue
            }
            if (set(knob, value) != value) clamped += id
        }
        return ApplyResult(unknown, clamped)
    }

    /** Knobs whose value differs from their default, id → value: what a share code carries. */
    fun diffs(): Map<String, Float> = all.filter { !it.isDefault }.associate { it.id to it.raw }
}

data class ApplyResult(val unknownIds: List<String>, val clampedIds: List<String>)
