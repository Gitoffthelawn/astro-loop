package com.astroloop.game.lab

import com.astroloop.game.tuning.RunListener
import com.astroloop.game.tuning.RunLoadout
import com.astroloop.game.tuning.RunSummary
import com.astroloop.game.tuning.Tunables
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LabSessionTest {

    @After
    fun tearDown() {
        Tunables.resetAll()
    }

    private val summary = RunSummary(
        shipId = "ship_blue", pilotId = "pilot_astro", startMinute = 0,
        survivedSeconds = 60f, cause = "x", finalWeapons = emptyMap(), finalPassives = emptyMap(),
        damageByWeapon = emptyMap(), damageTakenBy = emptyMap(), asteroidsDestroyed = 0,
        avgFps = 60f, tunedMidRunAtSeconds = null, loadout = null,
    )

    @Test
    fun `the report hash is the hash of the normalised preset that was flown`() {
        val imported = TuningPayload(
            "some-older-base", "Odd", "",
            mapOf(
                "pulse_cannon.damage" to 15f,          // equals the default
                "scatter_shot.damage" to 1_000_000f,   // out of range
                "no.such.knob" to 3f,                  // unknown
                "railgun.damage" to 40f,               // a real change
            ),
        )
        val store = PresetStore(MapKeyValue())
        val original = imported.copy(parent = ShareCode.hash(imported))
        val preset = store.create(original)

        val activation = LabSession.activatePreset(preset)
        store.save(activation.normalised)
        LabSession.onRunEnded(summary)

        val stored = store.get(preset.id)!!.payload
        val report = RunReport.parse(LabSession.lastReport!!)
        assertEquals(ShareCode.hash(stored), report["code-hash"])
        assertEquals(LabBuild.BASE, report["build"])
        assertEquals(LabBuild.BASE, stored.base)
        assertEquals(ShareCode.hash(imported), stored.parent)
        assertFalse(stored.values.containsKey("pulse_cannon.damage"))
        assertFalse(stored.values.containsKey("no.such.knob"))
        assertEquals(40f, stored.values["railgun.damage"])
        assertEquals(listOf("no.such.knob"), activation.result.unknownIds)
        assertEquals(listOf("scatter_shot.damage"), activation.result.clampedIds)
    }

    @Test
    fun `a run that reports the loadout it was started with`() {
        val loadout = RunLoadout("ship_blue", "pilot_astro", listOf("railgun" to 2), emptyList(), 0)
        LabSession.activatePreset(Preset(PresetStore.DEFAULT_ID, TuningPayload(LabBuild.BASE, "Default", "", emptyMap())))
        LabSession.onRunEnded(summary.copy(loadout = loadout))
        assertEquals("railgun L2", RunReport.parse(LabSession.lastReport!!)["loadout"])
    }

    @Test
    fun `starting a run clears stale errors`() {
        LabSession.onUpdateError(IllegalStateException("old"))
        assertTrue(LabSession.errors.isNotEmpty())
        (LabSession as RunListener).onRunStarted()
        assertTrue(LabSession.errors.isEmpty())
    }

    @Test
    fun `each finished run bumps the report sequence, and the Lab offers tuning`() {
        val before = LabSession.reportSeq
        LabSession.onRunEnded(summary)
        assertEquals(before + 1, LabSession.reportSeq)
        assertTrue(LabSession.tuneAvailable())
        assertTrue(LabSession.quitAvailable())
    }

    @Test
    fun `the last report survives a process restart`() {
        val disk = MapKeyValue()
        LabSession.attach(disk)
        LabSession.onRunEnded(summary)
        val report = LabSession.lastReport
        val seq = LabSession.reportSeq
        assertNotNull(report)

        LabSession.attach(MapKeyValue())          // a new process starts empty ...
        assertNull(LabSession.lastReport)
        LabSession.attach(disk)                   // ... and restores from disk

        assertEquals(report, LabSession.lastReport)
        assertEquals(seq, LabSession.reportSeq)
        LabSession.attach(MapKeyValue())
    }
}
