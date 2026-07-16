package com.powerforge.core.physics

import kotlin.math.exp

/** Rate-limits [current] toward [target] at a bounded rate, like a hand-turned valve wheel. */
fun approachLinear(current: Double, target: Double, maxRatePerSecond: Double, dtSeconds: Double): Double {
    val maxStep = maxRatePerSecond * dtSeconds
    val delta = target - current
    return when {
        delta > maxStep -> current + maxStep
        delta < -maxStep -> current - maxStep
        else -> target
    }
}

/** First-order exponential lag, like field-winding current catching up to a new voltage. */
fun approachExponential(current: Double, target: Double, timeConstantSeconds: Double, dtSeconds: Double): Double {
    val alpha = 1.0 - exp(-dtSeconds / timeConstantSeconds)
    return current + (target - current) * alpha
}

/**
 * A boolean control with real throw latency: flipping [commanded] doesn't change
 * [effective] - what the physics actually sees - until [latencySeconds] of travel
 * time has elapsed, the way a real switch or lever takes a moment to complete its
 * motion instead of teleporting the mechanism it operates.
 */
class LatchedSwitch(initial: Boolean, private val latencySeconds: Double) {
    var commanded: Boolean = initial
    var effective: Boolean = initial
        private set
    private var remainingSeconds: Double = -1.0

    fun update(dtSeconds: Double) {
        if (commanded == effective) {
            remainingSeconds = -1.0
            return
        }
        if (remainingSeconds < 0.0) remainingSeconds = latencySeconds
        remainingSeconds -= dtSeconds
        if (remainingSeconds <= 0.0) {
            effective = commanded
            remainingSeconds = -1.0
        }
    }

    fun reset(value: Boolean) {
        commanded = value
        effective = value
        remainingSeconds = -1.0
    }
}
