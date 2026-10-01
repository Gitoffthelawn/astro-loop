package com.astroloop.game.lab

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.astroloop.game.render.FontManager
import com.astroloop.game.tuning.ApplyResult

/** The tuning library: import, create, and manage saved slots, plus any that no longer parse. */
class LibraryActivity : ComponentActivity() {

    private val presets: PresetStore get() = LabApp.presets
    private lateinit var box: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontManager.initialize(this)
        box = LabUi.column(this)
        setContentView(LabUi.screen(this, box))
        render()
    }

    private fun render() {
        box.removeAllViews()
        box.addView(LabUi.text(this, 26f, bold = true).apply { text = "Tuning library" })
        box.addView(LabUi.button(this, "Import code from clipboard") { importFromClipboard() }.focusable())
        box.addView(LabUi.button(this, "New from Default") {
            val p = presets.create(TuningPayload(LabBuild.BASE, "My tuning", "", emptyMap()))
            toast("Created: ${p.payload.title}")
            render()
        }.focusable())

        val activeId = presets.active()?.id
        for (p in presets.all()) box.addView(slotRow(p, p.id == activeId))

        val broken = presets.broken()
        if (broken.isNotEmpty()) {
            box.addView(LabUi.text(this, 18f, bold = true, color = LabUi.CHANGED).apply {
                text = "Damaged slots"
                setPadding(0, LabUi.dp(context, 24), 0, LabUi.dp(context, 4))
            })
            for ((id, raw) in broken) box.addView(brokenRow(id, raw))
        }
    }

    private fun slotRow(p: Preset, active: Boolean): View {
        val pl = p.payload
        val line = "${pl.title} · ${ShareCode.hash(pl)} · ${pl.values.size} changes" + if (active) "  ● active" else ""
        return LabUi.column(this).apply {
            isFocusable = true
            isClickable = true
            setPadding(0, LabUi.dp(context, 10), 0, LabUi.dp(context, 10))
            setOnClickListener { showActions(p) }
            setOnFocusChangeListener { v, hasFocus -> v.setBackgroundColor(if (hasFocus) 0x33FFFFFF else 0) }
            addView(LabUi.text(context, 16f, color = if (active) LabUi.CHANGED else LabUi.FG).apply { text = line })
            if (pl.base != LabBuild.BASE) {
                addView(LabUi.text(context, 13f, color = LabUi.DIM).apply {
                    text = "Made for defaults ${pl.base}: values may mean something different now."
                })
            }
        }
    }

    private fun brokenRow(id: String, raw: String): View = LabUi.column(this).apply {
        setPadding(0, LabUi.dp(context, 8), 0, LabUi.dp(context, 8))
        addView(LabUi.text(context, 14f, color = LabUi.DIM).apply {
            text = "$id: " + raw.replace('\n', ' ').take(80)
        })
        addView(LabUi.row(context).apply {
            addView(LabUi.button(context, "Copy raw text") { copy("Damaged slot", raw); toast("Raw text copied") }.focusable())
            addView(LabUi.button(context, "Delete") { confirmDelete(id, id) }.focusable())
        })
    }

    private fun showActions(p: Preset) {
        val isDefault = p.id == PresetStore.DEFAULT_ID
        val labels = mutableListOf("Use")
        if (!isDefault) labels += "Rename"
        labels += "Duplicate"
        labels += "Copy share code"
        if (!isDefault) labels += "Delete"
        AlertDialog.Builder(this)
            .setTitle(p.payload.title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "Use" -> use(p)
                    "Rename" -> rename(p)
                    "Duplicate" -> { presets.duplicate(p.id); render() }
                    "Copy share code" -> { copy("Astro Loop Lab tuning", ShareCode.encode(p.payload)); toast("Code copied") }
                    "Delete" -> confirmDelete(p.id, p.payload.title)
                }
            }
            .show()
    }

    private fun use(p: Preset) {
        val activation = LabSession.activatePreset(p)
        presets.save(activation.normalised)
        presets.activeId = p.id
        val r = activation.result
        toast(
            "Using: ${p.payload.title}" +
                if (r.unknownIds.isNotEmpty() || r.clampedIds.isNotEmpty())
                    " (skipped ${r.unknownIds.size}, clamped ${r.clampedIds.size})" else ""
        )
        render()
    }

    private fun rename(p: Preset) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(p.payload.title)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> renameSlot(presets, p.id, input.text.toString()); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(id: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$title\"?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (deleteSlot(presets, id)) toast("Default restored")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromClipboard() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        when (val decoded = ShareCode.decode(text)) {
            is Decoded.Error -> toast(decoded.message)
            is Decoded.Ok -> {
                val p = presets.create(decoded.payload.copy(parent = ShareCode.hash(decoded.payload)))
                presets.activeId = p.id
                val activation = LabSession.activatePreset(p)
                presets.save(activation.normalised)
                render()
                AlertDialog.Builder(this)
                    .setTitle("Imported")
                    .setMessage(importSummary(
                        decoded.payload.title, activation.normalised.payload.values.size,
                        activation.result, decoded.payload.base, LabBuild.BASE,
                    ))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun copy(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun <V : View> V.focusable(): V = apply { isFocusable = true }

    internal fun shownText(): String = buildString { collect(box, this) }

    private fun collect(v: View, sb: StringBuilder) {
        if (v is android.widget.TextView) sb.append(v.text).append('\n')
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), sb)
    }

    companion object {
        /** Renames [id]; if it is the active slot, the session picks up the new title too. */
        internal fun renameSlot(store: PresetStore, id: String, title: String) {
            store.rename(id, title) ?: return
            if (store.activeId == id) LabSession.adoptEdited(store.get(id)!!)
        }

        /** What an import did: the title, how many knobs it changes, what was dropped or clamped, and a base warning. */
        internal fun importSummary(title: String, changes: Int, result: ApplyResult, madeFor: String, thisBase: String): String =
            buildList {
                add("Imported: $title")
                add(if (changes == 1) "1 change" else "$changes changes")
                if (result.unknownIds.isNotEmpty()) add("Skipped (unknown): ${result.unknownIds.joinToString(", ")}")
                if (result.clampedIds.isNotEmpty()) add("Clamped to range: ${result.clampedIds.joinToString(", ")}")
                if (madeFor != thisBase) add("Made for $madeFor defaults; values may mean something different in this build.")
            }.joinToString("\n")

        /** Deletes [id]; if it was the active slot, puts Default's knobs back. Returns true when it did. */
        internal fun deleteSlot(store: PresetStore, id: String): Boolean {
            val wasActive = id != PresetStore.DEFAULT_ID && store.activeId == id
            store.delete(id)
            if (!wasActive) return false
            val activation = LabSession.activatePreset(store.get(PresetStore.DEFAULT_ID)!!)
            store.save(activation.normalised)
            store.activeId = PresetStore.DEFAULT_ID
            return true
        }
    }
}
