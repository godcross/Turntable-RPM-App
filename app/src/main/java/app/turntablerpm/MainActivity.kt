package app.turntablerpm

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    companion object {
        private const val REQ_EXPORT = 42
        private const val SAMPLE_PERIOD_US = 5000 // 200 Hz, the max allowed without a permission
        private const val UI_PERIOD_NS = 100_000_000L
        private const val CAL_NS = 3_000_000_000L

        private val BG = Color.parseColor("#000000")
        private val FG = Color.parseColor("#FFFFFF")
        private val DIM = Color.parseColor("#9E9E9E")
        private val ACCENT = Color.parseColor("#00BCD4")
        private val GOOD = Color.parseColor("#4CAF50")
        private val WARN = Color.parseColor("#FFC107")
        private val BAD = Color.parseColor("#F44336")
    }

    private lateinit var sensorManager: SensorManager
    private var gyro: Sensor? = null
    private val analyzer = RpmAnalyzer()
    private lateinit var log: MeasurementLog
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var statusText: TextView
    private lateinit var rpmText: TextView
    private lateinit var devText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var graph: GraphView
    private lateinit var resultText: TextView
    private val targetButtons = ArrayList<Pair<Button, Double?>>()

    private var lastResult: MeasurementResult? = null
    private var lastUiNs = 0L
    private var lastGraphNs = 0L
    private var graphSum = 0.0
    private var graphN = 0
    private var tone: ToneGenerator? = null

    private var calibrating = false
    private var calStart = -1L
    private var calSx = 0.0
    private var calSy = 0.0
    private var calSz = 0.0
    private var calN = 0
    private var calMax = 0.0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log = MeasurementLog(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        loadSettings()
        buildUi()
        render()
        if (gyro == null) {
            statusText.text = "This phone has no gyroscope, so it can't measure speed."
            statusText.setTextColor(BAD)
        }
    }

    override fun onResume() {
        super.onResume()
        gyro?.let { sensorManager.registerListener(this, it, SAMPLE_PERIOD_US) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        tone?.release()
        tone = null
    }

    private fun loadSettings() {
        analyzer.scale = prefs.getFloat("scale", 1f).toDouble()
        analyzer.biasX = prefs.getFloat("biasX", 0f).toDouble()
        analyzer.biasY = prefs.getFloat("biasY", 0f).toDouble()
        analyzer.biasZ = prefs.getFloat("biasZ", 0f).toDouble()
        val t = prefs.getFloat("target", 0f).toDouble()
        analyzer.targetOverride = if (t > 0) t else null
    }

    // ---------------------------------------------------------------- sensor

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val t = e.timestamp
        if (calibrating) {
            handleCalibration(t, e.values[0], e.values[1], e.values[2])
            return
        }
        val res = analyzer.onSample(t, e.values[0], e.values[1], e.values[2])
        if (res != null) {
            lastResult = res
            log.append(res)
            beep()
        }

        graphSum += analyzer.instantRpm
        graphN++
        if (t - lastGraphNs >= UI_PERIOD_NS) {
            if (analyzer.liveRpm > RpmAnalyzer.MIN_RPM) graph.add((graphSum / graphN).toFloat())
            graphSum = 0.0
            graphN = 0
            lastGraphNs = t
        }
        if (res != null || t - lastUiNs >= UI_PERIOD_NS) {
            lastUiNs = t
            render()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- display

    private fun render() {
        val live = analyzer.liveRpm
        val spinning = live > RpmAnalyzer.MIN_RPM
        val res = lastResult

        when (analyzer.state) {
            RpmAnalyzer.State.IDLE -> statusText.text =
                if (res != null) "Stopped. Showing last result. Start the platter to measure again."
                else "Place the phone flat on the platter and start the turntable."
            RpmAnalyzer.State.SETTLING -> statusText.text = "Waiting for the speed to settle…"
            RpmAnalyzer.State.MEASURING -> statusText.text =
                String.format("Measuring… %d %%", (analyzer.progress * 100).toInt())
            RpmAnalyzer.State.DONE -> statusText.text = "Done ✓  Result saved. Big number is live speed."
        }
        if (gyro != null) statusText.setTextColor(DIM)

        progressBar.progress = (analyzer.progress * 1000).toInt()

        val shown: Double? = when {
            spinning -> live
            res != null -> res.meanRpm
            else -> null
        }
        if (shown == null) {
            rpmText.text = "--.--"
            devText.text = "RPM"
            devText.setTextColor(DIM)
        } else {
            rpmText.text = String.format(Locale.US, "%.2f", shown)
            val target = analyzer.targetFor(shown)
            val dev = (shown - target) / target * 100.0
            devText.text = String.format(Locale.US, "%+.2f %% vs %s RPM", dev, RpmAnalyzer.presetLabel(target))
            devText.setTextColor(colorFor(abs(dev)))
            graph.target = target
        }

        resultText.text = if (res == null) "No result yet. A measurement takes about 10 turns." else formatResult(res)

        for ((b, v) in targetButtons) {
            val selected = v == analyzer.targetOverride
            b.background = pill(if (selected) ACCENT else Color.parseColor("#222222"))
            b.setTextColor(if (selected) BG else FG)
        }
    }

    private fun colorFor(absDevPct: Double): Int = when {
        absDevPct < 0.5 -> GOOD
        absDevPct < 1.0 -> WARN
        else -> BAD
    }

    private fun formatResult(r: MeasurementResult): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(r.timeMs))
        return buildString {
            append(String.format(Locale.US, "Last result (%s)\n", time))
            append(String.format(Locale.US, "Mean speed     %.3f RPM\n", r.meanRpm))
            append(String.format(Locale.US, "Target         %s RPM\n", RpmAnalyzer.presetLabel(r.targetRpm)))
            append(String.format(Locale.US, "Deviation      %+.2f %%\n", r.deviationPct))
            append(String.format(Locale.US, "Wow & flutter  %.2f %%  (±2σ, unweighted)\n", r.wowFlutterPct))
            append(String.format(Locale.US, "  Wow  <6 Hz   %.2f %%\n", r.wowPct))
            append(String.format(Locale.US, "  Flutter      %.2f %%\n", r.flutterPct))
            append(String.format(Locale.US, "Drift          %+.3f %%\n", r.driftPct))
            append(String.format(Locale.US, "Sampled        %.1f turns, %.1f s @ %.0f Hz", r.rotations, r.durationS, r.sampleRateHz))
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (e: RuntimeException) {
            // No audio available; the on-screen result is enough.
        }
    }

    // ---------------------------------------------------------------- UI build

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20f).toFloat()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(FG)
        background = pill(Color.parseColor("#222222"))
        setOnClickListener { onClick() }
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun weighted(marginDp: Float = 4f) =
        LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(marginDp), 0, dp(marginDp), 0) }

    private fun buildUi() {
        window.decorView.setBackgroundColor(BG)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val pad = dp(16f)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
                insets
            }
        }

        val title = TextView(this).apply {
            text = "Turntable RPM"
            textSize = 20f
            setTextColor(FG)
            typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(title)

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(DIM)
            setPadding(0, dp(6f), 0, dp(6f))
            minLines = 2
        }
        col.addView(statusText)

        rpmText = TextView(this).apply {
            textSize = 96f
            setTextColor(FG)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        col.addView(rpmText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        devText = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(DIM)
        }
        col.addView(devText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        col.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16f)).apply {
            setMargins(0, dp(12f), 0, dp(12f))
        })

        val targets = row()
        listOf<Pair<String, Double?>>(
            "Auto" to null, "33⅓" to 100.0 / 3.0, "45" to 45.0, "78" to 78.0
        ).forEach { (label, value) ->
            val b = button(label) {
                analyzer.targetOverride = value
                prefs.edit().putFloat("target", (value ?: 0.0).toFloat()).apply()
                render()
            }
            targetButtons.add(b to value)
            targets.addView(b, weighted())
        }
        col.addView(targets)

        graph = GraphView(this)
        col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180f)).apply {
            setMargins(0, dp(16f), 0, dp(16f))
        })

        resultText = TextView(this).apply {
            textSize = 14f
            setTextColor(FG)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
        }
        col.addView(resultText)

        val actions = row()
        actions.addView(button("Measure again") {
            analyzer.restart()
            graph.clear()
            render()
        }, weighted())
        actions.addView(button("Log") { showLog() }, weighted())
        actions.addView(button("Settings") { showSettings() }, weighted())
        col.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(20f), 0, 0)
        })

        setContentView(scroll)
    }

    // ---------------------------------------------------------------- log

    private fun showLog() {
        val rows = log.rows()
        val msg = if (rows.isEmpty()) "No measurements saved yet." else buildString {
            for (r in rows.asReversed()) {
                if (r.size < 5) continue
                val mean = r[1].toDoubleOrNull() ?: continue
                val dev = r[3].toDoubleOrNull() ?: 0.0
                val wf = r[4].toDoubleOrNull() ?: 0.0
                append(String.format(Locale.US, "%s\n  %.3f RPM  %+.2f %%  W&F %.2f %%\n", r[0], mean, dev, wf))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Measurement log (${rows.size})")
            .setMessage(msg)
            .setPositiveButton("Export CSV") { _, _ -> exportCsv() }
            .setNeutralButton("Clear…") { _, _ -> confirmClear() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Delete all saved measurements?")
            .setMessage("This can't be undone. Export first if you want to keep them.")
            .setPositiveButton("Delete") { _, _ ->
                log.clear()
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Uses Android's own "save as" screen, so the app needs no storage permission. */
    private fun exportCsv() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "turntable-rpm-$stamp.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EXPORT)
    }

    @Deprecated("Plain Activity API; fine for a dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(log.csv().toByteArray()) }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val items = arrayOf(
            "Zero the gyroscope",
            "Speed correction factor (now ${String.format(Locale.US, "%.4f", analyzer.scale)})",
            "Reset calibration",
            "About & privacy"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askZero()
                    1 -> askScale()
                    2 -> confirmResetCalibration()
                    3 -> showAbout()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askZero() {
        AlertDialog.Builder(this)
            .setTitle("Zero the gyroscope")
            .setMessage("Lay the phone on a flat surface that isn't moving (not the platter), then tap Start and don't touch it for 3 seconds.")
            .setPositiveButton("Start") { _, _ ->
                calSx = 0.0; calSy = 0.0; calSz = 0.0; calN = 0; calMax = 0.0
                calStart = -1L
                calibrating = true
                statusText.text = "Zeroing… keep the phone still"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleCalibration(t: Long, x: Float, y: Float, z: Float) {
        if (calStart < 0) calStart = t
        calSx += x; calSy += y; calSz += z; calN++
        calMax = max(calMax, sqrt((x * x + y * y + z * z).toDouble()))
        if (t - calStart < CAL_NS) return
        calibrating = false
        if (calMax > 0.05 || calN < 20) {
            Toast.makeText(this, "Phone moved. Try again with it lying still.", Toast.LENGTH_LONG).show()
            render()
            return
        }
        analyzer.biasX = calSx / calN
        analyzer.biasY = calSy / calN
        analyzer.biasZ = calSz / calN
        prefs.edit()
            .putFloat("biasX", analyzer.biasX.toFloat())
            .putFloat("biasY", analyzer.biasY.toFloat())
            .putFloat("biasZ", analyzer.biasZ.toFloat())
            .apply()
        val offsetRpm = sqrt(analyzer.biasX * analyzer.biasX + analyzer.biasY * analyzer.biasY +
            analyzer.biasZ * analyzer.biasZ) * RpmAnalyzer.RAD_S_TO_RPM
        Toast.makeText(this, String.format(Locale.US, "Zeroed (offset was %.4f RPM)", offsetRpm), Toast.LENGTH_LONG).show()
        render()
    }

    private fun askScale() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.4f", analyzer.scale))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Speed correction factor")
            .setMessage(
                "Phone gyros can read a little high or low. Only change this if you've checked " +
                "against a trusted reference such as a strobe disc.\n\n" +
                "Example: app says 33.60, strobe says exactly 33.33 → enter 33.33 ÷ 33.60 = 0.9920.\n\n" +
                "Allowed range 0.9000 to 1.1000. Leave at 1.0000 if unsure."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || v < 0.9 || v > 1.1) {
                    Toast.makeText(this, "Not saved: enter a number between 0.9 and 1.1", Toast.LENGTH_LONG).show()
                } else {
                    analyzer.scale = v
                    prefs.edit().putFloat("scale", v.toFloat()).apply()
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmResetCalibration() {
        AlertDialog.Builder(this)
            .setTitle("Reset calibration?")
            .setMessage("Sets the gyro zero offset back to 0 and the correction factor back to 1.0000.")
            .setPositiveButton("Reset") { _, _ ->
                analyzer.biasX = 0.0; analyzer.biasY = 0.0; analyzer.biasZ = 0.0
                analyzer.scale = 1.0
                prefs.edit().remove("biasX").remove("biasY").remove("biasZ").remove("scale").apply()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("About & privacy")
            .setMessage(
                "Turntable RPM measures platter speed with the phone's gyroscope.\n\n" +
                "Privacy: this app requests no permissions at all. It has no internet access, " +
                "no ads, no analytics and no accounts. Measurements are stored only on this " +
                "phone and leave it only if you export them yourself.\n\n" +
                "Tips: put the phone flat on a record (a mat protects it), keep it clear of the " +
                "tonearm, and let the platter reach speed. Wow & flutter here is unweighted ±2σ, " +
                "so it reads higher than DIN/IEC weighted figures on spec sheets."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
