package io.github.jerahya.camheadtracker

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : AppCompatActivity() {
    private companion object {
        val RESOLUTIONS = arrayOf(Size(240, 320), Size(480, 640), Size(720, 1280))

        // bump when default axis signs change, resets saved invert boxes
        const val AXIS_SIGNS_VERSION = 2
    }

    private lateinit var root: LinearLayout
    private lateinit var cameraPanel: View
    private lateinit var controls: View
    private lateinit var wireframe: FaceWireframeView
    private lateinit var readout: TextView
    private lateinit var status: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var startStop: Button
    private lateinit var exposureBar: SeekBar
    private lateinit var exposureLabel: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val processor = PoseProcessor()
    private val sender = UdpSender()
    private val pose = DoubleArray(PoseProcessor.AXES)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private var tracker: FaceTracker? = null
    private var analysis: ImageAnalysis? = null
    private var fpsChoices = intArrayOf()
    private var exposureMin = 0
    private var rotation = -1
    private var countdown = 0

    @Volatile private var showImage = true
    @Volatile private var boost = 1f
    @Volatile private var sending = false
    @Volatile private var hasPose = false
    private var lastReadoutMs = 0L
    private var rateSinceMs = 0L
    private var rateFrames = 0
    @Volatile private var frameRate = 0.0

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN)
                    return
                val newRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (newRotation == rotation)
                    return
                val first = rotation < 0
                rotation = newRotation
                analysis?.targetRotation = newRotation
                if (!first)
                    startCalibration()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        cameraPanel = findViewById(R.id.cameraPanel)
        controls = findViewById(R.id.controls)
        wireframe = findViewById(R.id.wireframe)
        readout = findViewById(R.id.readout)
        status = findViewById(R.id.status)
        hostEdit = findViewById(R.id.host)
        portEdit = findViewById(R.id.port)
        startStop = findViewById(R.id.startStop)
        exposureBar = findViewById(R.id.exposure)
        exposureLabel = findViewById(R.id.exposureLabel)
        applyOrientation(resources.configuration)

        if (prefs.getInt("axisSigns", 1) < AXIS_SIGNS_VERSION) {
            val edit = prefs.edit()
            for (i in 0 until PoseProcessor.AXES)
                edit.remove("invert$i")
            edit.putInt("axisSigns", AXIS_SIGNS_VERSION).apply()
        }

        hostEdit.setText(prefs.getString("host", "192.168.1.100"))
        portEdit.setText(prefs.getInt("port", 4242).toString())
        startStop.setOnClickListener { if (sending) stopSending() else startSending() }
        findViewById<Button>(R.id.calibrate).setOnClickListener { startCalibration() }
        updateSendingUi()

        setupViewToggles()
        setupSmoothing()
        setupBoost()
        setupResolution()
        setupAxes()
        findViewById<TextView>(R.id.licenses).setOnClickListener { showLicenses() }
        exposureLabel.setText(R.string.exposure_unsupported)
        exposureBar.isEnabled = false

        cameraExecutor = Executors.newSingleThreadExecutor()
        if (hasCameraPermission())
            startCamera()
        else
            requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation())
            orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.execute { tracker?.close() }
        cameraExecutor.shutdown()
        sender.shutdown()
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        cameraPanel.layoutParams = if (landscape)
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        else
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        controls.layoutParams = if (landscape)
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        else
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f)
    }

    // This app's license and the third-party notices (copied into the assets at build time).
    private fun showLicenses() {
        val text = listOf("LICENSE", "THIRD_PARTY_NOTICES.txt").joinToString("\n\n") { name ->
            assets.open(name).bufferedReader().use { it.readText() }
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            setHorizontallyScrolling(true) // the files are 80 columns: scroll, don't wrap
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.licenses)
            .setView(ScrollView(this).apply { addView(HorizontalScrollView(context).apply { addView(view) }) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun setupViewToggles() {
        val imageBox = findViewById<CheckBox>(R.id.showImage)
        val pointsBox = findViewById<CheckBox>(R.id.showPoints)
        showImage = prefs.getBoolean("showImage", true)
        imageBox.isChecked = showImage
        pointsBox.isChecked = prefs.getBoolean("showPoints", true)
        wireframe.showImage = showImage
        wireframe.showPoints = pointsBox.isChecked
        imageBox.setOnCheckedChangeListener { _, checked ->
            showImage = checked
            wireframe.showImage = checked
            tracker?.keepImage = checked
            prefs.edit().putBoolean("showImage", checked).apply()
        }
        pointsBox.setOnCheckedChangeListener { _, checked ->
            wireframe.showPoints = checked
            prefs.edit().putBoolean("showPoints", checked).apply()
        }
    }

    private fun setupSmoothing() {
        val bar = findViewById<SeekBar>(R.id.smoothing)
        val label = findViewById<TextView>(R.id.smoothingLabel)
        fun show(value: Int) {
            processor.smoothing = value / 100.0
            label.text = getString(R.string.smoothing,
                if (value == 0) getString(R.string.smoothing_off) else value.toString())
        }
        bar.progress = prefs.getInt("smoothing", 0)
        show(bar.progress)
        bar.setOnSeekBarChangeListener(seekListener { value ->
            show(value)
            prefs.edit().putInt("smoothing", value).apply()
        })
    }

    private fun setupBoost() {
        val bar = findViewById<SeekBar>(R.id.boost)
        val label = findViewById<TextView>(R.id.boostLabel)
        fun show(value: Int) {
            boost = 1f + value / 50f
            tracker?.boost = boost
            label.text = getString(R.string.boost,
                if (value == 0) getString(R.string.boost_off) else String.format("%.1fx", boost))
        }
        bar.progress = prefs.getInt("boost", 0)
        show(bar.progress)
        bar.setOnSeekBarChangeListener(seekListener { value ->
            show(value)
            prefs.edit().putInt("boost", value).apply()
        })
    }

    private fun setupResolution() {
        val spinner = findViewById<Spinner>(R.id.resolution)
        val names = RESOLUTIONS.map { "${it.height} x ${it.width}" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(prefs.getInt("resolution", 0).coerceIn(0, RESOLUTIONS.size - 1))
        spinner.onItemSelectedListener = itemListener { index ->
            if (index != prefs.getInt("resolution", 0)) {
                prefs.edit().putInt("resolution", index).apply()
                restartCamera()
            }
        }
    }

    private fun setupFrameRate(ranges: List<Range<Int>>) {
        val spinner = findViewById<Spinner>(R.id.fps)
        val rates = ranges.map { it.upper }.distinct().sortedDescending()
        fpsChoices = intArrayOf(0) + rates.toIntArray()
        val names = fpsChoices.map { rate ->
            when {
                rate == 0 -> getString(R.string.fps_auto)
                ranges.any { it.lower == rate && it.upper == rate } -> getString(R.string.fps_fixed, rate)
                else -> getString(R.string.fps_up_to, rate)
            }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(fpsChoices.indexOf(prefs.getInt("fps", 30)).coerceAtLeast(0))
        spinner.onItemSelectedListener = itemListener { index ->
            val rate = fpsChoices[index]
            if (rate != prefs.getInt("fps", 30)) {
                prefs.edit().putInt("fps", rate).apply()
                restartCamera()
            }
        }
    }

    private fun setupExposure(camera: Camera) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            exposureBar.isEnabled = false
            exposureLabel.setText(R.string.exposure_unsupported)
            return
        }
        val range = state.exposureCompensationRange
        val step = state.exposureCompensationStep.toFloat()
        exposureMin = range.lower
        fun show(index: Int) {
            exposureLabel.text = getString(R.string.exposure, String.format("%+.1f", index * step))
        }
        val index = prefs.getInt("exposure", 0).coerceIn(range.lower, range.upper)
        exposureBar.setOnSeekBarChangeListener(null)
        exposureBar.max = range.upper - range.lower
        exposureBar.progress = index - range.lower
        exposureBar.isEnabled = true
        camera.cameraControl.setExposureCompensationIndex(index)
        show(index)
        exposureBar.setOnSeekBarChangeListener(seekListener { value ->
            val newIndex = value + exposureMin
            camera.cameraControl.setExposureCompensationIndex(newIndex)
            prefs.edit().putInt("exposure", newIndex).apply()
            show(newIndex)
        })
    }

    private fun setupAxes() {
        val grid = findViewById<GridLayout>(R.id.invertGrid)
        val enabled = BooleanArray(PoseProcessor.AXES) { prefs.getBoolean("enable$it", true) }
        val inverted = BooleanArray(PoseProcessor.AXES) { prefs.getBoolean("invert$it", false) }
        processor.enabled = enabled.copyOf()
        processor.inverted = inverted.copyOf()

        fun cell(view: View) = grid.addView(view, GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        })
        fun header(text: Int) = cell(TextView(this).apply {
            setText(text)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        })
        header(R.string.axes)
        header(R.string.axis_send)
        header(R.string.axis_invert)

        for (i in 0 until PoseProcessor.AXES) {
            cell(TextView(this).apply {
                text = PoseProcessor.AXIS_NAMES[i]
                textSize = 16f
            })
            cell(CheckBox(this).apply {
                isChecked = enabled[i]
                setOnCheckedChangeListener { _, checked ->
                    enabled[i] = checked
                    processor.enabled = enabled.copyOf()
                    prefs.edit().putBoolean("enable$i", checked).apply()
                }
            })
            cell(CheckBox(this).apply {
                isChecked = inverted[i]
                setOnCheckedChangeListener { _, checked ->
                    inverted[i] = checked
                    processor.inverted = inverted.copyOf()
                    prefs.edit().putBoolean("invert$i", checked).apply()
                }
            })
        }
    }

    private fun startCalibration() {
        handler.removeCallbacksAndMessages(null)
        countdown = 3
        calibrationTick()
    }

    private fun calibrationTick() {
        if (countdown > 0) {
            status.text = getString(R.string.calibrating, countdown)
            countdown--
            handler.postDelayed(::calibrationTick, 1000)
        } else {
            processor.calibrate()
            status.setText(R.string.calibrated)
            handler.postDelayed({ status.text = "" }, 1500)
        }
    }

    private fun startSending() {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull() ?: 4242
        prefs.edit().putString("host", host).putInt("port", port).apply()
        sender.start(host, port)
        sending = true
        updateSendingUi()
    }

    private fun stopSending() {
        sending = false
        sender.stop()
        updateSendingUi()
    }

    private fun updateSendingUi() {
        startStop.setText(if (sending) R.string.stop else R.string.start)
        val color = ContextCompat.getColor(this, if (sending) R.color.danger else R.color.accent)
        startStop.backgroundTintList = ColorStateList.valueOf(color)
        hostEdit.isEnabled = !sending
        portEdit.isEnabled = !sending
    }

    private fun restartCamera() {
        if (hasCameraPermission())
            startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ranges = frontCameraFpsRanges(provider)
            if (fpsChoices.isEmpty())
                setupFrameRate(ranges)

            val size = RESOLUTIONS[prefs.getInt("resolution", 0).coerceIn(0, RESOLUTIONS.size - 1)]
            val builder = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            val rate = prefs.getInt("fps", 30)
            if (rate > 0) {
                val range = ranges.firstOrNull { it.lower == rate && it.upper == rate }
                    ?: ranges.filter { it.upper == rate }.maxByOrNull { it.lower }
                    ?: Range(rate, rate)
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            val useCase = builder.build()
            if (rotation >= 0)
                useCase.targetRotation = rotation
            useCase.setAnalyzer(cameraExecutor) { image ->
                val t = tracker ?: FaceTracker(this, ::onTrackerResult).also {
                    it.keepImage = showImage
                    it.boost = boost
                    tracker = it
                }
                t.analyze(image)
            }
            analysis = useCase
            provider.unbindAll()
            setupExposure(provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun frontCameraFpsRanges(provider: ProcessCameraProvider): List<Range<Int>> = try {
        val info = CameraSelector.DEFAULT_FRONT_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
        val ranges = info?.let {
            Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }
        ranges?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun onTrackerResult(result: FaceResult) {
        val matrix = result.matrix
        if (matrix != null) {
            processor.process(matrix, result.timestampMs, pose)
            hasPose = true
        }
        // keep sending the last pose while the face is lost
        if (sending && hasPose)
            sender.send(pose)

        wireframe.update(result.image, result.landmarks, result.imageWidth, result.imageHeight)

        val now = SystemClock.uptimeMillis()
        rateFrames++
        if (now - rateSinceMs >= 1000) {
            frameRate = rateFrames * 1000.0 / (now - rateSinceMs)
            rateFrames = 0
            rateSinceMs = now
        }
        if (now - lastReadoutMs < 100)
            return
        lastReadoutMs = now
        val target = if (sending) getString(R.string.sending, hostEdit.text) else getString(R.string.not_sending)
        val text = getString(R.string.live, frameRate, target) + "\n" +
            if (matrix == null) getString(R.string.no_face) else formatPose(pose)
        runOnUiThread { readout.text = text }
    }

    private fun formatPose(p: DoubleArray): String =
        String.format(
            "x %6.1f  y %6.1f  z %6.1f cm\nyaw %6.1f  pitch %6.1f  roll %6.1f°",
            p[0], p[1], p[2], p[3], p[4], p[5],
        )

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser)
                onChange(value)
        }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    }

    private fun itemListener(onSelect: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }
}
