package com.astroloop.game.lab

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.astroloop.game.data.PassiveDefinitions
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.data.WeaponDefinitions
import com.astroloop.game.render.FontManager
import com.astroloop.game.tuning.RunLoadout

/**
 * Picks the ship, pilot, weapons, passives and start minute of the next Lab run. Every change is
 * saved at once, except a −/+, which saves when it is let go.
 */
class LoadoutActivity : ComponentActivity() {
    private lateinit var kv: KeyValue
    private lateinit var loadout: RunLoadout
    private lateinit var box: LinearLayout
    private lateinit var scroller: android.view.View
    private var focusTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontManager.initialize(this)
        kv = PrefsKeyValue(getSharedPreferences("lab_loadout", MODE_PRIVATE))
        loadout = LoadoutModel.load(kv)
        box = LabUi.column(this)
        scroller = LabUi.screen(this, box)
        setContentView(scroller)
        rebuild()
    }

    private fun change(tag: String, next: RunLoadout) {
        focusTag = tag
        loadout = next
        LoadoutModel.save(kv, next)
        rebuild()
    }

    /** Shows [next] while a −/+ is held: only the stepped label changes, nothing is saved or rebuilt. */
    private fun preview(next: RunLoadout, key: String, name: String, icon: Drawable?, value: Int?) {
        loadout = next
        box.findViewWithTag<android.widget.TextView>("label:$key")?.text = LabIcons.label("$name  L$value", icon, iconFirst = true)
    }

    private fun previewMinute(next: RunLoadout) {
        loadout = next
        box.findViewWithTag<android.widget.TextView>("minute-value")?.text = "  ${next.startMinute}  "
    }

    /** True while the screen is going away: a release still saves, but the screen is not rebuilt. */
    private val tearingDown: Boolean
        get() = isFinishing || isDestroyed || !box.isAttachedToWindow

    /** Saves the loadout once a −/+ is let go and rebuilds the screen around the button [tag]. */
    private fun commit(tag: String) {
        LoadoutModel.save(kv, loadout)
        if (tearingDown) return
        focusTag = tag
        rebuild()
    }

    /**
     * A held −/+ that moves the loadout with [next] from whatever it holds now, and saves on release.
     * [next] returns null to stop (nothing to change).
     */
    private fun repeatStep(label: String, tag: String, next: () -> RunLoadout?, show: (RunLoadout) -> Unit): View {
        var changed = false
        return LabUi.repeatButton(this, label,
            onStep = {
                val n = next()
                if (n == null || n == loadout) false else { changed = true; show(n); true }
            },
            onRelease = {
                if (changed) {
                    changed = false
                    commit(tag)
                }
            },
        ).apply { this.tag = tag }
    }

    private fun heading(label: String) {
        box.addView(LabUi.text(this, 18f, bold = true).apply {
            text = label
            setPadding(0, LabUi.dp(this@LoadoutActivity, 16), 0, LabUi.dp(this@LoadoutActivity, 4))
        })
    }

    private fun choice(tag: String, label: String, icon: Drawable?, chosen: Boolean, onClick: () -> Unit) =
        LabUi.button(this, label, onClick).apply {
            this.tag = tag
            text = LabIcons.label(label, icon, iconFirst = true)
            isFocusable = true
            setTextColor(if (chosen) LabUi.ACCENT else LabUi.FG)
        }

    /** Lays [items] out two to a row, each half the width. */
    private fun <T> grid(items: List<T>, cell: (T) -> View) {
        for (pair in items.chunked(2)) {
            val row = LabUi.row(this)
            for (item in pair) row.addView(cell(item), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            box.addView(row)
        }
    }

    private fun stepper(
        key: String, name: String, icon: Drawable?, value: Int?, adjustable: Boolean,
        valueIn: (RunLoadout) -> Int?, with: (RunLoadout, Int) -> RunLoadout, onToggle: () -> Unit,
    ) {
        val row = LabUi.row(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(LabUi.text(this, 16f, color = if (value != null) LabUi.ACCENT else LabUi.FG).apply {
            tag = "label:$key"
            text = LabIcons.label(if (value != null) "$name  L$value" else name, icon, iconFirst = true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null && adjustable) {
            // Minus stops at 1; "remove" takes the item out.
            for ((label, delta) in listOf("−" to -1, "+" to 1)) {
                row.addView(repeatStep(label, stepTag(key, if (delta < 0) "minus" else "plus"),
                    next = { valueIn(loadout)?.let { cur -> if (cur + delta < 1) null else with(loadout, cur + delta) } },
                    show = { n -> preview(n, key, name, icon, valueIn(n)) }))
            }
        }
        row.addView(LabUi.button(this, if (value != null) "remove" else "add", onToggle).apply { tag = stepTag(key, "toggle") })
        box.addView(row)
    }

    private fun rebuild() {
        val savedScroll = scroller.scrollY
        box.removeAllViews()
        buildContent()
        val want = focusTag
        scroller.post {
            scroller.scrollTo(0, savedScroll)
            if (want != null) {
                var focus = box.findViewWithTag<View>(want)
                // If the button is gone, try the toggle tag (for stepper buttons).
                if (focus == null && (want.contains("-minus:") || want.contains("-plus:"))) {
                    val toggleTag = want.replace("-minus:", "-toggle:").replace("-plus:", "-toggle:")
                    focus = box.findViewWithTag(toggleTag)
                }
                // If toggle is also gone, find the section's first focusable button.
                if (focus == null) {
                    for (i in 0 until box.childCount) {
                        val child = box.getChildAt(i)
                        if (child is android.view.ViewGroup) {
                            for (j in 0 until child.childCount) {
                                val button = child.getChildAt(j)
                                if (button != null && button.isFocusable) {
                                    focus = button
                                    break
                                }
                            }
                        }
                        if (focus != null) break
                    }
                }
                focus?.requestFocus()
            }
        }
    }

    private fun buildContent() {
        box.addView(LabUi.text(this, 22f, bold = true).apply { text = "Loadout" })

        heading("Ship")
        grid(ShipDefinitions.ships) { s -> choice("ship:${s.id}", s.name, LabIcons.ship(this, s.id), s.id == loadout.shipId) { change("ship:${s.id}", loadout.copy(shipId = s.id)) } }

        heading("Pilot")
        box.addView(LabUi.text(this, 14f, color = LabUi.DIM).apply {
            text = "Pilots bring their passive in a real run; add it below if you want it here."
        })
        grid(PilotDefinitions.pilots) { p -> choice("pilot:${p.id}", p.callsign, LabIcons.pilot(this, p.id), p.id == loadout.pilotId) { change("pilot:${p.id}", loadout.copy(pilotId = p.id)) } }

        heading("Weapons ${loadout.weapons.size}/${LoadoutModel.maxWeapons(loadout)}")
        for (w in WeaponDefinitions.weapons + WeaponDefinitions.evolutions) {
            val level = loadout.weapons.firstOrNull { it.first == w.id }?.second
            val isEvo = WeaponDefinitions.isEvolution(w.id)
            val k = "w:${w.id}"
            // Evolutions are always level 5, so they get no -/+.
            stepper(k, w.name, LabIcons.weapon(this, w.id), level, !isEvo,
                { l -> l.weapons.firstOrNull { it.first == w.id }?.second },
                { l, lvl -> LoadoutModel.withWeapon(l, w.id, lvl) },
                { change("w-toggle:${w.id}", if (level != null) LoadoutModel.withoutWeapon(loadout, w.id) else LoadoutModel.withWeapon(loadout, w.id, 1)) })
        }

        heading("Passives ${LoadoutModel.passiveCount(loadout)}/${LoadoutModel.maxPassives(loadout)}")
        for (p in PassiveDefinitions.passives) {
            val stacks = loadout.passives.firstOrNull { it.first == p.id }?.second
            stepper("p:${p.id}", p.name, LabIcons.passive(this, p.id), stacks, true,
                { l -> l.passives.firstOrNull { it.first == p.id }?.second },
                { l, n -> LoadoutModel.withPassive(l, p.id, n) },
                { change("p-toggle:${p.id}", if (stacks != null) LoadoutModel.withoutPassive(loadout, p.id) else LoadoutModel.withPassive(loadout, p.id, 1)) })
        }

        heading("Start minute")
        val row = LabUi.row(this)
        row.addView(repeatStep("−", "minute-minus",
            next = { loadout.copy(startMinute = (loadout.startMinute - 1).coerceAtLeast(0)) }, show = ::previewMinute))
        row.addView(LabUi.text(this, 18f).apply { text = "  ${loadout.startMinute}  "; tag = "minute-value" })
        row.addView(repeatStep("+", "minute-plus",
            next = { loadout.copy(startMinute = (loadout.startMinute + 1).coerceAtMost(30)) }, show = ::previewMinute))
        box.addView(row)
    }

    /** Tag of a stepper button: "w-plus:railgun" for key "w:railgun"; [change] refocuses by the same tag. */
    private fun stepTag(key: String, action: String) = "${key.substringBefore(':')}-$action:${key.substringAfter(':')}"

    /** The control that should hold focus after the last change, for tests. */
    internal fun focusTarget(): View? = focusTag?.let { box.findViewWithTag(it) }

    /** How many buttons whose tag starts with [prefix] sit in the first grid row that has any, for tests. */
    internal fun gridColumns(prefix: String): Int {
        for (i in 0 until box.childCount) {
            val row = box.getChildAt(i) as? android.view.ViewGroup ?: continue
            val n = (0 until row.childCount).count { (row.getChildAt(it).tag as? String)?.startsWith(prefix) == true }
            if (n > 0) return n
        }
        return 0
    }

    /** Text of every label on screen, for tests. */
    internal fun shownText(): String = buildString { collect(box, this) }

    private fun collect(v: View, sb: StringBuilder) {
        if (v is android.widget.TextView) sb.append(v.text).append('\n')
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), sb)
    }
}
