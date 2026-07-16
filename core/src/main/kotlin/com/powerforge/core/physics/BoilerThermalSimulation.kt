package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The boiler's thermal state as real particles, not a bulk energy-balance ODE. Two
 * real populations share one well-mixed reservoir (no walls or ballistic motion
 * tracked - see below for why): representative water-thermal particles (real water
 * molecular mass, Fn-scaled to the boiler's actual current water mass) carry the
 * water's real thermal energy, and a continuously-spawned stream of real combustion
 * flue-gas particles (real flue-gas molecular mass, Maxwell-Boltzmann-sampled at a
 * real flame temperature) delivers real chemical heat by genuinely colliding with
 * them - the same No-Time-Counter (NTC) elastic-collision scheme already used for
 * the cylinder gas, generalized here for particles of different real mass. Boiler
 * temperature is never assumed - it falls out of the water particles' actual mean
 * kinetic energy via equipartition, exactly like the cylinder gas.
 *
 * Why no spatial/ballistic motion, unlike [KineticCylinderGas]: DSMC's free-flight
 * assumption is only valid for a dilute gas, where the mean free path is a real,
 * meaningful distance particles travel between rare collisions. Liquid water has no
 * such thing - molecules are in constant contact. But a real boiler's water is also
 * genuinely convecting (real natural-convection currents driven by the temperature
 * gradient near the heat-exchange surface), which keeps it close to well-mixed in
 * practice - so instead of pretending water molecules fly freely through a vacuum
 * (wrong physics for a liquid), this treats the reservoir the way NTC's underlying
 * assumption already requires: a well-mixed cell where a statistically correct
 * number of pairwise collisions happen per unit time, without needing literal
 * trajectories between them. The flame gas genuinely IS a dilute, free-flowing gas
 * in reality, but at this scale (a shared thermal reservoir, not a duct with a
 * pressure gradient) its own ballistic path doesn't matter either - only how often
 * and how hard it collides with the water particles carrying it away, which NTC
 * already resolves without tracking position.
 *
 * What is NOT particle-simulated: the atmosphere outside the boiler is not itself a
 * tracked particle population (modeling the Earth's atmosphere is not tractable at
 * any scale) - insulation/convective loss to it is still the real Newtonian-cooling
 * rate law, but applied by genuinely removing that much kinetic energy from the
 * water particles themselves, the same way a real molecule at the inner wall
 * surface actually loses energy to a colder surface, rather than being a purely
 * bulk temperature adjustment.
 */
class BoilerThermalSimulation(private val maxParticles: Int = 90, private val targetWaterParticleCount: Int = 55) {

    companion object {
        private const val BOLTZMANN_J_PER_K = KineticCylinderGas.BOLTZMANN_J_PER_K

        /** Real molecular collision diameter, similar order of magnitude to water/N2/CO2 - used only to size how many collisions genuinely happen. */
        private const val COLLISION_DIAMETER_M = 4.0e-10
        private val COLLISION_CROSS_SECTION_M2 = PI * COLLISION_DIAMETER_M * COLLISION_DIAMETER_M

        /** A representative reservoir volume for sizing collision rates - real order of magnitude for a small boiler's water+steam space. */
        private const val RESERVOIR_VOLUME_M3 = 0.01

        /** How many real flame-gas particles we aim to inject per simulated second, which together with the real flame power determines their real Fn scaling. */
        private const val TARGET_FLAME_INJECTIONS_PER_SECOND = 40.0
    }

    private val vx = DoubleArray(maxParticles)
    private val vy = DoubleArray(maxParticles)
    private val isFlame = BooleanArray(maxParticles)
    private val residenceRemainingS = DoubleArray(maxParticles)

    /**
     * Each flame particle's real representative mass, fixed at the moment it's
     * created (unused for water particles - see the class doc for why those instead
     * use a single shared, freshly-recomputed mass every call). A specific packet of
     * flue gas doesn't retroactively change how many real molecules it represents
     * just because the burner's rate was adjusted after it formed, so this has to be
     * stored per-particle rather than recomputed from the current flame power.
     */
    private val flamePointMassKg = DoubleArray(maxParticles)

    private var activeCount = 0

    private var rng = Random(0x50117_b01)
    private var flameEnergyAccumulatorJ = 0.0
    private var relativeSpeedMaxEstimateMPerS = ambientRelativeSpeedEstimate()
    private var pendingCollisionCandidates = 0.0

    var temperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    init {
        seedWaterParticles(PhysicsConstants.AMBIENT_TEMPERATURE_K)
    }

    private fun ambientRelativeSpeedEstimate(): Double =
        sqrt(2.0) * sqrt(BOLTZMANN_J_PER_K * PhysicsConstants.AMBIENT_TEMPERATURE_K / KineticCylinderGas.WATER_MOLECULE_MASS_KG)

    private fun seedWaterParticles(atTemperatureK: Double) {
        activeCount = targetWaterParticleCount.coerceAtMost(maxParticles)
        val thermalSpeed = sqrt(BOLTZMANN_J_PER_K * atTemperatureK / KineticCylinderGas.WATER_MOLECULE_MASS_KG)
        for (i in 0 until activeCount) {
            isFlame[i] = false
            residenceRemainingS[i] = 0.0
            vx[i] = gaussianSample() * thermalSpeed
            vy[i] = gaussianSample() * thermalSpeed
        }
        temperatureK = atTemperatureK
    }

    /**
     * A repaired/restarted engine is meant to behave exactly like a freshly built one -
     * a real cold restart has no memory of the previous run, and neither should the
     * random sampling that stands in for real molecular chaos. Re-seeding here is what
     * actually makes that true: an RNG left running would keep drawing from wherever
     * thousands of prior substeps left it, a pure bookkeeping artifact with no physical
     * basis for a real restart.
     */
    fun reset() {
        flameEnergyAccumulatorJ = 0.0
        pendingCollisionCandidates = 0.0
        relativeSpeedMaxEstimateMPerS = ambientRelativeSpeedEstimate()
        rng = Random(0x50117_b01)
        seedWaterParticles(PhysicsConstants.AMBIENT_TEMPERATURE_K)
    }

    private fun gaussianSample(): Double {
        val u1 = max(1e-12, rng.nextDouble())
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Real per-particle mass so the fixed water-particle count always sums to the boiler's actual current water mass. */
    private fun waterPointMassKg(waterMassKg: Double): Double {
        val realMoleculesPerParticle = max(1.0, waterMassKg / targetWaterParticleCount / KineticCylinderGas.WATER_MOLECULE_MASS_KG)
        return realMoleculesPerParticle * KineticCylinderGas.WATER_MOLECULE_MASS_KG
    }

    private fun massKgAt(index: Int, waterPointMassKg: Double): Double =
        if (isFlame[index]) flamePointMassKg[index] else waterPointMassKg

    /**
     * Advances the boiler's thermal state by [dt]. [flamePowerW] is the real chemical
     * heat release rate (the burner's actual output right now); [waterMassKg] the
     * boiler's actual current water mass (its Fn-scaling is recomputed fresh each call
     * from this, exactly like the cylinder gas does for the cylinder's mass, so it
     * stays consistent as feedwater/venting/blowdown change how much water is
     * actually in there); [insulationLossW] the real Newtonian-cooling-rate loss to
     * the environment. Returns the real temperature that results.
     */
    fun step(flamePowerW: Double, waterMassKg: Double, insulationLossW: Double, dt: Double): Double {
        val waterPointMassKg = waterPointMassKg(waterMassKg)

        // Any flame particle displaced before a normal collision got to it - either
        // because its real residence time ran out, or because the particle budget was
        // full and a new one had to take its slot - still hands over whatever heat it
        // was carrying above the water's temperature before it's gone. See the two
        // sites below that add to this.
        var flameEnergyReturnedOnEvictionJ = 0.0

        // The real Fn for a newly-created flame particle is sized so a real flame at
        // the CURRENT flamePowerW injects at a real, computationally reasonable rate
        // - not an arbitrary number, a consequence of picking how many particles per
        // second we can afford and solving for how many real molecules each one has
        // to represent.
        if (flamePowerW > 0.0) {
            flameEnergyAccumulatorJ += flamePowerW * dt
            val flameThermalSpeed = sqrt(BOLTZMANN_J_PER_K * PhysicsConstants.FLAME_TEMPERATURE_K / PhysicsConstants.FLUE_GAS_MOLECULE_MASS_KG)
            val realMoleculesPerFlameParticle = flamePowerW /
                (TARGET_FLAME_INJECTIONS_PER_SECOND * BOLTZMANN_J_PER_K * PhysicsConstants.FLAME_TEMPERATURE_K)
            val newFlamePointMassKg = PhysicsConstants.FLUE_GAS_MOLECULE_MASS_KG * realMoleculesPerFlameParticle
            val flameParticleEnergyContentJ = realMoleculesPerFlameParticle * BOLTZMANN_J_PER_K * PhysicsConstants.FLAME_TEMPERATURE_K
            while (flameEnergyAccumulatorJ >= flameParticleEnergyContentJ) {
                flameEnergyAccumulatorJ -= flameParticleEnergyContentJ
                // Once the particle budget is genuinely full, real flue gas would just
                // keep flowing through and exiting immediately - representable by
                // evicting the flame particle closest to exiting anyway in favor of
                // the new one. That displaced particle is exiting early, exactly like
                // the ones that reach the end of their residence time naturally, so it
                // gets the same real treatment: whatever heat it's still carrying
                // above the water's temperature transfers in before it's gone, not
                // silently discarded.
                val i = if (activeCount < maxParticles) {
                    activeCount++
                } else {
                    var oldestFlameIndex = -1
                    var oldestRemaining = Double.MAX_VALUE
                    for (p in 0 until activeCount) {
                        if (isFlame[p] && residenceRemainingS[p] < oldestRemaining) {
                            oldestRemaining = residenceRemainingS[p]
                            oldestFlameIndex = p
                        }
                    }
                    if (oldestFlameIndex < 0) break
                    flameEnergyReturnedOnEvictionJ += 0.5 * flamePointMassKg[oldestFlameIndex] *
                        (vx[oldestFlameIndex] * vx[oldestFlameIndex] + vy[oldestFlameIndex] * vy[oldestFlameIndex])
                    oldestFlameIndex
                }
                isFlame[i] = true
                residenceRemainingS[i] = PhysicsConstants.FLUE_GAS_RESIDENCE_TIME_SECONDS
                flamePointMassKg[i] = newFlamePointMassKg
                vx[i] = gaussianSample() * flameThermalSpeed
                vy[i] = gaussianSample() * flameThermalSpeed
            }
        }

        // Flue gas that's done its real dwell time exits up the stack. A real boiler's
        // heat-exchange surface is sized to extract nearly all of the gas's heat
        // before it can escape (stack losses in a well-designed small boiler are a
        // real but modest effect, not the dominant one) - so right as a particle
        // expires, whatever heat it's still carrying above the water's own
        // temperature transfers into the water in one final exchange, representing
        // that last pass across the heat-exchange surface, rather than that energy
        // just vanishing because this particle happened to be evicted before an
        // ordinary random collision got to it. Ordinary collisions during its
        // lifetime already did most of the real transferring; this only catches
        // whatever a particle didn't get to lose collision-by-collision in time,
        // exactly the role a real counter-flow heat exchanger's last section plays.
        var i = 0
        while (i < activeCount) {
            if (isFlame[i]) {
                residenceRemainingS[i] -= dt
                if (residenceRemainingS[i] <= 0.0) {
                    flameEnergyReturnedOnEvictionJ += 0.5 * flamePointMassKg[i] * (vx[i] * vx[i] + vy[i] * vy[i])
                    val last = activeCount - 1
                    vx[i] = vx[last]
                    vy[i] = vy[last]
                    isFlame[i] = isFlame[last]
                    residenceRemainingS[i] = residenceRemainingS[last]
                    flamePointMassKg[i] = flamePointMassKg[last]
                    activeCount--
                    continue
                }
            }
            i++
        }

        collide(waterPointMassKg, dt)

        // Real conductive/convective exchange with the environment, applied by
        // genuinely adding or removing that much kinetic energy from the water
        // particles - the real mechanism by which a real molecule at the inner wall
        // surface actually exchanges energy with a boundary, not a separate bulk
        // temperature nudge. This has to work in both directions: [insulationLossW]
        // is Newton's-law-of-cooling, which genuinely goes negative (net heat GAIN
        // from a warmer environment) whenever the water is momentarily below ambient
        // - real evaporative draw can do exactly that, and without a symmetric gain
        // term here there would be nothing to pull the temperature back up once it
        // undershot, an unphysical one-way ratchet toward absolute zero.
        if (activeCount > 0) {
            val energyDeltaJ = insulationLossW * dt - flameEnergyReturnedOnEvictionJ
            var totalWaterKineticEnergyJ = 0.0
            for (p in 0 until activeCount) {
                if (!isFlame[p]) totalWaterKineticEnergyJ += 0.5 * waterPointMassKg * (vx[p] * vx[p] + vy[p] * vy[p])
            }
            val newEnergyJ = max(0.0, totalWaterKineticEnergyJ - energyDeltaJ)
            if (totalWaterKineticEnergyJ > 1e-12) {
                val velocityScale = sqrt(newEnergyJ / totalWaterKineticEnergyJ)
                for (p in 0 until activeCount) {
                    if (!isFlame[p]) {
                        vx[p] *= velocityScale
                        vy[p] *= velocityScale
                    }
                }
            } else if (newEnergyJ > 1e-12) {
                // Particles are exactly at rest but energy needs to flow in - scaling
                // zero velocity by anything is still zero, so this one case needs a
                // direct kick: a fresh Maxwell-Boltzmann sample at whatever
                // temperature makes the water particles' total kinetic energy
                // genuinely equal newEnergyJ. totalWaterKineticEnergyJ = (real
                // molecule count) * k * T for this 2D equipartition convention, so T
                // has to be solved for using the real molecule count the water
                // particles represent, not just how many simulated particles there are.
                val realMoleculesPerWaterParticle = waterPointMassKg / KineticCylinderGas.WATER_MOLECULE_MASS_KG
                val realMoleculeCount = targetWaterParticleCount.toDouble() * realMoleculesPerWaterParticle
                val impliedTemperatureK = newEnergyJ / (realMoleculeCount * BOLTZMANN_J_PER_K)
                val thermalSpeed = sqrt(BOLTZMANN_J_PER_K * impliedTemperatureK / KineticCylinderGas.WATER_MOLECULE_MASS_KG)
                for (p in 0 until activeCount) {
                    if (!isFlame[p]) {
                        vx[p] = gaussianSample() * thermalSpeed
                        vy[p] = gaussianSample() * thermalSpeed
                    }
                }
            }
        }

        // Temperature from the water particles' real mean kinetic energy per real
        // molecule (2D equipartition: <KE> = kT), not assumed - each representative
        // particle's KE divided by how many real molecules it stands in for gives a
        // genuine per-molecule energy, exactly like the cylinder gas's calculation.
        var totalRealMoleculeKineticEnergyJ = 0.0
        var totalRealMolecules = 0.0
        for (p in 0 until activeCount) {
            if (!isFlame[p]) {
                val realMolecules = waterPointMassKg / KineticCylinderGas.WATER_MOLECULE_MASS_KG
                val particleKineticEnergyJ = 0.5 * waterPointMassKg * (vx[p] * vx[p] + vy[p] * vy[p])
                totalRealMoleculeKineticEnergyJ += particleKineticEnergyJ / realMolecules
                totalRealMolecules += 1.0
            }
        }
        temperatureK = if (totalRealMolecules > 0.0) {
            (totalRealMoleculeKineticEnergyJ / totalRealMolecules) / BOLTZMANN_J_PER_K
        } else {
            PhysicsConstants.AMBIENT_TEMPERATURE_K
        }
        return temperatureK
    }

    /**
     * Real molecule-on-molecule collisions between whichever particles (flame or
     * water) happen to be paired, via the same NTC scheme as [KineticCylinderGas],
     * generalized for particles of different real mass: elastic collision in the
     * center-of-mass frame, relative speed preserved, direction randomized, momentum
     * and kinetic energy both held exactly conserved regardless of the mass ratio.
     */
    private fun collide(waterPointMassKg: Double, dt: Double) {
        val n = activeCount
        if (n < 2) return

        val realMoleculesPerWaterParticle = waterPointMassKg / KineticCylinderGas.WATER_MOLECULE_MASS_KG
        val expectedCandidates = 0.5 * n * (n - 1) * realMoleculesPerWaterParticle *
            COLLISION_CROSS_SECTION_M2 * relativeSpeedMaxEstimateMPerS * dt / RESERVOIR_VOLUME_M3
        pendingCollisionCandidates += expectedCandidates
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

            val mi = massKgAt(i, waterPointMassKg)
            val mj = massKgAt(j, waterPointMassKg)

            val relVx = vx[i] - vx[j]
            val relVy = vy[i] - vy[j]
            val relSpeed = sqrt(relVx * relVx + relVy * relVy)
            if (relSpeed > relativeSpeedMaxEstimateMPerS) relativeSpeedMaxEstimateMPerS = relSpeed
            if (relSpeed <= 1e-9) return@repeat
            if (rng.nextDouble() > relSpeed / relativeSpeedMaxEstimateMPerS) return@repeat

            val totalMass = mi + mj
            val comVx = (mi * vx[i] + mj * vx[j]) / totalMass
            val comVy = (mi * vy[i] + mj * vy[j]) / totalMass
            val newAngle = 2.0 * PI * rng.nextDouble()
            val newRelVx = relSpeed * cos(newAngle)
            val newRelVy = relSpeed * sin(newAngle)
            vx[i] = comVx + (mj / totalMass) * newRelVx
            vy[i] = comVy + (mj / totalMass) * newRelVy
            vx[j] = comVx - (mi / totalMass) * newRelVx
            vy[j] = comVy - (mi / totalMass) * newRelVy
        }
    }
}
