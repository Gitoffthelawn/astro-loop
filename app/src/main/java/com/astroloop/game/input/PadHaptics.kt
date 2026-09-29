package com.astroloop.game.input

import android.os.Build
import android.os.Vibrator
import android.view.InputDevice

/**
 * Where a haptic goes right now: the controller in the player's hands, or the phone.
 *
 * Every haptic in the game already funnels through a handful of helpers, so this is the one place
 * that has to know. The choice follows the input mode — the same thing the focus ring and the
 * disconnect-pause rule follow — so a pad connected but idle changes nothing, and a phone in a dock
 * stays quiet while someone plays on a pad.
 *
 * **Not verifiable on an emulator.** A virtual pad has no force-feedback device, and rumble has no
 * visible output, so only the rule below is testable; the lookup is hardware-checked or not at all.
 */
object PadHaptics {

    /** The rule, kept pure because the lookup around it cannot be tested off-device. */
    internal fun shouldUsePad(mode: InputMode, padHasVibrator: Boolean): Boolean =
        mode == InputMode.DIRECTIONAL && padHasVibrator

    /**
     * [phone] is the caller's own system vibrator, passed rather than looked up so the views keep
     * the one they already built.
     */
    fun target(phone: Vibrator): Vibrator {
        val pad = padVibrator() ?: return phone
        return if (shouldUsePad(InputModeState.mode, pad.hasVibrator())) pad else phone
    }

    /**
     * Stops a repeating rumble on both devices.
     *
     * The halo and the store hold repeat until something cancels them. If the player put the pad
     * down mid-rumble, the target has already changed and cancelling only the current one would
     * leave the other buzzing with nothing on screen to explain it.
     */
    fun cancelAll(phone: Vibrator) {
        phone.cancel()
        padVibrator()?.cancel()
    }

    private fun padVibrator(): Vibrator? {
        val device = InputDevice.getDevice(InputModeState.activeDeviceId) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            device.vibrator
        }
    }
}
