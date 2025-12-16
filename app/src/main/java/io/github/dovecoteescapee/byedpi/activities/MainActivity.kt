package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.services.setStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { log ->
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = log.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "=== BROADCAST RECEIVED ===")
            Log.i(TAG, "Intent action: ${intent?.action}")
            Log.i(TAG, "Intent: $intent")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            Log.i(TAG, "Sender ordinal: $senderOrd, sender: $sender")
            
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST -> {
                    Log.i(TAG, "Received STARTED_BROADCAST from $sender")
                    updateStatus()
                }
                STOPPED_BROADCAST -> {
                    Log.i(TAG, "Received STOPPED_BROADCAST from $sender")
                    updateStatus()
                }
                FAILED_BROADCAST -> {
                    Log.i(TAG, "Received FAILED_BROADCAST from $sender")
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
            Log.i(TAG, "=== BROADCAST HANDLED ===")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.statusButton.setOnClickListener {
            val (status, mode) = appStatus
            val preferences = getPreferences()
            val currentMode = preferences.mode()
            
            Log.i(TAG, "=== BUTTON CLICKED ===")
            Log.i(TAG, "Current status: $status, mode: $mode")
            Log.i(TAG, "Preferred mode from settings: $currentMode")
            Log.i(TAG, "Button isClickable: ${binding.statusButton.isClickable}, isEnabled: ${binding.statusButton.isEnabled}")
            
            // Временно отключаем кнопку для предотвращения двойных кликов
            binding.statusButton.isClickable = false
            binding.statusButton.isEnabled = false
            Log.i(TAG, "Button disabled for click handling")
            
            // Если режим изменился в настройках, обновляем статус
            if (mode != currentMode && status == AppStatus.Halted) {
                Log.i(TAG, "Mode mismatch detected, updating status: $mode -> $currentMode")
                setStatus(AppStatus.Halted, currentMode)
            }
            
            when (status) {
                AppStatus.Halted -> {
                    Log.i(TAG, "Starting service with mode: $currentMode")
                    start()
                }
                AppStatus.Running -> {
                    Log.i(TAG, "Stopping service")
                    stop()
                }
            }

            // Восстанавливаем кнопку через небольшую задержку
            binding.statusButton.postDelayed({
                Log.i(TAG, "Restoring button state after delay")
                binding.statusButton.isClickable = true
                binding.statusButton.isEnabled = true
                // Обновляем статус для синхронизации
                updateStatus()
            }, 1000)
        }

        binding.openEditorLink.setOnClickListener {
            val (status, _) = appStatus

            if (status == AppStatus.Halted) {
                val intent = Intent(this, SettingsActivity::class.java)
                val useCmdSettings = getPreferences().getBoolean("byedpi_enable_cmd_settings", false)
                intent.putExtra("open_fragment", if (useCmdSettings) "cmd" else "ui")
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (getPreferences().getBoolean("auto_connect", false) && appStatus.first != AppStatus.Running) {
            this.start()
        }

        ShortcutUtils.update(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                if (status == AppStatus.Halted) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        val mode = getPreferences().mode()
        Log.i(TAG, "=== START() CALLED ===")
        Log.i(TAG, "Mode: $mode")
        Log.i(TAG, "Current appStatus: ${appStatus.first}, ${appStatus.second}")
        
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting VPN mode")
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    Log.i(TAG, "VPN permission required, launching request")
                    vpnRegister.launch(intentPrepare)
                } else {
                    Log.i(TAG, "VPN permission granted, starting service")
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting Proxy mode")
                ServiceManager.start(this, Mode.Proxy)
            }
        }
        Log.i(TAG, "=== START() COMPLETE ===")
    }

    private fun stop() {
        val (status, mode) = appStatus
        Log.i(TAG, "=== STOP() CALLED ===")
        Log.i(TAG, "Current status: $status, mode: $mode")
        ServiceManager.stop(this)
        Log.i(TAG, "ServiceManager.stop() called")
        Log.i(TAG, "=== STOP() COMPLETE ===")
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "=== UPDATE_STATUS() CALLED ===")
        Log.i(TAG, "Current appStatus: status=$status, mode=$mode")

        val preferences = getPreferences()
        val preferredMode = preferences.mode()
        val (ip, port) = preferences.getProxyIpAndPort()

        Log.i(TAG, "Preferred mode from settings: $preferredMode")
        Log.i(TAG, "Proxy address: $ip:$port")

        binding.proxyAddress.text = getString(R.string.proxy_address, ip, port)

        // ВАЖНО: Если режим изменился в настройках, обновляем статус
        // Это нужно, чтобы кнопка показывала правильный текст после переключения режима
        if (mode != preferredMode && status == AppStatus.Halted) {
            Log.i(TAG, "Mode mismatch detected: $mode -> $preferredMode, updating status")
            setStatus(AppStatus.Halted, preferredMode)
            // Обновляем локальные переменные после изменения статуса
            val updatedStatus = appStatus
            val updatedMode = updatedStatus.second
            Log.i(TAG, "After setStatus: status=${updatedStatus.first}, mode=$updatedMode")
            updateStatusUI(updatedStatus.first, updatedMode, preferences)
        } else {
            Log.i(TAG, "No mode mismatch, updating UI with current status")
            updateStatusUI(status, mode, preferences)
        }
        Log.i(TAG, "=== UPDATE_STATUS() COMPLETE ===")
    }
    
    private fun updateStatusUI(status: AppStatus, mode: Mode, preferences: android.content.SharedPreferences) {
        Log.i(TAG, "=== UPDATE_STATUS_UI() CALLED ===")
        Log.i(TAG, "Status: $status, mode: $mode")
        Log.i(TAG, "Button state before: isClickable=${binding.statusButton.isClickable}, isEnabled=${binding.statusButton.isEnabled}")
        
        // Убеждаемся, что кнопка всегда кликабельна
        binding.statusButton.isClickable = true
        binding.statusButton.isEnabled = true
        
        Log.i(TAG, "Button state after: isClickable=${binding.statusButton.isClickable}, isEnabled=${binding.statusButton.isEnabled}")

        when (status) {
            AppStatus.Halted -> {
                val displayMode = preferences.mode()
                Log.i(TAG, "Status is Halted, display mode: $displayMode")
                when (displayMode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                        binding.statusButton.setText(R.string.vpn_connect)
                        Log.i(TAG, "Set button text to: VPN Connect")
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                        binding.statusButton.setText(R.string.proxy_start)
                        Log.i(TAG, "Set button text to: Proxy Start")
                    }
                }
            }

            AppStatus.Running -> {
                Log.i(TAG, "Status is Running, mode: $mode")
                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                        binding.statusButton.setText(R.string.vpn_disconnect)
                        Log.i(TAG, "Set button text to: VPN Disconnect")
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                        binding.statusButton.setText(R.string.proxy_stop)
                        Log.i(TAG, "Set button text to: Proxy Stop")
                    }
                }
            }
        }
        Log.i(TAG, "=== UPDATE_STATUS_UI() COMPLETE ===")
    }
}