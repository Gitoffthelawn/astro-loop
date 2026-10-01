package com.astroloop.game.lab

import android.content.SharedPreferences

interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

class MapKeyValue : KeyValue {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

data class Preset(val id: String, val payload: TuningPayload)

/**
 * The player's named tunings. Each slot is stored as share-code payload text, so what is saved
 * is exactly what would be shared. Exactly one slot is active; "default" always exists.
 */
class PresetStore(private val kv: KeyValue) {

    fun all(): List<Preset> = ids().mapNotNull { get(it) }

    fun get(id: String): Preset? {
        if (id == DEFAULT_ID) return Preset(DEFAULT_ID, defaultPayload())
        val text = kv.get("$PREFIX$id") ?: return null
        return ShareCode.parsePayloadText(text)?.let { Preset(id, it) }
    }

    fun save(p: Preset) {
        if (p.id == DEFAULT_ID) return
        kv.put("$PREFIX${p.id}", ShareCode.payloadText(p.payload))
        if (p.id !in ids()) kv.put(IDS_KEY, (ids() + p.id).joinToString(","))
    }

    fun create(payload: TuningPayload): Preset {
        val id = nextId()
        return Preset(id, payload).also { save(it) }
    }

    fun duplicate(id: String): Preset? {
        val source = get(id) ?: return null
        return create(source.payload.copy(title = source.payload.title + " (copy)", parent = ShareCode.hash(source.payload)))
    }

    fun rename(id: String, title: String): Preset? {
        if (id == DEFAULT_ID) return null
        val source = get(id) ?: return null
        val kept = title.trim().ifEmpty { source.payload.title.trim().ifEmpty { "Untitled" } }
        val renamed = Preset(id, source.payload.copy(title = kept))
        save(renamed)
        return get(id)
    }

    /** Slots that are listed but no longer parse: id to their raw stored text. */
    fun broken(): List<Pair<String, String>> =
        ids().filter { it != DEFAULT_ID && get(it) == null }.map { it to kv.get("$PREFIX$it").orEmpty() }

    fun delete(id: String) {
        if (id == DEFAULT_ID) return
        kv.put("$PREFIX$id", null)
        kv.put(IDS_KEY, ids().filter { it != id }.joinToString(","))
        if (activeId == id) activeId = DEFAULT_ID
    }

    var activeId: String?
        get() = kv.get(ACTIVE_KEY)
        set(value) = kv.put(ACTIVE_KEY, value)

    fun active(): Preset? {
        val id = activeId
        val found = id?.let { get(it) }
        if (found != null) return found
        if (id != null && id != DEFAULT_ID) activeId = DEFAULT_ID
        return get(DEFAULT_ID)
    }

    private fun ids(): List<String> =
        listOf(DEFAULT_ID) + kv.get(IDS_KEY).orEmpty().split(',').filter { it.isNotBlank() && it != DEFAULT_ID }

    private fun nextId(): String {
        val n = (kv.get(COUNTER_KEY)?.toIntOrNull() ?: 0) + 1
        kv.put(COUNTER_KEY, n.toString())
        return "s$n"
    }

    private fun defaultPayload() = TuningPayload(LabBuild.BASE, "Default", "", emptyMap())

    companion object {
        const val DEFAULT_ID = "default"
        private const val PREFIX = "preset."
        private const val IDS_KEY = "preset_ids"
        private const val ACTIVE_KEY = "preset_active"
        private const val COUNTER_KEY = "preset_counter"
    }
}

/** The defaults set this build's knobs were made from; stamped into every code it creates. */
object LabBuild {
    val BASE: String get() = com.astroloop.game.BuildConfig.VERSION_NAME
}
