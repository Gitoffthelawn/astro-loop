package com.astroloop.game.render

import com.astroloop.game.core.GameState
import com.astroloop.game.data.PilotDefinitions
import com.astroloop.game.data.ShipDefinitions
import com.astroloop.game.entity.Boss
import com.astroloop.game.system.VampiricLeecherSystem
import com.astroloop.game.weapon.weapons.LingeringNova
import com.astroloop.game.weapon.weapons.NovaBlast
import kotlin.math.sin

/**
 * The faint circles around the ship showing how far each effect reaches.
 *
 * The ring belongs to the PASSIVE, not to the effect. Magnet Field's pickup range is always on —
 * 80f even with no stacks — but no ring is drawn until the passive is picked up, because the ring
 * is a reward for taking it. A permanently-drawn magnet ring is furniture, not a reward.
 *
 * Every radius is read from the one expression gameplay uses. A ring that disagrees with its
 * effect is worse than no ring, and nothing in the build would catch the drift.
 *
 * Colours are the owning pilot's, never the HUD's categorical palette: that palette buckets
 * `magnet_field` and `cryo_field` into the same Utility yellow, and those two rings are already
 * near-coincident in radius. The existing cryo ring set this precedent by using ice blue instead
 * of its yellow HUD colour.
 *
 * **No Android imports.** The drawing lives in VectorRenderer; this file stays pure so the whole
 * rule is testable on plain JVM, the same split as [HudBand] and HUDRenderer.
 */
object EffectRings {

    /** One ring: what it belongs to, how far it reaches, what colour it draws. */
    data class Ring(val id: String, val radius: Float, val color: Int)

    /** Built once: scanning the pilot list per ring per frame was the hot path's worst offender. */
    private val pilotColorByPassive: Map<String, Int> =
        PilotDefinitions.pilots.associate { it.startingPassiveId to it.color }

    /**
     * The owning pilot's colour, or white if the passive has no owner.
     *
     * Falls back rather than throwing: GameThread catches Throwables per frame, so an exception
     * here would silently blank every frame instead of crashing. Matches
     * [ShipDefinitions.getWeaponColor], which likewise returns white for an unknown id.
     */
    internal fun pilotColorFor(passiveId: String): Int =
        pilotColorByPassive[passiveId] ?: 0xFFFFFFFF.toInt()

    /**
     * The rings the current state should show, in no particular order.
     *
     * Cryo keys off `cryoSlowPercent` rather than the stack count because that is the flag the
     * shipped ring already used, and it is set for exactly the same condition.
     */
    fun ringsFor(state: GameState): List<Ring> {
        val corrupted = state.isCorruptionRun
        fun passiveColor(passiveId: String) =
            if (corrupted) Boss.CORRUPTION_COLOR else pilotColorFor(passiveId)

        val rings = ArrayList<Ring>(4)
        if (state.cryoSlowPercent > 0f) {
            rings.add(Ring("cryo_field", state.getCryoRadius(), passiveColor("cryo_field")))
        }
        if (state.getPassiveStacks("magnet_field") > 0) {
            rings.add(Ring("magnet_field", state.getPickupRange(), passiveColor("magnet_field")))
        }
        if (state.getPassiveStacks("vampiric_core") > 0) {
            rings.add(
                Ring("vampiric_core", VampiricLeecherSystem.LEECH_RANGE, passiveColor("vampiric_core"))
            )
        }
        val novaLevel = state.weaponLevels["nova_blast"] ?: 0
        if (novaLevel > 0) {
            rings.add(
                Ring(
                    "nova_blast",
                    NovaBlast.blastRadiusFor(novaLevel, state.areaMultiplier),
                    ShipDefinitions.getWeaponColor("nova_blast", corrupted)
                )
            )
        } else if (state.weaponLevels.containsKey("lingering_nova")) {
            // The evolution reaches FURTHER than the base weapon's top rung, so dropping the ring
            // here would both blink it out and understate the blast. Evolution colour, matching
            // what LingeringNova's own projectiles draw in.
            rings.add(
                Ring(
                    "lingering_nova",
                    LingeringNova.blastRadiusFor(state.areaMultiplier),
                    ShipDefinitions.getEvolutionColor("nova_blast", corrupted)
                )
            )
        }
        return rings
    }

    /**
     * How close two radii must be before they are drawn as one merged ring, in design px.
     *
     * Tuned by eye on device; 6px is the distance below which two 2px strokes read as a single
     * smudged ring rather than as two.
     */
    const val MERGE_THRESHOLD = 6f

    /**
     * Rings bucketed into groups that will draw as one merged ring, ascending by radius.
     *
     * Membership is measured against the group's SMALLEST radius, not the previous member, so a
     * long run of rings 5px apart cannot chain into one arbitrarily wide group.
     */
    fun group(rings: List<Ring>): List<List<Ring>> {
        if (rings.isEmpty()) return emptyList()
        val sorted = ArrayList(rings)
        for (i in 1 until sorted.size) {
            val key = sorted[i]
            var j = i - 1
            while (j >= 0 && sorted[j].radius > key.radius) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = key
        }
        val groups = ArrayList<MutableList<Ring>>()
        var current = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val ring = sorted[i]
            if (ring.radius - current[0].radius <= MERGE_THRESHOLD) {
                current.add(ring)
            } else {
                groups.add(current)
                current = mutableListOf(ring)
            }
        }
        groups.add(current)
        return groups
    }

    /** How many arcs each ring contributes to a merged group. */
    const val ARCS_PER_RING = 3

    /** Blank degrees between neighbouring arcs, so the alternation reads as separate strokes. */
    const val ARC_GAP_DEGREES = 4f

    const val ALPHA_BASE = 0.15f
    const val ALPHA_PULSE = 0.05f
    const val STROKE_WIDTH = 2f

    /** How long the rings take to fade once the player dies. Shared with the leech stream so the
     *  two break off together; they are the same moment. */
    const val DEATH_FADE_SECONDS = VampiricLeecherSystem.DEATH_FADE_SECONDS

    /** One stroke of a ring. A solo ring is a single arc sweeping the whole 360. */
    data class Arc(val radius: Float, val color: Int, val startDegrees: Float, val sweepDegrees: Float)

    /**
     * How a group draws.
     *
     * A solo group is one full circle. A merged group alternates its members round-robin, and
     * critically **each arc keeps its own ring's radius** — no ring is nudged to a shared value.
     * Since a group spans at most [MERGE_THRESHOLD] px, the result reads as one ring with a slight
     * stagger, and that stagger is the tell that more than one effect is in play.
     */
    fun arcsFor(group: List<Ring>): List<Arc> {
        if (group.isEmpty()) return emptyList()
        if (group.size == 1) {
            return listOf(Arc(group[0].radius, group[0].color, 0f, 360f))
        }
        val segments = group.size * ARCS_PER_RING
        val step = 360f / segments
        val arcs = ArrayList<Arc>(segments)
        for (i in 0 until segments) {
            val ring = group[i % group.size]
            arcs.add(
                Arc(
                    radius = ring.radius,
                    color = ring.color,
                    startDegrees = i * step + ARC_GAP_DEGREES / 2f,
                    sweepDegrees = step - ARC_GAP_DEGREES
                )
            )
        }
        return arcs
    }

    /**
     * The shared opacity for every ring: a slow pulse in a deliberately faint band, scaled by the
     * death fade. Keeping all four rings on one budget is what makes four of them at once readable.
     *
     * [timeSeconds] is passed in rather than read here so this stays pure; callers use the
     * modulo'd clock, never raw millis.
     */
    fun alpha(timeSeconds: Float, fadeAlpha: Float): Float =
        (ALPHA_BASE + ALPHA_PULSE * sin(timeSeconds * 2f)) * fadeAlpha
}
