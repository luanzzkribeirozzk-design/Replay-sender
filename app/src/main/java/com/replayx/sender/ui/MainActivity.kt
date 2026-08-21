package com.replayx.sender.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.replayx.sender.R
import com.replayx.sender.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvShellStatus: android.widget.TextView
    private lateinit var tvLog: android.widget.TextView
    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var overlayAguarde: View
    private lateinit var tvAguarde: android.widget.TextView

    private lateinit var secPermissao: View
    private lateinit var secPareamento: View
    private lateinit var secEnviar: View

    private lateinit var tabPermissao: android.widget.Button
    private lateinit var tabPareamento: android.widget.Button
    private lateinit var tabEnviar: android.widget.Button

    private lateinit var tvCodigo: android.widget.TextView
    private lateinit var boxCodigo: View
    private lateinit var boxConectado: View
    private lateinit var tvDispositivoModelo: android.widget.TextView

    private val SHIZUKU_CODE = 4001
    private val STORAGE_CODE = 4002

    private var pollHandler: android.os.Handler? = null
    private var modoAcesso = "AUTO"
    private var licenseTimer: CountDownTimer? = null
    private var licenseValidationRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!com.replayx.sender.security.SecurityGate.allow(this)) {
            redirectToLogin()
            return
        }

        setContentView(R.layout.activity_main)

        tvShellStatus = findViewById(R.id.tvShellStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        overlayAguarde = findViewById(R.id.overlayAguarde)
        tvAguarde = findViewById(R.id.tvAguarde)

        secPermissao = findViewById(R.id.secPermissao)
        secPareamento = findViewById(R.id.secPareamento)
        secEnviar = findViewById(R.id.secEnviar)

        tabPermissao = findViewById(R.id.tabPermissao)
        tabPareamento = findViewById(R.id.tabPareamento)
        tabEnviar = findViewById(R.id.tabEnviar)

        tvCodigo = findViewById(R.id.tvCodigo)
        boxCodigo = findViewById(R.id.boxCodigo)
        boxConectado = findViewById(R.id.boxConectado)
        tvDispositivoModelo = findViewById(R.id.tvDispositivoModelo)

        tabPermissao.setOnClickListener { showTab(0) }
        tabPareamento.setOnClickListener { showTab(1) }
        tabEnviar.setOnClickListener { showTab(2) }

        findViewById<View>(R.id.btnSolicitarRoot).setOnClickListener { selecionarAcesso() }
        findViewById<View>(R.id.btnSolicitarShizuku).setOnClickListener { abrirShizuku() }
        findViewById<View>(R.id.btnSolicitarArquivos).setOnClickListener { solicitarArquivos() }

        findViewById<View>(R.id.btnGerarCodigo).setOnClickListener { gerarCodigo() }
        findViewById<View>(R.id.btnCopiarCodigo).setOnClickListener { copiarCodigo() }
        findViewById<View>(R.id.btnMostrarQr).setOnClickListener { mostrarQrCode() }
        findViewById<View>(R.id.btnDesparear).setOnClickListener { desparear() }
        findViewById<View>(R.id.btnEnviarFFM).setOnClickListener { enviarReplay(ReplayReader.FFM_PKG, "FF MAX") }
        findViewById<View>(R.id.btnEnviarFFN).setOnClickListener { enviarReplay(ReplayReader.FFN_PKG, "FF Normal") }
        findViewById<View>(R.id.btnLimparLogs).setOnClickListener { tvLog.text = "" }
        findViewById<View>(R.id.btnCopiarLogs).setOnClickListener { copiarLogs() }
        findViewById<View>(R.id.tvTitulo).setOnLongClickListener { rodarDiagnostico(); true }

        showTab(0)
        checarAcesso(mostrarResultado = false)
        atualizarStatusPareamento()
        startLicenseTimer()
    }

    override fun onResume() {
        super.onResume()
        checarAcesso(mostrarResultado = false)
        revalidateLicense()
    }

    private fun revalidateLicense() {
        if (licenseValidationRunning) return
        val key = com.replayx.sender.security.LicenseManager.savedKey(this)
        if (key.isEmpty()) return
        licenseValidationRunning = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.replayx.sender.security.LicenseManager.validate(this@MainActivity, key, com.replayx.sender.security.LicenseManager.shouldRemember(this@MainActivity))
            }
            licenseValidationRunning = false
            if (!result.ok && !result.networkError) {
                com.replayx.sender.security.LicenseManager.clear(this@MainActivity)
                val intent = Intent(this@MainActivity, ExpiredActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } else if (result.ok) {
                startLicenseTimer()
            }
        }
    }

    private fun startLicenseTimer() {
        val timerView = findViewById<android.widget.TextView>(R.id.tvLicenseTimer)
        val userView = findViewById<android.widget.TextView>(R.id.tvLicenseUser)
        val user = com.replayx.sender.security.LicenseManager.savedUser(this)
        val deviceCount = com.replayx.sender.security.LicenseManager.savedDeviceCount(this)
        userView.text = (if (user.isEmpty()) "Key ativa" else "Key: $user") + " · Dispositivos: $deviceCount/2"
        val remaining = com.replayx.sender.security.LicenseManager.remainingMs(this)
        if (remaining == Long.MAX_VALUE) {
            timerView.text = "Validade: permanente"
            return
        }
        licenseTimer?.cancel()
        licenseTimer = object : CountDownTimer(remaining.coerceAtLeast(0L), 1000L) {
            override fun onTick(ms: Long) {
                timerView.text = "Validade: ${formatLicenseTime(ms)}"
                timerView.setTextColor(when {
                    ms < 86400000L -> 0xFFFF453A.toInt()
                    ms < 259200000L -> 0xFFFFD60A.toInt()
                    else -> 0xFF34C759.toInt()
                })
            }

            override fun onFinish() {
                com.replayx.sender.security.LicenseManager.clear(this@MainActivity)
                val intent = Intent(this@MainActivity, ExpiredActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }.start()
    }

    private fun formatLicenseTime(ms: Long): String {
        val seconds = ms / 1000L
        val days = seconds / 86400L
        val hours = (seconds % 86400L) / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return String.format(Locale.ROOT, "%02dd %02dh %02dm %02ds", days, hours, minutes, secs)
    }

    private fun showTab(i: Int) {
        val secs = listOf(secPermissao, secPareamento, secEnviar)
        secs.forEachIndexed { idx, v ->
            if (idx == i) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(280).start()
            } else {
                v.visibility = View.GONE
            }
        }

        val tabs = listOf(tabPermissao, tabPareamento, tabEnviar)
        tabs.forEachIndexed { idx, btn ->
            if (idx == i) {
                btn.setBackgroundResource(R.drawable.ios_tab_selected)
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(0x00000000)
                btn.setTextColor(0xFF8E8E93.toInt())
            }
        }
    }

    private fun selecionarAcesso() {
        val opcoes = arrayOf("ADB (via PC)", "Shizuku", "Root")
        val selecionado = when (modoAcesso) { "ADB" -> 0; "SHIZUKU" -> 1; "ROOT" -> 2; else -> -1 }
        android.app.AlertDialog.Builder(this)
            .setTitle("Método de acesso")
            .setSingleChoiceItems(opcoes, selecionado) { dialog, which ->
                modoAcesso = when (which) { 0 -> "ADB"; 1 -> "SHIZUKU"; else -> "ROOT" }
                dialog.dismiss()
                if (modoAcesso == "ADB") {
                    log("Aguardando ADB pelo PC")
                    android.widget.Toast.makeText(this, "ADB é conectado pelo PC", android.widget.Toast.LENGTH_SHORT).show()
                } else checarAcesso(mostrarResultado = true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checarAcesso(mostrarResultado: Boolean) {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            val shizuku = withContext(Dispatchers.IO) { RootShell.hasShizuku() }
            when (modoAcesso) {
                "ADB" -> { tvShellStatus.text = "● ADB selecionado"; tvShellStatus.setTextColor(0xFFFFD60A.toInt()) }
                "SHIZUKU" -> {
                    tvShellStatus.text = if (shizuku) "● Shizuku ativo" else "● Shizuku aguardando"
                    tvShellStatus.setTextColor(if (shizuku) 0xFF34C759.toInt() else 0xFFFFD60A.toInt())
                    if (mostrarResultado) log(if (shizuku) "Concluído" else "Aguardando Shizuku")
                }
                "ROOT" -> {
                    tvShellStatus.text = if (root) "● Root ativo" else "● Root aguardando"
                    tvShellStatus.setTextColor(if (root) 0xFF34C759.toInt() else 0xFFFFD60A.toInt())
                    if (mostrarResultado) log(if (root) "Concluído" else "Aguardando Root")
                }
                else -> {
                    tvShellStatus.text = when { root -> "● Root ativo"; shizuku -> "● Shizuku ativo"; else -> "● Escolha um acesso" }
                    tvShellStatus.setTextColor(if (root || shizuku) 0xFF34C759.toInt() else 0xFFFFD60A.toInt())
                }
            }
        }
    }

    private fun abrirShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) startActivity(intent)
                else log("[ERR] Instale o app Shizuku primeiro")
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_CODE)
            } else {
                log("[OK] Shizuku já está ativo e permitido")
            }
        } catch (e: Exception) {
            log("[ERR] " + e.message)
        }
    }

    private fun solicitarArquivos() {
        if (Build.VERSION.SDK_INT <= 32) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_CODE
            )
        } else {
            log("[OK] Nesse Android a permissão de arquivos é controlada por root/Shizuku, não precisa de permissão separada")
        }
    }

    private fun gerarCodigo() {
        if (!com.replayx.sender.security.SecurityGate.allow(this)) {
            redirectToLogin()
            return
        }
        lifecycleScope.launch {
            log("[..] gerando código de pareamento...")
            val code = PairingManager.genCode()
            val ok = withContext(Dispatchers.IO) { PairingManager.createPairing(this@MainActivity, code) }
            if (!ok) {
                log("[ERR] Falha ao gerar código")
                return@launch
            }
            tvCodigo.text = code
            boxCodigo.visibility = View.VISIBLE
            boxConectado.visibility = View.GONE
            log("[OK] código gerado: $code")
            startPolling()
        }
    }

    private fun startPolling() {
        pollHandler?.removeCallbacksAndMessages(null)
        pollHandler = android.os.Handler(mainLooper)
        lateinit var pollRunnable: Runnable
        pollRunnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    val fields = withContext(Dispatchers.IO) { PairingManager.getStatus(this@MainActivity) }
                    if (fields != null && Fs.getStr(fields, "status", "none") == "connected") {
                        val modelo = Fs.getStr(fields, "receiverModel", "—")
                        tvDispositivoModelo.text = modelo
                        boxConectado.visibility = View.VISIBLE
                        boxCodigo.visibility = View.GONE
                        log("[OK] dispositivo conectado: $modelo")
                    } else {
                        pollHandler?.postDelayed(pollRunnable, 3000)
                    }
                }
            }
        }
        pollHandler?.postDelayed(pollRunnable, 3000)
    }

    private fun atualizarStatusPareamento() {
        lifecycleScope.launch {
            val fields = withContext(Dispatchers.IO) { PairingManager.getStatus(this@MainActivity) }
            if (fields != null && Fs.getStr(fields, "status", "none") == "connected") {
                val modelo = Fs.getStr(fields, "receiverModel", "—")
                tvDispositivoModelo.text = modelo
                boxConectado.visibility = View.VISIBLE
            }
        }
    }

    private fun desparear() {
        AlertDialog.Builder(this)
            .setTitle("Desparear dispositivo")
            .setMessage("Isso desconecta o aparelho pareado. Você pode parear outro depois.")
            .setPositiveButton("Desparear") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { PairingManager.unpair(this@MainActivity) }
                    if (ok) {
                        boxConectado.visibility = View.GONE
                        boxCodigo.visibility = View.GONE
                        log("[OK] dispositivo despareado")
                    } else {
                        log("[ERR] Falha ao desparear")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarReplay(pkg: String, label: String) {
        if (!com.replayx.sender.security.SecurityGate.allow(this)) {
            redirectToLogin()
            return
        }
        overlayAguarde.visibility = View.VISIBLE
        tvAguarde.text = "Enviando replay $label…"
        val startMs = System.currentTimeMillis()
        lifecycleScope.launch {
            log("--------------------------------")
            log("[SYS] >> Enviar replay $label")
            val found = withContext(Dispatchers.IO) {
                ReplayReader.readLatest(pkg) { msg -> lifecycleScope.launch(Dispatchers.Main) { log(msg) } }
            }
            if (found == null) {
                overlayAguarde.visibility = View.GONE
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                TransferUploader.upload(this@MainActivity, found, pkg) { msg ->
                    lifecycleScope.launch(Dispatchers.Main) { log(msg) }
                }
            }
            val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
            log(if (ok) "[OK] Enviado com sucesso" else "[ERR] Falha no envio")
            log("Concluído em %.1fs".format(elapsed))
            log("--------------------------------")
            overlayAguarde.visibility = View.GONE
        }
    }

    private fun redirectToLogin() {
        licenseTimer?.cancel()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun copiarCodigo() {
        val code = tvCodigo.text.toString().trim()
        if (code.isEmpty() || code == "------") {
            log("[ERR] Nenhum código de pareamento disponível para copiar")
            return
        }
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("codigo_replayx", code))
        log("[OK] código copiado para a área de transferência")
        android.widget.Toast.makeText(this, "Código copiado", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun mostrarQrCode() {
        val code = tvCodigo.text.toString().trim()
        if (code.isEmpty() || code == "------") {
            log("[ERR] Gere um código antes de mostrar o QR")
            android.widget.Toast.makeText(this, "Gere um código de pareamento primeiro", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), 0)
            }
            val image = android.widget.ImageView(this).apply {
                setImageBitmap(QrCodeUtil.create(code))
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "QR Code de pareamento $code"
            }
            content.addView(image, android.widget.LinearLayout.LayoutParams(dp(280), dp(280)))
            val hint = android.widget.TextView(this).apply {
                text = "Escaneie com o Receiver\nCódigo: $code"
                setTextColor(0xFF222222.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
            content.addView(hint, android.widget.LinearLayout.LayoutParams(-1, -2))
            AlertDialog.Builder(this)
                .setTitle("Pareamento por QR Code")
                .setView(content)
                .setPositiveButton("Fechar", null)
                .show()
        } catch (e: Exception) {
            log("[ERR] Não foi possível gerar o QR")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun copiarLogs() {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", tvLog.text.toString()))
        android.widget.Toast.makeText(this, "Logs copiados", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun rodarDiagnostico() {
        log("[SYS] >> Rodando diagnóstico (toque longo detectado)...")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DiagDump.run { msg -> lifecycleScope.launch(Dispatchers.Main) { log(msg) } }
            }
        }
    }

    private fun log(msg: String) {
        val estado = when {
            msg.contains("ERR", ignoreCase = true) || msg.contains("falha", ignoreCase = true) || msg.contains("erro", ignoreCase = true) -> "Erro"
            msg.contains("envi", ignoreCase = true) || msg.contains("baix", ignoreCase = true) || msg.contains("copi", ignoreCase = true) -> "Enviando"
            msg.contains("paread", ignoreCase = true) || msg.contains("conect", ignoreCase = true) -> "Pareado"
            msg.contains("aguard", ignoreCase = true) || msg.contains("gerando", ignoreCase = true) -> "Aguardando"
            else -> "Concluído"
        }
        tvLog.text = estado
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler?.removeCallbacksAndMessages(null)
        licenseTimer?.cancel()
    }
}
