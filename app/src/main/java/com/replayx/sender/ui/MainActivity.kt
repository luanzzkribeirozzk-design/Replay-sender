package com.replayx.sender.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.replayx.sender.R
import com.replayx.sender.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvShellStatus: android.widget.TextView
    private lateinit var tvLog: android.widget.TextView
    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var overlayAguarde: View
    private lateinit var tvAguarde: android.widget.TextView

    private lateinit var secPareamento: View
    private lateinit var secFFM: View
    private lateinit var secFFN: View

    private lateinit var tvCodigo: android.widget.TextView
    private lateinit var tvCodigoExpira: android.widget.TextView
    private lateinit var boxCodigo: View
    private lateinit var boxConectado: View
    private lateinit var tvDispositivoModelo: android.widget.TextView
    private lateinit var tvDispositivoBateria: android.widget.TextView

    private var pollHandler: android.os.Handler? = null
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)

        if (!com.replayx.sender.security.IntegrityCheck.isValid(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvShellStatus = findViewById(R.id.tvShellStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        overlayAguarde = findViewById(R.id.overlayAguarde)
        tvAguarde = findViewById(R.id.tvAguarde)

        secPareamento = findViewById(R.id.secPareamento)
        secFFM = findViewById(R.id.secFFM)
        secFFN = findViewById(R.id.secFFN)

        tvCodigo = findViewById(R.id.tvCodigo)
        tvCodigoExpira = findViewById(R.id.tvCodigoExpira)
        boxCodigo = findViewById(R.id.boxCodigo)
        boxConectado = findViewById(R.id.boxConectado)
        tvDispositivoModelo = findViewById(R.id.tvDispositivoModelo)
        tvDispositivoBateria = findViewById(R.id.tvDispositivoBateria)

        findViewById<View>(R.id.tabPareamento).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.tabFFM).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.tabFFN).setOnClickListener { showTab(2) }

        findViewById<View>(R.id.btnGerarCodigo).setOnClickListener { gerarCodigo() }
        findViewById<View>(R.id.btnDesparear).setOnClickListener { desparear() }
        findViewById<View>(R.id.btnEnviarFFM).setOnClickListener { enviarReplay(ReplayReader.FFM_PKG, "FF MAX") }
        findViewById<View>(R.id.btnEnviarFFN).setOnClickListener { enviarReplay(ReplayReader.FFN_PKG, "FF NORMAL") }

        showTab(0)
        checarAcesso()
        atualizarStatusPareamento()
    }

    private fun showTab(i: Int) {
        currentTab = i
        secPareamento.visibility = if (i == 0) View.VISIBLE else View.GONE
        secFFM.visibility = if (i == 1) View.VISIBLE else View.GONE
        secFFN.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    private fun checarAcesso() {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            val shizuku = withContext(Dispatchers.IO) { RootShell.hasShizuku() }
            when {
                root -> { tvShellStatus.text = "● ACESSO ROOT ATIVO"; tvShellStatus.setTextColor(0xFF33CC55.toInt()) }
                shizuku -> { tvShellStatus.text = "● SHIZUKU ATIVO"; tvShellStatus.setTextColor(0xFF33CC55.toInt()) }
                else -> { tvShellStatus.text = "● SEM ACESSO (root/Shizuku)"; tvShellStatus.setTextColor(0xFFFF4444.toInt()) }
            }
        }
    }

    private fun gerarCodigo() {
        lifecycleScope.launch {
            log("[..] gerando código de pareamento...")
            val code = PairingManager.genCode()
            val ok = withContext(Dispatchers.IO) { PairingManager.createPairing(this@MainActivity, code) }
            if (!ok) {
                log("[ERR] FALHA_AO_GERAR_CODIGO")
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
                        val bateria = Fs.getLong(fields, "receiverBattery", -1)
                        tvDispositivoModelo.text = modelo
                        tvDispositivoBateria.text = if (bateria >= 0) "Bateria: $bateria%" else "Bateria: —"
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
                val bateria = Fs.getLong(fields, "receiverBattery", -1)
                tvDispositivoModelo.text = modelo
                tvDispositivoBateria.text = if (bateria >= 0) "Bateria: $bateria%" else "Bateria: —"
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
                        log("[ERR] FALHA_AO_DESPAREAR")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarReplay(pkg: String, label: String) {
        overlayAguarde.visibility = View.VISIBLE
        tvAguarde.text = "AGUARDE, ENVIANDO REPLAY $label..."
        val startMs = System.currentTimeMillis()
        lifecycleScope.launch {
            log("--------------------------------")
            log("[SYS] >> Enviar replay $label")
            val found = withContext(Dispatchers.IO) { ReplayReader.readLatest(pkg) }
            if (found == null) {
                log("[ERR] REPLAY_NAO_ENCONTRADO ($label não instalado ou sem replay salvo)")
                overlayAguarde.visibility = View.GONE
                return@launch
            }
            log("[OK] replay encontrado: ${ReplayReader.fileName(found.binPath)}")
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

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cur = tvLog.text.toString()
        val sep = System.lineSeparator()
        tvLog.text = if (cur.isEmpty()) "[$t] $msg" else "$cur$sep[$t] $msg"
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler?.removeCallbacksAndMessages(null)
    }
}
