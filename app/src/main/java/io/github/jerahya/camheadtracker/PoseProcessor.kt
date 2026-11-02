package io.github.jerahya.camheadtracker

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow

class PoseProcessor {
    companion object {
        const val AXES = 6
        val AXIS_NAMES = arrayOf("x", "y", "z", "yaw", "pitch", "roll")
    }

    private val rot = DoubleArray(9)
    private val pos = DoubleArray(3)
    private val rot0 = DoubleArray(9)
    private val pos0 = DoubleArray(3)
    private val rel = DoubleArray(9)
    private val raw = DoubleArray(AXES)
    private val signs = doubleArrayOf(1.0, 1.0, -1.0, -1.0, -1.0, -1.0)
    private val filters = Array(AXES) { OneEuroFilter(beta = 0.1) }
    private var calibrated = false
    private var lastTimestampMs = 0L

    @Volatile private var calibrateRequested = false
    @Volatile var inverted = BooleanArray(AXES)
    @Volatile var enabled = BooleanArray(AXES) { true }

    @Volatile var smoothing = 0.0

    fun calibrate() {
        calibrateRequested = true
    }

    fun process(matrix: FloatArray, timestampMs: Long, out: DoubleArray) {
        readMatrix(matrix)

        if (!calibrated || calibrateRequested) {
            rot.copyInto(rot0)
            pos.copyInto(pos0)
            calibrated = true
            calibrateRequested = false
            filters.forEach { it.reset() }
        }

        // pose relative to calibration, in calibrated head axes
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3)
                    sum += rot0[k * 3 + i] * rot[k * 3 + j]
                rel[i * 3 + j] = sum
            }
            var t = 0.0
            for (k in 0 until 3)
                t += rot0[k * 3 + i] * (pos[k] - pos0[k])
            raw[i] = t
        }
        raw[3] = Math.toDegrees(atan2(rel[2], rel[8]))
        raw[4] = Math.toDegrees(asin((-rel[5]).coerceIn(-1.0, 1.0)))
        raw[5] = Math.toDegrees(atan2(rel[3], rel[4]))

        val dt = if (lastTimestampMs == 0L) 0.0 else (timestampMs - lastTimestampMs) / 1000.0
        lastTimestampMs = timestampMs

        val minCutoff = 8.0 * 0.0625.pow(smoothing)
        val invert = inverted
        val send = enabled
        val bypass = smoothing <= 0.0
        for (i in 0 until AXES) {
            filters[i].minCutoff = minCutoff
            val signed = raw[i] * signs[i]
            val value = if (bypass) signed else filters[i].filter(signed, dt)
            out[i] = if (!send[i]) 0.0 else if (invert[i]) -value else value
        }
    }

    private fun readMatrix(m: FloatArray) {
        // expected column-major; handle row-major too
        val rowMajor = abs(m[3]) + abs(m[7]) + abs(m[11]) > abs(m[12]) + abs(m[13]) + abs(m[14])
        fun r(row: Int, col: Int): Double =
            (if (rowMajor) m[row * 4 + col] else m[col * 4 + row]).toDouble()

        for (row in 0 until 3) {
            for (col in 0 until 3)
                rot[row * 3 + col] = r(row, col)
            pos[row] = r(row, 3)
        }
    }
}
