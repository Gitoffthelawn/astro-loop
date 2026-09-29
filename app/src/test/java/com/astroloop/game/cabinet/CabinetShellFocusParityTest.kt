package com.astroloop.game.cabinet

import android.graphics.RectF
import com.astroloop.game.input.FocusRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CabinetShellFocusParityTest {

    private lateinit var focus: FocusRegistry
    private val pressed = mutableListOf<ShellButton>()

    @Before
    fun setup() {
        focus = FocusRegistry()
        pressed.clear()
    }

    private fun publish(vararg buttons: ShellButton) {
        val rects = buttons.mapIndexed { i, b -> b to RectF(0f, i * 100f, 200f, i * 100f + 80f) }.toMap()
        focus.begin()
        CabinetShellRenderer.publishShellTargets(focus, rects) { pressed.add(it) }
        focus.commit()
    }

    @Test
    fun `every drawn button becomes a target`() {
        publish(ShellButton.PLAY, ShellButton.SCORES, ShellButton.EXIT)

        assertEquals(
            setOf("shell:PLAY", "shell:SCORES", "shell:EXIT"),
            focus.targets().map { it.id }.toSet()
        )
    }

    @Test
    fun `activating dispatches the same button the tap would`() {
        publish(ShellButton.PLAY, ShellButton.EXIT)
        focus.focusedId = "shell:EXIT"

        assertTrue(focus.activate())
        assertEquals(listOf(ShellButton.EXIT), pressed)
    }

    @Test
    fun `a screen with no buttons publishes nothing`() {
        publish()

        assertTrue(focus.isEmpty())
    }

    @Test
    fun `targets come out in the order they are drawn down the screen`() {
        // hitRects is a HashMap, whose iteration order is arbitrary. Focus order must follow the
        // layout instead, or DOWN from the top button could land anywhere in the list.
        val rects = linkedMapOf(
            ShellButton.EXIT to RectF(0f, 300f, 200f, 380f),
            ShellButton.PLAY to RectF(0f, 100f, 200f, 180f),
            ShellButton.SCORES to RectF(0f, 200f, 200f, 280f)
        )
        focus.begin()
        CabinetShellRenderer.publishShellTargets(focus, rects) { pressed.add(it) }
        focus.commit()

        assertEquals(
            listOf("shell:PLAY", "shell:SCORES", "shell:EXIT"),
            focus.targets().map { it.id }
        )
    }
}
