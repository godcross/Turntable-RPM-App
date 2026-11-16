package app.turntablerpm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Measurement history, stored only in the app's private folder on the phone. */
class MeasurementLog(private val context: Context) {

    companion object {
        const val HEADER = "timestamp,mean_rpm,target_rpm,deviation_pct," +
            "wow_flutter_2sigma_pct,wow_pct,flutter_pct,drift_pct," +
            "rotations,duration_s,sample_rate_hz"
    }

    private val file: File
        get() = File(context.filesDir, "measurements.csv")

    fun append(r: MeasurementResult) {
        val f = file
        if (!f.exists()) f.writeText(HEADER + "\n")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(r.timeMs))
        val line = String.format(
            Locale.US,
            "%s,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.1f",
            ts, r.meanRpm, r.targetRpm, r.deviationPct, r.wowFlutterPct,
            r.wowPct, r.flutterPct, r.driftPct, r.rotations, r.durationS, r.sampleRateHz
        )
        f.appendText(line + "\n")
    }

    /** Data rows, oldest first, split into columns. */
    fun rows(): List<List<String>> {
        val f = file
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
    }

    fun csv(): String {
        val f = file
        return if (f.exists()) f.readText() else HEADER + "\n"
    }

    fun clear() {
        file.delete()
    }
}
