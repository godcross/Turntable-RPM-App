package app.turntablerpm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class MeasurementResult(
    val timeMs: Long,
    val meanRpm: Double,
    val targetRpm: Double,
    val deviationPct: Double,
    val wowFlutterPct: Double,
    val wowPct: Double,
    val flutterPct: Double,
    val driftPct: Double,
    val rotations: Double,
    val durationS: Double,
    val sampleRateHz: Double
)

/**
 * Turns raw gyroscope samples into turntable speed figures.
 *
 * The phone lies on the platter, so its angular velocity vector is the
 * platter's. We use the vector magnitude, which makes the reading
 * independent of how the phone is oriented or where it sits on the platter.
 *
 * Flow: IDLE -> SETTLING (spinning, waiting for steady speed)
 *       -> MEASURING (records TARGET_ROTATIONS turns) -> DONE.
 */
class RpmAnalyzer {

    enum class State { IDLE, SETTLING, MEASURING, DONE }

    companion object {
        val PRESETS = doubleArrayOf(100.0 / 3.0, 45.0, 78.0)
        const val MIN_RPM = 10.0
        const val TARGET_ROTATIONS = 10.0
        const val RAD_S_TO_RPM = 60.0 / (2.0 * Math.PI)
        private const val WINDOW_NS = 1_000_000_000L
        private const val STABLE_NS = 2_000_000_000L
        private const val WOW_CUTOFF_HZ = 6.0

        fun nearestPreset(rpm: Double): Double =
            PRESETS.minByOrNull { abs(it - rpm) } ?: PRESETS[0]

        fun presetLabel(target: Double): String =
            if (abs(target - 100.0 / 3.0) < 0.01) "33⅓" else String.format("%.0f", target)
    }

    var state = State.IDLE
        private set

    /** null = pick the nearest preset automatically. */
    var targetOverride: Double? = null
    var scale = 1.0
    var biasX = 0.0
    var biasY = 0.0
    var biasZ = 0.0

    var progress = 0.0
        private set
    /** Average speed over the last second. */
    var liveRpm = 0.0
        private set
    /** Speed from the latest single sample. */
    var instantRpm = 0.0
        private set

    private val winT = ArrayDeque<Long>()
    private val winR = ArrayDeque<Double>()

    private var stableRef = 0.0
    private var stableSince = -1L

    private val recT = ArrayList<Long>()
    private val recR = ArrayList<Double>()
    private var rotations = 0.0
    private var measuredTime = 0.0

    fun targetFor(rpm: Double): Double = targetOverride ?: nearestPreset(rpm)

    /** Throw away the current/last measurement and wait for steady speed again. */
    fun restart() {
        state = if (liveRpm > MIN_RPM) State.SETTLING else State.IDLE
        stableSince = -1L
        clearRecording()
    }

    private fun clearRecording() {
        recT.clear()
        recR.clear()
        rotations = 0.0
        measuredTime = 0.0
        progress = 0.0
    }

    /** Feed one gyro sample (rad/s). Returns a result when a measurement completes. */
    fun onSample(tNs: Long, wx: Float, wy: Float, wz: Float): MeasurementResult? {
        val x = wx - biasX
        val y = wy - biasY
        val z = wz - biasZ
        val rpm = sqrt(x * x + y * y + z * z) * RAD_S_TO_RPM * scale
        instantRpm = rpm

        winT.addLast(tNs)
        winR.addLast(rpm)
        while (winT.size > 1 && tNs - winT.first() > WINDOW_NS) {
            winT.removeFirst()
            winR.removeFirst()
        }
        val n = winR.size
        var mean = 0.0
        for (r in winR) mean += r
        mean /= n
        var v = 0.0
        for (r in winR) {
            val d = r - mean
            v += d * d
        }
        val sd = sqrt(v / n)
        liveRpm = mean
        val span = tNs - winT.first()

        var result: MeasurementResult? = null
        when (state) {
            State.IDLE -> if (mean > MIN_RPM) {
                state = State.SETTLING
                stableSince = -1L
            }

            State.SETTLING -> {
                if (mean < MIN_RPM) {
                    state = State.IDLE
                } else if (span >= WINDOW_NS * 9 / 10) {
                    val quiet = sd / mean < 0.03
                    if (!quiet) {
                        stableSince = -1L
                    } else if (stableSince < 0 || abs(mean - stableRef) / stableRef > 0.003) {
                        stableSince = tNs
                        stableRef = mean
                    } else if (tNs - stableSince >= STABLE_NS) {
                        clearRecording()
                        state = State.MEASURING
                    }
                }
            }

            State.MEASURING -> {
                if (mean < MIN_RPM) {
                    state = State.IDLE
                    clearRecording()
                } else {
                    if (recT.isNotEmpty()) {
                        val dt = (tNs - recT[recT.size - 1]) / 1e9
                        // Skip gaps (sensor hiccups) instead of guessing across them.
                        if (dt > 0 && dt < 0.5) {
                            rotations += (rpm + recR[recR.size - 1]) / 2.0 / 60.0 * dt
                            measuredTime += dt
                        }
                    }
                    recT.add(tNs)
                    recR.add(rpm)
                    progress = (rotations / TARGET_ROTATIONS).coerceIn(0.0, 1.0)
                    if (rotations >= TARGET_ROTATIONS && recR.size > 10) {
                        result = compute()
                        state = State.DONE
                        progress = 1.0
                    }
                }
            }

            State.DONE -> if (mean < MIN_RPM) state = State.IDLE
        }
        return result
    }

    private fun compute(): MeasurementResult {
        val n = recR.size
        val meanRpm = rotations * 60.0 / measuredTime
        val target = targetFor(meanRpm)

        var m = 0.0
        for (r in recR) m += r
        m /= n
        var v = 0.0
        for (r in recR) {
            val d = r - m
            v += d * d
        }
        val sd = sqrt(v / n)

        // Zero-phase low-pass at 6 Hz splits wow (slow) from flutter (fast).
        val tau = 1.0 / (2.0 * Math.PI * WOW_CUTOFF_HZ)
        val lp = DoubleArray(n)
        lp[0] = recR[0]
        for (i in 1 until n) {
            val dt = (recT[i] - recT[i - 1]) / 1e9
            val a = 1.0 - exp(-dt / tau)
            lp[i] = lp[i - 1] + a * (recR[i] - lp[i - 1])
        }
        for (i in n - 2 downTo 0) {
            val dt = (recT[i + 1] - recT[i]) / 1e9
            val a = 1.0 - exp(-dt / tau)
            lp[i] = lp[i + 1] + a * (lp[i] - lp[i + 1])
        }
        var wv = 0.0
        var fv = 0.0
        for (i in 0 until n) {
            val dw = lp[i] - m
            wv += dw * dw
            val df = recR[i] - lp[i]
            fv += df * df
        }

        val half = n / 2
        var a1 = 0.0
        for (i in 0 until half) a1 += recR[i]
        a1 /= half
        var a2 = 0.0
        for (i in half until n) a2 += recR[i]
        a2 /= (n - half)

        val span = (recT[n - 1] - recT[0]) / 1e9
        return MeasurementResult(
            timeMs = System.currentTimeMillis(),
            meanRpm = meanRpm,
            targetRpm = target,
            deviationPct = (meanRpm - target) / target * 100.0,
            wowFlutterPct = 2.0 * sd / m * 100.0,
            wowPct = 2.0 * sqrt(wv / n) / m * 100.0,
            flutterPct = 2.0 * sqrt(fv / n) / m * 100.0,
            driftPct = (a2 - a1) / m * 100.0,
            rotations = rotations,
            durationS = measuredTime,
            sampleRateHz = if (span > 0) (n - 1) / span else 0.0
        )
    }
}
