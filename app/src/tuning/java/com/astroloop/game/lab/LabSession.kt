package com.astroloop.game.lab

import com.astroloop.game.tuning.ApplyResult
import com.astroloop.game.tuning.RunListener
import com.astroloop.game.tuning.RunSummary
import com.astroloop.game.tuning.Tunables

/**
 * The Lab's side of a run: which tuning and loadout it started with, the errors it hit, and the
 * report it produced. Lives for the process; the last report and its counter are also saved to disk when a run ends, so a process death between the run and the results screen loses nothing.
 */
object LabSession : RunListener {
    private const val MAX_ERROR_LINES = 12
    private const val KEY_REPORT = "last_report"
    private const val KEY_SEQ = "report_seq"

    @Volatile var lastReport: String? = null
        private set
    /** Counts finished runs, so a screen can tell whether there is a report it has not shown yet. */
    @Volatile var reportSeq: Int = 0
        private set
    /** The tuning the game actually holds, as last applied; what the screen and reports name. */
    @Volatile var activePayload: TuningPayload? = null
        private set
    private var store: KeyValue? = null

    /** Uses [kv] to keep the last report across process death, and restores what it holds. */
    fun attach(kv: KeyValue) {
        store = kv
        lastReport = kv.get(KEY_REPORT)
        reportSeq = kv.get(KEY_SEQ)?.toIntOrNull() ?: 0
    }

    private val errorLines = mutableListOf<String>()
    val errors: List<String> get() = synchronized(errorLines) { errorLines.toList() }

    /** The result of applying a preset: what the game rejected, and the preset as actually flown. */
    class Activation(val result: ApplyResult, val normalised: Preset)

    /**
     * Applies [p] and remembers what the game really holds afterwards: this build's base and only
     * the values that differ from defaults, so a report's hash always names the tuning flown.
     */
    fun activatePreset(p: Preset): Activation {
        val result = Tunables.applySlot(p.payload.values)
        val normalised = p.payload.copy(base = LabBuild.BASE, values = Tunables.diffs())
        activePayload = normalised
        return Activation(result, Preset(p.id, normalised))
    }

    /** Records [p] as the active tuning without re-applying it: the knobs already hold these values. */
    fun adoptEdited(p: Preset) {
        activePayload = p.payload
    }

    override fun onRunStarted() {
        synchronized(errorLines) { errorLines.clear() }
    }

    override fun onUpdateError(error: Throwable) {
        synchronized(errorLines) {
            if (errorLines.isNotEmpty()) return            // the first error is the one that matters
            errorLines += "${error::class.simpleName}: ${error.message}"
            error.stackTrace.take(MAX_ERROR_LINES - 1).forEach { errorLines += "at $it" }
        }
    }

    override fun tuneAvailable(): Boolean = true

    override fun quitAvailable(): Boolean = true

    @Volatile private var editorOpen = false

    internal fun editorClosed() { editorOpen = false }

    override fun onTuneRequested(host: android.app.Activity, weaponLevels: Map<String, Int>, passiveIds: List<String>) {
        if (editorOpen) return
        editorOpen = true
        host.startActivity(
            android.content.Intent(host, TuningEditorActivity::class.java)
                .putExtra(TuningEditorActivity.EXTRA_WEAPONS, weaponLevels.keys.toTypedArray())
                .putExtra(TuningEditorActivity.EXTRA_WEAPON_LEVELS, weaponLevels.values.toIntArray())
                .putExtra(TuningEditorActivity.EXTRA_PASSIVES, passiveIds.toTypedArray()),
        )
    }

    override fun onRunEnded(summary: RunSummary) {
        val payload = activePayload ?: TuningPayload(LabBuild.BASE, "Default", "", emptyMap())
        val live = payload.copy(base = LabBuild.BASE, values = Tunables.diffs())
        lastReport = RunReport.format(
            summary, LabBuild.BASE,
            code = ShareCode.encode(live), codeHash = ShareCode.hash(live), errors = errors,
        )
        reportSeq++
        store?.let { it.put(KEY_REPORT, lastReport); it.put(KEY_SEQ, reportSeq.toString()) }
    }
}
