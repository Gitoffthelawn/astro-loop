package com.astroloop.game.cabinet

/**
 * What the cabinet knows about a pilot: their initials, and nothing else.
 *
 * This carried twelve per-pilot twists until a device pass, when the owner cut them —
 * they are recoverable from git history if a future pass wants them back. What remains
 * is load-bearing three times over: the high score board ranks by initials, the HUD
 * strip shows them, and the stage 3 gate reads a per-pilot score table.
 *
 * The consequence is recorded rather than hidden: with no twists, the gate's twelve
 * runs are the same run twelve times. Original spec decision existed to prevent
 * exactly that.
 *
 * **Settled 2026-08-21: accepted.** The reckoning is an easter egg rather than a
 * mandatory ending, so twelve identical runs is a price the player opts into rather than
 * one the game charges. A design decision is retired, not deferred — do not reintroduce the
 * twists to "fix" a gate that is no longer considered broken.
 */
data class CabinetRules(val initials: String) {
    companion object {
        /** The baseline, and the unknown-pilot fallback. */
        val NONE = CabinetRules(initials = "")
    }
}

/**
 * The twelve, keyed by pilot id.
 *
 * Initials are a CONSONANT SQUEEZE, settled 2026-08-14 after rendering three schemes on
 * a board. Plain truncation was rejected on evidence: it produces FAN, WHI, FRO and RAS,
 * which the eye reads as unrelated English words before it reads them as people — and
 * recognition is the only job these have. Astro is the exception, keeping AST.
 */
object PilotCabinetRules {

    private val byPilot: Map<String, CabinetRules> = mapOf(
        "pilot_medic" to CabinetRules("MDC"),
        "pilot_rascal" to CabinetRules("RSC"),
        "pilot_brutus" to CabinetRules("BRT"),
        "pilot_frost" to CabinetRules("FRS"),
        "pilot_dash" to CabinetRules("DSH"),
        "pilot_ember" to CabinetRules("MBR"),
        "pilot_fang" to CabinetRules("FNG"),
        "pilot_kraken" to CabinetRules("KRK"),
        "pilot_whiskers" to CabinetRules("WSK"),
        "pilot_unit7" to CabinetRules("UN7"),
        "pilot_havoc" to CabinetRules("HVC"),
        "pilot_astro" to CabinetRules("AST")
    )

    /**
     * The ids that have a real entry, as distinct from ids that would fall through to
     * [CabinetRules.NONE]. Exposed so a test can tell those two cases apart — `forPilot`
     * cannot, because its return type is non-nullable.
     */
    internal val mappedPilotIds: Set<String> get() = byPilot.keys

    fun forPilot(pilotId: String): CabinetRules =
        byPilot[pilotId] ?: CabinetRules.NONE
}
