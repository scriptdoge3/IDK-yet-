package com.powerforge.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamEnginePlantTest {

    private fun runToSteadyState(plant: SteamEnginePlant, totalSeconds: Double = 180.0): PlantStatus {
        val tick = 0.5
        var elapsed = 0.0
        while (elapsed < totalSeconds) {
            plant.step(tick)
            elapsed += tick
        }
        return plant.status()
    }

    @Test
    fun `level 1 steam engine settles near 50 watts`() {
        val plant = SteamEnginePlant()
        val status = runToSteadyState(plant)

        println(
            "L1 steady state: T=${status.boilerTemperatureK}K P=${status.boilerPressurePa}Pa " +
                "RPM=${status.rpm} W=${status.electricalPowerW} eff=${status.overallEfficiency} " +
                "failure=${status.failureReason}"
        )

        assertEquals(FailureReason.NONE, status.failureReason)
        assertTrue("expected 30-75W, got ${status.electricalPowerW}", status.electricalPowerW in 30.0..75.0)
    }

    @Test
    fun `upgrading boiler and piston increases power output`() {
        val base = runToSteadyState(SteamEnginePlant())
        val upgraded = runToSteadyState(
            SteamEnginePlant(boiler = Boiler(4), piston = PistonAssembly(3))
        )

        println("base=${base.electricalPowerW}W upgraded=${upgraded.electricalPowerW}W")
        assertTrue(upgraded.electricalPowerW > base.electricalPowerW)
    }

    @Test
    fun `oversized flywheel and rotor overload the frame and produce zero power`() {
        val plant = SteamEnginePlant(
            flywheel = Flywheel(8),
            rotor = GeneratorRotor(8),
            frame = Frame(1),
        )
        val status = runToSteadyState(plant, totalSeconds = 60.0)

        println(
            "overload test: rotatingMass=${status.rotatingAssemblyMassKg} " +
                "maxSupported=${status.maxSupportedRotatingMassKg} failure=${status.failureReason}"
        )

        assertEquals(FailureReason.STRUCTURAL_OVERLOAD, status.failureReason)
        assertEquals(0.0, status.electricalPowerW, 1e-9)
    }

    @Test
    fun `upgrading the frame alongside flywheel and rotor keeps it running`() {
        val plant = SteamEnginePlant(
            flywheel = Flywheel(8),
            rotor = GeneratorRotor(8),
            frame = Frame(10),
        )
        val status = runToSteadyState(plant, totalSeconds = 240.0)

        println("frame-upgraded heavy build: mass=${status.rotatingAssemblyMassKg} failure=${status.failureReason} W=${status.electricalPowerW}")

        assertEquals(FailureReason.NONE, status.failureReason)
        assertTrue(status.electricalPowerW > 0.0)
    }

    @Test
    fun `cold boiler cannot immediately turn the shaft`() {
        val plant = SteamEnginePlant()
        val status = plant.status()

        assertEquals(FailureReason.STALLED_INSUFFICIENT_TORQUE, status.failureReason)
        assertEquals(0.0, status.electricalPowerW, 1e-9)
    }

    @Test
    fun `saturation pressure matches known water boiling point`() {
        val p = saturationPressurePa(373.15)
        assertEquals(PhysicsConstants.ATMOSPHERIC_PRESSURE_PA, p, 1.0)
    }
}
