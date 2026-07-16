package com.powerforge.core.physics

import kotlin.math.pow
import kotlin.math.roundToLong

/** Shared exponential cost curve so every progression track gets steadily more expensive. */
fun exponentialCost(baseCost: Int, currentLevel: Int, growth: Double = 1.22): Long {
    return (baseCost * growth.pow(currentLevel - 1)).roundToLong()
}
