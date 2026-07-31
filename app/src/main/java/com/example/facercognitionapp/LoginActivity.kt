package com.example.facercognitionapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.facercognitionapp.databinding.ActivityLoginBinding
import com.example.facercognitionapp.model.MobileAppAuthRequest
import com.example.facercognitionapp.network.ApiClient
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.loginBtn.setOnClickListener { login() }
    }

    private fun login() {
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email ID and password", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        val request = MobileAppAuthRequest(emailId = email, mobileAppPassword = password)

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.mobileAppAuthentication(request)

                if (!response.isSuccessful) {
                    val msg = "Login failed (HTTP ${response.code()})"
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    return@launch
                }

                val body = response.body()
                if (body == null || !body.success || body.data == null) {
                    val msg = body?.message ?: "Invalid email or password. Please try again."
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    return@launch
                }

                val userData = body.data
                val companyId = userData.companyId ?: 1
                val userId = userData.userId ?: 0
                val userName = userData.name ?: ""

                // Persist session data needed for Recognize API
                getSharedPreferences("auth", MODE_PRIVATE).edit().apply {
                    putBoolean("logged_in", true)
                    putInt("user_id", userId)
                    putInt("employee_id", userId)
                    putInt("company_id", companyId)
                    putString("employee_name", userName)
                    putString("email_id", userData.emailId ?: email)
                    putString("contact_no", userData.contactNo ?: "")
                    apply()
                }

                Toast.makeText(this@LoginActivity, "Welcome, $userName", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, PunchActivity::class.java))
                finish()

            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Network error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginBtn.isEnabled = !loading
        binding.loginBtn.text = if (loading) "Please wait…" else "Sign In"
    }
}
