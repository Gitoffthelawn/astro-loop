package com.astroloop.game.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.GameSurfaceView
import com.astroloop.game.hangar.HangarSurfaceView
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The game's surfaces must never be able to take focus.
 *
 * Every key reaches the game through MainActivity.dispatchKeyEvent, and a focusable view breaks
 * that in one specific way: while the screen is in touch mode, the first navigation key (D-pad,
 * OK, Enter, Space) makes ViewRootImpl leave touch mode, hand focus to that view, and consume the
 * key before the Activity sees it. Anyone who touched the glass and then picked up a remote or a
 * keyboard lost their first press — on the pause screen, OK did nothing until it was pressed again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ViewFocusTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the hangar cannot take focus`() {
        assertFalse(HangarSurfaceView(context) { _, _ -> }.isFocusable)
    }

    @Test
    fun `the game cannot take focus`() {
        assertFalse(GameSurfaceView(context, "ship_blue", "pilot_medic") { _, _ -> }.isFocusable)
    }
}
