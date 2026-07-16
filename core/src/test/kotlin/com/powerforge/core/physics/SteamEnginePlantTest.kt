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

    /**
     * The lubricator's default feed rate already keeps pace with bearing wear (a real
     * force-feed lubricator running normally does), so this is just [runToSteadyState] -
     * kept as a separate name at call sites that specifically want to call out "and this
     * run isn't testing lubrication at all."
     */
    private fun runMaintained(plant: SteamEnginePlant, totalSeconds: Double): PlantStatus =
        runToSteadyState(plant, totalSeconds)

    @Test
    fun `level 1 steam engine settles near 50 watts with default controls`() {
        val plant = SteamEnginePlant()
        val status = runToSteadyState(plant)

        println(
            "L1 steady state: T=${status.boilerTemperatureK}K P=${status.boilerPressurePa}Pa " +
                "water=${status.boilerWaterLevelFraction} RPM=${status.rpm} W=${status.electricalPowerW} " +
                "eff=${status.overallEfficiency} failure=${status.failureReason}"
        )

        assertEquals(FailureReason.NONE, status.failureReason)
        assertTrue("expected 20-90W, got ${status.electricalPowerW}", status.electricalPowerW in 20.0..90.0)
    }

    @Test
    fun `upgrading boiler and piston increases power output`() {
        val base = runToSteadyState(SteamEnginePlant())
        val upgraded = runToSteadyState(
            SteamEnginePlant(boiler = Boiler(3), piston = PistonAssembly(3))
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
        val status = runToSteadyState(plant, totalSeconds = 5.0)

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
            flywheel = Flywheel(6),
            rotor = GeneratorRotor(6),
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
    fun `closing the throttle chokes flow and reduces power output`() {
        val fullThrottle = runToSteadyState(SteamEnginePlant())

        val halfThrottlePlant = SteamEnginePlant().apply { throttleFraction = 0.08 }
        val halfThrottle = runToSteadyState(halfThrottlePlant)

        println("full=${fullThrottle.electricalPowerW}W restricted=${halfThrottle.electricalPowerW}W")
        assertTrue(halfThrottle.electricalPowerW < fullThrottle.electricalPowerW)
    }

    @Test
    fun `shortening cutoff trades peak power for less steam per revolution`() {
        val longCutoff = SteamEnginePlant().apply { cutoffFraction = 0.9 }
        val longStatus = runToSteadyState(longCutoff)

        val shortCutoff = SteamEnginePlant().apply { cutoffFraction = 0.2 }
        val shortStatus = runToSteadyState(shortCutoff)

        println("longCutoff=${longStatus.electricalPowerW}W shortCutoff=${shortStatus.electricalPowerW}W")
        assertTrue(shortStatus.electricalPowerW < longStatus.electricalPowerW)
    }

    @Test
    fun `turning off ignition stops heat production and the shaft`() {
        val plant = SteamEnginePlant().apply { ignitionOn = false }
        val status = runToSteadyState(plant, totalSeconds = 60.0)

        assertEquals(FailureReason.IGNITION_OFF, status.failureReason)
        assertEquals(0.0, status.electricalPowerW, 1e-9)
    }

    @Test
    fun `disengaging the clutch removes electromagnetic braking and the shaft revs up`() {
        val engagedPlant = SteamEnginePlant()
        val engaged = runToSteadyState(engagedPlant)

        // Disengage only after it's already up to speed - a real operator wouldn't declutch
        // a cold, stationary engine. Check the immediate response (no more braking torque
        // means it should accelerate right away) rather than a long-run steady state: at
        // high enough RPM a disengaged flywheel can outrun what its own valve can admit
        // per stroke and choke itself via over-expansion, which is a real, separate risk
        // this test isn't about.
        val disengagedPlant = SteamEnginePlant()
        runToSteadyState(disengagedPlant)
        disengagedPlant.clutchEngaged = false
        disengagedPlant.step(1.0)
        val disengagedShortly = disengagedPlant.status()

        println("engaged rpm=${engaged.rpm} W=${engaged.electricalPowerW}; disengaged+1s rpm=${disengagedShortly.rpm}")
        assertEquals(0.0, disengagedShortly.electricalPowerW, 1e-9)
        assertTrue("disengaged flywheel should spin faster with no electromagnetic braking", disengagedShortly.rpm > engaged.rpm)
    }

    @Test
    fun `emergency brake halts a spinning shaft`() {
        val plant = SteamEnginePlant()
        runToSteadyState(plant)
        assertTrue(plant.angularVelocityRadPerS > 0.0)

        plant.emergencyBrakeEngaged = true
        plant.step(30.0)

        assertEquals(0.0, plant.angularVelocityRadPerS, 1e-6)
    }

    @Test
    fun `turning off the lubricator lets friction climb and can seize a bearing`() {
        val neglected = SteamEnginePlant().apply { lubricatorFeedRateFraction = 0.0 }
        val status = runToSteadyState(neglected, totalSeconds = 20000.0)

        println("neglected lubrication result: failure=${status.failureReason} lube=${neglected.lubricationPercent}")
        assertEquals(FailureReason.BEARING_SEIZED, status.failureReason)
        assertTrue(neglected.isDamaged)
    }

    @Test
    fun `the lubricator's default feed rate keeps the plant running indefinitely`() {
        val plant = SteamEnginePlant()
        val status = runToSteadyState(plant, totalSeconds = 16000.0)

        assertEquals(FailureReason.NONE, status.failureReason)
        assertTrue(status.electricalPowerW > 0.0)
        assertTrue(plant.lubricationPercent > 50.0)
    }

    @Test
    fun `never opening the feedwater valve eventually dry-fires the boiler`() {
        val plant = SteamEnginePlant().apply { feedwaterValveFraction = 0.0 }
        val status = runMaintained(plant, totalSeconds = 20000.0)

        println("dry-fire test: water=${status.boilerWaterLevelFraction} failure=${status.failureReason}")
        assertEquals(FailureReason.BOILER_DRY_FIRE, status.failureReason)
        assertTrue(plant.isDamaged)
    }

    @Test
    fun `keeping the feedwater valve open avoids dry-firing indefinitely`() {
        val plant = SteamEnginePlant()
        val status = runMaintained(plant, totalSeconds = 20000.0)

        assertTrue(status.boilerWaterLevelFraction > 0.0)
        assertEquals(FailureReason.NONE, status.failureReason)
    }

    @Test
    fun `sustained overexcitation burns out the rotor windings`() {
        val plant = SteamEnginePlant().apply { excitationFraction = 1.5 }
        val status = runMaintained(plant, totalSeconds = 3000.0)

        println("overexcitation test: windingT=${status.rotorWindingTemperatureK}K failure=${status.failureReason}")
        assertEquals(FailureReason.ROTOR_BURNOUT, status.failureReason)
        assertTrue(plant.isDamaged)
    }

    @Test
    fun `rated excitation never burns out the rotor`() {
        val plant = SteamEnginePlant()
        val status = runMaintained(plant, totalSeconds = 3000.0)

        assertEquals(FailureReason.NONE, status.failureReason)
    }

    @Test
    fun `disengaging the clutch with the boiler wide open can overspeed and burst the flywheel`() {
        val plant = SteamEnginePlant(
            boiler = Boiler(8),
            piston = PistonAssembly(1),
            flywheel = Flywheel(1),
        ).apply { clutchEngaged = false }
        val status = runMaintained(plant, totalSeconds = 3000.0)

        println("overspeed test: rpm=${status.rpm} failure=${status.failureReason}")
        assertEquals(FailureReason.FLYWHEEL_BURST, status.failureReason)
        assertTrue(plant.isDamaged)
    }

    @Test
    fun `leaving the burner on full while stalled overloads and ruptures the boiler`() {
        val plant = SteamEnginePlant(
            boiler = Boiler(6),
            flywheel = Flywheel(8),
            rotor = GeneratorRotor(8),
            frame = Frame(1),
        )
        val status = runToSteadyState(plant, totalSeconds = 3000.0)

        println("rupture test: P=${status.boilerPressurePa} failure=${status.failureReason}")
        assertEquals(FailureReason.BOILER_RUPTURED, status.failureReason)
        assertTrue(plant.isDamaged)
    }

    @Test
    fun `repair clears damage and restores normal operation`() {
        val plant = SteamEnginePlant().apply { excitationFraction = 1.5 }
        runMaintained(plant, totalSeconds = 3000.0)
        assertTrue(plant.isDamaged)

        plant.repair()
        plant.excitationFraction = 1.0

        assertTrue(!plant.isDamaged)
        val status = runToSteadyState(plant, totalSeconds = 180.0)
        assertEquals(FailureReason.NONE, status.failureReason)
        assertTrue(status.electricalPowerW > 0.0)
    }

    @Test
    fun `slamming the throttle shut doesn't cut power instantly - the valve has to physically turn`() {
        val plant = SteamEnginePlant()
        val fullThrottleStatus = runToSteadyState(plant)
        plant.throttleFraction = 0.0

        plant.step(0.05)
        val justAfter = plant.status()
        println("just after slamming shut: W=${justAfter.electricalPowerW} (was ${fullThrottleStatus.electricalPowerW})")
        assertTrue(
            "the valve can't have physically closed yet, power should barely have moved",
            justAfter.electricalPowerW > fullThrottleStatus.electricalPowerW * 0.85,
        )

        plant.step(5.0)
        val longAfter = plant.status()
        println("5s after slamming shut: W=${longAfter.electricalPowerW}")
        assertTrue(longAfter.electricalPowerW < fullThrottleStatus.electricalPowerW * 0.2)
    }

    @Test
    fun `excitation follows an electrical lag, not an instant field change`() {
        val plant = SteamEnginePlant()
        runToSteadyState(plant)
        plant.excitationFraction = 0.0

        plant.step(0.02)
        val justAfter = plant.status()
        plant.step(3.0)
        val longAfter = plant.status()

        println("excitation lag: justAfter W=${justAfter.electricalPowerW} longAfter W=${longAfter.electricalPowerW}")
        assertTrue(
            "field current can't collapse in one tiny substep, some EMF should remain",
            justAfter.electricalPowerW > longAfter.electricalPowerW,
        )
    }

    @Test
    fun `ignition switch has real throw latency before the burner actually dies`() {
        val plant = SteamEnginePlant()
        runToSteadyState(plant)
        plant.ignitionOn = false

        plant.step(0.1)
        assertTrue("burner shouldn't be out yet, the switch is still mid-throw", plant.status().heatInputW > 0.0)

        plant.step(1.0)
        assertEquals(0.0, plant.status().heatInputW, 1e-9)
    }

    @Test
    fun `circuit breaker cuts current without removing the rotor's inertia like the clutch does`() {
        val plant = SteamEnginePlant()
        runToSteadyState(plant)
        plant.circuitBreakerClosed = false
        // Short window: with no electrical braking the shaft is free to speed up (same
        // over-expansion/overspeed risk as declutching, not what this test is checking),
        // so just confirm the immediate breaker-specific behavior.
        plant.step(2.0)
        val status = plant.status()

        println("breaker open: W=${status.electricalPowerW} clutchStillEngaged=${status.generatorEngaged} failure=${status.failureReason}")
        assertEquals(0.0, status.electricalPowerW, 1e-9)
        assertTrue("the clutch itself is untouched by the breaker", status.generatorEngaged)
    }

    @Test
    fun `lowering the load rheostat draws more current and changes output`() {
        val lightLoad = runToSteadyState(SteamEnginePlant().apply { loadRheostatOhm = 4.0 })
        val heavyLoad = runToSteadyState(SteamEnginePlant().apply { loadRheostatOhm = 0.8 })

        println("lightLoad(4ohm) W=${lightLoad.electricalPowerW} heavyLoad(0.8ohm) W=${heavyLoad.electricalPowerW}")
        assertTrue(lightLoad.electricalPowerW != heavyLoad.electricalPowerW)
    }

    @Test
    fun `air damper far from the optimum wastes combustion heat`() {
        val optimal = runToSteadyState(SteamEnginePlant())
        val starvedOfAir = runToSteadyState(SteamEnginePlant().apply { airDamperFraction = 0.0 })

        println("optimalDamper W=${optimal.electricalPowerW} starvedDamper W=${starvedOfAir.electricalPowerW}")
        assertTrue(starvedOfAir.electricalPowerW < optimal.electricalPowerW)
    }

    @Test
    fun `open drain cocks waste working pressure instead of producing torque`() {
        val closed = runToSteadyState(SteamEnginePlant())
        val open = runToSteadyState(SteamEnginePlant().apply { drainCocksOpen = true })

        println("drainCocksClosed W=${closed.electricalPowerW} drainCocksOpen W=${open.electricalPowerW}")
        assertTrue(open.electricalPowerW < closed.electricalPowerW)
    }

    @Test
    fun `boiler scale builds up while firing and blowing down purges it`() {
        val plant = SteamEnginePlant()
        runToSteadyState(plant, totalSeconds = 12000.0)
        val scaledStatus = plant.status()
        println("scale after running: ${scaledStatus.boilerScalePercent}%")
        assertTrue(scaledStatus.boilerScalePercent > 0.0)

        plant.blowdownValveOpen = true
        plant.step(60.0)
        val descaledStatus = plant.status()
        println("scale after blowdown: ${descaledStatus.boilerScalePercent}%")
        assertTrue(descaledStatus.boilerScalePercent < scaledStatus.boilerScalePercent)
    }
}
