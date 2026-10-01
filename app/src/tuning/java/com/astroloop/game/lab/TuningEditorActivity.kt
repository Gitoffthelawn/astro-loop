package com.astroloop.game.lab

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import com.astroloop.game.render.FontManager
import com.astroloop.game.tuning.Applies
import com.astroloop.game.tuning.Knob
import com.astroloop.game.tuning.KnobsPassives
import com.astroloop.game.tuning.KnobsWeapons
import com.astroloop.game.tuning.Tunables

/**
 * Parse typed text from decimal input, handling decimal commas (German, French keyboards).
 * Returns null for non-finite or empty results.
 */
internal fun parseTyped(text: String): Float? {
    val trimmed = text.trim().replace(',', '.')
    if (trimmed.isEmpty()) return null
    val value = trimmed.toFloatOrNull() ?: return null
    return if (value.isFinite()) value else null
}

/** Every tunable knob, grouped, with the current run's knobs first. */
class TuningEditorActivity : ComponentActivity() {

    private val store: PresetStore get() = LabApp.presets
    private val knobs: List<Knob> = Tunables.all.filterNot { it.group.startsWith("Test/") }
    private var weaponIds: List<String> = emptyList()
    private var passiveIds: List<String> = emptyList()
    private var levels: Map<String, Int> = emptyMap()
    private var query = ""

    private lateinit var scroll: ScrollView
    private lateinit var title: android.widget.TextView
    private lateinit var rowsBox: LinearLayout
    private val knobViews = HashMap<String, View>()
    private val inRunIds = HashSet<String>()
    private var shownHeaders: List<String> = emptyList()

    override fun onDestroy() {
        LabSession.editorClosed()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontManager.initialize(this)
        weaponIds = intent.getStringArrayExtra(EXTRA_WEAPONS)?.toList() ?: emptyList()
        passiveIds = intent.getStringArrayExtra(EXTRA_PASSIVES)?.toList() ?: emptyList()
        val passed = intent.getIntArrayExtra(EXTRA_WEAPON_LEVELS)
        levels = if (passed != null && passed.size == weaponIds.size) weaponIds.zip(passed.toList()).toMap()
        else LoadoutModel.load(PrefsKeyValue(getSharedPreferences(LoadoutModel.KEY, MODE_PRIVATE))).weapons.toMap()

        val content = LabUi.column(this)
        title = LabUi.text(this, 20f, bold = true)
        content.addView(title)
        val search = EditText(this).apply {
            hint = "Search knobs"
            setTextColor(LabUi.FG)
            setHintTextColor(LabUi.DIM)
            typeface = FontManager.getRegular()
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    rebuild(null, scrollY = 0)
                }
            })
        }
        content.addView(search)
        rowsBox = LabUi.column(this)
        content.addView(rowsBox)
        scroll = LabUi.screen(this, content) as ScrollView
        setContentView(scroll)
        rebuild(null)
    }

    private fun updateTitle() {
        val p = LabSession.activePayload ?: LabApp.presets.active()!!.payload
        title.text = "Tuning: ${p.title} · ${ShareCode.hash(p)}"
    }

    /** Rebuilds only the row list, optionally restoring scroll position and focus on the pressed button. */
    private fun rebuild(focusTag: String?, scrollY: Int? = null) {
        val y = scrollY ?: scroll.scrollY
        updateTitle()
        rowsBox.removeAllViews()
        knobViews.clear()
        inRunIds.clear()
        val headers = mutableListOf<String>()
        for (row in EditorModel.rows(knobs, weaponIds, passiveIds, query)) {
            when (row) {
                is EditorRow.Header -> {
                    headers += row.title
                    if (row.title == EditorModel.IN_THIS_RUN) inRunIds += row.knobIds
                    rowsBox.addView(headerView(row))
                }
                is EditorRow.KnobRow -> rowsBox.addView(knobView(row.knob, row.knob.id in inRunIds).also { knobViews[row.knob.id] = it })
            }
        }
        shownHeaders = headers
        if (focusTag != null) rowsBox.findViewWithTag<View>(focusTag)?.requestFocus()
        scroll.post { scroll.scrollTo(0, y) }
    }

    /**
     * Redraws just the given knobs' rows in place. A full [rebuild] makes hundreds of views, which
     * a slow phone feels as a stall on every press; an edit only ever changes the knob it touched.
     */
    private fun refreshKnobs(ids: List<String>, focusTag: String?) {
        updateTitle()
        // An evolution's rate warning depends on its base weapon's cooldown, so it redraws with it.
        val rateRows = KnobsWeapons.all
            .filter { w -> w.rate != null && w.evolvesFrom?.cooldown?.id in ids }
            .map { it.rate!!.id }
        // The effective line reads the weapon's knobs and the duplicator's, so it follows their edits.
        val editedWeapons = ids.mapNotNull { Tunables.find(it)?.let(EffectiveLine::weaponOf) }.toSet()
        val duplicatorEdited = KnobsPassives.duplicatorExtraProjectiles.id in ids
        val lineRows = KnobsWeapons.all
            .filter { duplicatorEdited || it in editedWeapons }
            .mapNotNull { EffectiveLine.carrier(it)?.id }
        for (id in (ids + rateRows + lineRows).distinct()) {
            val old = knobViews[id] ?: continue
            val knob = Tunables.find(id) ?: continue
            val idx = rowsBox.indexOfChild(old)
            if (idx < 0) continue
            rowsBox.removeViewAt(idx)
            val fresh = knobView(knob, id in inRunIds)
            rowsBox.addView(fresh, idx)
            knobViews[id] = fresh
        }
        if (focusTag != null) rowsBox.findViewWithTag<View>(focusTag)?.requestFocus()
    }

    private fun headerView(h: EditorRow.Header): View {
        val r = LabUi.row(this)
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        r.setPadding(0, LabUi.dp(this, 16), 0, 0)
        val prefix = h.knobIds.firstOrNull()?.substringBefore('.')
        val icon = if (h.title.startsWith("Weapons/") || h.title.startsWith("Passives/")) prefix?.let { LabIcons.forKnobGroup(this, it) } else null
        r.addView(LabUi.text(this, 18f, bold = true).apply { text = LabIcons.label(h.title, icon, iconFirst = false); tag = "header" }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(LabUi.button(this, "Reset group") {
            val targets = h.knobIds.mapNotNull { Tunables.find(it) }
            LabEdits.resetKnobs(store, targets)
            refreshKnobs(h.knobIds, "resetgroup:${h.title}")
        }.apply { tag = "resetgroup:${h.title}" })
        return r
    }

    private fun knobView(k: Knob, inRun: Boolean = false): View {
        val col = LabUi.column(this)
        col.setPadding(0, LabUi.dp(this, 6), 0, LabUi.dp(this, 6))
        val top = LabUi.row(this)
        top.gravity = android.view.Gravity.CENTER_VERTICAL
        val labelAndValue = LabUi.column(this)
        labelAndValue.addView(LabUi.text(this, 15f).apply { 
            text = if (inRun) {
                val group = k.group.substringAfterLast('/')
                val icon = if (k.group.startsWith("Weapons/") || k.group.startsWith("Passives/")) LabIcons.forKnobGroup(this@TuningEditorActivity, k.id.substringBefore('.')) else null
                android.text.SpannableStringBuilder(LabIcons.label(group, icon, iconFirst = false)).append(" · ").append(k.label)
            } else k.label
        })
        labelAndValue.addView(LabUi.text(this, 14f, color = if (k.isDefault) LabUi.FG else LabUi.CHANGED).apply {
            text = if (k.isDefault) KnobFormat.value(k) else "${KnobFormat.default(k)} → ${KnobFormat.value(k)}"
            tag = "value:${k.id}"
            isFocusable = true
            isClickable = true
            setOnClickListener { promptValue(k) }
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) 0x33FFFFFF else 0)
            }
        })
        top.addView(labelAndValue, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(step("−", "minus:${k.id}") { KnobFormat.stepDown(k) })
        top.addView(step("+", "plus:${k.id}") { KnobFormat.stepUp(k) })
        top.addView(LabUi.button(this, "⟲") { LabEdits.resetKnobs(store, listOf(k)); refreshKnobs(listOf(k.id), "reset:${k.id}") }
            .apply { tag = "reset:${k.id}" })
        col.addView(top)
        col.addView(LabUi.text(this, 12f, color = LabUi.DIM).apply {
            text = when (k.applies) {
                Applies.LIVE -> "live"
                Applies.NEW_SPAWNS -> "applies to new spawns"
                Applies.ON_EDITOR_CLOSE -> "applies when you resume"
            }
        })
        EffectiveLine.forKnob(k, levels[EffectiveLine.weaponOf(k)?.weaponId])?.let { line ->
            col.addView(LabUi.text(this, 13f, color = LabUi.DIM).apply {
                text = styled(line)
                tag = "effective:${k.id}"
            })
        }
        KnobFormat.fastWarning(k)?.let { w ->
            col.addView(LabUi.text(this, 12f, color = LabUi.ACCENT).apply { text = w })
        }
        return col
    }

    private fun styled(line: EffectiveLine.Line): CharSequence {
        val r = line.highlight ?: return line.text
        return android.text.SpannableString(line.text).apply {
            setSpan(android.text.style.ForegroundColorSpan(LabUi.CHANGED), r.first, r.last + 1, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), r.first, r.last + 1, 0)
        }
    }

    private fun step(label: String, tag: String, next: () -> Float): View {
        val id = tag.substringAfter(':')
        var changed = false
        return LabUi.repeatButton(this, label,
            onStep = {
                val k = Tunables.find(id)
                val moved = k != null && LabEdits.setLive(k, next())
                if (moved) { changed = true; updateInPlace(k!!) }
                moved
            },
            onRelease = {
                if (changed) {
                    changed = false
                    LabEdits.commitLive(store)
                    if (!tearingDown) refreshKnobs(listOf(id), tag)
                }
            },
        ).apply { this.tag = tag }
    }

    /** True while the screen is going away: a release still saves, but the rows are not rebuilt. */
    private val tearingDown: Boolean
        get() = isFinishing || isDestroyed || !rowsBox.isAttachedToWindow

    /** Updates a knob's value text and its weapon's effective lines without replacing any view. */
    private fun updateInPlace(k: Knob) {
        rowsBox.findViewWithTag<android.widget.TextView>("value:${k.id}")?.apply {
            setTextColor(if (k.isDefault) LabUi.FG else LabUi.CHANGED)
            text = if (k.isDefault) KnobFormat.value(k) else "${KnobFormat.default(k)} → ${KnobFormat.value(k)}"
        }
        val weapons = if (k === KnobsPassives.duplicatorExtraProjectiles) KnobsWeapons.all
            else listOfNotNull(EffectiveLine.weaponOf(k))
        for (w in weapons) {
            val carrier = EffectiveLine.carrier(w) ?: continue
            val line = EffectiveLine.forKnob(carrier, levels[w.weaponId]) ?: continue
            rowsBox.findViewWithTag<android.widget.TextView>("effective:${carrier.id}")?.text = styled(line)
        }
    }

    private fun promptValue(k: Knob) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(ShareCode.formatValue(k.raw))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(k.label)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = parseTyped(input.text.toString())
                if (v != null) {
                    LabEdits.set(store, k, v)
                    refreshKnobs(listOf(k.id), "value:${k.id}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @VisibleForTesting
    internal fun headerTitles(): List<String> = shownHeaders

    @VisibleForTesting
    internal fun headerTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.tag == "header") out += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(rowsBox)
        return out
    }

    @VisibleForTesting
    internal fun rowView(knobId: String): View? = knobViews[knobId]

    @VisibleForTesting
    internal fun effectiveText(knobId: String): String? =
        rowsBox.findViewWithTag<android.widget.TextView>("effective:$knobId")?.text?.toString()

    @VisibleForTesting
    internal fun pressPlus(knobId: String) {
        rowsBox.findViewWithTag<View>("plus:$knobId")!!.performClick()
    }

    @VisibleForTesting
    internal fun pressMinus(knobId: String) {
        rowsBox.findViewWithTag<View>("minus:$knobId")!!.performClick()
    }

    companion object {
        const val EXTRA_WEAPONS = "com.astroloop.game.lab.EXTRA_WEAPONS"
        const val EXTRA_WEAPON_LEVELS = "com.astroloop.game.lab.EXTRA_WEAPON_LEVELS"
        const val EXTRA_PASSIVES = "com.astroloop.game.lab.EXTRA_PASSIVES"
    }
}
