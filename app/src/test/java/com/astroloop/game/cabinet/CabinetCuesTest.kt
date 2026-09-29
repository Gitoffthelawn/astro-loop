package com.astroloop.game.cabinet

import org.junit.Assert.*
import org.junit.Test

/**
 * The audio DECISION, split from the playing.
 *
 * Every cue the cabinet makes used to be an `if` in `HangarSurfaceView` that nothing could
 * reach — the samples did not exist, so none of it had ever made a sound, and none of it had
 * ever been driven by a test either. That is this branch's recurring defect exactly: the units
 * are fine and the host wiring is where it breaks. So the host keeps the gate and the loop,
 * and everything it would otherwise decide lives here, where it can be asserted.
 */
class CabinetCuesTest {

    private val out = ArrayList<CabinetCues.Cue>()

    /** A frame in which nothing happened. */
    private fun quiet() = CabinetCues.Frame()

    private fun cues(f: CabinetCues.Frame): List<CabinetCues.Cue> {
        out.clear()
        CabinetCues.forFrame(f, out)
        return out.toList()
    }

    private fun ids(f: CabinetCues.Frame) = cues(f).map { it.eventId }

    // ── the machine ──────────────────────────────────────────────────────

    @Test fun aQuietFrameSaysNothing() {
        assertEquals(emptyList<String>(), ids(quiet()))
    }

    @Test fun firingOnceIsOneCueNoMatterHowManyBarrels() {
        assertEquals(listOf("sfx_belt_fire"), ids(quiet().copy(shotsFired = 3)))
    }

    @Test fun eachRockSizeHasItsOwnBreak() {
        assertEquals(listOf("sfx_belt_break_lge"), ids(quiet().copy(destroyedLarge = 1)))
        assertEquals(listOf("sfx_belt_break_med"), ids(quiet().copy(destroyedMedium = 1)))
        assertEquals(listOf("sfx_belt_break_sml"), ids(quiet().copy(destroyedSmall = 1)))
    }

    @Test fun severalRocksOfOneSizeInAFrameStillOnlySoundOnce() {
        assertEquals(listOf("sfx_belt_break_sml"), ids(quiet().copy(destroyedSmall = 4)))
    }

    @Test fun theHeartbeatPicksItsToneFromTheBeat() {
        assertEquals(listOf("sfx_belt_beat_lo"), ids(quiet().copy(heartbeat = true, heartbeatHigh = false)))
        assertEquals(listOf("sfx_belt_beat_hi"), ids(quiet().copy(heartbeat = true, heartbeatHigh = true)))
    }

    @Test fun theToneIsIgnoredWhenNoBeatLanded() {
        assertEquals(emptyList<String>(), ids(quiet().copy(heartbeat = false, heartbeatHigh = true)))
    }

    @Test fun deathSounds() {
        assertEquals(listOf("sfx_belt_death"), ids(quiet().copy(shipDied = true)))
    }

    // ── the crystal ──────────────────────────────────────────────────────

    @Test fun anEmissionIsOneCueForTheWholeRingNotOnePerBullet() {
        // PULSE puts nine bullets on the field in a single frame; SHATTER eight.
        assertEquals(listOf("sfx_belt_crystal_fire"), ids(quiet().copy(bulletsEmitted = 9)))
    }

    @Test fun hittingTheCrystalAnswers() {
        assertEquals(listOf("sfx_belt_crystal_hit"), ids(quiet().copy(crystalHits = 1)))
    }

    @Test fun severalHitsInAFrameStillOnlySoundOnce() {
        assertEquals(listOf("sfx_belt_crystal_hit"), ids(quiet().copy(crystalHits = 3)))
    }

    @Test fun theLandingSounds() {
        assertEquals(listOf("sfx_crystal_activate"), ids(quiet().copy(landed = true)))
    }

    @Test fun theLandingSitsUnderTheEnding() {
        // A design decision makes the shatter "the biggest sound the cabinet makes". The landing
        // borrows a MAIN-GAME sting that was levelled for a different mix
        // entirely, and at full volume it measured 0.66 dB ABOVE the shatter — the arrival
        // out-shouting the ending.
        val landing = cues(quiet().copy(landed = true)).single().volume
        val shatter = cues(quiet().copy(shattered = true)).single().volume
        assertTrue("the arrival $landing must not out-shout the ending $shatter",
            landing < shatter)
    }

    @Test fun theShatterSounds() {
        assertEquals(listOf("sfx_belt_crystal_shatter"), ids(quiet().copy(shattered = true)))
    }

    // ── the two guns ─────────────────────────────────────────────────────

    @Test fun eachPatternPitchesTheCrystalsGunDifferently() {
        val rates = (0..4).map { p ->
            cues(quiet().copy(bulletsEmitted = 1, phaseIndex = p)).single().rate
        }
        assertEquals("five patterns, five pitches", 5, rates.toSet().size)
        rates.forEach { assertTrue("rate $it outside playSFX's clamp", it in 0.5f..2.0f) }
    }

    @Test fun aPhaseIndexOffTheEndDoesNotThrow() {
        // The director coerces, but this must not depend on that: a cue that throws inside
        // GameThread's swallowed update() is a frozen machine, not a missing sound.
        assertEquals(listOf("sfx_belt_crystal_fire"), ids(quiet().copy(bulletsEmitted = 1, phaseIndex = 99)))
        assertEquals(listOf("sfx_belt_crystal_fire"), ids(quiet().copy(bulletsEmitted = 1, phaseIndex = -1)))
    }

    @Test fun onlyTheCrystalsGunIsPitched() {
        val f = quiet().copy(shotsFired = 1, destroyedSmall = 1, shipDied = true, heartbeat = true)
        cues(f).forEach { assertEquals("${it.eventId} must play at its own pitch", 1f, it.rate, 1e-6f) }
    }

    // ── the mix ──────────────────────────────────────────────────────────

    @Test fun everyCueCarriesItsAuthoredVolume() {
        fun vol(f: CabinetCues.Frame) = cues(f).single().volume
        assertEquals(0.60f, vol(quiet().copy(shotsFired = 1)), 1e-6f)
        assertEquals(0.70f, vol(quiet().copy(destroyedLarge = 1)), 1e-6f)
        assertEquals(0.70f, vol(quiet().copy(heartbeat = true)), 1e-6f)
        assertEquals(0.80f, vol(quiet().copy(shipDied = true)), 1e-6f)
        assertEquals(0.45f, vol(quiet().copy(bulletsEmitted = 1)), 1e-6f)
        assertEquals(0.55f, vol(quiet().copy(crystalHits = 1)), 1e-6f)
        assertEquals(0.90f, vol(quiet().copy(shattered = true)), 1e-6f)
    }

    @Test fun theCrystalsGunSitsUnderThePlayersSoNeitherMasksTheOther() {
        // A design decision: the two are told apart by timbre, not by level — but the crystal's
        // is the one the player is dodging rather than firing, so it takes the lower trim.
        val player = cues(quiet().copy(shotsFired = 1)).single().volume
        val crystal = cues(quiet().copy(bulletsEmitted = 1)).single().volume
        assertTrue("crystal gun $crystal should sit under the player's $player", crystal < player)
    }

    // ── a busy frame ─────────────────────────────────────────────────────

    @Test fun aFrameCanCarryEverythingAtOnce() {
        val f = CabinetCues.Frame(
            shotsFired = 1, destroyedLarge = 1, destroyedMedium = 1, destroyedSmall = 1,
            shipDied = true, heartbeat = true, heartbeatHigh = true,
            bulletsEmitted = 4, crystalHits = 1, shattered = true, landed = true, phaseIndex = 2
        )
        val got = ids(f)
        assertEquals("no cue may be dropped when the frame is busy", 10, got.size)
        assertEquals("no cue may be played twice", got.size, got.toSet().size)
    }

    @Test fun theOutputListIsAppendedToNotReplaced() {
        // The host owns one reusable list across frames; forFrame must not allocate its own
        // or clear someone else's.
        out.clear()
        out.add(CabinetCues.Cue("marker", 1f, 1f))
        CabinetCues.forFrame(quiet().copy(shotsFired = 1), out)
        assertEquals(listOf("marker", "sfx_belt_fire"), out.map { it.eventId })
    }
}
