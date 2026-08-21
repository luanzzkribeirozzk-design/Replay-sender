package com.replayx.sender.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.replayx.sender.R
import com.replayx.sender.security.IntegrityCheck
import com.replayx.sender.security.LicenseManager
import java.util.concurrent.Executors

class LoginActivity : AppCompatActivity() {
    private lateinit var etKey: EditText
    private lateinit var btnLogin: android.widget.Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var splash: View
    private lateinit var switchRemember: SwitchMaterial
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IntegrityCheck.isValid(this)) {
            finish()
            return
        }
        setContentView(R.layout.activity_login)
        etKey = findViewById(R.id.etKey)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)
        splash = findViewById(R.id.splashScreen)
        switchRemember = findViewById(R.id.switchRemember)

        switchRemember.isChecked = LicenseManager.shouldRemember(this)
        switchRemember.isEnabled = true
        btnLogin.setOnClickListener { login(false) }
        etKey.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { login(false); true } else false
        }

        val savedKey = LicenseManager.savedKey(this)
        if (savedKey.isNotEmpty()) {
            setLoading(true)
            setStatus("Verificando acesso...", 0xFFB0B0B0.toInt())
            login(true, savedKey)
        } else {
            setupForm()
        }
    }

    private fun setupForm() {
        splash.animate().alpha(0f).setDuration(220).withEndAction { splash.visibility = View.GONE }.start()
        setLoading(false)
    }

    private fun login(auto: Boolean, saved: String = "") {
        val key = if (auto) saved else etKey.text.toString().trim()
        if (key.isEmpty()) {
            setStatus("Insira sua key", 0xFFFF5555.toInt())
            return
        }
        setLoading(true)
        if (!auto) setStatus("Validando key...", 0xFFFFD60A.toInt())
        val remember = auto || switchRemember.isChecked
        executor.execute {
            val result = LicenseManager.validate(this, key, remember)
            main.post {
                if (result.ok) {
                    if (!auto) LicenseManager.setRemember(this, remember)
                    setStatus("Key validada com sucesso", 0xFF34C759.toInt())
                    goMain()
                } else if (auto && result.networkError && LicenseManager.hasLocalLicense(this)) {
                    setStatus("Acesso local restaurado", 0xFFFFD60A.toInt())
                    goMain()
                } else {
                    LicenseManager.clear(this)
                    setupForm()
                    setStatus(if (auto) "Faça login novamente" else result.message, 0xFFFF5555.toInt())
                }
            }
        }
    }

    private fun goMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun setStatus(text: String, color: Int) {
        tvError.text = text
        tvError.setTextColor(color)
        tvError.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
