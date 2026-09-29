package com.astroloop.game.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a haptic goes.
 *
 * The Android lookup around this rule is thin and hardware-only — a virtual pad has no motor, and
 * rumble has no visible output — so the choice itself is what gets tested, because the choice is
 * what can be wrong.
 */
class PadHapticsTest {

    @Test
    fun `a pad with a motor gets the rumble while it is the thing being used`() {
        assertTrue(PadHaptics.shouldUsePad(InputMode.DIRECTIONAL, padHasVibrator = true))
    }

    @Test
    fun `a pad without a motor falls back to the phone`() {
        // A TV remote, or a pad whose rumble Android does not expose.
        assertFalse(PadHaptics.shouldUsePad(InputMode.DIRECTIONAL, padHasVibrator = false))
    }

    @Test
    fun `a touch player always feels it in the phone`() {
        // The pad may be connected and idle on the table. The mode decides, exactly as it decides
        // the focus ring and the disconnect pause.
        assertFalse(PadHaptics.shouldUsePad(InputMode.TOUCH, padHasVibrator = true))
    }
}
