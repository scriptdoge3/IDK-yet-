package com.powerforge.core.physics

import kotlin.math.pow
import kotlin.math.roundToLong

/** Shared exponential cost curve so every part gets steadily more expensive to upgrade. */
fun upgradeCostCredits(baseCost: Int, currentLevel: Int, growth: Double = 1.22): Long {
    return (baseCost * growth.pow(currentLevel - 1)).roundToLong()
}
