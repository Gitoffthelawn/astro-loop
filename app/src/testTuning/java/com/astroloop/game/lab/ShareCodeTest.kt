package com.astroloop.game.lab

import org.junit.Assert.*
import org.junit.Test

class ShareCodeTest {

    private val p = TuningPayload(
        base = "1.5-lab.1",
        title = "Railgun beam v3",
        parent = "a3f9c2",
        values = mapOf("railgun.damage" to 120f, "railgun.cooldown" to 8f, "asteroid.metal.hp_mult" to 1.5f),
    )

    @Test
    fun `round trip`() {
        val code = ShareCode.encode(p)
        assertTrue(code.startsWith("ALT1:"))
        assertEquals(Decoded.Ok(p), ShareCode.decode(code))
    }

    @Test
    fun `codes are url-safe without padding`() {
        val code = ShareCode.encode(p).removePrefix("ALT1:")
        assertTrue(code.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `payload text is canonical and readable`() {
        assertEquals(
            "v=1\nbase=1.5-lab.1\ntitle=Railgun beam v3\nparent=a3f9c2\n" +
                "asteroid.metal.hp_mult=1.5\nrailgun.cooldown=8\nrailgun.damage=120\n",
            ShareCode.payloadText(p),
        )
    }

    @Test
    fun `hash ignores title parent and key order but not values`() {
        val h = ShareCode.hash(p)
        assertEquals(6, h.length)
        assertEquals(h, ShareCode.hash(p.copy(title = "renamed", parent = "")))
        assertEquals(h, ShareCode.hash(p.copy(values = p.values.entries.reversed().associate { it.key to it.value })))
        assertNotEquals(h, ShareCode.hash(p.copy(values = p.values + ("railgun.damage" to 121f))))
    }

    @Test
    fun `a code pasted inside a sentence still decodes`() {
        val pasted = "Try this: ${ShareCode.encode(p)} — it melts metal rocks."
        assertEquals(Decoded.Ok(p), ShareCode.decode(pasted))
    }

    @Test
    fun `bad input is an error, never a throw`() {
        for (bad in listOf("", "hello", "ALT1:", "ALT1:!!!!", "ALT1:AAAA", "ALT2:abc")) {
            assertTrue(bad, ShareCode.decode(bad) is Decoded.Error)
        }
    }

    @Test
    fun `unknown version is an error`() {
        val text = ShareCode.payloadText(p).replace("v=1", "v=2")
        assertNull(ShareCode.parsePayloadText(text))
    }

    @Test
    fun `non-finite and malformed values are dropped`() {
        val text = "v=1\nbase=x\ntitle=\nparent=\na.b=NaN\nc.d=oops\ne.f=2\n"
        assertEquals(mapOf("e.f" to 2f), ShareCode.parsePayloadText(text)!!.values)
    }

    @Test
    fun `value text is a canonical fixpoint`() {
        for (v in listOf(0.1f, 1.1e-5f, 16777216f, 3.4e38f, 120f, 2.6179938f, -0f)) {
            val text = ShareCode.payloadText(p.copy(values = mapOf("a.b" to v))).lines().first { it.startsWith("a.b=") }.removePrefix("a.b=")
            assertFalse(text, text.contains('E') || text.contains('e'))
            val back = ShareCode.parsePayloadText("v=1\na.b=$text\n")!!.values["a.b"]!!
            val again = ShareCode.payloadText(p.copy(values = mapOf("a.b" to back))).lines().first { it.startsWith("a.b=") }.removePrefix("a.b=")
            assertEquals(text, again)
        }
        fun t(v: Float) = ShareCode.payloadText(p.copy(values = mapOf("a.b" to v))).lines().first { it.startsWith("a.b=") }.removePrefix("a.b=")
        assertEquals("120", t(120f))
        assertEquals("16777216", t(16777216f))
        assertEquals("0", t(-0f))
        assertEquals("0.1", t(0.1f))
    }

    @Test
    fun `invalid keys are dropped on decode and never emitted`() {
        val text = "v=1\nbase=x\ntitle=t\nparent=\nUPPER.case=1\nnodot=2\na.b=3\nx y.z=4\nc.d=5\n"
        assertEquals(mapOf("a.b" to 3f, "c.d" to 5f), ShareCode.parsePayloadText(text)!!.values)
        val bad = p.copy(values = mapOf("a=b.c" to 1f, "a\nb.c" to 2f, "Up.per" to 3f, "nodot" to 4f, "title" to 5f, "ok.key" to 6f))
        val out = ShareCode.payloadText(bad)
        assertEquals("v=1\nbase=1.5-lab.1\ntitle=Railgun beam v3\nparent=a3f9c2\nok.key=6\n", out)
        assertEquals(ShareCode.hash(bad), ShareCode.hash(bad.copy(values = mapOf("ok.key" to 6f))))
        assertEquals(bad.title, (ShareCode.decode(ShareCode.encode(bad)) as Decoded.Ok).payload.title)
        assertTrue(ShareCode.parsePayloadText("v=1\n" + "a.${"b".repeat(70)}=1\n")!!.values.isEmpty())
    }

    @Test
    fun `knob lines are capped at 1000`() {
        val text = "v=1\n" + (1..1500).joinToString("") { "k.n$it=1\n" }
        assertEquals(1000, ShareCode.parsePayloadText(text)!!.values.size)
    }

    @Test
    fun `title parent and base are sanitised`() {
        val long = "x".repeat(500)
        val t1 = ShareCode.parsePayloadText("v=1\ntitle=$long\n")!!
        assertEquals(60, t1.title.length)
        val t2 = ShareCode.parsePayloadText("v=1\nbase=a\u0000b\u007f${"c".repeat(100)}\ntitle=  he\u0000llo\r  \nparent=ZZZZZZ\n")!!
        assertEquals("hello", t2.title)
        assertEquals("", t2.parent)
        assertEquals(40, t2.base.length)
        assertFalse(t2.base.any { it < ' ' || it == '\u007f' })
        val enc = ShareCode.payloadText(p.copy(title = "a\u0000b\nc\r ", parent = "nothex"))
        assertTrue(enc.contains("title=abc\n"))
        assertTrue(enc.contains("parent=\n"))
        val clean = p.copy(title = "a\u0001b")
        assertEquals(Decoded.Ok(clean.copy(title = "ab")), ShareCode.decode(ShareCode.encode(clean)))
        assertEquals(Decoded.Ok(p), ShareCode.decode(ShareCode.encode(p)))
    }

    @Test
    fun `an earlier ALT1 mention does not break a later code`() {
        val pasted = "Codes start with ALT1: and here is mine: ${ShareCode.encode(p)} enjoy"
        assertEquals(Decoded.Ok(p), ShareCode.decode(pasted))
    }

    @Test
    fun `a code followed immediately by an accented word still decodes`() {
        assertEquals(Decoded.Ok(p), ShareCode.decode(ShareCode.encode(p) + "é voilà"))
    }

    @Test
    fun `title cap never splits a surrogate pair`() {
        val title = "a".repeat(59) + "\uD83D\uDE80" + "tail"
        val decoded = (ShareCode.decode(ShareCode.encode(p.copy(title = title))) as Decoded.Ok).payload
        assertEquals("a".repeat(59) + "\uD83D\uDE80", decoded.title)
    }
}
