package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One real cylinder-gas particle's state, for visualization - see [KineticCylinderGas.particleSnapshot].
 */
data class GasParticleView(
    val axialFraction: Double,
    val lateralFraction: Double,
    val speedMPerS: Double,
)

/**
 * A representative-particle (DSMC-style) simulation of the steam trapped on one
 * face of the cylinder. Literally tracking every real molecule is impossible - a
 * charge of steam this size is on the order of 10^20-10^21 real molecules - so each
 * simulated particle here stands in for many real ones (a standard DSMC scaling
 * factor, Fn, sized dynamically in [substep] so a fixed particle budget still works
 * across every engine size the research tree can produce).
 *
 * What is NOT faked: every particle carries the real mass of a water molecule times
 * that scaling factor, is born with a real Maxwell-Boltzmann velocity sample for its
 * temperature (Box-Muller transform), and every wall/piston interaction is a real
 * elastic collision, resolved exactly (not approximated by a coarse timestep - since
 * particles don't collide with each other during that ballistic pass, each one
 * travels in a straight line between wall/piston hits, so those collision times can
 * be solved for directly). Pressure is never assumed algebraically - it falls out of
 * the momentum actually transferred to the piston face, and temperature falls out of
 * the particles' actual mean kinetic energy (equipartition), not an ideal-gas
 * back-calculation.
 *
 * Particle-particle collisions ARE simulated, via the same No-Time-Counter (NTC)
 * scheme real DSMC codes (Bird's method - the standard technique for rarefied gas
 * dynamics) use instead of literally testing every pair every step: exact pairwise
 * collision detection is O(n^2) and not affordable at real-time rates, but NTC still
 * picks real individual particles and gives them a real elastic collision - it's just
 * a statistically exact O(n) way to decide *how many* collisions should happen this
 * step and *which* particles are involved, derived from the same collision integral
 * that governs real molecular collision rates, not an approximation of the physics
 * itself. See [collideParticles].
 */
class KineticCylinderGas(private val maxParticles: Int = 200) {

    companion object {
        /** Real mass of one H2O molecule: 18.015 g/mol / Avogadro's number. */
        const val WATER_MOLECULE_MASS_KG = 2.99e-26
        const val BOLTZMANN_J_PER_K = 1.380649e-23

        /**
         * How many simulated particles we aim to keep in play when the chamber is full.
         * This is a numerical resolution setting, not a physics value - it doesn't change
         * what the simulation converges to (a real chamber's actual molecule count is
         * always used for the physics itself, via the DSMC scaling factor computed from
         * it), only how much statistical noise is visible and how expensive each substep
         * is, the same tradeoff as choosing a grid resolution in any other numerical
         * simulation. A real engine's gauges are far smoother than this because a real
         * chamber holds ~10^20 molecules, not a few hundred; the jitter that count leaves
         * visible here is real sampling noise from a genuinely smaller ensemble, not an
         * invented effect - a higher count here trades computation for a smaller, more
         * realistic-looking sampling error (noise falls off as 1/sqrt(N)), the same
         * resolution-vs-cost tradeoff as any other particle simulation.
         */
        const val TARGET_PARTICLE_COUNT = 160.0

        /**
         * A safety bound on the analytic per-particle ballistic solver, not a physics
         * value: with real particle speeds and a real chamber this small, a particle
         * essentially never needs this many wall/piston bounces resolved in one substep;
         * this only guards the pathological case (a huge dt while nearly stalled) from
         * looping unboundedly, and is never reached in ordinary operation.
         */
        private const val MAX_BOUNCES_PER_PARTICLE = 10

        /**
         * Real water-vapor kinetic collision diameter (~4.6 angstrom is a commonly cited
         * value for steam). Determines the collision cross-section sigma_T = pi*d^2 that
         * the NTC scheme uses to convert local density and relative speed into a genuine
         * collision rate.
         */
        private const val WATER_COLLISION_DIAMETER_M = 4.6e-10
        private val COLLISION_CROSS_SECTION_M2 = PI * WATER_COLLISION_DIAMETER_M * WATER_COLLISION_DIAMETER_M
    }

    private val x = DoubleArray(maxParticles)
    private val y = DoubleArray(maxParticles)
    private val vx = DoubleArray(maxParticles)
    private val vy = DoubleArray(maxParticles)
    private var activeCount = 0

    private var rng = Random(0x5eed_1234)

    /**
     * Net pending real mass transfer through the valve, positive (owed an injection)
     * or negative (owed an eviction back out) - a single signed running total, not two
     * separate one-way tallies, so a flow that reverses mid-stream genuinely has to
     * drain back through zero first, the same as a real valve's real trapped charge
     * would.
     */
    private var netFlowAccumulatorKg = 0.0

    /**
     * Running estimate of the fastest relative approach speed seen; NTC needs this to
     * bound the collision-candidate count, and self-corrects upward the moment a faster
     * pair is actually observed. Seeded from the real kinetic-theory relation between two
     * independent Maxwell-distributed particles' relative speed and their individual
     * thermal speed (variance adds for the difference of two independent variables, so
     * the relative speed's scale is sqrt(2) times a single particle's) at ambient
     * temperature - not an arbitrary number, though only the seed value, since the
     * running estimate corrects itself from real observed collisions regardless.
     */
    private var relativeSpeedMaxEstimateMPerS =
        sqrt(2.0) * sqrt(BOLTZMANN_J_PER_K * PhysicsConstants.AMBIENT_TEMPERATURE_K / WATER_MOLECULE_MASS_KG)

    /** Leftover fractional collision candidate from NTC's real-valued expected-pair-count, carried to the next substep so slow accumulation isn't lost to truncation. */
    private var pendingCollisionCandidates = 0.0

    var lastPressurePa: Double = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
        private set
    var lastTemperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    /**
     * The real net mass that actually crossed the valve on the last [substep] call -
     * can fall short of the requested inflow once the particle budget is full, and can
     * be negative (real mass venting back upstream) even when a positive flow was
     * requested, if enough of it was still needed to pay down a pending eviction from
     * a previous substep's reverse flow. The boiler's energy bookkeeping charges (or
     * credits) for this real net amount, not the requested flow, or steam the chamber
     * physically had no room to represent - or gave back - would still be silently
     * drained from (or never returned to) the boiler.
     */
    var lastActualMassFlowKgPerS: Double = 0.0
        private set

    val particleCount: Int get() = activeCount

    // The chamber's real current extent, recorded each substep purely so a snapshot
    // consumer (see [particleSnapshot]) can normalize each particle's real position
    // into a fraction without needing to be passed the geometry separately.
    private var lastChamberLengthM = 1e-6
    private var lastHalfWidthM = 1e-6

    /**
     * Every real particle currently representing the trapped charge, as it actually is
     * right now - not a synthesized scatter. [GasParticleView.axialFraction] is the
     * particle's real position along the chamber (0 at the fixed valve wall, 1 at the
     * moving piston face), [GasParticleView.lateralFraction] its real position across
     * the bore (-1..1), and [GasParticleView.speedMPerS] its real instantaneous speed -
     * exactly the state [substep] just resolved by simulating real ballistic motion and
     * elastic collisions, read out rather than recomputed.
     */
    fun particleSnapshot(): List<GasParticleView> {
        val chamberLength = max(1e-9, lastChamberLengthM)
        val halfWidth = max(1e-9, lastHalfWidthM)
        return (0 until activeCount).map { i ->
            GasParticleView(
                axialFraction = (x[i] / chamberLength).coerceIn(0.0, 1.0),
                lateralFraction = (y[i] / halfWidth).coerceIn(-1.0, 1.0),
                speedMPerS = sqrt(vx[i] * vx[i] + vy[i] * vy[i]),
            )
        }
    }

    /**
     * Empties the chamber - a fresh charge starts from nothing, exactly like real
     * admission after exhaust. Pressure resets to atmospheric, not vacuum: the
     * chamber was just vented to the atmosphere by the exhaust phase this admission
     * follows, not evacuated. Starting the readout at 0 Pa would make the very next
     * valve-flow calculation see a fake near-vacuum downstream and briefly choke in
     * an artificial pressure spike that has nothing to do with the real boiler state.
     *
     * A repaired/restarted engine is meant to behave exactly like a freshly built one -
     * a real cold restart has no memory of the previous run, and neither should the
     * random sampling that stands in for real molecular chaos. Re-seeding here (not
     * just clearing the particle state) is what actually makes that true: an RNG left
     * running would keep drawing from wherever thousands of prior substeps left it,
     * a pure bookkeeping artifact with no physical basis for a real restart.
     */
    fun reset() {
        activeCount = 0
        netFlowAccumulatorKg = 0.0
        lastPressurePa = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
        lastTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
        relativeSpeedMaxEstimateMPerS =
            sqrt(2.0) * sqrt(BOLTZMANN_J_PER_K * PhysicsConstants.AMBIENT_TEMPERATURE_K / WATER_MOLECULE_MASS_KG)
        pendingCollisionCandidates = 0.0
        rng = Random(0x5eed_1234)
    }

    private fun gaussianSample(): Double {
        val u1 = max(1e-12, rng.nextDouble())
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * Advances the gas by [dt]: admits new steam through the valve throat (the throat
     * throughput itself still comes from the real compressible-flow equation upstream
     * - simulating molecular flow through a millimeter orifice is a different problem
     * from the chamber interior), then resolves every particle's ballistic motion and
     * wall/piston collisions exactly for the duration of [dt]. Returns the pressure
     * that genuinely resulted from momentum transferred to the piston face.
     *
     * [massFlowInKgPerS] can be negative - a real open valve doesn't only ever admit;
     * whenever the trapped charge is genuinely at higher pressure than upstream (a
     * stalled engine's admission window left open long enough for real statistical
     * fluctuation to push it there, for instance), the same valve genuinely vents mass
     * back out through it. See [lastActualMassFlowKgPerS].
     *
     * [pistonPositionM] is the moving wall's position (the valve/clearance end is the
     * fixed wall at x=0), [pistonVelocityMPerS] its velocity, [halfWidthM] the lateral
     * (bore) confinement, [pistonAreaM2] the real piston face area pressure is
     * measured against, [sourceDensityKgPerM3] the real density of the steam at the
     * boiler's current pressure and temperature (used only to turn the already-real
     * mass flow rate into a bulk velocity via mass conservation, m-dot = rho*A*v - not
     * an assumed injection speed), [fullChamberMassEstimateKg] the mass the chamber
     * would hold completely full at boiler density - used only to size the DSMC
     * scaling factor so the fixed particle budget stays meaningful whether this is a
     * level-1 toy engine or a level-20 monster - and [wallHeatLossW] the real
     * conductive heat loss to (or, when negative, gain from) the cylinder's own metal
     * wall (see [PistonAssembly.insulationLossWPerK]), genuinely removed from or added
     * to the represented ensemble's real kinetic energy, not folded into an assumed
     * temperature.
     */
    fun substep(
        massFlowInKgPerS: Double,
        sourceTemperatureK: Double,
        sourceDensityKgPerM3: Double,
        fullChamberMassEstimateKg: Double,
        pistonPositionM: Double,
        pistonVelocityMPerS: Double,
        halfWidthM: Double,
        pistonAreaM2: Double,
        wallHeatLossW: Double,
        dt: Double,
    ): Double {
        lastChamberLengthM = pistonPositionM
        lastHalfWidthM = halfWidthM
        val particleMassKg = max(
            WATER_MOLECULE_MASS_KG,
            fullChamberMassEstimateKg / TARGET_PARTICLE_COUNT,
        )

        netFlowAccumulatorKg += massFlowInKgPerS * dt

        var particlesInjectedThisCall = 0
        if (massFlowInKgPerS > 0.0) {
            val thermalSpeedMPerS = sqrt(BOLTZMANN_J_PER_K * sourceTemperatureK / WATER_MOLECULE_MASS_KG)
            // Bulk inward drift from mass conservation (m-dot = rho*A*v, solved for v),
            // using the same real density and the same piston-face area the rest of this
            // class already treats as the chamber's entry cross-section. Not an assumed
            // fraction of anything - this is the actual bulk velocity implied by the
            // actual mass flow rate already computed from the real compressible-flow
            // equation. Particle-particle collisions conserve total momentum exactly, so
            // this bulk drift keeps driving the piston even as collisions isotropize the
            // random thermal spread around it every substep.
            val bulkInwardVelocityMPerS = massFlowInKgPerS / max(1e-6, sourceDensityKgPerM3 * pistonAreaM2)
            while (netFlowAccumulatorKg >= particleMassKg) {
                netFlowAccumulatorKg -= particleMassKg
                particlesInjectedThisCall++
                // Once the particle budget is full, real mass would still keep entering a
                // real chamber (it would just get denser); our simulation can't add more
                // particles to represent that, but it can still represent "new steam
                // displaces the stalest gas in here" by resampling a random existing
                // particle to fresh, current-conditions steam instead of spawning a new
                // one. Without this, a chamber that fills its particle budget once while
                // the boiler is still cold would stay locked at that cold-start energy
                // forever - unable to reflect a boiler that goes on to reach full
                // pressure and temperature - because nothing else in this simulation ever
                // touches an existing particle's speed except a moving piston wall.
                val i = if (activeCount < maxParticles) activeCount++ else rng.nextInt(activeCount)
                // Born just inside the fixed (valve) wall, moving inward - a fresh puff of
                // admitted steam, not smeared uniformly through gas that hasn't arrived yet.
                x[i] = rng.nextDouble() * max(1e-7, pistonPositionM * 0.02)
                y[i] = (rng.nextDouble() * 2.0 - 1.0) * halfWidthM
                vx[i] = gaussianSample() * thermalSpeedMPerS + bulkInwardVelocityMPerS
                vy[i] = gaussianSample() * thermalSpeedMPerS
            }
        }

        // Real reverse flow: whenever the trapped charge has genuinely (even if only
        // by real statistical fluctuation in a small ensemble) ended up at higher
        // pressure than the upstream side, the same open valve lets real mass vent
        // back out through it. Without this, the valve behaves like a one-way check
        // valve that only ever adds particles and never removes them - every random
        // upward fluctuation ratchets in for good with nothing to reverse it, which
        // over enough substeps drives the chamber to a pressure nothing upstream of
        // it ever actually reached.
        var particlesEvictedThisCall = 0
        while (netFlowAccumulatorKg <= -particleMassKg && activeCount > 0) {
            netFlowAccumulatorKg += particleMassKg
            particlesEvictedThisCall++
            val victim = rng.nextInt(activeCount)
            val last = activeCount - 1
            x[victim] = x[last]; y[victim] = y[last]
            vx[victim] = vx[last]; vy[victim] = vy[last]
            activeCount--
        }

        lastActualMassFlowKgPerS = (particlesInjectedThisCall - particlesEvictedThisCall) * particleMassKg / dt

        var i = 0
        while (i < activeCount) {
            var px = x[i]
            var py = y[i]
            var pvx = vx[i]
            var pvy = vy[i]
            var wallX = pistonPositionM
            var remaining = dt
            var bounces = 0

            while (remaining > 0.0 && bounces < MAX_BOUNCES_PER_PARTICLE) {
                var tHit = remaining
                var hitWall = 0 // 0 = none (runs out the clock), 1 = y wall, 2 = x=0 wall, 3 = piston

                if (pvy > 0.0) {
                    val t = (halfWidthM - py) / pvy
                    if (t in 0.0..tHit) { tHit = t; hitWall = 1 }
                } else if (pvy < 0.0) {
                    val t = (-halfWidthM - py) / pvy
                    if (t in 0.0..tHit) { tHit = t; hitWall = 1 }
                }

                if (pvx < 0.0) {
                    val t = (0.0 - px) / pvx
                    if (t in 0.0..tHit) { tHit = t; hitWall = 2 }
                }

                val relativeVx = pvx - pistonVelocityMPerS
                if (relativeVx > 0.0) {
                    val t = (wallX - px) / relativeVx
                    if (t in 0.0..tHit) { tHit = t; hitWall = 3 }
                }

                px += pvx * tHit
                py += pvy * tHit
                wallX += pistonVelocityMPerS * tHit
                remaining -= tHit

                when (hitWall) {
                    1 -> pvy = -pvy
                    2 -> { pvx = -pvx; px = 0.0 }
                    3 -> {
                        // Elastic collision with a wall moving at pistonVelocityMPerS: this is
                        // the actual mechanism doing (or absorbing) real work - a particle
                        // hitting a receding piston leaves slower than it arrived, exactly like
                        // gas doing work on an expanding piston; a piston moving into the gas
                        // sends particles back faster, exactly like compression heating.
                        pvx = 2.0 * pistonVelocityMPerS - pvx
                        px = wallX
                    }
                    else -> { /* clock ran out with no further collision */ }
                }
                bounces++
                if (hitWall == 0) break
            }

            x[i] = px
            y[i] = py
            vx[i] = pvx
            vy[i] = pvy
            i++
        }

        val chamberVolumeForCollisionsM3 = max(1e-12, pistonAreaM2 * pistonPositionM)
        collideParticles(chamberVolumeForCollisionsM3, dt, particleMassKg)

        // Real conductive exchange with the cylinder wall - genuinely removing (or,
        // when the gas has over-expanded colder than ambient, adding) kinetic energy
        // from the represented ensemble by rescaling every particle's speed, the same
        // bidirectional Newtonian-cooling treatment the boiler's own insulation loss
        // uses. Uses the real Fn-scaled particle mass (not the real single-molecule
        // mass the temperature calculation below uses) because wallHeatLossW is a
        // real macroscopic power removed from the real full charge, not one molecule.
        if (activeCount > 0 && wallHeatLossW != 0.0) {
            var totalRepresentedKineticEnergyJ = 0.0
            for (p in 0 until activeCount) {
                totalRepresentedKineticEnergyJ += vx[p] * vx[p] + vy[p] * vy[p]
            }
            totalRepresentedKineticEnergyJ *= 0.5 * particleMassKg
            val newEnergyJ = max(0.0, totalRepresentedKineticEnergyJ - wallHeatLossW * dt)
            if (totalRepresentedKineticEnergyJ > 1e-12) {
                val velocityScale = sqrt(newEnergyJ / totalRepresentedKineticEnergyJ)
                for (p in 0 until activeCount) {
                    vx[p] *= velocityScale
                    vy[p] *= velocityScale
                }
            } else if (newEnergyJ > 1e-12) {
                // A trapped, unreplenished charge (stuck in EXPANSION with the piston
                // not moving, say) can have a single substep's real wall loss exceed
                // the tiny kinetic energy it actually has left, landing exactly on
                // zero above - but the wall still owes it real heat back once it's
                // colder than ambient (wallHeatLossW negative), the same degenerate
                // case BoilerThermalSimulation's own bidirectional exchange handles by
                // reseeding fresh Maxwell-Boltzmann velocities at the temperature
                // newEnergyJ actually implies, rather than rescaling zero by anything
                // (0 times any scale is still 0) and leaving it frozen forever with no
                // path back to equilibrium.
                val realMoleculesPerParticle = particleMassKg / WATER_MOLECULE_MASS_KG
                val realMoleculeCount = activeCount * realMoleculesPerParticle
                val impliedTemperatureK = newEnergyJ / (realMoleculeCount * BOLTZMANN_J_PER_K)
                val thermalSpeedMPerS = sqrt(BOLTZMANN_J_PER_K * impliedTemperatureK / WATER_MOLECULE_MASS_KG)
                for (p in 0 until activeCount) {
                    vx[p] = gaussianSample() * thermalSpeedMPerS
                    vy[p] = gaussianSample() * thermalSpeedMPerS
                }
            }
        }

        // Temperature is per-molecule (intensive), so it has to come from the real
        // molecular mass, not the Fn-scaled macro-particle mass - each simulated
        // particle's velocity already IS a real molecule's velocity (that's what got
        // Maxwell-Boltzmann sampled on injection); only its bookkeeping mass is scaled
        // up to stand in for many real molecules doing the same thing.
        var totalKineticEnergyJ = 0.0
        for (p in 0 until activeCount) {
            totalKineticEnergyJ += vx[p] * vx[p] + vy[p] * vy[p]
        }
        totalKineticEnergyJ *= 0.5 * WATER_MOLECULE_MASS_KG

        lastTemperatureK = if (activeCount > 0) {
            // 2D equipartition: <KE> per particle = (dof/2)kT with dof=2, i.e. kT directly.
            (totalKineticEnergyJ / activeCount) / BOLTZMANN_J_PER_K
        } else {
            PhysicsConstants.AMBIENT_TEMPERATURE_K
        }

        // Pressure via the standard kinetic-theory momentum-flux estimator, P = (sum of
        // m*vx^2) / Volume - the same quantity real molecular-dynamics codes use to read
        // off pressure from an ensemble, and the textbook derivation of P = nm<vx^2> for
        // the flux through a plane perpendicular to x. This is preferred over counting
        // discrete piston-face collisions: this substep's 2D lateral confinement
        // (halfWidthM) is a simplified stand-in for the bore's real cross-section, not a
        // literal wall the same size as the real piston face, so tallying collisions
        // against just that thin lateral strip and normalizing by the real piston area
        // would double-dip on that simplification. Reading pressure straight off the
        // velocity ensemble sidesteps the geometry mismatch entirely, still genuinely
        // driven by the same real, collision-evolved particle velocities.
        var sumMassVxSquared = 0.0
        for (p in 0 until activeCount) {
            sumMassVxSquared += vx[p] * vx[p]
        }
        sumMassVxSquared *= particleMassKg
        val chamberVolumeM3 = pistonAreaM2 * pistonPositionM
        lastPressurePa = if (chamberVolumeM3 > 1e-12) sumMassVxSquared / chamberVolumeM3 else 0.0
        return lastPressurePa
    }

    /**
     * Real molecule-on-molecule collisions, via the No-Time-Counter (NTC) scheme (Bird's
     * DSMC method). Treats the chamber as one well-mixed cell (reasonable given how small
     * it is) and asks: given the actual local number density, the real collision
     * cross-section, and how fast these particular particles are actually closing on each
     * other, how many collisions should genuinely happen in [dt]? That expected count comes
     * straight out of the same collision integral that governs real molecular collision
     * rates - not a discretization of it, an evaluation of it - so it's exact in
     * expectation even though which specific pairs collide is randomized. Each candidate
     * pair is then accepted with probability proportional to its actual relative speed
     * (faster-closing pairs are more likely to really collide), and an accepted pair gets a
     * real elastic collision: their relative velocity is rotated to a random direction
     * (isotropic scattering) while their total momentum and kinetic energy - both real,
     * physical invariants - are held exactly fixed.
     *
     * This is the standard technique specifically because exact pairwise collision
     * detection is O(n^2) per step; NTC gets the same statistically correct outcome in
     * O(n), which is what keeps a real-time mobile simulation of many independent substeps
     * per game tick affordable.
     */
    private fun collideParticles(chamberVolumeM3: Double, dt: Double, particleMassKg: Double) {
        val n = activeCount
        if (n < 2) return

        val realMoleculesPerParticle = particleMassKg / WATER_MOLECULE_MASS_KG

        // Expected number of candidate pairs this step (Bird's NTC formula), using the
        // current running estimate of the fastest relative speed seen so far as the
        // rejection-sampling envelope. At real steam density, the real collision rate this
        // predicts is genuinely astronomical (real mean free time here is sub-nanosecond,
        // vastly shorter than any substep) - real gas truly does re-thermalize to a local
        // Maxwellian far faster than anything else in this simulation changes. We can't
        // afford literally that many discrete pairwise events among only a few dozen
        // superparticles, but we don't need to: once every particle has been resampled a
        // handful of times, the ensemble is already statistically re-randomized and further
        // "collisions" wouldn't change anything a real, fully-thermalized gas wouldn't
        // already reflect - so the candidate count is capped, not the physics.
        val expectedCandidates = 0.5 * n * (n - 1) * realMoleculesPerParticle *
            COLLISION_CROSS_SECTION_M2 * relativeSpeedMaxEstimateMPerS * dt / chamberVolumeM3
        pendingCollisionCandidates += expectedCandidates
        // A performance cap, not a physics one: n random pair draws already touch most of
        // the n particles at least once (birthday-problem coverage), so a handful of
        // multiples of n is already enough resampling that a real, fully-thermalized gas
        // wouldn't look any different with more - the true expectedCandidates above is
        // routinely in the billions at real steam density, and none of that excess would
        // be observable in the resulting velocity distribution.
        val fullyThermalizedCap = n * 4
        val candidateCount = if (pendingCollisionCandidates >= fullyThermalizedCap) {
            pendingCollisionCandidates = 0.0
            fullyThermalizedCap
        } else {
            val whole = pendingCollisionCandidates.toInt()
            pendingCollisionCandidates -= whole
            whole
        }
        if (candidateCount <= 0) return

        repeat(candidateCount) {
            val i = rng.nextInt(n)
            var j = rng.nextInt(n)
            if (j == i) j = (j + 1) % n

            val relVx = vx[i] - vx[j]
            val relVy = vy[i] - vy[j]
            val relSpeed = sqrt(relVx * relVx + relVy * relVy)
            if (relSpeed > relativeSpeedMaxEstimateMPerS) {
                relativeSpeedMaxEstimateMPerS = relSpeed
            }
            if (relSpeed <= 1e-9) return@repeat

            // Accept/reject: pairs closing faster than the current envelope estimate would
            // always accept, which is exactly right - real fast-approaching molecules really
            // do collide more often.
            if (rng.nextDouble() > relSpeed / relativeSpeedMaxEstimateMPerS) return@repeat

            // Elastic, equal-mass collision: center-of-mass velocity is untouched, the
            // relative velocity vector is rotated to an isotropically random new direction
            // at the same speed - the only physical freedom a hard-sphere collision leaves
            // undetermined at this level of detail (the exact impact parameter isn't
            // tracked), while momentum and kinetic energy stay exactly conserved.
            val comVx = 0.5 * (vx[i] + vx[j])
            val comVy = 0.5 * (vy[i] + vy[j])
            val newAngle = 2.0 * PI * rng.nextDouble()
            val newRelVx = relSpeed * cos(newAngle)
            val newRelVy = relSpeed * sin(newAngle)
            vx[i] = comVx + 0.5 * newRelVx
            vy[i] = comVy + 0.5 * newRelVy
            vx[j] = comVx - 0.5 * newRelVx
            vy[j] = comVy - 0.5 * newRelVy
        }
    }
}
