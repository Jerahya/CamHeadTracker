package io.github.jerahya.camheadtracker

import kotlin.math.PI
import kotlin.math.abs

// 1 Euro filter, Casiez et al. 2012
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.02,
    private val derivativeCutoff: Double = 1.0,
) {
    private var hasValue = false
    private var lastValue = 0.0
    private var lastDerivative = 0.0

    fun reset() {
        hasValue = false
    }

    fun filter(value: Double, dt: Double): Double {
        if (!hasValue || dt <= 0.0) {
            hasValue = true
            lastValue = value
            lastDerivative = 0.0
            return value
        }
        val derivative = (value - lastValue) / dt
        lastDerivative += alpha(derivativeCutoff, dt) * (derivative - lastDerivative)
        val cutoff = minCutoff + beta * abs(lastDerivative)
        lastValue += alpha(cutoff, dt) * (value - lastValue)
        return lastValue
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
