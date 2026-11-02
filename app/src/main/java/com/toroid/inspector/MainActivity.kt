package com.toroid.inspector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // Views (found via findViewById — no view binding needed)
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvViewMode: TextView
    private lateinit var tvStability: TextView
    private lateinit var tvOd: TextView
    private lateinit var tvOdStatus: TextView
    private lateinit var tvId: TextView
    private lateinit var tvIdStatus: TextView
    private lateinit var tvHt: TextView
    private lateinit var tvHtStatus: TextView
    private lateinit var btnCapture: MaterialButton
    private lateinit var tvBatchCount: TextView
    private lateinit var btnTorch: ImageButton

    private lateinit var settings: AppSettings
    private lateinit var db: AppDatabase
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Camera
    private var cameraObj: androidx.camera.core.Camera? = null
    private var torchOn = false

    // State
    private var currentResult: MeasureEngine.MeasureResult? = null
    private var lockedOd: Double? = null
    private var lockedId: Double? = null
    private var lockedHt: Double? = null
    private var isLocked = false
    private var batchId = UUID.randomUUID().toString()
    private var batchCount = 0
    private var batchPass = 0
    private var batchFail = 0

    private var frameCount = 0
    private var isProcessing = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load OpenCV native library (bundled in the AAR — no Manager app needed)
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV failed to load", Toast.LENGTH_LONG).show()
            finish(); return
        }

        setContentView(R.layout.activity_main)

        // Bind views
        toolbar      = findViewById(R.id.toolbar)
        tvViewMode   = findViewById(R.id.tv_view_mode)
        tvStability  = findViewById(R.id.tv_stability)
        tvOd         = findViewById(R.id.tv_od)
        tvOdStatus   = findViewById(R.id.tv_od_status)
        tvId         = findViewById(R.id.tv_id)
        tvIdStatus   = findViewById(R.id.tv_id_status)
        tvHt         = findViewById(R.id.tv_ht)
        tvHtStatus   = findViewById(R.id.tv_ht_status)
        btnCapture   = findViewById(R.id.btn_capture)
        tvBatchCount = findViewById(R.id.tv_batch_count)
        btnTorch     = findViewById(R.id.btn_torch)

        settings = AppSettings.load(this)
        db = AppDatabase.getInstance(this)
        MeasureEngine.pxPerMm = settings.pxPerMm

        tts = TextToSpeech(this, this)

        setupUI()
        requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        settings = AppSettings.load(this)
        MeasureEngine.pxPerMm = settings.pxPerMm
        updateBatchDisplay()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────
    private fun setupUI() {
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Toroid Inspector"

        btnTorch.setOnClickListener {
            torchOn = !torchOn
            cameraObj?.cameraControl?.enableTorch(torchOn)
            btnTorch.setImageResource(
                if (torchOn) android.R.drawable.btn_star
                else android.R.drawable.btn_star_big_off
            )
        }

        btnCapture.setOnClickListener { captureResult() }
        btnCapture.isEnabled = false

        tvViewMode.text = "SCANNING"
        tvStability.text = "Stabilizing… 0%"
        updateBatchDisplay()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_batch   -> { newBatch(); true }
            R.id.action_export_pdf  -> { exportPdf(); true }
            R.id.action_history     -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
            R.id.action_settings    -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_calibrate   -> { showCalibrateDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showCalibrateDialog() {
        val dialog = CalibrationDialog(this, settings) { newPxPerMm ->
            settings.pxPerMm = newPxPerMm
            AppSettings.save(this, settings)
            MeasureEngine.pxPerMm = newPxPerMm
            Toast.makeText(this, "Calibrated: ${String.format("%.2f", newPxPerMm)} px/mm",
                Toast.LENGTH_LONG).show()
        }
        dialog.show()
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.preview_view)
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1280, 960))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 960))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), ::analyzeFrame) }

            try {
                cameraProvider.unbindAll()
                cameraObj = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                cameraObj?.cameraControl?.setLinearZoom(0f)

                if (settings.torchOnStart) {
                    torchOn = true
                    cameraObj?.cameraControl?.enableTorch(true)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Frame Analysis ────────────────────────────────────────────────────────
    private fun analyzeFrame(imageProxy: ImageProxy) {
        frameCount++
        if (frameCount % 3 != 0 || isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val yBytes = ByteArray(yBuffer.remaining())
        yBuffer.get(yBytes)
        val width = imageProxy.width
        val height = imageProxy.height
        imageProxy.close()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val gray = Mat(height, width, CvType.CV_8UC1)
                gray.put(0, 0, yBytes)

                val mode = MeasureEngine.detectViewMode(gray)
                val result = when (mode) {
                    MeasureEngine.ViewMode.TOP_VIEW  -> MeasureEngine.measureTopView(gray)
                    MeasureEngine.ViewMode.SIDE_VIEW -> MeasureEngine.measureSideView(gray)
                    else -> null
                }
                gray.release()

                withContext(Dispatchers.Main) {
                    if (result != null && result.isValid) {
                        currentResult = result
                        MeasureEngine.addToStabilityBuffer(result)

                        val stable   = MeasureEngine.isStable(result.mode)
                        val progress = MeasureEngine.stableProgress(result.mode)

                        if (stable && !isLocked) {
                            val (od, id, ht) = MeasureEngine.getStableResult()
                            lockedOd = od; lockedId = id; lockedHt = ht
                            isLocked = true
                            onMeasurementLocked(result.mode, od, id, ht)
                        } else if (!isLocked) {
                            updateLiveDisplay(result, progress)
                        }
                    }
                    isProcessing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isProcessing = false }
            }
        }
    }

    // ── Display Updates ───────────────────────────────────────────────────────
    private fun updateLiveDisplay(res: MeasureEngine.MeasureResult, progress: Float) {
        val pct = (progress * 100).toInt()
        tvStability.text = "Stabilizing… $pct%"
        tvViewMode.text = when (res.mode) {
            MeasureEngine.ViewMode.TOP_VIEW  -> "TOP VIEW"
            MeasureEngine.ViewMode.SIDE_VIEW -> "SIDE VIEW"
            else -> "SCANNING"
        }
        // Live measurement values
        if (res.mode == MeasureEngine.ViewMode.TOP_VIEW) {
            tvOd.text = res.odMm?.let { String.format("%.2f", it) } ?: "–"
            tvId.text = res.idMm?.let { String.format("%.2f", it) } ?: "–"
        } else if (res.mode == MeasureEngine.ViewMode.SIDE_VIEW) {
            tvHt.text = res.htMm?.let { String.format("%.2f", it) } ?: "–"
        }
    }

    private fun onMeasurementLocked(mode: MeasureEngine.ViewMode,
                                     od: Double?, id: Double?, ht: Double?) {
        tvStability.text = "✅ LOCKED"
        tvViewMode.text  = if (mode == MeasureEngine.ViewMode.TOP_VIEW) "TOP VIEW" else "SIDE VIEW"
        btnCapture.isEnabled = true

        // Show values with status
        val odEff  = if (mode == MeasureEngine.ViewMode.TOP_VIEW) od else lockedOd
        val idEff  = if (mode == MeasureEngine.ViewMode.TOP_VIEW) id else lockedId
        val htEff  = if (mode == MeasureEngine.ViewMode.SIDE_VIEW) ht else lockedHt
        val overall = settings.overallStatus(odEff, idEff, htEff)

        fun statusColor(st: String) = when (st.lowercase()) {
            "pass" -> getColor(R.color.color_pass)
            "fail" -> getColor(R.color.color_fail)
            "warn" -> getColor(R.color.color_warn)
            else   -> getColor(R.color.color_muted)
        }

        if (odEff != null) {
            tvOd.text = String.format("%.2f", odEff)
            val st = settings.statusOf("OD", odEff)
            tvOdStatus.text = st.uppercase()
            tvOdStatus.setTextColor(statusColor(st))
        }
        if (idEff != null) {
            tvId.text = String.format("%.2f", idEff)
            val st = settings.statusOf("ID", idEff)
            tvIdStatus.text = st.uppercase()
            tvIdStatus.setTextColor(statusColor(st))
        }
        if (htEff != null) {
            tvHt.text = String.format("%.2f", htEff)
            val st = settings.statusOf("HT", htEff)
            tvHtStatus.text = st.uppercase()
            tvHtStatus.setTextColor(statusColor(st))
        }

        // Voice feedback
        if (settings.voiceFeedback && ttsReady) {
            tts?.speak(overall, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        // Vibrate
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

        // Auto-capture
        if (settings.autoCapture) captureResult()
    }

    // ── Capture ───────────────────────────────────────────────────────────────
    private fun captureResult() {
        val od = lockedOd ?: currentResult?.odMm
        val id = lockedId ?: currentResult?.idMm
        val ht = lockedHt ?: currentResult?.htMm

        if (od == null && ht == null) {
            Toast.makeText(this, "No measurement to capture", Toast.LENGTH_SHORT).show()
            return
        }

        val overall = settings.overallStatus(od, id, ht)
        batchCount++
        if (overall == "PASS") batchPass++ else batchFail++
        updateBatchDisplay()

        val record = MeasurementRecord(
            partNumber = settings.partNumber,
            operator   = settings.operator,
            odMm       = od,
            idMm       = id,
            htMm       = ht,
            odStatus   = settings.statusOf("OD", od),
            idStatus   = settings.statusOf("ID", id),
            htStatus   = settings.statusOf("HT", ht),
            overall    = overall,
            batchId    = batchId
        )
        lifecycleScope.launch {
            db.measurementDao().insert(record)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Captured — $overall", Toast.LENGTH_SHORT).show()
                resetLock()
            }
        }
    }

    private fun resetLock() {
        isLocked = false
        lockedOd = null; lockedId = null; lockedHt = null
        MeasureEngine.clearBuffers()
        btnCapture.isEnabled = false
        tvStability.text = "Stabilizing… 0%"
        tvViewMode.text  = "SCANNING"
        tvOd.text = "–"; tvOdStatus.text = "–"
        tvId.text = "–"; tvIdStatus.text = "–"
        tvHt.text = "–"; tvHtStatus.text = "–"
    }

    // ── Batch ─────────────────────────────────────────────────────────────────
    private fun newBatch() {
        batchId    = UUID.randomUUID().toString()
        batchCount = 0; batchPass = 0; batchFail = 0
        updateBatchDisplay()
        Toast.makeText(this, "New batch started", Toast.LENGTH_SHORT).show()
    }

    private fun updateBatchDisplay() {
        tvBatchCount.text = "$batchCount | ✅$batchPass ❌$batchFail"
    }

    // ── PDF Export ────────────────────────────────────────────────────────────
    private fun exportPdf() {
        lifecycleScope.launch(Dispatchers.IO) {
            val records = db.measurementDao().getBatch(batchId)
            if (records.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No records in current batch", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val intent = PdfExporter.exportAndShare(this@MainActivity, records, settings)
            withContext(Dispatchers.Main) {
                if (intent != null) {
                    startActivity(Intent.createChooser(intent, "Share PDF Report"))
                } else {
                    Toast.makeText(this@MainActivity, "PDF export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
