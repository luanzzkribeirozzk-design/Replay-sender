package com.replayx.sender.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var secFFM: View
    private lateinit var secFFN: View

    private lateinit var tabPermissao: android.widget.Button
    private lateinit var tabPareamento: android.widget.Button
    private lateinit var tabFFM: android.widget.Button
    private lateinit var tabFFN: android.widget.Button

    private lateinit var tvCodigo: android.widget.TextView
    private lateinit var boxCodigo: View
    private lateinit var boxConectado: View
    private lateinit var tvDispositivoModelo: android.widget.TextView
    private lateinit var tvDispositivoBateria: android.widget.TextView

    private val SHIZUKU_CODE = 4001
    private val STORAGE_CODE = 4002

    private var pollHandler: android.os.Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
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

        secPermissao = findViewById(R.id.secPermissao)
        secPareamento = findViewById(R.id.secPareamento)
        secFFM = findViewById(R.id.secFFM)
        secFFN = findViewById(R.id.secFFN)

        tabPermissao = findViewById(R.id.tabPermissao)
        tabPareamento = findViewById(R.id.tabPareamento)
        tabFFM = findViewById(R.id.tabFFM)
        tabFFN = findViewById(R.id.tabFFN)

        tvCodigo = findViewById(R.id.tvCodigo)
        boxCodigo = findViewById(R.id.boxCodigo)
        boxConectado = findViewById(R.id.boxConectado)
        tvDispositivoModelo = findViewById(R.id.tvDispositivoModelo)
        tvDispositivoBateria = findViewById(R.id.tvDispositivoBateria)

        tabPermissao.setOnClickListener { showTab(0) }
        tabPareamento.setOnClickListener { showTab(1) }
        tabFFM.setOnClickListener { showTab(2) }
        tabFFN.setOnClickListener { showTab(3) }

        findViewById<View>(R.id.btnSolicitarRoot).setOnClickListener { checarAcesso(mostrarResultado = true) }
        findViewById<View>(R.id.btnSolicitarShizuku).setOnClickListener { abrirShizuku() }
        findViewById<View>(R.id.btnSolicitarArquivos).setOnClickListener { solicitarArquivos() }

        findViewById<View>(R.id.btnGerarCodigo).setOnClickListener { gerarCodigo() }
        findViewById<View>(R.id.btnDesparear).setOnClickListener { desparear() }
        findViewById<View>(R.id.btnEnviarFFM).setOnClickListener { enviarReplay(ReplayReader.FFM_PKG, "FF MAX") }
        findViewById<View>(R.id.btnEnviarFFN).setOnClickListener { enviarReplay(ReplayReader.FFN_PKG, "FF Normal") }
        findViewById<View>(R.id.btnLimparLogs).setOnClickListener { tvLog.text = "" }

        showTab(0)
        checarAcesso(mostrarResultado = false)
        atualizarStatusPareamento()
    }

    override fun onResume() {
        super.onResume()
        checarAcesso(mostrarResultado = false)
    }

    private fun showTab(i: Int) {
        val secs = listOf(secPermissao, secPareamento, secFFM, secFFN)
        secs.forEachIndexed { idx, v ->
            if (idx == i) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(280).start()
            } else {
                v.visibility = View.GONE
            }
        }

        val tabs = listOf(tabPermissao, tabPareamento, tabFFM, tabFFN)
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

    private fun checarAcesso(mostrarResultado: Boolean) {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            val shizuku = withContext(Dispatchers.IO) { RootShell.hasShizuku() }
            when {
                root -> { tvShellStatus.text = "● Acesso root ativo"; tvShellStatus.setTextColor(0xFF34C759.toInt()) }
                shizuku -> { tvShellStatus.text = "● Shizuku ativo"; tvShellStatus.setTextColor(0xFF34C759.toInt()) }
                else -> { tvShellStatus.text = "● Sem acesso (root/Shizuku)"; tvShellStatus.setTextColor(0xFFFF453A.toInt()) }
            }
            if (mostrarResultado) {
                if (root) log("[OK] Acesso root detectado e funcionando")
                else log("[ERR] Root não detectado — tenta ativar nas configurações do emulador (BlueStacks: Configurações > Avançado > Acesso root / MSI: opção parecida) e testa de novo")
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
                        log("[ERR] Falha ao desparear")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarReplay(pkg: String, label: String) {
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
