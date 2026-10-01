package com.astroloop.game.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.astroloop.game.render.FontManager

/** The report after a run, with the one-tap way to copy it for GitHub Discussions. */
class ResultsActivity : ComponentActivity() {
    private lateinit var body: TextView
    private lateinit var banner: TextView
    private var launching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontManager.initialize(this)
        val report = LabSession.lastReport ?: "No run yet."
        val box = LabUi.column(this)
        box.addView(LabUi.text(this, 26f, bold = true).apply { text = "Results" })
        banner = LabUi.text(this, 16f, bold = true, color = LabUi.ACCENT).apply {
            text = "This tuning broke something: the report includes the error"
            visibility = if (hasError(report)) android.view.View.VISIBLE else android.view.View.GONE
            setPadding(0, LabUi.dp(context, 8), 0, LabUi.dp(context, 8))
        }
        box.addView(banner)
        box.addView(LabUi.button(this, "Copy report") { copy(report) }.apply { isEnabled = LabSession.lastReport != null })
        box.addView(LabUi.button(this, "Run again") {
            if (launching) return@button
            launching = true
            startActivity(LabActivity.launchIntent(this))
            finish()
        })
        box.addView(LabUi.button(this, "Home") { finish() })
        body = LabUi.mono(this, 12f).apply {
            text = report
            setPadding(0, LabUi.dp(context, 16), 0, 0)
        }
        box.addView(body)
        setContentView(LabUi.screen(this, box))
    }

    private fun hasError(report: String) = report.lineSequence().any { it.startsWith("error.") }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Astro Loop Lab report", text))
        Toast.makeText(this, "Report copied: paste it in Lab reports on GitHub Discussions", Toast.LENGTH_LONG).show()
    }

    internal fun shownText(): String =
        (if (banner.visibility == android.view.View.VISIBLE) banner.text.toString() + "\n" else "") + body.text
}
