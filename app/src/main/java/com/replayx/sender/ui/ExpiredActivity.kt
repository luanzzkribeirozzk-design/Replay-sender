package com.replayx.sender.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayx.sender.security.LicenseManager

class ExpiredActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LicenseManager.clear(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(0xFF080800.toInt())
        }
        val title = TextView(this).apply {
            text = "KEY EXPIRADA"
            setTextColor(0xFFFFD60A.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "A validade desta key terminou ou ela foi revogada. Faça login novamente para continuar."
            setTextColor(0xFFE0E0E5.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(24))
        }
        val button = Button(this).apply {
            text = "Fazer login novamente"
            isAllCaps = false
            setTextColor(0xFF000000.toInt())
            setBackgroundResource(com.replayx.sender.R.drawable.btn_primary)
            setOnClickListener {
                val intent = Intent(this@ExpiredActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(message, LinearLayout.LayoutParams(-1, -2))
        root.addView(button, LinearLayout.LayoutParams(-1, dp(54)))
        setContentView(root)
    }

    override fun onBackPressed() {
        // Bloqueia retorno para não acessar o app sem licença.
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
