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
 * through - admission (steam flows in through the throttle valve and fills the
 * cylinder per the ideal gas law), expansion (valve shuts at the cutoff angle and
 * the trapped steam expands adiabatically, PV^k = const), and exhaust (the cylinder
 * vents to backpressure). Instantaneous torque comes from the actual cylinder
 * pressure at the actual crank angle via the connecting-rod-corrected crank-effort
 * formula, not an averaged mean-effective-pressure shortcut - so effects like
 * over-expansion (cutting steam off so early the pressure drops below atmospheric
 * before bottom dead center, dragging the piston) fall out on their own.
 *
 * Everything else - boiler temperature and water mass, drivetrain angular velocity,
 * rotor winding temperature, bearing lubrication - is integrated the same way.
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

    private var cylinderSteamMassKg: Double = 0.0
    private var cutoffReferencePressurePa: Double = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
    private var cutoffReferenceVolumeM3: Double = 0.0
    private var previousPhase: CylinderPhase = CylinderPhase.EXHAUST

    private var secondsAtZeroLubricationWhileRunning: Double = 0.0
    private var secondsDryFiring: Double = 0.0

    // --- Operator controls: the full panel, always present ---

    /** Main steam admission valve: 0 = shut, 1 = wide open. Governs compressible mass flow. */
    var throttleFraction: Double = 1.0

    /** Fraction of the half-revolution (TDC to BDC) steam is admitted for before cutting off to expand. */
    var cutoffFraction: Double = 0.75

    /** Fuel/air valve on the burner: 0 = no fire, 1 = full burn rate. */
    var fuelValveFraction: Double = 1.0

    /** Master ignition. When off, no heat is produced regardless of the fuel valve. */
    var ignitionOn: Boolean = true

    /** How hard the feedwater pump is pushing fresh water into the boiler. */
    var feedwaterValveFraction: Double = 1.0

    /** Manually bleeds boiler pressure, independent of the automatic safety relief. */
    var safetyValveOpen: Boolean = false

    /** Generator field strength. 1.0 = rated. Above 1.0 trades winding life for more output. */
    var excitationFraction: Double = 1.0

    /** True mechanical clutch: disengaged, the rotor's mass/inertia leaves the shaft entirely. */
    var clutchEngaged: Boolean = true

    /** Emergency stop: dumps a large friction torque onto the shaft. */
    var emergencyBrakeEngaged: Boolean = false

    fun addLubrication(amount: Double = 100.0) {
        lubricationPercent = (lubricationPercent + amount).coerceIn(0.0, 100.0)
    }

    fun repair() {
        isDamaged = false
        damageReason = FailureReason.NONE
        lubricationPercent = 100.0
        rotorWindingTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
        boilerWaterMassKg = boiler.waterCapacityKg
        boilerTemperatureK = PhysicsConstants.AMBIENT_TEMPERATURE_K
        angularVelocityRadPerS = 0.0
        crankAngleRad = 0.3
        cylinderPressurePa = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
        cylinderSteamMassKg = 0.0
        previousPhase = CylinderPhase.EXHAUST
        secondsAtZeroLubricationWhileRunning = 0.0
        secondsDryFiring = 0.0
    }

    /** Fixed shaft stub every generation always has, independent of upgrades. */
    private val shaftMassKg = 0.09
    private val shaftRadiusM = 0.010
    private val shaftMomentOfInertiaKgM2 = 0.5 * shaftMassKg * shaftRadiusM * shaftRadiusM

    /** Resistance of whatever the plant is feeding power into (battery bank / local grid). */
    private val loadResistanceOhm = 2.0

    private val lubricationDepletionPercentPerSecond = 0.08
    private val bearingSeizeThresholdSeconds = 90.0
    private val dryFireGraceSeconds = 0.5
    private val boilerRuptureMargin = 1.5
    private val emergencyBrakeTorqueNm = 40.0

    private enum class CylinderPhase { ADMISSION, EXPANSION, EXHAUST }

    fun rotatingAssemblyMassKg(): Double = flywheel.massKg + rotor.massKg + shaftMassKg

    fun isStructurallyOverloaded(): Boolean =
        rotatingAssemblyMassKg() > frame.maxSupportedRotatingMassKg

    private fun totalMomentOfInertiaKgM2(): Double =
        flywheel.momentOfInertiaKgM2 + shaftMomentOfInertiaKgM2 +
            if (clutchEngaged) rotor.momentOfInertiaKgM2 else 0.0

    private fun coulombFrictionCoefficient(omega: Double): Double {
        val lubeQuality = (lubricationPercent / 100.0).coerceIn(0.05, 1.0)
        val boundaryCoeff = frame.bearingFrictionCoeff * (1.0 + 1.6 * (1.0 - lubeQuality))
        val filmCoeff = frame.bearingFrictionCoeff * (0.35 + 0.55 * (1.0 - lubeQuality))
        val omegaTransition = max(1.0, 25.0 * lubeQuality)
        return filmCoeff + (boundaryCoeff - filmCoeff) * exp(-omega / omegaTransition)
    }

    /** Cylinder volume at crank angle [theta] via slider-crank kinematics (0 = TDC). */
    private fun cylinderVolumeM3(theta: Double): Double {
        val r = piston.crankRadiusM
        val l = piston.connectingRodLengthM
        val displacementM = r * (1.0 - cos(theta)) + (r * r / (4.0 * l)) * (1.0 - cos(2.0 * theta))
        return piston.clearanceVolumeM3 + piston.pistonAreaM2 * displacementM
    }

    /**
     * Advances the simulation by [dtSeconds] of in-game time. Substep size adapts to
     * how fast the crank is turning, so the admission/expansion/exhaust phases are
     * always resolved to roughly 60 slices per revolution regardless of RPM.
     */
    fun step(dtSeconds: Double) {
        val maxSubsteps = 20_000
        val minSubstepSeconds = if (angularVelocityRadPerS > 0.5) {
            (2.0 * PI / angularVelocityRadPerS / 60.0).coerceIn(0.00005, 0.01)
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

    private fun integrateSubstep(dt: Double) {
        if (isDamaged) {
            coolDown(dt)
            return
        }

        val overloaded = isStructurallyOverloaded()
        val rawSaturationPa = saturationPressurePa(boilerTemperatureK)
        val boilerPressurePa = min(rawSaturationPa, boiler.maxPressurePa)
        val hasWater = boilerWaterMassKg > 1e-6
        val throttleAreaM2 = piston.maxValveAreaM2 * throttleFraction.coerceIn(0.0, 1.0)
        val cutoffAngleRad = cutoffFraction.coerceIn(0.05, 0.98) * PI

        // Double-acting: steam is admitted alternately on each face of the piston, so every
        // half-revolution is its own fresh admission/expansion/exhaust cycle, mirrored from
        // whichever dead center it just left. localTheta is progress through the current half.
        val theta = crankAngleRad
        val localTheta = if (theta < PI) theta else theta - PI
        val volume = cylinderVolumeM3(localTheta)
        val phase = when {
            overloaded || !hasWater -> CylinderPhase.EXHAUST
            localTheta < cutoffAngleRad -> CylinderPhase.ADMISSION
            localTheta < PI -> CylinderPhase.EXPANSION
            else -> CylinderPhase.EXHAUST
        }
        if (phase != previousPhase) {
            when (phase) {
                CylinderPhase.EXPANSION -> {
                    cutoffReferencePressurePa = cylinderPressurePa
                    cutoffReferenceVolumeM3 = volume
                }
                CylinderPhase.EXHAUST -> {
                    cylinderSteamMassKg = 0.0
                }
                CylinderPhase.ADMISSION -> {
                    cylinderSteamMassKg = 0.0
                }
            }
            previousPhase = phase
        }

        var steamMassFlowIntoCylinderKgPerS = 0.0
        when (phase) {
            CylinderPhase.ADMISSION -> {
                val flow = compressibleMassFlowKgPerS(
                    areaM2 = throttleAreaM2,
                    dischargeCoefficient = 0.85,
                    upstreamPressurePa = boilerPressurePa,
                    upstreamTemperatureK = boilerTemperatureK,
                    downstreamPressurePa = cylinderPressurePa,
                )
                cylinderSteamMassKg += flow * dt
                cylinderPressurePa = min(
                    boilerPressurePa,
                    cylinderSteamMassKg * PhysicsConstants.STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K *
                        boilerTemperatureK / volume,
                )
                steamMassFlowIntoCylinderKgPerS = flow
            }
            CylinderPhase.EXPANSION -> {
                cylinderPressurePa = cutoffReferencePressurePa *
                    (cutoffReferenceVolumeM3 / volume).pow(STEAM_SPECIFIC_HEAT_RATIO)
            }
            CylinderPhase.EXHAUST -> {
                cylinderPressurePa = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
            }
        }

        // Both halves push the crank the same rotational direction (that's the point of
        // double-acting), so the crank-effort kinematics are evaluated on localTheta, not
        // the full angle - otherwise the second half would wrongly compute as reverse torque.
        val netForceN = (cylinderPressurePa - PhysicsConstants.ATMOSPHERIC_PRESSURE_PA) * piston.pistonAreaM2
        val r = piston.crankRadiusM
        val l = piston.connectingRodLengthM
        val drivingTorqueNm = netForceN * (r * sin(localTheta) + (r * r / (2.0 * l)) * sin(2.0 * localTheta))

        val muCoulombAtRest = coulombFrictionCoefficient(0.0)
        val normalForceN = rotatingAssemblyMassKg() * PhysicsConstants.GRAVITY_M_PER_S2
        val staticFrictionTorqueNm =
            muCoulombAtRest * normalForceN * frame.bearingRadiusM * PhysicsConstants.STATIC_FRICTION_MULTIPLIER

        var omega = angularVelocityRadPerS
        omega = when {
            overloaded -> 0.0
            omega <= 1e-6 && drivingTorqueNm <= staticFrictionTorqueNm -> 0.0
            else -> {
                val muCoulomb = coulombFrictionCoefficient(omega)
                val kineticFrictionTorqueNm = muCoulomb * normalForceN * frame.bearingRadiusM
                val lubeQuality = (lubricationPercent / 100.0).coerceIn(0.05, 1.0)
                val viscousFrictionTorqueNm =
                    frame.viscousFrictionCoeffNmSPerRad * (2.0 - lubeQuality) * omega
                val brakeTorqueNm = if (emergencyBrakeEngaged) emergencyBrakeTorqueNm else 0.0

                val generatorLoadTorqueNm = if (clutchEngaged) {
                    val ke = rotor.backEmfConstantVSPerRad * excitationFraction.coerceIn(0.0, 1.5)
                    ke * ke * omega / (rotor.internalResistanceOhm + loadResistanceOhm)
                } else {
                    0.0
                }

                val netTorqueNm = drivingTorqueNm - kineticFrictionTorqueNm - viscousFrictionTorqueNm -
                    generatorLoadTorqueNm - brakeTorqueNm
                val angularAccelerationRadPerS2 = netTorqueNm / totalMomentOfInertiaKgM2()
                max(0.0, omega + angularAccelerationRadPerS2 * dt)
            }
        }
        angularVelocityRadPerS = omega
        crankAngleRad = (crankAngleRad + omega * dt) % (2.0 * PI)

        // --- Rotor winding thermal balance ---
        val current = if (clutchEngaged) {
            val ke = rotor.backEmfConstantVSPerRad * excitationFraction.coerceIn(0.0, 1.5)
            ke * omega / (rotor.internalResistanceOhm + loadResistanceOhm)
        } else {
            0.0
        }
        val resistiveHeatingW = current * current * rotor.internalResistanceOhm
        val overExcitation = max(0.0, excitationFraction - 1.0)
        val fieldWindingHeatingW = if (clutchEngaged) (3.0 + rotor.level * 0.5) * overExcitation * overExcitation * 220.0 else 0.0
        val windingCoolingW = (0.8 + 0.01 * omega) * (rotorWindingTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        val netWindingHeatW = resistiveHeatingW + fieldWindingHeatingW - windingCoolingW
        rotorWindingTemperatureK = max(
            PhysicsConstants.AMBIENT_TEMPERATURE_K,
            rotorWindingTemperatureK + netWindingHeatW / rotor.thermalMassJPerK * dt,
        )

        // --- Lubrication wear ---
        if (omega > 1.0) {
            lubricationPercent = (lubricationPercent - lubricationDepletionPercentPerSecond * dt).coerceIn(0.0, 100.0)
        }
        secondsAtZeroLubricationWhileRunning = if (lubricationPercent <= 0.5 && omega > 1.0) {
            secondsAtZeroLubricationWhileRunning + dt
        } else {
            0.0
        }

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
        val manualVentMassFlowKgPerS = if (safetyValveOpen) 0.7 * maxBoilerGenerationKgPerS else 0.0
        val manualVentLossW = manualVentMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG

        // A real boiler is level-regulated: it never forces in more water than there's room
        // for. Without this cap, an always-open feed valve on an already-full tank would
        // keep cold-shocking the boiler for water that's actually just overflowing away.
        val waterOutflowKgPerS = steamMassFlowIntoCylinderKgPerS + automaticVentMassFlowKgPerS + manualVentMassFlowKgPerS
        val commandedFeedwaterFlowKgPerS = feedwaterValveFraction.coerceIn(0.0, 1.0) * boiler.feedwaterMaxFlowKgPerS
        val headroomKg = max(0.0, boiler.waterCapacityKg - boilerWaterMassKg)
        val maxUsefulFeedwaterFlowKgPerS = headroomKg / dt + waterOutflowKgPerS
        val feedwaterMassFlowKgPerS = min(commandedFeedwaterFlowKgPerS, maxUsefulFeedwaterFlowKgPerS)
        val coldFeedwaterHeatSinkW = feedwaterMassFlowKgPerS *
            PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K * max(0.0, boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)

        val effectiveHeatInputW = if (ignitionOn) boiler.heatInputW * fuelValveFraction.coerceIn(0.0, 1.0) else 0.0
        val netHeatW = effectiveHeatInputW - heatExtractedByPistonW - heatLossToEnvironmentW -
            automaticVentLossW - manualVentLossW - coldFeedwaterHeatSinkW
        val dTemperatureK = netHeatW / (max(0.005, boilerWaterMassKg) * PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K) * dt
        boilerTemperatureK = max(PhysicsConstants.AMBIENT_TEMPERATURE_K, boilerTemperatureK + dTemperatureK)

        boilerWaterMassKg = (boilerWaterMassKg + (feedwaterMassFlowKgPerS - waterOutflowKgPerS) * dt)
            .coerceIn(0.0, boiler.waterCapacityKg)

        val lowWaterThresholdKg = 0.15 * boiler.waterCapacityKg
        secondsDryFiring = if (boilerWaterMassKg <= lowWaterThresholdKg && ignitionOn && fuelValveFraction > 0.0) {
            secondsDryFiring + dt
        } else {
            0.0
        }

        checkForDamage(rawSaturationPa, omega)
    }

    private fun checkForDamage(rawBoilerSaturationPa: Double, omega: Double) {
        val tipSpeedMPerS = omega * flywheel.radiusM
        when {
            secondsDryFiring > dryFireGraceSeconds ->
                triggerDamage(FailureReason.BOILER_DRY_FIRE)
            rawBoilerSaturationPa > boiler.maxPressurePa * boilerRuptureMargin ->
                triggerDamage(FailureReason.BOILER_RUPTURED)
            tipSpeedMPerS > flywheel.maxSafeTipSpeedMPerS ->
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
        if (!clutchEngaged) return 0.0
        val ke = rotor.backEmfConstantVSPerRad * excitationFraction.coerceIn(0.0, 1.5)
        val current = ke * angularVelocityRadPerS / (rotor.internalResistanceOhm + loadResistanceOhm)
        return current * current * loadResistanceOhm
    }

    fun status(): PlantStatus {
        val failureReason = when {
            isDamaged -> damageReason
            isStructurallyOverloaded() -> FailureReason.STRUCTURAL_OVERLOAD
            !ignitionOn -> FailureReason.IGNITION_OFF
            angularVelocityRadPerS <= 1e-6 -> FailureReason.STALLED_INSUFFICIENT_TORQUE
            else -> FailureReason.NONE
        }
        val electricalW = electricalPowerW()
        val effectiveHeatInputW = if (ignitionOn) boiler.heatInputW * fuelValveFraction.coerceIn(0.0, 1.0) else 0.0
        return PlantStatus(
            boilerTemperatureK = boilerTemperatureK,
            boilerPressurePa = min(saturationPressurePa(boilerTemperatureK), boiler.maxPressurePa),
            boilerWaterLevelFraction = (boilerWaterMassKg / boiler.waterCapacityKg).coerceIn(0.0, 1.0),
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
            generatorEngaged = clutchEngaged,
            isDamaged = isDamaged,
            failureReason = failureReason,
        )
    }
}
