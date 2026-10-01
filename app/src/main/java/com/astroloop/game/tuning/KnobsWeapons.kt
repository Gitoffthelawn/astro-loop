package com.astroloop.game.tuning

import kotlin.math.PI

/**
 * Every weapon's knobs. Defaults are the shipped values: changing one here changes the game for
 * everyone, which makes this the file a balance pass edits.
 *
 * Cooldowns are in 16th notes: 2 = 0.25 s, 4 = 0.5 s, 8 = 1 s, 12 = 1.5 s, 16 = 2 s, 32 = 4 s.
 */
object KnobsWeapons {

    // ── Base weapons ────────────────────────────────────────────────────────────────────────────
    val pulseCannon = WeaponKnobs.base("pulse_cannon", "Pulse Cannon", damage = 15f, sixteenths = 4, count = 1, speed = 600f)
    val energySaw = WeaponKnobs.ticking("energy_saw", "Energy Saw", damage = 8f, tickMs = 100)
    val scatterShot = WeaponKnobs.base("scatter_shot", "Scatter Shot", damage = 10f, sixteenths = 8, count = 5, speed = 500f, hasArea = true)
    val homingMissiles = WeaponKnobs.base("homing_missiles", "Homing Missiles", damage = 35f, sixteenths = 8, count = 1, speed = 350f)
    val ionOrbiters = WeaponKnobs.base(
        "ion_orbiters", "Ion Orbiters", damage = 19f, sixteenths = 32, count = 2, hasArea = true,
        countApplies = Applies.ON_EDITOR_CLOSE,
    )
    val railgun = WeaponKnobs.base("railgun", "Railgun", damage = 80f, sixteenths = 12, count = 1, speed = 2000f)
    val spaceMines = WeaponKnobs.base("space_mines", "Space Mines", damage = 60f, sixteenths = 16, count = 1, hasArea = true)
    val solarStorm = WeaponKnobs.base("solar_storm", "Solar Storm", damage = 29f, sixteenths = 16, count = 1, countLabel = "Targets at L1")
    val novaBlast = WeaponKnobs.base("nova_blast", "Nova Blast", damage = 40f, sixteenths = 32, count = null, hasArea = true)
    val needleGun = WeaponKnobs.base("needle_gun", "Needle Gun", damage = 5f, sixteenths = 2, count = 3, speed = 800f)
    val clusterBomb = WeaponKnobs.base("cluster_bomb", "Cluster Bomb", damage = 60f, sixteenths = 16, count = 1, speed = 200f, hasArea = true)
    val flakCannon = WeaponKnobs.base("flak_cannon", "Flak Cannon", damage = 44f, sixteenths = 8, count = 1, speed = 400f, hasArea = true)

    // ── Evolutions ──────────────────────────────────────────────────────────────────────────────
    val stormCannon = WeaponKnobs.evolution("storm_cannon", "Storm Cannon", from = pulseCannon, rate = 0.5f, damage = 18f, count = 3, speed = 700f)
    val warpSaw = WeaponKnobs.ticking("warp_saw", "Warp Saw", damage = 15f, tickMs = 100)
    val leechBurst = WeaponKnobs.evolution("leech_burst", "Leech Burst", from = scatterShot, rate = 0.5f, damage = 12f, count = 13, speed = 550f, hasArea = true)
    val autonomousAce = WeaponKnobs.evolution("autonomous_ace", "Autonomous Ace", from = homingMissiles, rate = 1f, damage = 45f, count = 5, speed = 500f)
    // No count knob: the rings use frost_ring.inner_count and frost_ring.outer_count.
    val frostRing = WeaponKnobs.evolution("frost_ring", "Frost Ring", from = ionOrbiters, rate = 0.5f, damage = 20f, count = null, fixedCount = 18, hasArea = true)
    // Damage is per tick (10 ticks/sec) and the tick is each entity's own interval; it was 50 before a heavy nerf.
    val oblivionBeam = WeaponKnobs.ticking("oblivion_beam", "Oblivion Beam", damage = 18f, tickMs = 100)
    val jackpotMines = WeaponKnobs.evolution("jackpot_mines", "Gambler's Mines", from = spaceMines, rate = 1f, damage = 90f, count = 3)
    val phoenixFlare = WeaponKnobs.evolution("phoenix_flare", "Phoenix Flare", from = solarStorm, rate = 1f, damage = 50f, count = 8, hasArea = true, countLabel = "Projectiles per ring")
    val lingeringNova = WeaponKnobs.evolution("lingering_nova", "Lingering Nova", from = novaBlast, rate = 1f, damage = 30f, count = null, hasArea = true)
    val siphonNeedles = WeaponKnobs.evolution("siphon_needles", "Siphon Needles", from = needleGun, rate = 1f, damage = 9f, count = 7, speed = 900f)
    // Hunter-Killer fires at double the Cluster Bomb's rate: a clean beat-halving.
    val hunterKiller = WeaponKnobs.evolution("hunter_killer", "Hunter-Killer", from = clusterBomb, rate = 0.5f, damage = 60f, count = 1, speed = 200f, hasArea = true)
    val flakBarrage = WeaponKnobs.evolution("flak_barrage", "Flak Barrage", from = flakCannon, rate = 0.5f, damage = 25f, count = 5, speed = 450f, hasArea = true)

    // ── Specials: projectile weapons ────────────────────────────────────────────────────────────
    val pulseSpread = pulseCannon.g.float("spread", "Fan spread (rad)", PI.toFloat() / 12f)
    val pulseLifetime = pulseCannon.g.float("lifetime", "Bolt lifetime (s)", 2f)

    val stormSpiralStep = stormCannon.g.float("spiral_step", "Spiral step (rad)", PI.toFloat() / 6f)
    val stormLifetime = stormCannon.g.float("lifetime", "Bolt lifetime (s)", 2f)

    val needleSpread = needleGun.g.float("spread", "Spread (rad)", 0.08f)
    val needlePierce = needleGun.g.int("pierce", "Pierces", 3, max = 50)
    val needleLifetime = needleGun.g.float("lifetime", "Needle lifetime (s)", 2f)
    val needleCountGrowth = needleGun.g.int("count_growth", "Needles per level", 1, min = 0, max = 10)

    val siphonSpread = siphonNeedles.g.float("spread", "Spread (rad)", 0.08f)
    val siphonPierce = siphonNeedles.g.int("pierce", "Pierces", 1, max = 50)
    val siphonLifetime = siphonNeedles.g.float("lifetime", "Needle lifetime (s)", 2f)
    val siphonHealPerHit = siphonNeedles.g.float("heal_per_hit", "Heal per hit (HP)", 0.1f, min = 0f, max = 5f)

    val railgunPierce = railgun.g.int("pierce", "Pierces", 10, max = 100)
    val railgunShotRadius = railgun.g.floatsPerLevel("shot_radius", "Shot radius", floatArrayOf(4f, 5.5f, 7f, 8.5f, 10f))
    val railgunShotWidth = railgun.g.floatsPerLevel("shot_width", "Shot width", floatArrayOf(3f, 5f, 7f, 9f, 11f))
    val railgunLifetime = railgun.g.float("lifetime", "Slug lifetime (s)", 3f)

    /** Leech Burst throws this same cone: an evolution must never scatter wider than its base. */
    val scatterCone = scatterShot.g.float("cone", "Cone (rad)", PI.toFloat() / 6f)
    val scatterCountGrowth = scatterShot.g.int("count_growth", "Pellets per level", 2, min = 0, max = 10)
    val scatterLifetime = scatterShot.g.float("lifetime", "Pellet lifetime (s)", 1.5f)
    val scatterPelletRadius = scatterShot.g.float("pellet_radius", "Pellet radius", 3f)

    val leechLifetime = leechBurst.g.float("lifetime", "Pellet lifetime (s)", 1.5f)
    val leechPelletRadius = leechBurst.g.float("pellet_radius", "Pellet radius", 3f)

    val homingStrength = homingMissiles.g.float("homing", "Homing strength", 3.5f)
    val homingLifetime = homingMissiles.g.float("lifetime", "Missile lifetime (s)", 4f)
    val homingFan = homingMissiles.g.float("fan", "Launch fan (rad)", 0.3f)

    val aceHoming = autonomousAce.g.float("homing", "Homing strength", 2.5f)
    val aceLifetime = autonomousAce.g.float("lifetime", "Missile lifetime (s)", 4f)

    val clusterExplosionRadius = clusterBomb.g.float("explosion_radius", "Explosion radius", 80f)
    val clusterBomblets = clusterBomb.g.int("bomblets", "Bomblets at L1 (+1 per level)", 2, min = 0, max = 20)
    val clusterExplosionFraction = clusterBomb.g.float("explosion_frac", "Explosion damage ×", 0.3f)
    val clusterBombletFraction = clusterBomb.g.float("bomblet_frac", "Bomblet damage ×", 0.3f)
    val clusterFragmentFraction = clusterBomb.g.float("fragment_frac", "Fragment damage ×", 0.15f)
    val clusterBombletRadiusFraction = clusterBomb.g.float("bomblet_radius_frac", "Bomblet radius ×", 0.6f)
    val clusterLifetime = clusterBomb.g.float("lifetime", "Fuse (s)", 6f)
    val clusterFan = clusterBomb.g.float("fan", "Launch fan (rad)", 0.2f)

    val hunterExplosionRadius = hunterKiller.g.float("explosion_radius", "Explosion radius (×1.4)", 80f)
    val hunterBomblets = hunterKiller.g.int("bomblets", "Bomblets", 7, min = 0, max = 30)
    val hunterHoming = hunterKiller.g.float("homing", "Homing strength", 3.5f)
    val hunterExplosionFraction = hunterKiller.g.float("explosion_frac", "Explosion damage ×", 0.3f)
    val hunterBombletFraction = hunterKiller.g.float("bomblet_frac", "Bomblet damage ×", 0.3f)
    val hunterFragmentFraction = hunterKiller.g.float("fragment_frac", "Fragment damage ×", 0.15f)
    val hunterBombletRadiusFraction = hunterKiller.g.float("bomblet_radius_frac", "Bomblet radius ×", 0.6f)
    val hunterLifetime = hunterKiller.g.float("lifetime", "Fuse (s)", 6f)

    val flakExplosionRadius = flakCannon.g.float("explosion_radius", "Explosion radius", 60f)
    val flakExplosionFraction = flakCannon.g.float("explosion_frac", "Explosion damage ×", 0.7f)
    val flakSpread = flakCannon.g.float("spread", "Spread (rad)", 0.15f)
    val flakJitter = flakCannon.g.float("jitter", "Jitter (rad)", 0.1f, min = 0f, max = 1f)
    val flakFuse = flakCannon.g.float("fuse", "Fuse (s)", 1.5f)
    val flakShellRadius = flakCannon.g.float("shell_radius", "Shell radius", 6f)

    val barrageExplosionRadius = flakBarrage.g.float("explosion_radius", "Explosion radius", 60f)
    val barrageExplosionFraction = flakBarrage.g.float("explosion_frac", "Explosion damage ×", 0.7f)
    val barrageSpread = flakBarrage.g.float("spread", "Spread (rad)", 0.15f)
    val barrageJitter = flakBarrage.g.float("jitter", "Jitter (rad)", 0.1f, min = 0f, max = 1f)
    val barrageFuse = flakBarrage.g.float("fuse", "Fuse (s)", 1.5f)
    val barrageShellRadius = flakBarrage.g.float("shell_radius", "Shell radius", 6f)

    // ── Specials: saws, beam, orbiters, mines, flare, novas ─────────────────────────────────────
    val sawDamagePerLevel = energySaw.g.float("damage_per_level", "Damage per level", 1f, min = 0f, max = 20f)
    val sawDiscRadius = energySaw.g.floatsPerLevel("disc_radius", "Blade radius", floatArrayOf(20f, 28f, 36f, 45f, 55f))
    val sawReach = energySaw.g.floatsPerLevel("reach", "Reach", floatArrayOf(80f, 90f, 100f, 112f, 125f))

    val warpDiscRadius = warpSaw.g.float("disc_radius", "Blade radius", 55f)
    val warpReach = warpSaw.g.float("reach", "Reach", 125f)
    val warpLeash = warpSaw.g.float("leash", "Leash range", 600f)
    val warpRoamSpeed = warpSaw.g.float("roam_speed", "Roam speed", 450f)
    val warpDuration = warpSaw.g.float("warp_duration", "Warp home (s)", 0.3f)

    val beamLength = oblivionBeam.g.float("length", "Beam length", 1600f)
    val beamHalfWidth = oblivionBeam.g.float("half_width", "Beam half-width", 10f)

    val ionOrbitRadius = ionOrbiters.g.floatsPerLevel("orbit_radius", "Orbit radius", floatArrayOf(70f, 85f, 100f, 105f, 110f))
    val ionOrbitSpeed = ionOrbiters.g.float("orbit_speed", "Orbit speed (rad/s)", 3f)
    val ionOrbitSpeedPerLevel = ionOrbiters.g.float("orbit_speed_per_level", "Orbit speed per level", 0.3f, min = 0f, max = 3f)
    val ionOrbRadius = ionOrbiters.g.float("orb_radius", "Orb radius", 10f, applies = Applies.ON_EDITOR_CLOSE)
    val ionCountGrowth = ionOrbiters.g.int("count_growth", "Orbiters per level", 1, min = 0, max = 10, applies = Applies.ON_EDITOR_CLOSE)

    val frostInnerCount = frostRing.g.int("inner_count", "Inner orbs", 6, applies = Applies.ON_EDITOR_CLOSE)
    val frostOuterCount = frostRing.g.int("outer_count", "Outer orbs", 12, applies = Applies.ON_EDITOR_CLOSE)
    val frostInnerRadius = frostRing.g.float("inner_radius", "Inner radius", 110f)
    val frostOuterRadius = frostRing.g.float("outer_radius", "Outer radius", 225f)
    val frostInnerSpeed = frostRing.g.float("inner_speed", "Inner speed (rad/s)", 3.5f)
    val frostOuterSpeed = frostRing.g.float("outer_speed", "Outer speed (rad/s)", 2.5f)
    val frostOrbRadius = frostRing.g.float("orb_radius", "Orb radius", 10f, applies = Applies.ON_EDITOR_CLOSE)

    val minesExplosionRadius = spaceMines.g.floatsPerLevel("explosion_radius", "Explosion radius", floatArrayOf(40f, 80f, 80f, 110f, 110f))
    val minesLifetime = spaceMines.g.float("lifetime", "Mine lifetime (s)", 30f, applies = Applies.NEW_SPAWNS)
    val minesSpawnDelay = spaceMines.g.float("spawn_delay", "Delay between mines (s)", 0.2f)
    val minesRadius = spaceMines.g.float("mine_radius", "Mine radius", 12f, applies = Applies.NEW_SPAWNS)
    val minesCountGrowth = spaceMines.g.float("count_growth", "Mines per level (rounded down)", 0.5f, min = 0f, max = 5f)

    val jackpotExplosionRadius = jackpotMines.g.float("explosion_radius", "Explosion radius", 80f)
    val jackpotLifetime = jackpotMines.g.float("lifetime", "Mine lifetime (s)", 30f, applies = Applies.NEW_SPAWNS)
    val jackpotRadius = jackpotMines.g.float("mine_radius", "Mine radius", 12f, applies = Applies.NEW_SPAWNS)

    /** One knob per ring, ids `…_l1` to `…_l3`: rings 1–3, fastest last. */
    val phoenixRingSpeed = phoenixFlare.g.floatsPerLevel("ring_speed", "Ring speed, ring", floatArrayOf(350f, 650f, 950f))
    val phoenixRingLifetime = phoenixFlare.g.floatsPerLevel("ring_lifetime", "Ring lifetime (s), ring", floatArrayOf(0.7f, 0.65f, 0.6f))
    val phoenixRingRadius = phoenixFlare.g.float("ring_radius", "Ring projectile radius", 35f)
    val phoenixPierce = phoenixFlare.g.int("pierce", "Pierces", 20, max = 200)

    val novaBlastRadius = novaBlast.g.floatsPerLevel("blast_radius", "Blast radius", floatArrayOf(180f, 234f, 281f, 316f, 351f))

    val lingeringBlastRadius = lingeringNova.g.float("blast_radius", "Blast radius", 360f)

    val all: List<WeaponKnobs> = listOf(
        pulseCannon, energySaw, scatterShot, homingMissiles, ionOrbiters, railgun,
        spaceMines, solarStorm, novaBlast, needleGun, clusterBomb, flakCannon,
        stormCannon, warpSaw, leechBurst, autonomousAce, frostRing, oblivionBeam,
        jackpotMines, phoenixFlare, lingeringNova, siphonNeedles, hunterKiller, flakBarrage,
    )

    fun forWeapon(id: String): WeaponKnobs = all.first { it.weaponId == id }
}
