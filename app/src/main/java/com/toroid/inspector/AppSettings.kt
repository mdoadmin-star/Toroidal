package com.toroid.inspector

import android.content.Context
import com.google.gson.Gson

data class AppSettings(
    // Calibration
    var pxPerMm: Double = 0.0,

    // Tolerances
    var odMaxMm: Double = 85.80,
    var odMinMm: Double = 60.00,
    var idMinMm: Double = 54.60,
    var idMaxMm: Double = 999.0,
    var htMaxMm: Double = 9.90,
    var htMinMm: Double = 0.0,
    var warnPct: Double = 3.0,

    // Job info
    var partNumber: String = "TRF-001",
    var operator: String = "",

    // App behaviour
    var torchOnStart: Boolean = false,
    var voiceFeedback: Boolean = true,
    var autoCapture: Boolean = false,   // auto-save when stable
    var stabilityFrames: Int = 4        // frames needed to be stable
) {
    fun statusOf(dim: String, value: Double?): String {
        if (value == null) return "–"
        val (lo, hi) = when (dim) {
            "OD" -> Pair(odMinMm, odMaxMm)
            "ID" -> Pair(idMinMm, idMaxMm)
            "HT" -> Pair(htMinMm, htMaxMm)
            else -> return "–"
        }
        val margin = (hi - lo) * warnPct / 100.0
        return when {
            value < lo || value > hi           -> "fail"
            value < lo + margin || value > hi - margin -> "warn"
            else                               -> "pass"
        }
    }

    fun overallStatus(od: Double?, id: Double?, ht: Double?): String {
        val statuses = listOf(
            statusOf("OD", od),
            statusOf("ID", id),
            statusOf("HT", ht)
        )
        return when {
            statuses.any { it == "fail" } -> "FAIL"
            statuses.any { it == "warn" } -> "WARN"
            statuses.all { it == "pass" } -> "PASS"
            else -> "–"
        }
    }

    companion object {
        private const val PREFS_KEY = "toroid_settings"
        private const val DATA_KEY  = "json"
        private val gson = Gson()

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            val json  = prefs.getString(DATA_KEY, null) ?: return AppSettings()
            return try { gson.fromJson(json, AppSettings::class.java) } catch (e: Exception) { AppSettings() }
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
                .edit().putString(DATA_KEY, gson.toJson(settings)).apply()
        }
    }
}
