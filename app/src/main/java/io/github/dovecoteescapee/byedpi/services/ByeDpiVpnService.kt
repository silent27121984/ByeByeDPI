package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.utility.checkIp
import io.github.dovecoteescapee.byedpi.utility.checkPort
import io.github.dovecoteescapee.byedpi.utility.checkDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch {
                    start()
                }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch {
                    stop()
                }
                START_NOT_STICKY
            }

            RESUME_ACTION -> {
                lifecycleScope.launch {
                    if (prepare(this@ByeDpiVpnService) == null) {
                        start()
                    }
                }
                START_STICKY
            }

            PAUSE_ACTION -> {
                lifecycleScope.launch {
                    stop()
                    createNotificationPause()
                }
                START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        mutex.withLock {
            if (status == ServiceStatus.Connected) {
                Log.w(TAG, "VPN already connected")
                return
            }
        }

        try {
            startForeground()
            mutex.withLock {
                startProxy()
                startTun2Socks()
            }
            updateStatus(ServiceStatus.Connected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            updateStatus(ServiceStatus.Failed)
            lifecycleScope.launch {
                stop()
            }
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        try {
            withContext(Dispatchers.IO) {
                stopProxy()
                stopTun2Socks()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена job - это нормально при остановке
            Log.d(TAG, "Job cancellation during stop (normal)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
        } finally {
            // Всегда обновляем статус и останавливаем сервис, даже если произошла ошибка
            updateStatus(ServiceStatus.Disconnected)
            stopSelf()
        }
    }

    private fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        // Validate proxy settings before starting
        val sharedPreferences = getPreferences()
        val (ip, portStr) = sharedPreferences.getProxyIpAndPort()
        
        if (!checkIp(ip)) {
            throw IllegalArgumentException("Invalid proxy IP address: $ip")
        }
        
        val port = portStr.toIntOrNull()
        if (port == null || !checkPort(port)) {
            throw IllegalArgumentException("Invalid proxy port: $portStr")
        }

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val code = byeDpiProxy.startProxy(preferences)
                delay(500)

                if (code != 0) {
                    Log.e(TAG, "Proxy stopped with code $code")
                    updateStatus(ServiceStatus.Failed)
                } else {
                    // Код 0 означает нормальное завершение
                    Log.i(TAG, "Proxy stopped normally")
                    updateStatus(ServiceStatus.Disconnected)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена job - это нормальное завершение при остановке
                Log.i(TAG, "Proxy job was cancelled (normal shutdown)")
                updateStatus(ServiceStatus.Disconnected)
            } catch (e: Exception) {
                Log.e(TAG, "Error in proxy execution", e)
                updateStatus(ServiceStatus.Failed)
            } finally {
                stopTun2Socks()
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        // Получаем currentJob без блокировки, чтобы избежать deadlock
        val currentJob = mutex.withLock { proxyJob }

        try {
            // Сначала пытаемся остановить прокси через JNI
            // Всегда вызываем, даже если status уже Disconnected,
            // чтобы убедиться, что native proxy действительно остановлен
            try {
                Log.d(TAG, "Calling byeDpiProxy.stopProxy()")
                val result = byeDpiProxy.stopProxy()
                Log.d(TAG, "byeDpiProxy.stopProxy() returned: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop proxy via JNI, trying force close", e)
                try {
                    Log.d(TAG, "Calling byeDpiProxy.jniForceClose()")
                    val result = byeDpiProxy.jniForceClose()
                    Log.d(TAG, "byeDpiProxy.jniForceClose() returned: $result")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to force close proxy", e2)
                }
            }

            // Затем отменяем job
            currentJob?.cancel()

            // Ждем завершения job с таймаутом
            val completed = withTimeoutOrNull(5000) {
                currentJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, force closing...")
                try {
                    byeDpiProxy.jniForceClose()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to force close proxy", e)
                }
            }

            mutex.withLock {
                proxyJob = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена job - это нормально при остановке
            Log.d(TAG, "Proxy job cancellation during stop (normal)")
            mutex.withLock {
                proxyJob = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
            mutex.withLock {
                proxyJob = null
            }
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val (ip, portStr) = sharedPreferences.getProxyIpAndPort()
        
        // Validate IP and port
        if (!checkIp(ip)) {
            throw IllegalArgumentException("Invalid proxy IP address: $ip")
        }
        
        val port = portStr.toIntOrNull()
        if (port == null || !checkPort(port)) {
            throw IllegalArgumentException("Invalid proxy port: $portStr")
        }

        val dns = sharedPreferences.getStringNotNull("dns_ip", "8.8.8.8")
        if (dns.isNotBlank() && !checkIp(dns)) {
            Log.w(TAG, "Invalid DNS address: $dns, using default 8.8.8.8")
        }
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")

            appendLine("misc:")
            appendLine("  task-stack-size: 81920")

            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }

        val configPath = try {
            File.createTempFile("config", ".tmp", cacheDir).apply {
                writeText(tun2socksConfig)
                deleteOnExit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw IllegalStateException("Failed to create config file", e)
        }

        val fd = try {
            createBuilder(dns, ipv6).establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN connection", e)
            try {
                configPath.delete()
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to delete config file", e2)
            }
            throw IllegalStateException("VPN connection failed", e)
        } ?: run {
            try {
                configPath.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete config file", e)
            }
            throw IllegalStateException("VPN connection failed: establish() returned null")
        }

        this.tunFd = fd

        try {
            TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TProxyService", e)
            try {
                fd.close()
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to close fd", e2)
            }
            tunFd = null
            try {
                configPath.delete()
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to delete config file", e2)
            }
            throw IllegalStateException("Failed to start TProxyService", e)
        }

        Log.i(TAG, "Tun2Socks started. ip: $ip port: $port")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TProxyService", e)
        }

        // Clean up all temporary config files
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("config") && file.name.endsWith(".tmp")) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete config file: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup config files", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            ByeDpiVpnService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("ByeDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            // Validate DNS before adding
            if (checkIp(dns) || checkDomain(dns)) {
                try {
                    builder.addDnsServer(dns)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add DNS server: $dns", e)
                    // Fallback to default DNS
                    builder.addDnsServer("8.8.8.8")
                }
            } else {
                Log.w(TAG, "Invalid DNS address: $dns, using default 8.8.8.8")
                builder.addDnsServer("8.8.8.8")
            }
        } else {
            // Default DNS if not specified
            builder.addDnsServer("8.8.8.8")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = getPreferences()
        val listType = preferences.getStringNotNull("applist_type", "disable")
        val listedApps = preferences.getSelectedApps()

        when (listType) {
            "blacklist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в черный список", e)
                    }
                }

                builder.addDisallowedApplication(applicationContext.packageName)
            }

            "whitelist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в белый список", e)
                    }
                }
            }

            "disable" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }

        return builder
    }
}
