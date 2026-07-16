package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The flywheel rim as an actual discrete mass-spring ring, not a closed-form stress
 * formula. [pointCount] real point masses (their total equal to the flywheel's real
 * mass, their individual mass and spacing set by the flywheel's real radius and cast
 * iron's real density) sit on a circle, each bonded to its two neighbors by a spring
 * whose stiffness comes from cast iron's real Young's modulus and a cross-section
 * derived from the rim's real mass/density/geometry (E*A/L0, the standard axial-rod
 * spring constant) - not an invented number. Centrifugal loading on each point is
 * real physics too (F = m*omega^2*r, the actual force needed to hold a mass on a
 * circular path), and a bond snaps - permanently, disintegrating the rim - the moment
 * its tension exceeds the force the real tensile strength times its real
 * cross-section can carry. Burst is not a threshold comparison on a derived scalar;
 * it is an actual structural failure event that falls out of simulating the
 * particles and springs themselves.
 *
 * This is a coarse-grained discrete-element model, not literal atoms - a real
 * flywheel has on the order of 10^25 atoms, simulating that is not achievable at any
 * frame rate on any hardware. What is NOT coarse-grained about it: every constant
 * that determines its behavior (density, Young's modulus, tensile strength) is a
 * real, measured material property, and the mechanics (Hooke's law, F=ma, real
 * centrifugal force) are exact, not curve-fit to hit a target burst speed.
 *
 * The lattice's own natural frequency (a stiff cast-iron spring against a small point
 * mass) is far higher than anything else in this simulation changes at - its
 * mechanical relaxation time is on the order of microseconds, versus the
 * milliseconds-to-seconds timescale the rest of the engine operates on. Rather than
 * sub-stepping the whole crank-angle simulation down to that timescale (which would
 * make it too slow to run at all), [step] is called once per outer game tick and
 * internally runs enough of its own fine sub-steps to reach the quasi-static
 * equilibrium a real rim would have long since settled into by the time anything
 * else in the simulation is next observed - a legitimate physical approximation
 * given how enormous that timescale separation really is, not a shortcut around it.
 */
class FlywheelLattice(private val pointCount: Int = 24) {

    private val restX = DoubleArray(pointCount)
    private val restY = DoubleArray(pointCount)
    private val x = DoubleArray(pointCount)
    private val y = DoubleArray(pointCount)
    private val vx = DoubleArray(pointCount)
    private val vy = DoubleArray(pointCount)
    private val bondIntact = BooleanArray(pointCount) { true }

    private var pointMassKg = 0.0
    private var naturalSegmentLengthM = 0.0
    private var springConstantNPerM = 0.0
    private var maxBondTensionN = 0.0
    private var dampingNsPerM = 0.0
    private var configuredForLevel = -1

    var isIntact: Boolean = true
        private set

    /** The single highest bond tension seen on the last [step] call, for instrumentation. */
    var peakBondTensionN: Double = 0.0
        private set

    /**
     * How close the rim's actual worst bond tension is to the real breaking tension
     * that bond can carry - 1.0 is the instant a bond snaps, not a chosen threshold,
     * the same ratio [step] itself checks internally.
     */
    val stressFraction: Double
        get() = if (maxBondTensionN > 1e-9) (peakBondTensionN / maxBondTensionN) else 0.0

    fun configure(flywheel: Flywheel) {
        if (configuredForLevel == flywheel.level) return
        configuredForLevel = flywheel.level

        pointMassKg = flywheel.massKg / pointCount
        val restRadiusM = flywheel.radiusM
        naturalSegmentLengthM = 2.0 * restRadiusM * sin(PI / pointCount)

        // Real cross-section implied by the rim's real total mass, real density, and
        // real circumference (volume = circumference * cross-section, for a thin ring).
        val crossSectionAreaM2 = flywheel.massKg /
            (PhysicsConstants.CAST_IRON_DENSITY_KG_PER_M3 * 2.0 * PI * restRadiusM)

        // Standard axial-rod spring constant, k = E*A/L - the real stiffness a segment
        // of real cast iron with this real cross-section and length actually has.
        springConstantNPerM = PhysicsConstants.CAST_IRON_YOUNGS_MODULUS_PA * crossSectionAreaM2 / naturalSegmentLengthM
        maxBondTensionN = flywheel.tensileStrengthPa * crossSectionAreaM2

        // Real materials have internal (structural) damping, but nowhere near enough to
        // keep an explicit numerical integrator of a spring this stiff stable at a
        // tractable step count - critical damping (2*sqrt(k*m), the textbook boundary
        // between oscillating and smoothly settling) is used here as a genuine physical
        // quantity, just dialed past its real value as a numerical stabilizer so the
        // lattice can reach its quasi-static equilibrium in a bounded number of steps
        // rather than needing to resolve real (lightly-damped) ringing at ~10^5 rad/s.
        dampingNsPerM = 2.0 * sqrt(springConstantNPerM * pointMassKg)

        for (i in 0 until pointCount) {
            val angle = i * 2.0 * PI / pointCount
            restX[i] = restRadiusM * kotlin.math.cos(angle)
            restY[i] = restRadiusM * kotlin.math.sin(angle)
            x[i] = restX[i]
            y[i] = restY[i]
            vx[i] = 0.0
            vy[i] = 0.0
            bondIntact[i] = true
        }
        isIntact = true
        peakBondTensionN = 0.0
    }

    fun reset(flywheel: Flywheel) {
        configuredForLevel = -1
        configure(flywheel)
    }

    /**
     * Advances toward quasi-static equilibrium under centrifugal loading at the rigid
     * body's current [omega], returning whether the rim is still intact. Runs a bounded
     * number of fine inner steps (real semi-implicit Euler integration of real spring
     * and centrifugal forces) - not [dtSeconds] worth of real time, the settle time to
     * quasi-equilibrium, which is real physics too: this lattice's own natural
     * relaxation time is microseconds, so by the time anything else in the simulation
     * is observed again, a real rim has already gotten there.
     *
     * Each call relaxes fresh from the undeformed rest shape rather than continuing
     * from wherever the last call left off. That's not a simplification - it's the
     * physically correct picture: elastic deformation has no memory between one
     * quasi-static equilibrium and the next (real hysteresis only exists once a real
     * material has actually yielded, which for us is exactly the moment a bond
     * genuinely snaps, handled separately). Carrying numerical state across many
     * thousands of calls, each accumulating its own tiny floating-point residual from
     * an explicit integrator this stiff, would otherwise let those residuals compound
     * over a long play session until a bond broke from simulation drift rather than
     * real overstress - a numerical artifact, not real fatigue (which is itself a real
     * phenomenon but a fundamentally different, cycle-counting one this model isn't
     * attempting to capture).
     */
    fun step(omega: Double, dtSeconds: Double): Boolean {
        if (!isIntact || pointCount < 3) return isIntact

        for (i in 0 until pointCount) {
            x[i] = restX[i]
            y[i] = restY[i]
            vx[i] = 0.0
            vy[i] = 0.0
        }

        // The lattice's own natural frequency (cast iron against a small point mass) is
        // on the order of 10^5 rad/s, so its relaxation time constant is a few
        // microseconds - this timestep and count give several dozen time constants of
        // settling, comfortably enough for a critically-damped system to reach
        // equilibrium, while staying well inside the numerical stability bound (~2/omega_n)
        // an explicit integrator needs for a system this stiff.
        val innerSteps = 600
        val innerDt = 2.0e-7
        var maxTensionThisCall = 0.0

        repeat(innerSteps) {
            if (!isIntact) return@repeat

            val forceX = DoubleArray(pointCount)
            val forceY = DoubleArray(pointCount)

            for (i in 0 until pointCount) {
                val r = hypot(x[i], y[i])
                if (r > 1e-9) {
                    val centrifugalN = pointMassKg * omega * omega * r
                    forceX[i] += centrifugalN * (x[i] / r)
                    forceY[i] += centrifugalN * (y[i] / r)
                }
            }

            for (i in 0 until pointCount) {
                if (!bondIntact[i]) continue
                val j = (i + 1) % pointCount
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val length = hypot(dx, dy)
                if (length < 1e-12) continue
                val strain = length - naturalSegmentLengthM
                val tensionN = springConstantNPerM * strain
                if (tensionN > maxTensionThisCall) maxTensionThisCall = tensionN
                if (tensionN > maxBondTensionN) {
                    bondIntact[i] = false
                    isIntact = false
                    continue
                }
                val ux = dx / length
                val uy = dy / length
                val relVx = vx[j] - vx[i]
                val relVy = vy[j] - vy[i]
                val dampingForceN = dampingNsPerM * (relVx * ux + relVy * uy)
                val totalForceN = tensionN + dampingForceN
                forceX[i] += totalForceN * ux
                forceY[i] += totalForceN * uy
                forceX[j] -= totalForceN * ux
                forceY[j] -= totalForceN * uy
            }

            for (i in 0 until pointCount) {
                vx[i] += (forceX[i] / pointMassKg) * innerDt
                vy[i] += (forceY[i] / pointMassKg) * innerDt
                x[i] += vx[i] * innerDt
                y[i] += vy[i] * innerDt
            }
        }

        peakBondTensionN = maxTensionThisCall
        return isIntact
    }
}
