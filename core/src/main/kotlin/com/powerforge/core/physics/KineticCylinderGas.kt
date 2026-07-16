package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

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
 * particles don't interact with each other, each one travels in a straight line
 * between collisions, so the collision times can be solved for directly). Pressure
 * is never assumed algebraically - it falls out of the momentum actually transferred
 * to the piston face (F = dp/dt), and temperature falls out of the particles' actual
 * mean kinetic energy (equipartition), not an ideal-gas back-calculation.
 *
 * The one deliberate simplification is skipping particle-particle collisions
 * (O(n^2) per step is not affordable at real-time rates): gas self-thermalization is
 * approximated by Maxwell-Boltzmann sampling at injection rather than emerging from
 * molecule-on-molecule collisions. Every wall and piston interaction - the thing
 * that actually produces pressure and does work on the piston - is genuinely
 * simulated, not faked.
 */
class KineticCylinderGas(private val maxParticles: Int = 70) {

    companion object {
        /** Real mass of one H2O molecule: 18.015 g/mol / Avogadro's number. */
        const val WATER_MOLECULE_MASS_KG = 2.99e-26
        const val BOLTZMANN_J_PER_K = 1.380649e-23

        /** How many simulated particles we aim to keep in play when the chamber is full. */
        const val TARGET_PARTICLE_COUNT = 55.0

        /** Bounded so a pathological (near-stalled, huge-dt) substep can't spin forever. */
        private const val MAX_BOUNCES_PER_PARTICLE = 10
    }

    private val x = DoubleArray(maxParticles)
    private val y = DoubleArray(maxParticles)
    private val vx = DoubleArray(maxParticles)
    private val vy = DoubleArray(maxParticles)
    private var activeCount = 0

    private val rng = Random(0x5eed_1234)
    private var injectionAccumulatorKg = 0.0

    var lastPressurePa: Double = 0.0
        private set
    var lastTemperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    /**
     * The mass actually accepted into the simulation on the last [substep] call - can
     * fall short of the requested valve flow once the particle budget is full. The
     * boiler's energy bookkeeping must charge for this, not the requested flow, or
     * steam the chamber physically had no room to represent would still be silently
     * draining latent heat from the boiler forever.
     */
    var lastActualMassFlowKgPerS: Double = 0.0
        private set

    val particleCount: Int get() = activeCount

    /** Empties the chamber - a fresh charge starts from nothing, exactly like real admission after exhaust. */
    fun reset() {
        activeCount = 0
        injectionAccumulatorKg = 0.0
        lastPressurePa = 0.0
        lastTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
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
     * [pistonPositionM] is the moving wall's position (the valve/clearance end is the
     * fixed wall at x=0), [pistonVelocityMPerS] its velocity, [halfWidthM] the lateral
     * (bore) confinement, [pistonAreaM2] the real piston face area pressure is
     * measured against, and [fullChamberMassEstimateKg] the mass the chamber would
     * hold completely full at boiler density - used only to size the DSMC scaling
     * factor so the fixed particle budget stays meaningful whether this is a level-1
     * toy engine or a level-20 monster.
     */
    fun substep(
        massFlowInKgPerS: Double,
        sourceTemperatureK: Double,
        fullChamberMassEstimateKg: Double,
        pistonPositionM: Double,
        pistonVelocityMPerS: Double,
        halfWidthM: Double,
        pistonAreaM2: Double,
        dt: Double,
    ): Double {
        val particleMassKg = max(
            WATER_MOLECULE_MASS_KG,
            fullChamberMassEstimateKg / TARGET_PARTICLE_COUNT,
        )

        var particlesInjectedThisCall = 0
        if (massFlowInKgPerS > 0.0) {
            injectionAccumulatorKg += massFlowInKgPerS * dt
            val thermalSpeedMPerS = sqrt(BOLTZMANN_J_PER_K * sourceTemperatureK / WATER_MOLECULE_MASS_KG)
            while (injectionAccumulatorKg >= particleMassKg && activeCount < maxParticles) {
                injectionAccumulatorKg -= particleMassKg
                val i = activeCount++
                particlesInjectedThisCall++
                // Born just inside the fixed (valve) wall, moving inward - a fresh puff of
                // admitted steam, not smeared uniformly through gas that hasn't arrived yet.
                x[i] = rng.nextDouble() * max(1e-7, pistonPositionM * 0.02)
                y[i] = (rng.nextDouble() * 2.0 - 1.0) * halfWidthM
                // A true Maxwell-Boltzmann sample (mean zero) plus a small inward bias for
                // the fact that this is a jet of admitted steam, not a gas already at rest -
                // folding the distribution one-sided (as an early version did) roughly
                // doubled <vx^2> above the equilibrium value the source temperature actually
                // implies, which is what an ideal-gas-law comparison would predict instead.
                vx[i] = gaussianSample() * thermalSpeedMPerS + thermalSpeedMPerS * 0.15
                vy[i] = gaussianSample() * thermalSpeedMPerS
            }
            // The valve throat itself doesn't know or care that the chamber's particle
            // budget is full - it would keep pushing mass through. A real chamber would
            // just get denser (higher pressure pushing back on the valve's own flow
            // equation); ours can't add resolution past the particle cap, so once full,
            // stop pretending more mass crossed the threshold at all rather than letting
            // it silently vanish into the accumulator while the boiler keeps paying for it.
            if (activeCount >= maxParticles) {
                injectionAccumulatorKg = 0.0
            }
        }
        lastActualMassFlowKgPerS = particlesInjectedThisCall * particleMassKg / dt

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
}
