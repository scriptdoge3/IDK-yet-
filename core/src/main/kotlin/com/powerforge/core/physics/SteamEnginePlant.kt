package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * A single-cylinder steam engine driving a flywheel and a DC generator rotor.
 *
 * Each [step] integrates two coupled real state variables forward in time:
 *  - boiler water/steam temperature (drives saturation pressure via Clausius-Clapeyron)
 *  - drivetrain angular velocity (Newton's second law for rotation: I*dw/dt = sum of torques)
 *
 * Everything downstream (RPM, electrical watts, whether it runs at all) falls out
 * of those two numbers plus the current part specs, so upgrading a part never needs
 * special-casing: it just changes a coefficient the same equations already use.
 *
 * On top of that, a handful of operator controls modulate the same equations rather
 * than being bolted on as special cases: closing the throttle restricts the pressure
 * the cylinder actually sees, cutting fuel reduces heat input, disengaging the
 * generator zeroes the electromagnetic braking torque, and so on. Every control
 * defaults to "as if it didn't exist" so a plant with no controls unlocked yet
 * behaves exactly like the original always-on engine.
 */
class SteamEnginePlant(
    var boiler: Boiler = Boiler(1),
    var piston: PistonAssembly = PistonAssembly(1),
    var flywheel: Flywheel = Flywheel(1),
    var rotor: GeneratorRotor = GeneratorRotor(1),
    var frame: Frame = Frame(1),
) {
    var boilerTemperatureK: Double = PhysicsConstants.AMBIENT_TEMPERATURE_K
        private set

    var angularVelocityRadPerS: Double = 0.0
        private set

    // --- Operator controls (each unlocked independently via research) ---

    /** Steam admission valve: 0 = fully shut, 1 = wide open. Restricts cylinder pressure. */
    var throttleFraction: Double = 1.0

    /** Fuel/air valve on the burner: 0 = no fire, 1 = full burn rate. */
    var fuelValveFraction: Double = 1.0

    /** Master ignition. When off, no heat is produced regardless of the fuel valve. */
    var ignitionOn: Boolean = true

    /** Manually bleeds boiler pressure, independent of the automatic safety relief. */
    var safetyValveOpen: Boolean = false

    /** Whether the generator's armature is actually connected to the load circuit. */
    var generatorEngaged: Boolean = true

    /** Whether the lubrication system is being modeled at all (unlocked via research). */
    var lubricationSystemActive: Boolean = false

    /** 0-100. Only depletes/matters once [lubricationSystemActive] is true. */
    var lubricationPercent: Double = 100.0
        private set

    fun addLubrication(amount: Double = 100.0) {
        lubricationPercent = (lubricationPercent + amount).coerceIn(0.0, 100.0)
    }

    /** Fixed shaft stub every generation always has, independent of upgrades. */
    private val shaftMassKg = 0.09
    private val shaftRadiusM = 0.010
    private val shaftMomentOfInertiaKgM2 = 0.5 * shaftMassKg * shaftRadiusM * shaftRadiusM

    /** Resistance of whatever the plant is feeding power into (battery bank / local grid). */
    private val loadResistanceOhm = 2.0

    private val lubricationDepletionPercentPerSecond = 0.08
    private val maxFrictionMultiplierAtZeroLubrication = 3.0

    fun rotatingAssemblyMassKg(): Double =
        flywheel.massKg + rotor.massKg + shaftMassKg

    fun isStructurallyOverloaded(): Boolean =
        rotatingAssemblyMassKg() > frame.maxSupportedRotatingMassKg

    private fun totalMomentOfInertiaKgM2(): Double =
        flywheel.momentOfInertiaKgM2 + rotor.momentOfInertiaKgM2 + shaftMomentOfInertiaKgM2

    private fun frictionMultiplier(): Double {
        if (!lubricationSystemActive) return 1.0
        val wear = (100.0 - lubricationPercent) / 100.0
        return 1.0 + wear * (maxFrictionMultiplierAtZeroLubrication - 1.0)
    }

    /**
     * Advances the simulation by [dtSeconds] of in-game time, split into small
     * substeps for numerical stability (semi-implicit Euler).
     */
    fun step(dtSeconds: Double) {
        val substeps = max(1, (dtSeconds / 0.01).toInt())
        val h = dtSeconds / substeps
        repeat(substeps) { integrateSubstep(h) }
    }

    private fun integrateSubstep(dt: Double) {
        val overloaded = isStructurallyOverloaded()
        val rawSaturationPa = saturationPressurePa(boilerTemperatureK)
        val isVenting = rawSaturationPa > boiler.maxPressurePa
        val boilerPressurePa = min(rawSaturationPa, boiler.maxPressurePa)
        val throttle = throttleFraction.coerceIn(0.0, 1.0)

        val drivingTorqueNm = if (overloaded) {
            0.0
        } else {
            val cylinderPressurePa = boilerPressurePa * throttle
            val netPistonPressurePa = max(0.0, cylinderPressurePa - piston.exhaustPressurePa)
            val pistonForceN = netPistonPressurePa * piston.pistonAreaM2
            pistonForceN * piston.crankRadiusM * PhysicsConstants.MEAN_TORQUE_FACTOR
        }

        val frictionMultiplier = frictionMultiplier()
        val normalForceN = rotatingAssemblyMassKg() * PhysicsConstants.GRAVITY_M_PER_S2
        val kineticFrictionTorqueNm = frame.bearingFrictionCoeff * normalForceN * frame.bearingRadiusM * frictionMultiplier
        val staticFrictionTorqueNm = kineticFrictionTorqueNm * PhysicsConstants.STATIC_FRICTION_MULTIPLIER

        var omega = angularVelocityRadPerS
        omega = when {
            overloaded -> 0.0
            omega <= 1e-6 && drivingTorqueNm <= staticFrictionTorqueNm -> 0.0
            else -> {
                val generatorLoadTorqueNm = if (generatorEngaged) {
                    rotor.backEmfConstantVSPerRad * rotor.backEmfConstantVSPerRad * omega /
                        (rotor.internalResistanceOhm + loadResistanceOhm)
                } else {
                    0.0
                }
                val viscousFrictionTorqueNm = frame.viscousFrictionCoeffNmSPerRad * omega * frictionMultiplier
                val netTorqueNm =
                    drivingTorqueNm - kineticFrictionTorqueNm - viscousFrictionTorqueNm - generatorLoadTorqueNm
                val angularAccelerationRadPerS2 = netTorqueNm / totalMomentOfInertiaKgM2()
                max(0.0, omega + angularAccelerationRadPerS2 * dt)
            }
        }
        angularVelocityRadPerS = omega

        if (lubricationSystemActive && omega > 1.0) {
            lubricationPercent = (lubricationPercent - lubricationDepletionPercentPerSecond * dt).coerceIn(0.0, 100.0)
        }

        val revolutionsPerSecond = omega / (2.0 * PI)
        val steamMassFlowKgPerS = if (overloaded) {
            0.0
        } else {
            val chargeDensity = steamDensityKgPerM3(boilerPressurePa, boilerTemperatureK)
            chargeDensity * piston.sweptVolumeM3 * throttle * revolutionsPerSecond
        }
        val heatExtractedByPistonW = steamMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val heatLossToEnvironmentW = boiler.insulationLossWPerK * (boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        val automaticSafetyValveLossW = if (isVenting) boiler.heatInputW * 0.2 else 0.0
        val manualSafetyValveLossW = if (safetyValveOpen) boiler.heatInputW * 0.35 else 0.0

        val effectiveHeatInputW = if (ignitionOn) boiler.heatInputW * fuelValveFraction.coerceIn(0.0, 1.0) else 0.0
        val netHeatW = effectiveHeatInputW - heatExtractedByPistonW - heatLossToEnvironmentW -
            automaticSafetyValveLossW - manualSafetyValveLossW
        val dTemperatureK = netHeatW / (boiler.waterMassKg * PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K) * dt
        boilerTemperatureK = max(PhysicsConstants.AMBIENT_TEMPERATURE_K, boilerTemperatureK + dTemperatureK)
    }

    fun electricalPowerW(): Double {
        if (!generatorEngaged) return 0.0
        val current = rotor.backEmfConstantVSPerRad * angularVelocityRadPerS /
            (rotor.internalResistanceOhm + loadResistanceOhm)
        return current * current * loadResistanceOhm
    }

    fun status(): PlantStatus {
        val failureReason = when {
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
            angularVelocityRadPerS = angularVelocityRadPerS,
            rpm = angularVelocityRadPerS * 60.0 / (2.0 * PI),
            electricalPowerW = electricalW,
            heatInputW = effectiveHeatInputW,
            overallEfficiency = if (effectiveHeatInputW > 0) electricalW / effectiveHeatInputW else 0.0,
            rotatingAssemblyMassKg = rotatingAssemblyMassKg(),
            maxSupportedRotatingMassKg = frame.maxSupportedRotatingMassKg,
            lubricationPercent = lubricationPercent,
            generatorEngaged = generatorEngaged,
            failureReason = failureReason,
        )
    }
}
