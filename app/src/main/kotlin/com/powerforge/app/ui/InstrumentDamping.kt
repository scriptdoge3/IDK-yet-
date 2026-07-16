package com.powerforge.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * A real analog instrument - a Bourdon-tube pressure gauge, a bimetallic or mercury
 * thermometer - cannot track its input's true instantaneous value: it has real
 * mechanical/thermal inertia, and manufacturers deliberately damp that response
 * (often with an actual dashpot or liquid fill) specifically so a turbulent, noisy
 * real reading doesn't make the needle flutter faster than a human can read it.
 * [KineticCylinderGas] and [com.powerforge.core.physics.BoilerThermalSimulation]
 * read pressure and temperature straight off a fresh particle-ensemble snapshot
 * every tick with no inertia of their own (unlike RPM, which already has the real
 * flywheel's rotational inertia built into the physics) - that really is how
 * statistically noisy a genuinely small particle count looks instant-to-instant,
 * not a bug. This models the missing piece: the real damped response an actual
 * gauge reading that same true value would have, critically damped (no overshoot,
 * the way a real instrument is built) and slow enough to average out tick-to-tick
 * sampling noise while still following a genuine, sustained change in a few
 * seconds - the same distinction a real thermometer's mercury column draws from
 * the true instantaneous kinetic energy of the air molecules touching its bulb.
 */
@Composable
fun rememberDampedReading(target: Double): Double {
    val animated by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow),
        label = "instrumentDamping",
    )
    return animated.toDouble()
}
