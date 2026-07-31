package com.example.facercognitionapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.facercognitionapp.camera.CameraHelper
import com.example.facercognitionapp.databinding.ActivityMainBinding
import com.example.facercognitionapp.model.RecognizeResponse
import com.example.facercognitionapp.network.ApiClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

@ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraHelper
    private lateinit var tts: TextToSpeech

    private var inOutFlag: Int = 1
    private var punchType: String = "IN"
    private var apiInFlight = false

    // ── Permission launchers ──────────────────────────────────────────────────

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) checkLocationThenCamera()
            else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                startCamera()
            } else {
                Toast.makeText(this, "Location permission is required to punch", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inOutFlag = intent.getIntExtra(EXTRA_IN_OUT_FLAG, 1)
        punchType = intent.getStringExtra(EXTRA_PUNCH_TYPE) ?: if (inOutFlag == 2) "OUT" else "IN"

        binding.tvTitle.text = if (inOutFlag == 2) "Face Scan — Punch OUT" else "Face Scan — Punch IN"
        binding.btnBack.setOnClickListener { finish() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        }

        cameraHelper = CameraHelper(
            context = this,
            lifecycleOwner = this,
            onFaceDetected = { imageFile ->
                showStatus("Recognizing…")
                sendToBackend(imageFile)
            },
            onNoFace = {
                showStatus("Align your face in the frame")
            },
            onLowLightChanged = { isDark ->
                runOnUiThread {
                    binding.screenFlashOverlay.visibility = if (isDark) View.VISIBLE else View.GONE
                    val lp = window.attributes
                    lp.screenBrightness = if (isDark)
                        android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                    else
                        android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                }
            }
        )

        checkCameraPermission()
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationThenCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkLocationThenCamera() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startCamera() {
        cameraHelper.startCamera(binding.previewView)
        showStatus("Align your face — ${if (inOutFlag == 1) "Punch IN" else "Punch OUT"}")
    }

    // ── Location helper ───────────────────────────────────────────────────────

    private suspend fun getCurrentLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            val client = LocationServices.getFusedLocationProviderClient(this)
            val cts = CancellationTokenSource()

            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (e: SecurityException) {
                cont.resume(null)
            }

            cont.invokeOnCancellation { cts.cancel() }
        }

    // ── Recognize API ─────────────────────────────────────────────────────────

    private fun sendToBackend(imageFile: File) {
        if (apiInFlight) return
        apiInFlight = true
        cameraHelper.lockForApi()

        lifecycleScope.launch {
            var isSuccess = false
            try {
                // 1. Get cached location (instant) or fetch if missing
                val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                var latitude = prefs.getString("cached_latitude", null)
                var longitude = prefs.getString("cached_longitude", null)

                if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) {
                    showStatus("Getting location…")
                    val location = getCurrentLocation()
                    if (location != null) {
                        latitude = location.latitude.toString()
                        longitude = location.longitude.toString()
                        prefs.edit()
                            .putString("cached_latitude", latitude)
                            .putString("cached_longitude", longitude)
                            .apply()
                    } else {
                        latitude = "0.0"
                        longitude = "0.0"
                    }
                } else {
                    // Pre-cached location available! Refresh location in background without delaying Recognize API
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val freshLoc = getCurrentLocation()
                            if (freshLoc != null) {
                                prefs.edit()
                                    .putString("cached_latitude", freshLoc.latitude.toString())
                                    .putString("cached_longitude", freshLoc.longitude.toString())
                                    .apply()
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 2. Validate photo
                if (!imageFile.exists() || imageFile.length() < 512L) {
                    showStatus("Photo too small. Please try again.")
                    return@launch
                }

                Log.i(TAG, "POST /Recognize | flag=$inOutFlag lat=$latitude lon=$longitude file=${imageFile.name} (${imageFile.length()} bytes)")

                // 3. Build multipart request
                val filePart = MultipartBody.Part.createFormData(
                    "file", imageFile.name,
                    imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                val textType = "text/plain".toMediaTypeOrNull()

                val savedCompanyId = getSharedPreferences("auth", MODE_PRIVATE).getInt("company_id", 1)
                val companyIdStr = savedCompanyId.toString()

                val response = ApiClient.api.recognize(
                    file = filePart,
                    inOutFlag = inOutFlag.toString().toRequestBody(textType),
                    latitude = latitude.toRequestBody(textType),
                    longitude = longitude.toRequestBody(textType),
                    companyId = companyIdStr.toRequestBody(textType)
                )

                // 4. Parse response
                val rawJson = response.body()?.string() ?: response.errorBody()?.string()
                Log.i(TAG, "Response: HTTP ${response.code()} | $rawJson")

                val parsed = RecognizeResponse.fromJson(rawJson)
                val displayText = parsed?.primaryDisplayText()?.takeIf { it.isNotBlank() }
                    ?: rawJson?.trim()?.takeIf { it.isNotBlank() }
                    ?: "(No response from server)"

                val punchSuccess = parsed?.isPunchSuccess() == true

                if (!response.isSuccessful || !punchSuccess) {
                    // Failure
                    showStatus(displayText)
                    showResultCard(success = false, message = displayText)
                    tts.speak(displayText.take(100), TextToSpeech.QUEUE_FLUSH, null, null)
                    Handler(Looper.getMainLooper()).postDelayed({ hideResultCard() }, 3000)
                    return@launch
                }

                // Success
                isSuccess = true
                val employeeName = parsed?.employeeName
                showResultCard(success = true, message = displayText, name = employeeName)
                tts.speak(
                    (employeeName?.takeIf { it.isNotBlank() }?.let { "Welcome $it" } ?: displayText).take(120),
                    TextToSpeech.QUEUE_FLUSH, null, null
                )

                Handler(Looper.getMainLooper()).postDelayed({
                    setResult(RESULT_OK, Intent().apply {
                        putExtra(EXTRA_EMPLOYEE_NAME, employeeName)
                        putExtra(EXTRA_RESULT_MESSAGE, displayText)
                        putExtra(EXTRA_PUNCH_TYPE, punchType)
                    })
                    finish()
                }, 2500)

            } catch (e: Exception) {
                Log.e(TAG, "Recognize error", e)
                showStatus("Network error: ${e.localizedMessage}")
            } finally {
                if (!isSuccess) {
                    apiInFlight = false
                    cameraHelper.unlockAfterApi()
                }
                runOnUiThread {
                    binding.screenFlashOverlay.visibility = View.GONE
                    val lp = window.attributes
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                }
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showResultCard(success: Boolean, message: String, name: String? = null) {
        runOnUiThread {
            binding.resultCard.visibility = View.VISIBLE
            binding.tvResultIcon.text = if (success) "✓" else "✕"
            binding.tvResultName.text = name?.takeIf { it.isNotBlank() } ?: message
            binding.tvResultMessage.text = if (!name.isNullOrBlank()) message else ""
            val bgColor = if (success) 0xFFC8E6C9.toInt() else 0xFFFFCDD2.toInt()
            val iconColor = if (success) 0xFF22C55E.toInt() else 0xFFEF5350.toInt()
            binding.resultCard.setCardBackgroundColor(bgColor)
            binding.tvResultIconBg.backgroundTintList =
                android.content.res.ColorStateList.valueOf(iconColor)
        }
    }

    private fun hideResultCard() {
        runOnUiThread { binding.resultCard.visibility = View.GONE }
    }

    private fun showStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
        if (::cameraHelper.isInitialized) cameraHelper.stopCamera()
        try {
            val lp = window.attributes
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RecognizeAPI"
        const val EXTRA_IN_OUT_FLAG = "IN_OUT_FLAG"
        const val EXTRA_PUNCH_TYPE = "PUNCH_TYPE"
        const val EXTRA_EMPLOYEE_NAME = "EMPLOYEE_NAME"
        const val EXTRA_RESULT_MESSAGE = "RESULT_MESSAGE"
    }
}
