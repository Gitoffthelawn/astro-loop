package com.astroloop.game.lab

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import com.astroloop.game.MainActivity
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.render.FontManager
import com.astroloop.game.tuning.RunLoadout

/** The Lab's home: the active tuning, the loadout, and the way into a run and its last results. */
class LabActivity : ComponentActivity() {

    private lateinit var box: android.widget.LinearLayout
    private var seenSeq = 0
    private var launching = false
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontManager.initialize(this)
        // A fresh launch has nothing new to show; a recreated Home remembers what it already opened.
        seenSeq = savedInstanceState?.getInt(STATE_SEEN_SEQ)?.let { minOf(it, LabSession.reportSeq) } ?: LabSession.reportSeq
        box = LabUi.column(this)
        setContentView(LabUi.screen(this, box))
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SEEN_SEQ, seenSeq)
    }

    override fun onResume() {
        super.onResume()
        launching = false
        navigating = false
        render()
        if (LabSession.reportSeq > seenSeq) {
            seenSeq = LabSession.reportSeq
            startActivity(Intent(this, ResultsActivity::class.java))
        }
    }

    private fun render() {
        box.removeAllViews()
        box.addView(LabUi.text(this, 28f, bold = true).apply { text = "Astro Loop Lab" })

        val tuning = LabSession.activePayload ?: LabApp.presets.active()?.payload
        box.addView(card(
            tuning?.let { "Tuning: ${it.title} · ${ShareCode.hash(it)} · ${it.values.size} changes" } ?: "Tuning: Default",
        ))
        val tuningRow = LabUi.row(this)
        tuningRow.addView(half(LabUi.button(this, "Tunings") { open(LibraryActivity::class.java) }))
        tuningRow.addView(half(LabUi.button(this, "Edit knobs") { open(TuningEditorActivity::class.java) }))
        box.addView(tuningRow)

        val loadout = LoadoutModel.load(loadoutStore())
        box.addView(loadoutCard(loadout))
        box.addView(LabUi.button(this, "Change loadout") { open(LoadoutActivity::class.java) })

        box.addView(LabUi.button(this, "Launch") {
            if (launching) return@button
            launching = true
            startActivity(launchIntent(this))
        }.apply {
            textSize = 24f
            setPadding(paddingLeft, LabUi.dp(context, 20), paddingRight, LabUi.dp(context, 20))
        })

        box.addView(LabUi.button(this, "Last results") { open(ResultsActivity::class.java) }
            .apply { isEnabled = LabSession.lastReport != null })
    }

    /** Opens a Lab screen once; a second tap before Home resumes is ignored, as it is for Launch. */
    private fun open(screen: Class<*>) {
        if (navigating) return
        navigating = true
        startActivity(Intent(this, screen))
    }

    private fun card(line: String) = LabUi.text(this, 16f).apply {
        text = line
        setPadding(0, LabUi.dp(context, 16), 0, LabUi.dp(context, 4))
    }

    private fun half(b: Button) = b.apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun loadoutStore() = PrefsKeyValue(getSharedPreferences("lab_loadout", MODE_PRIVATE))

    /** Ship and pilot, then weapon icons with levels, passive icons with stacks, then the start minute. */
    private fun loadoutCard(l: RunLoadout) = LabUi.text(this, 16f).apply {
        tag = "loadout-card"
        setPadding(0, LabUi.dp(context, 16), 0, LabUi.dp(context, 4))
        setLineSpacing(LabUi.dp(context, 6).toFloat(), 1f)
        val ship = ShipDefinitions.getShip(l.shipId)?.name ?: l.shipId
        val pilot = PilotDefinitions.getPilot(l.pilotId)?.callsign ?: l.pilotId
        val sb = android.text.SpannableStringBuilder()
        sb.append(LabIcons.label(ship, LabIcons.ship(context, l.shipId), iconFirst = true))
        sb.append(" \u00b7 ").append(LabIcons.label(pilot, LabIcons.pilot(context, l.pilotId), iconFirst = true))
        sb.append('\n')
        if (l.weapons.isEmpty()) sb.append("no weapons")
        l.weapons.forEachIndexed { i, (id, lv) ->
            if (i > 0) sb.append("   ")
            sb.append(LabIcons.label("L$lv", LabIcons.weapon(context, id), iconFirst = true))
        }
        sb.append('\n')
        if (l.passives.isEmpty()) sb.append("no passives")
        l.passives.forEachIndexed { i, (id, n) ->
            if (i > 0) sb.append("   ")
            sb.append(LabIcons.label("$n", LabIcons.passive(context, id), iconFirst = true))
        }
        sb.append("\nStart minute ${l.startMinute}")
        text = sb
    }

    internal fun loadoutCardText(): String =
        box.findViewWithTag<android.widget.TextView>("loadout-card")!!.text.toString()

    companion object {
        private const val STATE_SEEN_SEQ = "seenSeq"

        /** Starts a run with the saved loadout; the knobs already hold the active tuning. */
        internal fun launchIntent(ctx: android.content.Context): Intent {
            val kv = PrefsKeyValue(ctx.getSharedPreferences("lab_loadout", android.content.Context.MODE_PRIVATE))
            return Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LOADOUT, LoadoutModel.load(kv).encode())
        }
    }
}
