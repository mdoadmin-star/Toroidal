package com.toroid.inspector

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*

/**
 * Calibration dialog.
 * User places toroid under camera, app detects OD in pixels,
 * user enters real OD in mm from vernier caliper → computes px/mm.
 */
class CalibrationDialog(
    context: Context,
    private val settings: AppSettings,
    private val onCalibrated: (Double) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_calibration)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvInstructions = findViewById<TextView>(R.id.tv_calib_instructions)
        val etRealOd       = findViewById<EditText>(R.id.et_real_od)
        val tvDetectedPx   = findViewById<TextView>(R.id.tv_detected_px)
        val btnDetect      = findViewById<Button>(R.id.btn_detect)
        val btnCalibrate   = findViewById<Button>(R.id.btn_calibrate)
        val btnCancel      = findViewById<Button>(R.id.btn_cancel)

        var detectedPx: Double? = null

        tvInstructions.text = """
            CALIBRATION STEPS:

            1. Place your toroid FLAT under the camera
            2. Tap "Detect OD in pixels"
            3. Enter the real OD (mm) from your vernier caliper
            4. Tap "Set Calibration"

            Tip: Use the same height/zoom for all measurements.
        """.trimIndent()

        if (settings.pxPerMm > 0) {
            tvDetectedPx.text = "Current: ${String.format("%.2f", settings.pxPerMm)} px/mm"
        }

        btnDetect.setOnClickListener {
            // Grab current measurement from MeasureEngine's last result
            // (analyzeFrame is still running in background)
            btnDetect.isEnabled = false
            btnDetect.text = "Detecting..."

            // Post delayed to let the next frame be processed
            btnDetect.postDelayed({
                val odPx = MeasureEngine.let { engine ->
                    // We need to re-run detection on a frame
                    // Signal main activity to capture a px value
                    engine.getStableResult().first?.let { odMm ->
                        if (engine.pxPerMm > 0) odMm * engine.pxPerMm else null
                    }
                }

                if (odPx != null && odPx > 10) {
                    detectedPx = odPx
                    tvDetectedPx.text = "Detected: ${String.format("%.1f", odPx)} px"
                    btnDetect.text = "✅ Detected"
                } else {
                    tvDetectedPx.text = "Not detected — point camera at toroid and retry"
                    btnDetect.isEnabled = true
                    btnDetect.text = "Detect OD in pixels"
                }
            }, 1500)
        }

        btnCalibrate.setOnClickListener {
            val realOdText = etRealOd.text.toString().trim()
            val realOd = realOdText.toDoubleOrNull()
            val px = detectedPx

            if (realOd == null || realOd <= 0) {
                etRealOd.error = "Enter real OD in mm"
                return@setOnClickListener
            }
            if (px == null || px <= 0) {
                Toast.makeText(context, "Detect OD in pixels first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pxPerMm = px / realOd
            onCalibrated(pxPerMm)
            dismiss()
        }

        btnCancel.setOnClickListener { dismiss() }
    }
}
