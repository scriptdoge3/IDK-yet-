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

    @Test
    fun `closing the throttle reduces power output`() {
        val fullThrottle = runToSteadyState(SteamEnginePlant())

        val halfThrottlePlant = SteamEnginePlant().apply { throttleFraction = 0.5 }
        val halfThrottle = runToSteadyState(halfThrottlePlant)

        println("full=${fullThrottle.electricalPowerW}W half=${halfThrottle.electricalPowerW}W")
        assertTrue(halfThrottle.electricalPowerW < fullThrottle.electricalPowerW)
    }

    @Test
    fun `turning off ignition stops heat production and the shaft`() {
        val plant = SteamEnginePlant().apply { ignitionOn = false }
        val status = runToSteadyState(plant, totalSeconds = 60.0)

        assertEquals(FailureReason.IGNITION_OFF, status.failureReason)
        assertEquals(0.0, status.electricalPowerW, 1e-9)
    }

    @Test
    fun `disengaging the generator lets the shaft spin free with zero electrical output`() {
        val engaged = runToSteadyState(SteamEnginePlant())

        val disengagedPlant = SteamEnginePlant().apply { generatorEngaged = false }
        val disengaged = runToSteadyState(disengagedPlant)

        println("engaged rpm=${engaged.rpm} W=${engaged.electricalPowerW}; disengaged rpm=${disengaged.rpm} W=${disengaged.electricalPowerW}")
        assertEquals(FailureReason.NONE, disengaged.failureReason)
        assertEquals(0.0, disengaged.electricalPowerW, 1e-9)
        assertTrue("disengaged flywheel should spin faster with no electromagnetic braking", disengaged.rpm > engaged.rpm)
    }

    @Test
    fun `depleted lubrication increases friction and reduces power until topped up`() {
        val worn = SteamEnginePlant(flywheel = Flywheel(1)).apply {
            lubricationSystemActive = true
        }
        // Run long enough for lubrication to fully deplete, then reach a new steady state.
        val wornStatus = runToSteadyState(worn, totalSeconds = 3600.0)
        assertEquals(0.0, worn.lubricationPercent, 1e-6)

        worn.addLubrication()
        val refreshedStatus = runToSteadyState(worn, totalSeconds = 180.0)

        println("worn W=${wornStatus.electricalPowerW} refreshed W=${refreshedStatus.electricalPowerW}")
        assertTrue(refreshedStatus.electricalPowerW > wornStatus.electricalPowerW)
    }
}
