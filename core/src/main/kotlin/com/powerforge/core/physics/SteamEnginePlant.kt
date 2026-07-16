package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A single-cylinder steam engine driving a flywheel and a DC generator rotor,
 * operated through a full real control panel rather than running on autopilot.
 *
 * The cylinder itself is simulated crank-angle by crank-angle rather than averaged
 * per revolution: [crankAngleRad] and [cylinderPressurePa] are real state, and each
 * substep runs the actual indicator-diagram phases a real slide-valve engine goes
 * through - admission, expansion (PV^k = const past the cutoff angle), exhaust.
 * Instantaneous torque comes from the actual cylinder pressure at the actual crank
 * angle via the connecting-rod-corrected crank-effort formula.
 *
 * Every control has a real actuator behind it, not an instant value change: the
 * continuous valves (throttle, cutoff, fuel, feedwater, air damper, the lubricator,
 * the load rheostat) slew at a bounded rate like a hand-turned wheel, field
 * excitation follows a first-order electrical lag like a winding's inductance
 * resisting a sudden current change, and every switch/lever has real throw latency.
 * [step] advances the commanded->effective actuator state the same way it advances
 * everything else.
 *
 * Mismanaging any control has a real, permanent consequence: overspeed bursts the
 * flywheel, sustained overpressure ruptures the boiler, firing it dry cooks it,
 * neglected bearings seize, and overexcited/overloaded windings burn out. Once
 * [isDamaged] is true the plant is dead until [repair] is called.
 */
class SteamEnginePlant(
    var boiler: Boiler = Boiler(1),
    var piston: PistonAssembly = PistonAssembly(1),
    var flywheel: Flywheel = Flywheel(1),
    var rotor: GeneratorRotor = GeneratorRotor(1),
    var frame: Frame = Frame(1),
) {
    // --- State ---

    var boilerTemperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    var boilerWaterMassKg: Double = boiler.waterCapacityKg
        private set

    /** Mineral scale from untreated water, deposited on the heat-exchange surface while firing. */
    var boilerScalePercent: Double = 0.0
        private set

    var angularVelocityRadPerS: Double = 0.0
        private set

    /** Where the crank actually is right now; starts slightly off dead center like a real parked engine. */
    var crankAngleRad: Double = 0.3
        private set

    /** Real, tracked cylinder pressure - not an average, the value at [crankAngleRad] right now. */
    var cylinderPressurePa: Double = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
        private set

    var rotorWindingTemperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    var lubricationPercent: Double = 100.0
        private set

    var isDamaged: Boolean = false
        private set

    var damageReason: FailureReason = FailureReason.NONE
        private set

    /**
     * The actual cylinder charge, molecule by (representative) molecule. Pressure and
     * temperature are never assumed algebraically here - they fall out of genuinely
     * simulated Maxwell-Boltzmann-sampled particles elastically colliding with the
     * bore wall and the moving piston face. See [KineticCylinderGas] for the full
     * explanation of what is and isn't simplified.
     */
    private val kineticGas = KineticCylinderGas()
    private var previousPhase: CylinderPhase = CylinderPhase.EXHAUST

    private var secondsAtZeroLubricationWhileRunning: Double = 0.0
    private var secondsDryFiring: Double = 0.0

    // --- Operator controls: commanded values (what the player sets) ---

    /** Main steam admission valve: 0 = shut, 1 = wide open. Governs compressible mass flow. */
    var throttleFraction: Double = 1.0

    /** Fraction of the half-revolution (TDC to BDC) steam is admitted for before cutting off to expand. */
    var cutoffFraction: Double = 0.75

    /** Fuel/air valve on the burner: 0 = no fire, 1 = full burn rate. */
    var fuelValveFraction: Double = 1.0

    /** Draft/air damper. Combustion is most efficient near the optimum; too little or too much air both hurt it. */
    var airDamperFraction: Double = 0.65

    /** How hard the feedwater pump is pushing fresh water into the boiler. */
    var feedwaterValveFraction: Double = 1.0

    /** Generator field strength. 1.0 = rated. Above 1.0 trades winding life for more output. */
    var excitationFraction: Double = 1.0

    /** External load resistance the generator is feeding. Lower draws more current (and more torque, and more heat). */
    var loadRheostatOhm: Double = 2.0

    /** Force-feed lubricator drip rate. Needs to roughly keep pace with wear or the bearings dry out. */
    var lubricatorFeedRateFraction: Double = 0.7

    private val ignitionSwitch = LatchedSwitch(true, IGNITION_LATENCY_SECONDS)
    private val safetyValveSwitch = LatchedSwitch(false, RELIEF_VALVE_LATENCY_SECONDS)
    private val clutchSwitch = LatchedSwitch(true, CLUTCH_LATENCY_SECONDS)
    private val emergencyBrakeSwitch = LatchedSwitch(false, BRAKE_LATENCY_SECONDS)
    private val circuitBreakerSwitch = LatchedSwitch(true, BREAKER_LATENCY_SECONDS)
    private val drainCocksSwitch = LatchedSwitch(false, DRAIN_COCKS_LATENCY_SECONDS)
    private val blowdownValveSwitch = LatchedSwitch(false, BLOWDOWN_LATENCY_SECONDS)

    /** Master ignition. When off, no heat is produced regardless of the fuel valve. */
    var ignitionOn: Boolean
        get() = ignitionSwitch.commanded
        set(value) { ignitionSwitch.commanded = value }

    /** Manually bleeds boiler pressure, independent of the automatic safety relief. */
    var safetyValveOpen: Boolean
        get() = safetyValveSwitch.commanded
        set(value) { safetyValveSwitch.commanded = value }

    /** True mechanical clutch: disengaged, the rotor's mass/inertia leaves the shaft entirely. */
    var clutchEngaged: Boolean
        get() = clutchSwitch.commanded
        set(value) { clutchSwitch.commanded = value }

    /** Emergency stop: dumps a large friction torque onto the shaft. */
    var emergencyBrakeEngaged: Boolean
        get() = emergencyBrakeSwitch.commanded
        set(value) { emergencyBrakeSwitch.commanded = value }

    /** Electrical disconnect, separate from the clutch: the rotor keeps spinning, current just stops flowing. */
    var circuitBreakerClosed: Boolean
        get() = circuitBreakerSwitch.commanded
        set(value) { circuitBreakerSwitch.commanded = value }

    /** Cylinder drain cocks. Real practice: open for a cold start to clear condensate, close once warmed up. */
    var drainCocksOpen: Boolean
        get() = drainCocksSwitch.commanded
        set(value) { drainCocksSwitch.commanded = value }

    /** Blows down boiler water to purge mineral scale, at the cost of water and heat while open. */
    var blowdownValveOpen: Boolean
        get() = blowdownValveSwitch.commanded
        set(value) { blowdownValveSwitch.commanded = value }

    // --- Effective values: what the physics actually sees, lagging the commanded ones ---

    private var effectiveThrottleFraction = throttleFraction
    private var effectiveCutoffFraction = cutoffFraction
    private var effectiveFuelValveFraction = fuelValveFraction
    private var effectiveAirDamperFraction = airDamperFraction
    private var effectiveFeedwaterValveFraction = feedwaterValveFraction
    private var effectiveExcitationFraction = excitationFraction
    private var effectiveLoadResistanceOhm = loadRheostatOhm
    private var effectiveLubricatorFeedRateFraction = lubricatorFeedRateFraction

    fun repair() {
        isDamaged = false
        damageReason = FailureReason.NONE
        lubricationPercent = 100.0
        rotorWindingTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
        boilerWaterMassKg = boiler.waterCapacityKg
        boilerScalePercent = 0.0
        boilerTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
        angularVelocityRadPerS = 0.0
        crankAngleRad = 0.3
        cylinderPressurePa = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
        kineticGas.reset()
        previousPhase = CylinderPhase.EXHAUST
        secondsAtZeroLubricationWhileRunning = 0.0
        secondsDryFiring = 0.0
        effectiveThrottleFraction = throttleFraction
        effectiveCutoffFraction = cutoffFraction
        effectiveFuelValveFraction = fuelValveFraction
        effectiveAirDamperFraction = airDamperFraction
        effectiveFeedwaterValveFraction = feedwaterValveFraction
        effectiveExcitationFraction = excitationFraction
        effectiveLoadResistanceOhm = loadRheostatOhm
        effectiveLubricatorFeedRateFraction = lubricatorFeedRateFraction
    }

    /** Fixed shaft stub every generation always has, independent of upgrades. */
    private val shaftMassKg = 0.09
    private val shaftRadiusM = 0.010
    private val shaftMomentOfInertiaKgM2 = 0.5 * shaftMassKg * shaftRadiusM * shaftRadiusM

    private val lubricationDepletionPercentPerSecond = 0.08
    private val lubricatorMaxFeedPercentPerSecond = 0.12
    private val scaleBuildupPercentPerSecond = 0.01
    private val blowdownDescalePercentPerSecond = 3.0
    private val bearingSeizeThresholdSeconds = 90.0
    private val dryFireGraceSeconds = 0.5
    private val emergencyBrakeTorqueNm = 40.0

    /**
     * The real ratio between what the shell actually bursts at and what it's rated to
     * hold: [PhysicsConstants.BOILER_DESIGN_SAFETY_FACTOR] is the real margin design
     * pressure is set below yield stress by, and ultimate tensile strength is itself a
     * real, higher stress than yield - shells don't tear the instant they permanently
     * deform. Multiplying the two gives the true pressure ratio between rated and
     * actually-bursts, derived entirely from real material constants.
     */
    private val boilerRuptureMargin = PhysicsConstants.BOILER_DESIGN_SAFETY_FACTOR *
        (PhysicsConstants.STEEL_ULTIMATE_TENSILE_STRENGTH_PA / PhysicsConstants.STEEL_YIELD_STRENGTH_PA)

    private enum class CylinderPhase { ADMISSION, EXPANSION, EXHAUST }

    private companion object {
        const val VALVE_SLEW_PER_SECOND = 0.5
        const val CUTOFF_SLEW_PER_SECOND = 0.3
        const val LUBRICATOR_SLEW_PER_SECOND = 0.5
        const val LOAD_RHEOSTAT_SLEW_OHM_PER_SECOND = 2.0
        const val EXCITATION_TIME_CONSTANT_SECONDS = 0.4

        const val IGNITION_LATENCY_SECONDS = 0.6
        const val RELIEF_VALVE_LATENCY_SECONDS = 0.3
        const val CLUTCH_LATENCY_SECONDS = 0.4
        const val BRAKE_LATENCY_SECONDS = 0.15
        const val BREAKER_LATENCY_SECONDS = 0.15
        const val DRAIN_COCKS_LATENCY_SECONDS = 0.3
        const val BLOWDOWN_LATENCY_SECONDS = 0.3
    }

    fun rotatingAssemblyMassKg(): Double = flywheel.massKg + rotor.massKg + shaftMassKg

    fun isStructurallyOverloaded(): Boolean =
        rotatingAssemblyMassKg() > frame.maxSupportedRotatingMassKg

    private fun totalMomentOfInertiaKgM2(): Double =
        flywheel.momentOfInertiaKgM2 + shaftMomentOfInertiaKgM2 +
            if (clutchSwitch.effective) rotor.momentOfInertiaKgM2 else 0.0

    private fun coulombFrictionCoefficient(omega: Double): Double {
        val lubeQuality = (lubricationPercent / 100.0).coerceIn(0.05, 1.0)
        val boundaryCoeff = frame.bearingFrictionCoeff * (1.0 + 1.6 * (1.0 - lubeQuality))
        val filmCoeff = frame.bearingFrictionCoeff * (0.35 + 0.55 * (1.0 - lubeQuality))
        val omegaTransition = max(1.0, 25.0 * lubeQuality)
        return filmCoeff + (boundaryCoeff - filmCoeff) * exp(-omega / omegaTransition)
    }

    /**
     * Real aerodynamic drag on the flywheel spinning through the surrounding atmosphere -
     * the standard turbulent free-rotating-disk torque correlation (Daily & Nece):
     * Cm = 0.146 / Re^0.2, torque = 0.5*Cm*rho_air*omega^2*r^5. Vanishingly small at low
     * RPM, genuinely significant at the tip speeds a large, fast flywheel can reach - a
     * real fluid-dynamics effect, not a friction-model addition.
     */
    private fun windageDragTorqueNm(omega: Double): Double {
        if (omega <= 1e-6) return 0.0
        val r = flywheel.radiusM
        val reynolds = max(1.0, omega * r * r / PhysicsConstants.AIR_KINEMATIC_VISCOSITY_M2_PER_S)
        val torqueCoefficient = 0.146 / reynolds.pow(0.2)
        return 0.5 * torqueCoefficient * PhysicsConstants.AIR_DENSITY_KG_PER_M3 * omega * omega * r.pow(5)
    }

    /**
     * Real electromagnetic core losses in the rotor's iron - eddy currents and magnetic
     * hysteresis, both present purely from spinning in an excited field, independent of
     * whether any load current is actually being drawn (that's what makes this distinct
     * from the Lenz's-law braking torque the load circuit produces). Steinmetz-equation
     * loss physics: hysteresis loss is proportional to flux density^1.6 and frequency
     * (so its reaction TORQUE is speed-independent - a real, textbook property of
     * hysteresis loss); eddy current loss is proportional to flux density^2 and
     * frequency^2 (so its torque grows linearly with speed).
     */
    private fun coreLossDragTorqueNm(omega: Double): Double {
        val fluxDensityT = PhysicsConstants.RATED_MAGNETIC_FLUX_DENSITY_T * effectiveExcitationFraction
        val electricalFrequencyPerRad = 1.0 / (2.0 * PI)
        val hysteresisTorqueNm = PhysicsConstants.STEINMETZ_HYSTERESIS_COEFF *
            fluxDensityT.pow(PhysicsConstants.STEINMETZ_HYSTERESIS_EXPONENT) * rotor.massKg * electricalFrequencyPerRad
        val eddyCurrentTorqueNm = PhysicsConstants.STEINMETZ_EDDY_CURRENT_COEFF *
            fluxDensityT * fluxDensityT * rotor.massKg * omega * electricalFrequencyPerRad * electricalFrequencyPerRad
        return hysteresisTorqueNm + eddyCurrentTorqueNm
    }

    /** Combustion is most efficient near a well-tuned air:fuel ratio; too little or too much air both waste heat. */
    private fun combustionEfficiencyFactor(airDamper: Double): Double {
        val optimal = 0.65
        val spread = 0.35
        val deviation = (airDamper - optimal) / spread
        return (1.0 - 0.4 * deviation * deviation).coerceIn(0.3, 1.0)
    }

    /**
     * The piston crown and cylinder head are real steel expanding by real linear thermal
     * expansion as they heat up in service - volume scales as length cubed, so to first
     * order a real solid's volume grows by 3*(alpha*deltaT), closing up a little of the
     * clearance (dead) space at TDC. [boilerTemperatureK] stands in for the metal's own
     * temperature (it's driven by the same steam that's heating the metal), a reasonable
     * proxy given there's no separate metal thermal mass tracked.
     */
    private fun thermallyExpandedClearanceVolumeM3(): Double {
        val deltaT = boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K
        val volumetricExpansion = 3.0 * PhysicsConstants.STEEL_LINEAR_EXPANSION_COEFF_PER_K * deltaT
        return piston.clearanceVolumeM3 * (1.0 - volumetricExpansion)
    }

    /** Cylinder volume at crank angle [theta] via slider-crank kinematics (0 = TDC). */
    private fun cylinderVolumeM3(theta: Double): Double {
        val r = piston.crankRadiusM
        val l = piston.connectingRodLengthM
        val displacementM = r * (1.0 - cos(theta)) + (r * r / (4.0 * l)) * (1.0 - cos(2.0 * theta))
        return thermallyExpandedClearanceVolumeM3() + piston.pistonAreaM2 * displacementM
    }

    /**
     * Advances the simulation by [dtSeconds] of in-game time. Substep size adapts to
     * how fast the crank is turning, so the admission/expansion/exhaust phases are
     * always resolved to roughly 60 slices per revolution regardless of RPM.
     */
    fun step(dtSeconds: Double) {
        val maxSubsteps = 20_000
        // Floored well above the crank-angle-only floor this used before the kinetic gas
        // existed: resolving each slice now costs a real per-particle collision pass, not
        // an O(1) formula, so an unbounded floor at extreme (pre-burst overspeed) RPM would
        // multiply substep count and per-substep cost together. 60 slices/rev is still held
        // at any RPM below ~5000 - well past where every flywheel level bursts anyway.
        val minSubstepSeconds = if (angularVelocityRadPerS > 0.5) {
            (2.0 * PI / angularVelocityRadPerS / 60.0).coerceIn(0.0004, 0.01)
        } else {
            0.01
        }
        val h = max(minSubstepSeconds, dtSeconds / maxSubsteps)
        var remaining = dtSeconds
        while (remaining > 1e-9) {
            val actualH = min(h, remaining)
            integrateSubstep(actualH)
            remaining -= actualH
        }
    }

    private fun updateActuators(dt: Double) {
        effectiveThrottleFraction = approachLinear(effectiveThrottleFraction, throttleFraction.coerceIn(0.0, 1.0), VALVE_SLEW_PER_SECOND, dt)
        effectiveCutoffFraction = approachLinear(effectiveCutoffFraction, cutoffFraction.coerceIn(0.05, 0.98), CUTOFF_SLEW_PER_SECOND, dt)
        effectiveFuelValveFraction = approachLinear(effectiveFuelValveFraction, fuelValveFraction.coerceIn(0.0, 1.0), VALVE_SLEW_PER_SECOND, dt)
        effectiveAirDamperFraction = approachLinear(effectiveAirDamperFraction, airDamperFraction.coerceIn(0.0, 1.0), VALVE_SLEW_PER_SECOND, dt)
        effectiveFeedwaterValveFraction = approachLinear(effectiveFeedwaterValveFraction, feedwaterValveFraction.coerceIn(0.0, 1.0), VALVE_SLEW_PER_SECOND, dt)
        effectiveLubricatorFeedRateFraction = approachLinear(effectiveLubricatorFeedRateFraction, lubricatorFeedRateFraction.coerceIn(0.0, 1.0), LUBRICATOR_SLEW_PER_SECOND, dt)
        effectiveLoadResistanceOhm = approachLinear(effectiveLoadResistanceOhm, loadRheostatOhm.coerceIn(0.5, 6.0), LOAD_RHEOSTAT_SLEW_OHM_PER_SECOND, dt)
        effectiveExcitationFraction = approachExponential(effectiveExcitationFraction, excitationFraction.coerceIn(0.0, 1.5), EXCITATION_TIME_CONSTANT_SECONDS, dt)

        ignitionSwitch.update(dt)
        safetyValveSwitch.update(dt)
        clutchSwitch.update(dt)
        emergencyBrakeSwitch.update(dt)
        circuitBreakerSwitch.update(dt)
        drainCocksSwitch.update(dt)
        blowdownValveSwitch.update(dt)
    }

    private fun integrateSubstep(dt: Double) {
        if (isDamaged) {
            coolDown(dt)
            return
        }

        updateActuators(dt)

        val overloaded = isStructurallyOverloaded()
        val rawSaturationPa = saturationPressurePa(boilerTemperatureK)
        val boilerPressurePa = min(rawSaturationPa, boiler.maxPressurePa)
        val hasWater = boilerWaterMassKg > 1e-6
        val throttleAreaM2 = piston.maxValveAreaM2 * effectiveThrottleFraction
        val cutoffAngleRad = effectiveCutoffFraction * PI

        // Double-acting: steam is admitted alternately on each face of the piston, so every
        // half-revolution is its own fresh admission/expansion/exhaust cycle, mirrored from
        // whichever dead center it just left. localTheta is progress through the current half.
        val theta = crankAngleRad
        val localTheta = if (theta < PI) theta else theta - PI
        val volume = cylinderVolumeM3(localTheta)
        val r = piston.crankRadiusM
        val l = piston.connectingRodLengthM
        // The same bracket term that corrects crank-effort torque for the connecting rod
        // is, by the virtual work principle, exactly dx/dtheta - so multiplying it by the
        // angular velocity already carried into this substep gives the piston's actual
        // linear velocity, which the gas needs to resolve elastic collisions off a moving wall.
        val pistonVelocityMPerS = angularVelocityRadPerS * (r * sin(localTheta) + (r * r / (2.0 * l)) * sin(2.0 * localTheta))
        val phase = when {
            overloaded || !hasWater -> CylinderPhase.EXHAUST
            localTheta < cutoffAngleRad -> CylinderPhase.ADMISSION
            localTheta < PI -> CylinderPhase.EXPANSION
            else -> CylinderPhase.EXHAUST
        }
        if (phase != previousPhase) {
            when (phase) {
                CylinderPhase.ADMISSION, CylinderPhase.EXHAUST -> kineticGas.reset()
                CylinderPhase.EXPANSION -> { /* carry the trapped charge straight into expansion */ }
            }
            previousPhase = phase
        }

        var steamMassFlowIntoCylinderKgPerS = 0.0
        when (phase) {
            CylinderPhase.ADMISSION, CylinderPhase.EXPANSION -> {
                val flow = if (phase == CylinderPhase.ADMISSION) {
                    compressibleMassFlowKgPerS(
                        areaM2 = throttleAreaM2,
                        dischargeCoefficient = 0.85,
                        upstreamPressurePa = boilerPressurePa,
                        upstreamTemperatureK = boilerTemperatureK,
                        downstreamPressurePa = kineticGas.lastPressurePa,
                    )
                } else {
                    0.0 // valve is shut past cutoff - the trapped charge just keeps expanding
                }
                // Sized off the boiler's rated max pressure, not its live (still-warming-up)
                // pressure - a live estimate would lock the particle scaling factor in
                // small during a cold start's low-density transient, and once the particle
                // budget filled up on that stale small charge it could never widen again
                // even after the boiler reached full pressure. The reference temperature
                // paired with that pressure is the real saturation temperature AT that
                // pressure (the same Clausius-Clapeyron relation used for the boiler's own
                // pressure everywhere else) - not an assumed number.
                val referenceTemperatureK = saturationTemperatureK(boiler.maxPressurePa)
                val fullChamberMassEstimateKg = (piston.clearanceVolumeM3 + piston.sweptVolumeM3) *
                    steamDensityKgPerM3(boiler.maxPressurePa, referenceTemperatureK)
                cylinderPressurePa = kineticGas.substep(
                    massFlowInKgPerS = flow,
                    sourceTemperatureK = boilerTemperatureK,
                    sourceDensityKgPerM3 = steamDensityKgPerM3(boilerPressurePa, boilerTemperatureK),
                    fullChamberMassEstimateKg = fullChamberMassEstimateKg,
                    pistonPositionM = volume / piston.pistonAreaM2,
                    pistonVelocityMPerS = pistonVelocityMPerS,
                    halfWidthM = piston.boreRadiusM,
                    pistonAreaM2 = piston.pistonAreaM2,
                    dt = dt,
                )
                // Charge the boiler for what the chamber actually had room to represent,
                // not the theoretical valve throughput - once the particle budget is full
                // the two can diverge, and the energy balance has to follow the real one.
                steamMassFlowIntoCylinderKgPerS = kineticGas.lastActualMassFlowKgPerS
            }
            CylinderPhase.EXHAUST -> {
                cylinderPressurePa = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
            }
        }

        // Open drain cocks bleed most of the working pressure straight to atmosphere instead of
        // doing work - correct procedure for a cold start (clears condensate) but a real loss
        // if left open once the engine is actually running.
        val drainCocksLossFactor = if (drainCocksSwitch.effective) 0.45 else 1.0

        // The far side of the piston is always open to the atmosphere (or to whatever
        // exhaust backpressure the previous stroke left, approximated here as
        // atmospheric) - so it's a real force on every stroke, not just a reference
        // level: when cylinderPressurePa is above atmospheric it's a net outward push,
        // and when the trapped charge over-expands below atmospheric (past where the
        // steam pressure alone would still be doing work) it's a genuine net INWARD pull
        // - real vacuum drag, the atmosphere resisting the piston the same way it resists
        // being over-expanded into on a real engine, not merely "less push."
        val atmosphericAndVacuumForceN = (cylinderPressurePa - PhysicsConstants.ATMOSPHERIC_PRESSURE_PA) * piston.pistonAreaM2

        // Both halves push the crank the same rotational direction (that's the point of
        // double-acting), so the crank-effort kinematics are evaluated on localTheta, not
        // the full angle - otherwise the second half would wrongly compute as reverse torque.
        val netForceN = atmosphericAndVacuumForceN * drainCocksLossFactor
        val drivingTorqueNm = netForceN * (r * sin(localTheta) + (r * r / (2.0 * l)) * sin(2.0 * localTheta))

        // Gravity acting on the piston's real reciprocating mass, converted to crank
        // torque via the same virtual-work kinematics (dx/dtheta) as the steam force -
        // but using the FULL crank angle, not localTheta: unlike the double-acting steam
        // force, gravity doesn't reset every half-stroke, it's the same constant downward
        // pull through the whole revolution. Assumes a vertical cylinder with TDC at the
        // top (the most common compact stationary-engine layout) - real weight, real
        // stroke, a genuine periodic assist-then-resist torque every revolution, the same
        // effect a real crank has to be balanced against.
        val fullThetaBracket = r * sin(theta) + (r * r / (2.0 * l)) * sin(2.0 * theta)
        val gravityTorqueNm = piston.pistonMassKg * PhysicsConstants.GRAVITY_M_PER_S2 * fullThetaBracket

        val muCoulombAtRest = coulombFrictionCoefficient(0.0)
        val normalForceN = rotatingAssemblyMassKg() * PhysicsConstants.GRAVITY_M_PER_S2
        val staticFrictionTorqueNm =
            muCoulombAtRest * normalForceN * frame.bearingRadiusM * PhysicsConstants.STATIC_FRICTION_MULTIPLIER

        val currentFlows = clutchSwitch.effective && circuitBreakerSwitch.effective

        var omega = angularVelocityRadPerS
        omega = when {
            overloaded -> 0.0
            omega <= 1e-6 && drivingTorqueNm + gravityTorqueNm <= staticFrictionTorqueNm -> 0.0
            else -> {
                val muCoulomb = coulombFrictionCoefficient(omega)
                val kineticFrictionTorqueNm = muCoulomb * normalForceN * frame.bearingRadiusM
                val lubeQuality = (lubricationPercent / 100.0).coerceIn(0.05, 1.0)
                val viscousFrictionTorqueNm =
                    frame.viscousFrictionCoeffNmSPerRad * (2.0 - lubeQuality) * omega
                val brakeTorqueNm = if (emergencyBrakeSwitch.effective) emergencyBrakeTorqueNm else 0.0

                // Windage: real aerodynamic drag on the flywheel spinning through the
                // surrounding atmosphere, from the standard turbulent free-rotating-disk
                // torque correlation (Cm = 0.146/Re^0.2, Daily & Nece) - not a friction
                // fudge, a real fluid-dynamics formula using real air density/viscosity.
                val windageTorqueNm = windageDragTorqueNm(omega)

                // Real electromagnetic core losses in the rotor's iron - eddy currents and
                // magnetic hysteresis, both present purely from spinning in a field
                // regardless of whether the breaker is closed or any load current flows
                // (that's what makes them distinct from the Lenz's-law load braking
                // below), using real Steinmetz-coefficient loss physics.
                val coreLossTorqueNm = if (clutchSwitch.effective) coreLossDragTorqueNm(omega) else 0.0

                val generatorLoadTorqueNm = if (currentFlows) {
                    val ke = rotor.backEmfConstantVSPerRad * effectiveExcitationFraction
                    ke * ke * omega / (rotor.internalResistanceOhm + effectiveLoadResistanceOhm)
                } else {
                    0.0
                }

                val netTorqueNm = drivingTorqueNm + gravityTorqueNm - kineticFrictionTorqueNm - viscousFrictionTorqueNm -
                    windageTorqueNm - coreLossTorqueNm - generatorLoadTorqueNm - brakeTorqueNm
                val angularAccelerationRadPerS2 = netTorqueNm / totalMomentOfInertiaKgM2()
                max(0.0, omega + angularAccelerationRadPerS2 * dt)
            }
        }
        angularVelocityRadPerS = omega
        crankAngleRad = (crankAngleRad + omega * dt) % (2.0 * PI)

        // --- Rotor winding thermal balance ---
        val current = if (currentFlows) {
            val ke = rotor.backEmfConstantVSPerRad * effectiveExcitationFraction
            ke * omega / (rotor.internalResistanceOhm + effectiveLoadResistanceOhm)
        } else {
            0.0
        }
        val resistiveHeatingW = current * current * rotor.internalResistanceOhm
        val overExcitation = max(0.0, effectiveExcitationFraction - 1.0)
        val fieldWindingHeatingW = if (currentFlows) (3.0 + rotor.level * 0.5) * overExcitation * overExcitation * 260.0 else 0.0
        val windingCoolingW = (0.8 + 0.01 * omega) * (rotorWindingTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        val netWindingHeatW = resistiveHeatingW + fieldWindingHeatingW - windingCoolingW
        rotorWindingTemperatureK = max(
            PhysicsConstants.AMBIENT_TEMPERATURE_K,
            rotorWindingTemperatureK + netWindingHeatW / rotor.thermalMassJPerK * dt,
        )

        // --- Lubrication: continuous force-feed drip versus continuous wear ---
        if (omega > 1.0) {
            lubricationPercent = (lubricationPercent - lubricationDepletionPercentPerSecond * dt).coerceIn(0.0, 100.0)
        }
        lubricationPercent = (lubricationPercent + effectiveLubricatorFeedRateFraction * lubricatorMaxFeedPercentPerSecond * dt)
            .coerceIn(0.0, 100.0)
        secondsAtZeroLubricationWhileRunning = if (lubricationPercent <= 0.5 && omega > 1.0) {
            secondsAtZeroLubricationWhileRunning + dt
        } else {
            0.0
        }

        // --- Boiler scale: builds up while firing, purged by blowing down ---
        if (ignitionSwitch.effective) {
            boilerScalePercent = (boilerScalePercent + scaleBuildupPercentPerSecond * dt).coerceIn(0.0, 100.0)
        }
        if (blowdownValveSwitch.effective) {
            boilerScalePercent = (boilerScalePercent - blowdownDescalePercentPerSecond * dt).coerceIn(0.0, 100.0)
        }
        val scaleEfficiencyMultiplier = 1.0 - (boilerScalePercent / 100.0) * 0.5

        // --- Boiler mass + energy balance ---
        val heatExtractedByPistonW = steamMassFlowIntoCylinderKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val heatLossToEnvironmentW = boiler.insulationLossWPerK * (boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)

        // The automatic relief valve is a real orifice too, sized to a fraction of what the
        // boiler could generate at full heat input - enough to hold pressure under normal
        // running (where the piston is also drawing steam), but not enough on its own to save
        // a boiler that's fully stalled with the fuel valve left wide open. That's what the
        // manual relief valve and fuel valve are for.
        val maxBoilerGenerationKgPerS = boiler.heatInputW / PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val isVenting = rawSaturationPa > boiler.maxPressurePa
        val automaticVentMassFlowKgPerS = if (isVenting) 0.55 * maxBoilerGenerationKgPerS else 0.0
        val automaticVentLossW = automaticVentMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val manualVentMassFlowKgPerS = if (safetyValveSwitch.effective) 0.7 * maxBoilerGenerationKgPerS else 0.0
        val manualVentLossW = manualVentMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val blowdownMassFlowKgPerS = if (blowdownValveSwitch.effective) 0.3 * maxBoilerGenerationKgPerS else 0.0
        val blowdownLossW = blowdownMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG

        // A real boiler is level-regulated: it never forces in more water than there's room
        // for. Without this cap, an always-open feed valve on an already-full tank would
        // keep cold-shocking the boiler for water that's actually just overflowing away.
        // (Open drain cocks don't add a separate mass loss here: that steam already left the
        // boiler as part of steamMassFlowIntoCylinderKgPerS, it just failed to do useful work -
        // already captured by drainCocksLossFactor knocking down the torque above.)
        val waterOutflowKgPerS = steamMassFlowIntoCylinderKgPerS + automaticVentMassFlowKgPerS +
            manualVentMassFlowKgPerS + blowdownMassFlowKgPerS
        val commandedFeedwaterFlowKgPerS = effectiveFeedwaterValveFraction * boiler.feedwaterMaxFlowKgPerS
        val headroomKg = max(0.0, boiler.waterCapacityKg - boilerWaterMassKg)
        val maxUsefulFeedwaterFlowKgPerS = headroomKg / dt + waterOutflowKgPerS
        val feedwaterMassFlowKgPerS = min(commandedFeedwaterFlowKgPerS, maxUsefulFeedwaterFlowKgPerS)
        val coldFeedwaterHeatSinkW = feedwaterMassFlowKgPerS *
            PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K * max(0.0, boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)

        val effectiveHeatInputW = if (ignitionSwitch.effective) {
            boiler.heatInputW * effectiveFuelValveFraction * combustionEfficiencyFactor(effectiveAirDamperFraction) *
                scaleEfficiencyMultiplier
        } else {
            0.0
        }
        val netHeatW = effectiveHeatInputW - heatExtractedByPistonW - heatLossToEnvironmentW -
            automaticVentLossW - manualVentLossW - blowdownLossW - coldFeedwaterHeatSinkW
        val dTemperatureK = netHeatW / (max(0.005, boilerWaterMassKg) * PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K) * dt
        boilerTemperatureK = max(PhysicsConstants.AMBIENT_TEMPERATURE_K, boilerTemperatureK + dTemperatureK)

        boilerWaterMassKg = (boilerWaterMassKg + (feedwaterMassFlowKgPerS - waterOutflowKgPerS) * dt)
            .coerceIn(0.0, boiler.waterCapacityKg)

        val lowWaterThresholdKg = 0.15 * boiler.waterCapacityKg
        secondsDryFiring = if (boilerWaterMassKg <= lowWaterThresholdKg && ignitionSwitch.effective && effectiveFuelValveFraction > 0.0) {
            secondsDryFiring + dt
        } else {
            0.0
        }

        checkForDamage(rawSaturationPa, omega)
    }

    /**
     * Real centrifugal hoop stress in the spinning rim, the textbook formula for a thin
     * rotating ring: sigma = rho * v_tip^2. Cast iron's real tensile strength is what
     * actually determines the burst speed, not a per-level number - a bigger rim at the
     * same tip speed carries the exact same stress regardless of level.
     */
    private fun flywheelHoopStressPa(omega: Double): Double {
        val tipSpeedMPerS = omega * flywheel.radiusM
        return PhysicsConstants.CAST_IRON_DENSITY_KG_PER_M3 * tipSpeedMPerS * tipSpeedMPerS
    }

    private fun checkForDamage(rawBoilerSaturationPa: Double, omega: Double) {
        when {
            secondsDryFiring > dryFireGraceSeconds ->
                triggerDamage(FailureReason.BOILER_DRY_FIRE)
            rawBoilerSaturationPa > boiler.maxPressurePa * boilerRuptureMargin ->
                triggerDamage(FailureReason.BOILER_RUPTURED)
            flywheelHoopStressPa(omega) > PhysicsConstants.CAST_IRON_TENSILE_STRENGTH_PA ->
                triggerDamage(FailureReason.FLYWHEEL_BURST)
            rotorWindingTemperatureK > rotor.maxWindingTemperatureK ->
                triggerDamage(FailureReason.ROTOR_BURNOUT)
            secondsAtZeroLubricationWhileRunning > bearingSeizeThresholdSeconds ->
                triggerDamage(FailureReason.BEARING_SEIZED)
        }
    }

    private fun triggerDamage(reason: FailureReason) {
        isDamaged = true
        damageReason = reason
        angularVelocityRadPerS = 0.0
    }

    private fun coolDown(dt: Double) {
        val heatLossToEnvironmentW = boiler.insulationLossWPerK * (boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        val dTemperatureK = -heatLossToEnvironmentW / (max(0.005, boilerWaterMassKg) * PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K) * dt
        boilerTemperatureK = max(PhysicsConstants.AMBIENT_TEMPERATURE_K, boilerTemperatureK + dTemperatureK)
        val windingCoolingW = 0.8 * (rotorWindingTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        rotorWindingTemperatureK = max(
            PhysicsConstants.AMBIENT_TEMPERATURE_K,
            rotorWindingTemperatureK - windingCoolingW / rotor.thermalMassJPerK * dt,
        )
    }

    fun electricalPowerW(): Double {
        if (!(clutchSwitch.effective && circuitBreakerSwitch.effective)) return 0.0
        val ke = rotor.backEmfConstantVSPerRad * effectiveExcitationFraction
        val current = ke * angularVelocityRadPerS / (rotor.internalResistanceOhm + effectiveLoadResistanceOhm)
        return current * current * effectiveLoadResistanceOhm
    }

    fun status(): PlantStatus {
        val failureReason = when {
            isDamaged -> damageReason
            isStructurallyOverloaded() -> FailureReason.STRUCTURAL_OVERLOAD
            !ignitionSwitch.effective -> FailureReason.IGNITION_OFF
            angularVelocityRadPerS <= 1e-6 -> FailureReason.STALLED_INSUFFICIENT_TORQUE
            else -> FailureReason.NONE
        }
        val electricalW = electricalPowerW()
        val effectiveHeatInputW = if (ignitionSwitch.effective) {
            boiler.heatInputW * effectiveFuelValveFraction * combustionEfficiencyFactor(effectiveAirDamperFraction) *
                (1.0 - (boilerScalePercent / 100.0) * 0.5)
        } else {
            0.0
        }
        return PlantStatus(
            boilerTemperatureK = boilerTemperatureK,
            boilerPressurePa = min(saturationPressurePa(boilerTemperatureK), boiler.maxPressurePa),
            boilerWaterLevelFraction = (boilerWaterMassKg / boiler.waterCapacityKg).coerceIn(0.0, 1.0),
            boilerScalePercent = boilerScalePercent,
            cylinderPressurePa = cylinderPressurePa,
            crankAngleRad = crankAngleRad,
            angularVelocityRadPerS = angularVelocityRadPerS,
            rpm = angularVelocityRadPerS * 60.0 / (2.0 * PI),
            electricalPowerW = electricalW,
            heatInputW = effectiveHeatInputW,
            overallEfficiency = if (effectiveHeatInputW > 0) electricalW / effectiveHeatInputW else 0.0,
            rotatingAssemblyMassKg = rotatingAssemblyMassKg(),
            maxSupportedRotatingMassKg = frame.maxSupportedRotatingMassKg,
            lubricationPercent = lubricationPercent,
            rotorWindingTemperatureK = rotorWindingTemperatureK,
            generatorEngaged = clutchSwitch.effective,
            circuitBreakerClosed = circuitBreakerSwitch.effective,
            drainCocksOpen = drainCocksSwitch.effective,
            blowdownValveOpen = blowdownValveSwitch.effective,
            isDamaged = isDamaged,
            failureReason = failureReason,
        )
    }
}
