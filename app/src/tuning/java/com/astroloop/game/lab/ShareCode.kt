package com.astroloop.game.lab

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A tuning as shared between players: which build's defaults it was made against, and the changes. */
data class TuningPayload(
    val base: String,
    val title: String,
    val parent: String,
    val values: Map<String, Float>,
)

sealed interface Decoded {
    data class Ok(val payload: TuningPayload) : Decoded
    data class Error(val message: String) : Decoded
}

/**
 * `ALT1:` + base64url(raw deflate(payload text)). The payload is plain lines — `v`, `base`,
 * `title`, `parent`, then one `knob.id=value` per changed knob, sorted — so a decoded code is
 * readable and a pasted one can be diffed by eye.
 */
object ShareCode {
    const val PREFIX = "ALT1:"
    private const val VERSION = "1"
    private const val MAX_PAYLOAD_BYTES = 64 * 1024
    private const val MAX_BODY_CHARS = 64 * 1024
    private const val MAX_KNOBS = 1000
    private const val MAX_TITLE = 60
    private const val MAX_BASE = 40

    fun encode(p: TuningPayload): String {
        val raw = payloadText(p).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decode(text: String): Decoded {
        var from = text.indexOf(PREFIX)
        if (from < 0) return Decoded.Error("No Lab code found: codes start with $PREFIX")
        var last: Decoded.Error = Decoded.Error("The code is empty")
        while (from >= 0) {
            val start = from + PREFIX.length
            val end = minOf(text.length, start + MAX_BODY_CHARS)
            var i = start
            while (i < end && isBodyChar(text[i])) i++
            when (val r = decodeBody(text.substring(start, i))) {
                is Decoded.Ok -> return r
                is Decoded.Error -> last = r
            }
            from = text.indexOf(PREFIX, start)
        }
        return last
    }

    private fun isBodyChar(c: Char) =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    private fun decodeBody(body: String): Decoded {
        if (body.isEmpty()) return Decoded.Error("The code is empty")
        val bytes = try {
            Base64.getUrlDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            return Decoded.Error("The code is damaged (not valid base64)")
        }
        val raw = inflate(bytes) ?: return Decoded.Error("The code is damaged (could not decompress)")
        val payload = parsePayloadText(String(raw, Charsets.UTF_8))
            ?: return Decoded.Error("This code is from a newer or unknown Lab version")
        return Decoded.Ok(payload)
    }

    fun payloadText(p: TuningPayload): String = buildString {
        append("v=").append(VERSION).append('\n')
        append("base=").append(cleanBase(p.base)).append('\n')
        append("title=").append(cleanTitle(p.title)).append('\n')
        append("parent=").append(cleanParent(p.parent)).append('\n')
        appendValues(p.values)
    }

    private fun StringBuilder.appendValues(values: Map<String, Float>) {
        for ((id, v) in values.toSortedMap()) {
            if (isValidKey(id)) append(id).append('=').append(formatValue(v)).append('\n')
        }
    }

    /** First 6 hex of SHA-256 over version, base and the sorted values — title and parent excluded. */
    fun hash(p: TuningPayload): String {
        val canonical = buildString {
            append("v=").append(VERSION).append('\n')
            append("base=").append(cleanBase(p.base)).append('\n')
            appendValues(p.values)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    fun parsePayloadText(text: String): TuningPayload? {
        var version: String? = null
        var base = ""
        var title = ""
        var parent = ""
        val values = sortedMapOf<String, Float>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "v" -> version = value
                "base" -> base = value
                "title" -> title = value
                "parent" -> parent = value
                else -> if (isValidKey(key) && (values.size < MAX_KNOBS || key in values)) {
                    value.toFloatOrNull()?.takeIf { it.isFinite() }?.let { values[key] = it }
                }
            }
        }
        if (version != VERSION) return null
        return TuningPayload(cleanBase(base), cleanTitle(title), cleanParent(parent), values)
    }

    /** Canonical text: exact decimal expansion (fractions rounded to 6 significant digits), plain notation, no trailing zeros. */
    internal fun formatValue(v: Float): String {
        val exact = BigDecimal(v.toDouble())
        // Whole numbers keep every digit (exact, so still identical everywhere); fractions get 6 significant.
        val text = if (v == kotlin.math.floor(v)) exact.toBigInteger().toString()
        else exact.round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    private val KEY_RE = Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$")
    private val PARENT_RE = Regex("^[0-9a-f]{6}$")

    private fun isValidKey(k: String) = k.length <= 64 && KEY_RE.matches(k)

    private fun stripControl(s: String) = s.filter { it >= ' ' && it != '\u007f' }
    private fun cleanTitle(s: String): String {
        val t = stripControl(s).trim()
        val cut = if (t.codePointCount(0, t.length) > MAX_TITLE) t.substring(0, t.offsetByCodePoints(0, MAX_TITLE)) else t
        return cut.trim()
    }
    private fun cleanBase(s: String) = stripControl(s).take(MAX_BASE)
    private fun cleanParent(s: String) = if (PARENT_RE.matches(s)) s else ""

    private fun inflate(bytes: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && !inflater.finished()) return null     // truncated or stuck: never spin
                out.write(buf, 0, n)
                if (out.size() > MAX_PAYLOAD_BYTES) return null
            }
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
