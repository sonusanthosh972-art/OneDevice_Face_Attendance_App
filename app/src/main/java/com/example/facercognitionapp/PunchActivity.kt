package com.example.facercognitionapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.core.content.ContextCompat
import com.example.facercognitionapp.databinding.ActivityPunchBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@ExperimentalGetImage
class PunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPunchBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideResultRunnable = Runnable { hideResult() }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                cacheLocationInBackground()
            }
        }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val name = result.data?.getStringExtra(MainActivity.EXTRA_EMPLOYEE_NAME)
            val message = result.data?.getStringExtra(MainActivity.EXTRA_RESULT_MESSAGE)
            val punchType = result.data?.getStringExtra(MainActivity.EXTRA_PUNCH_TYPE) ?: ""
            showResult(name, message, punchType)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPunchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionName = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("employee_name", null)
        binding.tvAdminName.text = sessionName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.punch_default_admin_name)

        binding.btnIn.setOnClickListener {
            hideResult()
            openCamera(inOutFlag = 1, punchType = "IN")
        }
        binding.btnOut.setOnClickListener {
            hideResult()
            openCamera(inOutFlag = 2, punchType = "OUT")
        }
        binding.btnLogout.setOnClickListener { logout() }

        checkLocationAndCache()
    }

    override fun onResume() {
        super.onResume()
        cacheLocationInBackground()
    }

    private fun checkLocationAndCache() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            cacheLocationInBackground()
        } else {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun cacheLocationInBackground() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return

        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    saveCachedLocation(loc.latitude, loc.longitude)
                }
            }
            val cts = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        saveCachedLocation(loc.latitude, loc.longitude)
                    }
                }
        } catch (_: SecurityException) {}
    }

    private fun saveCachedLocation(lat: Double, lon: Double) {
        getSharedPreferences("auth", MODE_PRIVATE).edit().apply {
            putString("cached_latitude", lat.toString())
            putString("cached_longitude", lon.toString())
            apply()
        }
    }

    private fun logout() {
        mainHandler.removeCallbacks(hideResultRunnable)
        getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun openCamera(inOutFlag: Int, punchType: String) {
        cameraLauncher.launch(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_IN_OUT_FLAG, inOutFlag)
                putExtra(MainActivity.EXTRA_PUNCH_TYPE, punchType)
            }
        )
    }

    private fun showResult(name: String?, message: String?, punchType: String) {
        val emoji = if (punchType == "IN") "✅" else "🚪"

        if (!name.isNullOrBlank()) {
            binding.tvResultName.text = "$emoji $name"
            if (!message.isNullOrBlank() && message != name) {
                binding.tvResultMessage.text = message
                binding.tvResultMessage.visibility = View.VISIBLE
            } else {
                binding.tvResultMessage.text = ""
                binding.tvResultMessage.visibility = View.GONE
            }
        } else if (!message.isNullOrBlank()) {
            binding.tvResultName.text = "$emoji $message"
            binding.tvResultMessage.text = ""
            binding.tvResultMessage.visibility = View.GONE
        } else {
            binding.tvResultName.text = "$emoji $punchType Punch Done"
            binding.tvResultMessage.text = ""
            binding.tvResultMessage.visibility = View.GONE
        }

        binding.resultCard.visibility = View.VISIBLE

        // Automatically hide the recognized result card after 4 seconds
        mainHandler.removeCallbacks(hideResultRunnable)
        mainHandler.postDelayed(hideResultRunnable, 4000)
    }

    private fun hideResult() {
        mainHandler.removeCallbacks(hideResultRunnable)
        binding.resultCard.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideResultRunnable)
    }
}
