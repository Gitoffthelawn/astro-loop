package com.astroloop.game.cabinet

/**
 * The thing at the centre of the reckoning.
 *
 * Anchored — it never moves. The spec: *"You always know where to point, so the question is
 * never where is it but can I afford to turn right now."* That anchoring is also what
 * keeps the radial patterns readable and what gives the wrap meaning, since fleeing
 * carries you around the field and back toward it.
 *
 * The only behaviour here is its own damage arithmetic, and since a design decision there is
 * barely any: damage comes straight off hp. The bands, their floor and the fractional carry
 * that made damage inside the floor visible are all gone — the pattern follows the health
 * now, so nothing needs to hold the crystal back from dying. See [damage].
 */
class CabinetCrystal(
    val x: Float,
    val y: Float,
    val maxHp: Int,
    /**
     * Whether there is anything inside the containment — a design decision.
     *
     * False on a replay: you released the crystal, and ??? runs a recording of a fight that
     * already happened, so what is on the tube is the shell with nothing in it.
     *
     * **It lives on the crystal rather than being passed to the draw call.** It used to be a
     * `crystalHasBody` parameter on `CabinetRenderer.drawPlayfield`, defaulting to true, which
     * every screen that draws the playfield had to remember to set. The GAME OVER screen did
     * not, so dying in a replay put the body back — and the exit path made it worse, because
     * `CabinetShell.onBack()` lets go of the run while the tube is still powering down over
     * that same screen, so even a corrected call site would have read its `?: true` fallback.
     * A fact carried by the object it describes cannot be forgotten by a caller or outlive
     * the handle someone reads it through.
     */
    val hasBody: Boolean = true
) {

    var hp: Int = maxHp
        private set


    val alive: Boolean get() = hp > 0

    /** 1.0 at full, 0.0 dead — what the top strip's health bar draws. */
    val healthFrac: Float get() = (hp.toFloat() / maxHp).coerceIn(0f, 1f)

    /**
     * Take [n] damage. Non-positive is a no-op rather than a heal: `damage` is a named
     * mutator and healing through it would be an abuse of the name, not a feature.
     *
     * **No floor, and no carry.** A design decision gave this a per-pattern HP floor with damage
     * inside it scaled to 12%, so that every pattern got its turn before the crystal could
     * die. A design decision makes the pattern follow the health instead, which guarantees the
     * same thing by construction — you cannot reach the last fifth without crossing the
     * four before it — so the floor, the carry and the crystal that stopped reacting mid
     * pattern all go away together.
     */
    fun damage(n: Int = 1) {
        if (n <= 0) return
        hp = (hp - n).coerceAtLeast(0)
    }

    fun radius(m: CabinetMetrics): Float = RADIUS_FRAC * m.minEdge

    companion object {
        /**
         * 23.8px at reference — a hair over a SMALL rock (21.6px) and about the size of the
         * player's own ship (24.8px).
         *
         * **This is the hitbox, and since a device pass it is also the drawing.** It was
         * 0.09 — 97.2px — back when a corruption-red shell was drawn at exactly this radius
         * and the orb sat small inside it: the shell was what told you where the target
         * ended. With the shell removed the orb had to take that job, so it was scaled up to
         * fill this radius, and the crystal got visibly enormous. Owner: "you made the
         * crystal really big now? why?"
         *
         * The answer is to shrink the RADIUS rather than to draw a small orb inside a large
         * hitbox, which would be a target that lies — shots that visibly miss would count,
         * and count in the player's favour, in a fight the same pass asked to make harder.
         *
         * 0.05 was the first attempt and still read wrong: at 54px it was nearly a LARGE
         * rock (59.4px), which is a glowing ball rather than the object the rest of the game
         * shows you. `renderTimeCrystalOrb` draws the real thing at a 10-unit glow around a
         * 4-unit core — DESIGN units, which the main game's `canvas.scale(renderScale)`
         * turns into 13.3 physical pixels on a Pixel 9 Pro — and that smallness is most of
         * what makes it recognisable. ⚠️ That orb is no
         * longer drawn at all — a design decision replaced it with a lattice, because the ending
         * is a release and there is no Time Crystal here to depict. The sizing history below
         * is kept because it is why this number moved, not because the orb is still in it.
         *
         * 0.022 — a hair over a SMALL rock — was then flown and judged the other way:
         * *"the red circle is too small."* 0.030 was the first step and drew the same note
         * again, with the size named: *"around the size of a medium asteroid."* So this is
         * `ROCK_MEDIUM_FRAC` exactly — 36.7px — rather than a number near it. It is still
         * well under the 0.05 that read as a ball. **The hitbox moves with it by design:** a shell drawn bigger
         * than the radius would be a target that lies, and counting shots that visibly miss
         * is worse than a target that is small.
         */
        const val RADIUS_FRAC = 0.034f


        /**
         * Points on the shell.
         *
         * Kept at 14 through the device pass reshape because it is not only a silhouette:
         * `CabinetSim.shatterCrystal` subdivides each of these edges to build the ending, so
         * `SHELL_POINTS * CRYSTAL_DEBRIS_SUBDIVISIONS` is the ~70 pieces the ending is
         * authored to throw. Changing it changes the ending's weight.
         */
        const val SHELL_POINTS = 14

        /**
         * The shell as a unit-radius closed polygon, interleaved x,y.
     *
     * **A plain ring, not a star.** It alternated radii 1.0 / 0.72 to read as spikes until
     * a device pass: *"with a corruption red shell around it, it makes the target a little
     * bigger, no spikes or anything."* The shell's job is to be a target the orb cannot be
     * on its own — the Time Crystal is a dot — and a spiked silhouette was reading as a
     * creature rather than as a containment around something.
         *
         * Lives here rather than on the renderer because the SIM needs it — the death
         * animation shatters exactly this outline into its own edges. Same reason
         * `CabinetShip.HULL` lives on the ship.
         */
        val OUTLINE: FloatArray = FloatArray(SHELL_POINTS * 2).also { a ->
            val twoPi = (2.0 * Math.PI).toFloat()
            for (v in 0 until SHELL_POINTS) {
                val ang = (v.toFloat() / SHELL_POINTS) * twoPi
                a[v * 2] = kotlin.math.cos(ang)
                a[v * 2 + 1] = kotlin.math.sin(ang)
            }
        }
    }
}
