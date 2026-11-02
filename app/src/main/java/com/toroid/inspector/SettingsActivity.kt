package com.toroid.inspector

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    // Tolerance fields
    private lateinit var etOdMin: EditText
    private lateinit var etOdMax: EditText
    private lateinit var etIdMin: EditText
    private lateinit var etIdMax: EditText
    private lateinit var etHtMin: EditText
    private lateinit var etHtMax: EditText
    private lateinit var etWarnPct: EditText

    // Job fields
    private lateinit var etPartNumber: EditText
    private lateinit var etOperator: EditText

    // Behaviour toggles
    private lateinit var swVoice: Switch
    private lateinit var swAutoCapture: Switch
    private lateinit var swTorch: Switch

    // Stability
    private lateinit var spStabilityFrames: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar_settings))
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppSettings.load(this)

        bindViews()
        populateFields()

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            if (collectAndSave()) {
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun bindViews() {
        etOdMin       = findViewById(R.id.et_od_min)
        etOdMax       = findViewById(R.id.et_od_max)
        etIdMin       = findViewById(R.id.et_id_min)
        etIdMax       = findViewById(R.id.et_id_max)
        etHtMin       = findViewById(R.id.et_ht_min)
        etHtMax       = findViewById(R.id.et_ht_max)
        etWarnPct     = findViewById(R.id.et_warn_pct)
        etPartNumber  = findViewById(R.id.et_part_number)
        etOperator    = findViewById(R.id.et_operator)
        swVoice       = findViewById(R.id.sw_voice)
        swAutoCapture = findViewById(R.id.sw_auto_capture)
        swTorch       = findViewById(R.id.sw_torch)
        spStabilityFrames = findViewById(R.id.sp_stability_frames)

        val frameOptions = arrayOf("3 frames", "4 frames", "5 frames", "6 frames")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frameOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spStabilityFrames.adapter = adapter
    }

    private fun populateFields() {
        etOdMin.setText(settings.odMinMm.toString())
        etOdMax.setText(settings.odMaxMm.toString())
        etIdMin.setText(settings.idMinMm.toString())
        etIdMax.setText(settings.idMaxMm.toString())
        etHtMin.setText(settings.htMinMm.toString())
        etHtMax.setText(settings.htMaxMm.toString())
        etWarnPct.setText(settings.warnPct.toString())
        etPartNumber.setText(settings.partNumber)
        etOperator.setText(settings.operator)
        swVoice.isChecked       = settings.voiceFeedback
        swAutoCapture.isChecked = settings.autoCapture
        swTorch.isChecked       = settings.torchOnStart
        spStabilityFrames.setSelection((settings.stabilityFrames - 3).coerceIn(0, 3))
    }

    private fun collectAndSave(): Boolean {
        val odMin  = etOdMin.text.toString().toDoubleOrNull()
        val odMax  = etOdMax.text.toString().toDoubleOrNull()
        val idMin  = etIdMin.text.toString().toDoubleOrNull()
        val idMax  = etIdMax.text.toString().toDoubleOrNull()
        val htMin  = etHtMin.text.toString().toDoubleOrNull()
        val htMax  = etHtMax.text.toString().toDoubleOrNull()
        val warn   = etWarnPct.text.toString().toDoubleOrNull()

        if (odMin == null || odMax == null || odMin >= odMax) {
            etOdMin.error = "Check OD range"; return false
        }
        if (idMin == null || idMax == null || idMin >= idMax) {
            etIdMin.error = "Check ID range"; return false
        }
        if (htMin == null || htMax == null || htMin > htMax) {
            etHtMin.error = "Check HT range"; return false
        }
        if (warn == null || warn < 0 || warn > 20) {
            etWarnPct.error = "0–20%"; return false
        }

        settings.odMinMm       = odMin
        settings.odMaxMm       = odMax
        settings.idMinMm       = idMin
        settings.idMaxMm       = idMax
        settings.htMinMm       = htMin
        settings.htMaxMm       = htMax
        settings.warnPct       = warn
        settings.partNumber    = etPartNumber.text.toString().trim().ifEmpty { "TRF-001" }
        settings.operator      = etOperator.text.toString().trim()
        settings.voiceFeedback = swVoice.isChecked
        settings.autoCapture   = swAutoCapture.isChecked
        settings.torchOnStart  = swTorch.isChecked
        settings.stabilityFrames = spStabilityFrames.selectedItemPosition + 3

        AppSettings.save(this, settings)
        return true
    }
}
