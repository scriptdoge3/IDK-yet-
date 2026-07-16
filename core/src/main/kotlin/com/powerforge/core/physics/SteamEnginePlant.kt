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

    /** Fixed shaft stub every generation always has, independent of upgrades. */
    private val shaftMassKg = 0.09
    private val shaftRadiusM = 0.010
    private val shaftMomentOfInertiaKgM2 = 0.5 * shaftMassKg * shaftRadiusM * shaftRadiusM

    /** Resistance of whatever the plant is feeding power into (battery bank / local grid). */
    private val loadResistanceOhm = 2.0

    fun rotatingAssemblyMassKg(): Double =
        flywheel.massKg + rotor.massKg + shaftMassKg

    fun isStructurallyOverloaded(): Boolean =
        rotatingAssemblyMassKg() > frame.maxSupportedRotatingMassKg

    private fun totalMomentOfInertiaKgM2(): Double =
        flywheel.momentOfInertiaKgM2 + rotor.momentOfInertiaKgM2 + shaftMomentOfInertiaKgM2

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

        val drivingTorqueNm = if (overloaded) {
            0.0
        } else {
            val netPistonPressurePa = max(0.0, boilerPressurePa - piston.exhaustPressurePa)
            val pistonForceN = netPistonPressurePa * piston.pistonAreaM2
            pistonForceN * piston.crankRadiusM * PhysicsConstants.MEAN_TORQUE_FACTOR
        }

        val normalForceN = rotatingAssemblyMassKg() * PhysicsConstants.GRAVITY_M_PER_S2
        val kineticFrictionTorqueNm = frame.bearingFrictionCoeff * normalForceN * frame.bearingRadiusM
        val staticFrictionTorqueNm = kineticFrictionTorqueNm * PhysicsConstants.STATIC_FRICTION_MULTIPLIER

        var omega = angularVelocityRadPerS
        omega = when {
            overloaded -> 0.0
            omega <= 1e-6 && drivingTorqueNm <= staticFrictionTorqueNm -> 0.0
            else -> {
                val generatorLoadTorqueNm =
                    rotor.backEmfConstantVSPerRad * rotor.backEmfConstantVSPerRad * omega /
                        (rotor.internalResistanceOhm + loadResistanceOhm)
                val viscousFrictionTorqueNm = frame.viscousFrictionCoeffNmSPerRad * omega
                val netTorqueNm =
                    drivingTorqueNm - kineticFrictionTorqueNm - viscousFrictionTorqueNm - generatorLoadTorqueNm
                val angularAccelerationRadPerS2 = netTorqueNm / totalMomentOfInertiaKgM2()
                max(0.0, omega + angularAccelerationRadPerS2 * dt)
            }
        }
        angularVelocityRadPerS = omega

        val revolutionsPerSecond = omega / (2.0 * PI)
        val steamMassFlowKgPerS = if (overloaded) {
            0.0
        } else {
            val chargeDensity = steamDensityKgPerM3(boilerPressurePa, boilerTemperatureK)
            chargeDensity * piston.sweptVolumeM3 * revolutionsPerSecond
        }
        val heatExtractedByPistonW = steamMassFlowKgPerS * PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
        val heatLossToEnvironmentW = boiler.insulationLossWPerK * (boilerTemperatureK - PhysicsConstants.AMBIENT_TEMPERATURE_K)
        val safetyValveLossW = if (isVenting) boiler.heatInputW * 0.2 else 0.0

        val netHeatW = boiler.heatInputW - heatExtractedByPistonW - heatLossToEnvironmentW - safetyValveLossW
        val dTemperatureK = netHeatW / (boiler.waterMassKg * PhysicsConstants.WATER_SPECIFIC_HEAT_J_PER_KG_K) * dt
        boilerTemperatureK = max(PhysicsConstants.AMBIENT_TEMPERATURE_K, boilerTemperatureK + dTemperatureK)
    }

    fun electricalPowerW(): Double {
        val current = rotor.backEmfConstantVSPerRad * angularVelocityRadPerS /
            (rotor.internalResistanceOhm + loadResistanceOhm)
        return current * current * loadResistanceOhm
    }

    fun status(): PlantStatus {
        val failureReason = when {
            isStructurallyOverloaded() -> FailureReason.STRUCTURAL_OVERLOAD
            angularVelocityRadPerS <= 1e-6 -> FailureReason.STALLED_INSUFFICIENT_TORQUE
            else -> FailureReason.NONE
        }
        val electricalW = electricalPowerW()
        return PlantStatus(
            boilerTemperatureK = boilerTemperatureK,
            boilerPressurePa = min(saturationPressurePa(boilerTemperatureK), boiler.maxPressurePa),
            angularVelocityRadPerS = angularVelocityRadPerS,
            rpm = angularVelocityRadPerS * 60.0 / (2.0 * PI),
            electricalPowerW = electricalW,
            heatInputW = boiler.heatInputW,
            overallEfficiency = if (boiler.heatInputW > 0) electricalW / boiler.heatInputW else 0.0,
            rotatingAssemblyMassKg = rotatingAssemblyMassKg(),
            maxSupportedRotatingMassKg = frame.maxSupportedRotatingMassKg,
            failureReason = failureReason,
        )
    }
}
