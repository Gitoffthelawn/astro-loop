package com.astroloop.game.hangar

import android.graphics.RectF
import com.astroloop.game.input.FocusRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoreFocusParityTest {

    private lateinit var focus: FocusRegistry
    private val tapped = mutableListOf<Pair<Float, Float>>()

    @Before
    fun setup() {
        focus = FocusRegistry()
        tapped.clear()
    }

    private fun rect(x: Float, y: Float) = RectF(x, y, x + 80f, y + 80f)

    private fun publish(
        upgrades: List<RectF> = (0 until 9).map { rect((it % 3) * 100f, (it / 3) * 100f) },
        buttons: List<RectF> = listOf(rect(0f, 400f)),
        audio: RectF? = rect(300f, 400f),
        vibration: RectF? = rect(400f, 400f),
        paper: RectF? = rect(500f, 400f),
        originX: Float = 0f
    ) {
        focus.begin()
        StorePageRenderer.publishStoreTargets(
            focus, upgrades, buttons, audio, vibration, paper, originX
        ) { x, y -> tapped.add(x to y) }
        focus.commit()
    }

    @Test
    fun `every store control is published`() {
        publish()

        val ids = focus.targets().map { it.id }
        assertEquals(9, ids.count { it.startsWith("store:upgrade:") })
        assertEquals(1, ids.count { it.startsWith("store:button:") })
        assertNotNull(focus.byId("store:audio"))
        assertNotNull(focus.byId("store:vibration"))
        assertNotNull(focus.byId("store:paper"))
    }

    @Test
    fun `absent optional controls publish no target`() {
        publish(audio = null, vibration = null, paper = null)

        assertEquals(null, focus.byId("store:audio"))
        assertEquals(null, focus.byId("store:paper"))
    }

    @Test
    fun `activating a control taps its own centre`() {
        val audioRect = rect(300f, 400f)
        publish()
        focus.focusedId = "store:audio"

        assertTrue(focus.activate())
        assertEquals(listOf(audioRect.centerX() to audioRect.centerY()), tapped)
    }

    @Test
    fun `a secret still hidden publishes no target at all`() {
        // The whole point: a ring that lands on the hatch draws brackets around the secret.
        publish(paper = null)

        assertEquals(null, focus.byId("store:paper"))
        assertEquals(null, focus.byId("store:hatch"))
    }

    @Test
    fun `once found, the paper is what a pad reaches`() {
        val paperRect = rect(500f, 400f)
        publish()
        focus.focusedId = "store:paper"

        assertTrue(focus.activate())
        assertEquals(listOf(paperRect.centerX() to paperRect.centerY()), tapped)
    }

    // --- the room-local / screen-space seam, as on the bar page -------------------------------

    @Test
    fun `rects and tap points are both shifted into screen space`() {
        // The shop draws in its room's translate and handleStoreTap's first act is roomX(x), so
        // an unconverted rect would sit left of its tile and an unconverted tap would convert
        // twice and miss it.
        publish(upgrades = listOf(rect(100f, 200f)), buttons = emptyList(),
                audio = null, vibration = null, paper = null, originX = 640f)

        val target = focus.byId("store:upgrade:0")!!
        assertEquals(740f, target.rect.left, 0.01f)

        focus.focusedId = "store:upgrade:0"
        focus.activate()
        assertEquals(listOf(780f to 240f), tapped)
    }

    @Test
    fun `upgrade ids carry the tile index the hold needs`() {
        // onActivateDown parses the index back out of the id to start the fill on that tile.
        publish()

        for (i in 0 until 9) {
            assertEquals(i, focus.byId("store:upgrade:$i")!!.id.substringAfterLast(':').toInt())
        }
    }
}
