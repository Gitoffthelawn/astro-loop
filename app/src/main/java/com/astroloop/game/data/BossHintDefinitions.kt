package com.astroloop.game.data

/**
 * TB-26's nudges for the ten-minute boss.
 *
 * The boss has exactly one answer: fly as Astro, whose passive brings TB-26 along, and survive it.
 * Every other pilot is out there alone and dies. Players were reaching nine and ten minutes
 * repeatedly without ever learning that, which is a frustrating way to meet a wall.
 *
 * Which nudge lands depends on why the run failed, and the split matters more than the wording:
 * telling someone already flying Astro not to go alone is nonsense, and nonsense from the one
 * character who can see the player costs him the authority the hints depend on.
 *
 * Hints escalate with repeated failures and never run out — the last one keeps being given,
 * because a player still failing at the fifth attempt is exactly who needs it.
 */
object BossHintDefinitions {

    /** Why the last attempt failed, which decides what TB-26 says about it. */
    enum class Track {
        /** Died past ten minutes flying anyone but Astro: the answer is company. */
        SOLO,

        /** Died past ten minutes as Astro: the answer is already in hand, so endure. */
        ASTRO
    }

    /**
     * For a pilot who keeps going out alone. Escalates from an observation to a next step, stopping
     * short of naming Astro — tapping a pilot shows their passive, so "doesn't fly alone" is
     * followable, and finding it is a better moment than being handed it.
     *
     * The last rung has to survive **Astro unlocking on ALL_OTHERS**, because it is the rung that
     * repeats forever: whoever is still failing on the fifth attempt reads it, and they may or may
     * not have him by then. So it names no pilot and asks for no particular next step — "find him"
     * is something the player can act on either way, recruiting him if he is locked and selecting
     * him if he is not. An earlier version said "keep recruiting", which was nonsense to anyone
     * who already had the whole roster, and nonsense from the one character who can see the player
     * costs him the authority the hints depend on.
     */
    private val soloHints = listOf(
        "Ten minutes in. Nobody gets that far alone.",
        "Still going out on your own, I see.",
        "There's one who doesn't fly alone. Find him."
    )

    /** For a pilot who has the answer and is still dying: stop trading, start lasting. */
    private val astroHints = listOf(
        "Close. You only have to still be there.",
        "Don't try to win it. Outlast it.",
        "It ends on its own. Stay alive that long."
    )

    /**
     * How each pilot reacts to TB-26 addressing someone who is not in the room.
     *
     * Keyed by callsign because any of them may be the one who just came back, and a shared pool
     * would flatten twelve voices into one shrug. Each line is the pilot noticing the same thing
     * in their own way — none of them can see the commander, and none of them ever address the
     * commander either. Only TB-26 does both.
     *
     * Lengths respect the per-speaker chat-column budget, which is tighter for longer callsigns:
     * WHISKERS gets 55 characters where DASH gets 59. A test holds every line to its own.
     *
     * "TB-26" is safe to say here: hints only fire in normal mode, where he is not yet Tobar.
     */
    val crewReactions = mapOf(
        "MEDIC" to "TB-26, there's nobody there. Do you need a scan?",
        "RASCAL" to "Who's he talking to? Is there loot in it?",
        "FROST" to "He does that. I stopped calculating why.",
        "UNIT-7" to "No recipient detected. TB-26 may be faulty.",
        "DASH" to "Talking to the air again? Great. Can we go?",
        "BRUTUS" to "...Nobody's there.",
        "EMBER" to "He's talking to a ghost. I respect the drama.",
        "FANG" to "No second heartbeat in here. He's alone.",
        "KRAKEN" to "He's addressing the deep. The deep isn't listening.",
        "WHISKERS" to "Boring. Talk to me or don't talk.",
        "HAVOC" to "Who are you TALKING to?! Is it me? It's me, right?",
        "ASTRO" to "Careful, TB-26. You'll worry the others."
    )

    /** The returning pilot's reaction, falling back so the wiring can never end in silence. */
    fun reactionFor(callsign: String): String =
        crewReactions[callsign] ?: "...Who is he talking to?"

    fun linesFor(track: Track): List<String> = when (track) {
        Track.SOLO -> soloHints
        Track.ASTRO -> astroHints
    }

    /**
     * The hint owed after [failures] failed attempts on [track], or null before the first one.
     *
     * Clamps to the last line rather than running out, so the most explicit nudge repeats for as
     * long as the player keeps hitting the wall.
     */
    fun hintFor(track: Track, failures: Int): String? {
        if (failures <= 0) return null
        val lines = linesFor(track)
        return lines[(failures - 1).coerceAtMost(lines.size - 1)]
    }
}
