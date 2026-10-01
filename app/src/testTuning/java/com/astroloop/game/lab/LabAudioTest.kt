package com.astroloop.game.lab

import android.content.Context
import android.widget.Button
import com.astroloop.game.core.AudioMode
import com.astroloop.game.core.SoundManager
import com.astroloop.game.data.PersistenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class LabAudioTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun resetAudio() {
        ctx.getSharedPreferences("astrohunt_save", Context.MODE_PRIVATE).edit().remove("audio_mode").commit()
        SoundManager.applyAudioMode(AudioMode.ALL)
    }

    @Before fun before() = resetAudio()
    @After fun after() = resetAudio()

    private fun home() = Robolectric.buildActivity(LabActivity::class.java).setup().get()
    private fun LabActivity.btn(tag: String) = window.decorView.findViewWithTag<Button>(tag)

    @Test
    fun `mapping covers all four combinations both ways`() {
        assertEquals(AudioMode.ALL, LabAudio.modeFor(true, true))
        assertEquals(AudioMode.NONE, LabAudio.modeFor(false, false))
        assertEquals(AudioMode.EFFECTS_MUTED, LabAudio.modeFor(true, false))
        assertEquals(AudioMode.MUSIC_MUTED, LabAudio.modeFor(false, true))
        for (m in AudioMode.values()) {
            assertEquals(m, LabAudio.modeFor(LabAudio.musicOn(m), LabAudio.effectsOn(m)))
        }
        assertEquals(true, LabAudio.musicOn(AudioMode.EFFECTS_MUTED))
        assertEquals(false, LabAudio.effectsOn(AudioMode.EFFECTS_MUTED))
    }

    @Test
    fun `home shows both on by default`() {
        val a = home()
        assertEquals("Music: On", a.btn("audio-music").text.toString())
        assertEquals("Effects: On", a.btn("audio-effects").text.toString())
    }

    @Test
    fun `presses flip one half each, persist and apply`() {
        val a = home()
        a.btn("audio-music").performClick()
        assertEquals("Music: Off", a.btn("audio-music").text.toString())
        assertEquals("Effects: On", a.btn("audio-effects").text.toString())
        assertEquals(AudioMode.MUSIC_MUTED, PersistenceManager(ctx).getAudioMode())
        assertEquals(AudioMode.MUSIC_MUTED, SoundManager.audioMode)

        a.btn("audio-effects").performClick()
        assertEquals(AudioMode.NONE, PersistenceManager(ctx).getAudioMode())
        assertEquals(AudioMode.NONE, SoundManager.audioMode)

        a.btn("audio-music").performClick()
        assertEquals(AudioMode.EFFECTS_MUTED, PersistenceManager(ctx).getAudioMode())
        assertEquals(AudioMode.EFFECTS_MUTED, SoundManager.audioMode)

        val b = home()
        assertEquals("Music: On", b.btn("audio-music").text.toString())
        assertEquals("Effects: Off", b.btn("audio-effects").text.toString())
    }

    @Test
    fun `focus stays on the pressed button`() {
        val a = home()
        val m = a.btn("audio-music")
        m.requestFocus()
        m.performClick()
        assertEquals(true, a.btn("audio-music").isFocused)
    }
}
