package com.astroloop.game.lab

import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LabApp::class)
class LibraryActivityTest {
    @Test
    fun `slots and damaged slots both show`() {
        val store = LabApp.presets
        store.create(TuningPayload(LabBuild.BASE, "Mine", "", emptyMap()))
        val b = store.create(TuningPayload(LabBuild.BASE, "Other", "", emptyMap()))
        PrefsKeyValue(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("lab_presets", android.content.Context.MODE_PRIVATE)).put("preset.${b.id}", "garbage")
        val text = Robolectric.buildActivity(LibraryActivity::class.java).setup().get().shownText()
        assertTrue(text, "Mine" in text)
        assertTrue(text, "Damaged slots" in text)
        assertTrue(text, "garbage" in text)
    }

    @After
    fun tearDown() { Tunables.resetAll(); LabSession.activatePreset(LabApp.presets.get(PresetStore.DEFAULT_ID)!!) }

    @Test
    fun `deleting the active slot restores Default's knobs`() {
        val store = LabApp.presets
        val id = Tunables.all.first().id
        val v = Tunables.all.first().let { if (it.raw == it.maxRaw) it.minRaw else it.maxRaw }
        val p = store.create(TuningPayload(LabBuild.BASE, "Mine", "", mapOf(id to v)))
        store.activeId = p.id
        LabSession.activatePreset(p)
        assertTrue(Tunables.diffs().isNotEmpty())
        assertTrue(LibraryActivity.deleteSlot(store, p.id))
        assertTrue(Tunables.diffs().isEmpty())
        assertTrue(LabSession.activePayload!!.values.isEmpty())
        assertEquals(PresetStore.DEFAULT_ID, store.activeId)
    }

    @Test
    fun `deleting an inactive slot leaves the active tuning alone`() {
        val store = LabApp.presets
        val id = Tunables.all.first().id
        val v = Tunables.all.first().let { if (it.raw == it.maxRaw) it.minRaw else it.maxRaw }
        val a = store.create(TuningPayload(LabBuild.BASE, "A", "", mapOf(id to v)))
        val b = store.create(TuningPayload(LabBuild.BASE, "B", "", emptyMap()))
        store.activeId = a.id
        LabSession.activatePreset(a)
        assertFalse(LibraryActivity.deleteSlot(store, b.id))
        assertTrue(Tunables.diffs().isNotEmpty())
        assertEquals(a.id, store.activeId)
    }

    @Test
    fun `the import summary names skipped and clamped ids and an older base`() {
        val r = com.astroloop.game.tuning.ApplyResult(listOf("no.such"), listOf("a.b", "c.d"))
        val text = LibraryActivity.importSummary("Odd", 3, r, "1.4", "1.5")
        assertTrue(text, "Imported: Odd" in text)
        assertTrue(text, "3 changes" in text)
        assertTrue(text, "Skipped (unknown): no.such" in text)
        assertTrue(text, "Clamped to range: a.b, c.d" in text)
        assertTrue(text, "Made for 1.4 defaults; values may mean something different in this build." in text)
    }

    @Test
    fun `a clean import from this base has only the title and count`() {
        val text = LibraryActivity.importSummary("Fine", 1, com.astroloop.game.tuning.ApplyResult(emptyList(), emptyList()), "1.5", "1.5")
        assertEquals("Imported: Fine\n1 change", text)
    }

    @Test
    fun `renaming the active slot updates the active tuning's title`() {
        val store = LabApp.presets
        val p = store.create(TuningPayload(LabBuild.BASE, "Before", "", emptyMap()))
        store.activeId = p.id
        LabSession.activatePreset(p)

        LibraryActivity.renameSlot(store, p.id, "After")

        assertEquals("After", LabSession.activePayload!!.title)
    }
}
