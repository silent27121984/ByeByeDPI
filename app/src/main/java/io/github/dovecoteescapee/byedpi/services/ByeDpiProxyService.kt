package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.utility.checkIp
import io.github.dovecoteescapee.byedpi.utility.checkPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
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
                    start()
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

            null -> {
                // Android может автоматически перезапустить сервис с null Intent
                // В этом случае просто останавливаем сервис, чтобы избежать проблем
                Log.w(TAG, "Received null action (likely auto-restart), stopping service")
                lifecycleScope.launch {
                    stopSelf()
                }
                START_NOT_STICKY
            }
            
            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "=== START() CALLED ===")
        Log.i(TAG, "Current status: $status")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        mutex.withLock {
            Log.i(TAG, "Checking status in mutex: $status")
            if (status == ServiceStatus.Connected) {
                Log.w(TAG, "Proxy already connected, returning")
                return
            }
            Log.i(TAG, "Status is not Connected, proceeding with start")
        }

        try {
            Log.i(TAG, "Calling startForeground()")
            startForeground()
            Log.i(TAG, "startForeground() completed")
            
            // ВАЖНО: Используем withTimeoutOrNull для избежания бесконечного ожидания mutex
            // Это нужно, если stopProxy() еще выполняется и держит mutex
            Log.i(TAG, "Trying to acquire mutex for startProxy() (timeout 5s)")
            val result = withTimeoutOrNull(5000) {
                mutex.withLock {
                    Log.i(TAG, "Acquired mutex for startProxy(), current proxyJob: $proxyJob")
                    if (proxyJob != null) {
                        Log.w(TAG, "proxyJob is not null, cancelling it first")
                        proxyJob?.cancel()
                        proxyJob = null
                        Log.i(TAG, "proxyJob cancelled and cleared")
                    }
                    startProxy()
                }
            }
            
            if (result == null) {
                Log.e(TAG, "Failed to acquire mutex for startProxy() within timeout (5s)")
                Log.e(TAG, "This usually means stopProxy() is still running and holding the mutex")
                updateStatus(ServiceStatus.Failed)
                return
            }
            
            Log.i(TAG, "startProxy() completed, updating status to Connected")
            updateStatus(ServiceStatus.Connected)
            Log.i(TAG, "=== START() COMPLETE ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
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
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "=== STOP() CALLED ===")
        Log.i(TAG, "Current status before stop: $status")

        // ВАЖНО: Сначала обновляем статус, чтобы UI сразу получил обновление
        // Это нужно, чтобы кнопка сразу стала активной, даже если stopProxy() зависнет
        Log.i(TAG, "Updating status to Disconnected BEFORE stopping proxy")
        updateStatus(ServiceStatus.Disconnected)
        
        // ВАЖНО: Получаем proxyJob БЕЗ блокировки mutex на все время выполнения stopProxy()
        // Это позволяет start() получить mutex, даже если stopProxy() еще выполняется
        // ВАЖНО: Получаем proxyJob ДО обновления статуса, чтобы он не стал null
        val jobToStop = mutex.withLock {
            Log.i(TAG, "Getting proxyJob for stopping, current proxyJob: $proxyJob")
            proxyJob
        }
        
        try {
            // Выполняем stopProxy() БЕЗ блокировки mutex
            // Это позволяет start() получить mutex и запустить новый прокси
            Log.i(TAG, "Calling stopProxy() without mutex lock")
            withContext(Dispatchers.IO) {
                stopProxy(jobToStop)
            }
            Log.i(TAG, "stopProxy() completed")
            
            // Очищаем proxyJob после остановки
            mutex.withLock {
                Log.i(TAG, "Clearing proxyJob after stop")
                proxyJob = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in stop()", e)
            // Очищаем proxyJob даже при ошибке
            mutex.withLock {
                proxyJob = null
            }
        } finally {
            Log.i(TAG, "In finally block")
            // Убеждаемся, что статус обновлен (на случай, если он не обновился выше)
            if (status != ServiceStatus.Disconnected) {
                Log.i(TAG, "Status is not Disconnected ($status), updating again")
                updateStatus(ServiceStatus.Disconnected)
            }
            Log.i(TAG, "Calling stopSelf()")
            stopSelf()
            Log.i(TAG, "=== STOP() COMPLETE ===")
        }
    }

    private fun startProxy() {
        Log.i(TAG, "=== START_PROXY() CALLED ===")
        Log.i(TAG, "Current proxyJob: $proxyJob")

        // Если proxyJob существует, отменяем его перед запуском нового
        if (proxyJob != null) {
            Log.w(TAG, "proxyJob is not null, cancelling it first")
            proxyJob?.cancel()
            proxyJob = null
            Log.i(TAG, "proxyJob cancelled and cleared")
        }

        // Validate proxy settings before starting
        val preferences = getByeDpiPreferences()
        val sharedPreferences = getPreferences()
        val (ip, portStr) = sharedPreferences.getProxyIpAndPort()
        
        if (!checkIp(ip)) {
            throw IllegalArgumentException("Invalid proxy IP address: $ip")
        }
        
        val port = portStr.toIntOrNull()
        if (port == null || !checkPort(port)) {
            throw IllegalArgumentException("Invalid proxy port: $portStr")
        }

        proxy = ByeDpiProxy()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Устанавливаем статус Connected сразу после запуска прокси
                // startProxy() блокирует выполнение, пока прокси работает
                updateStatus(ServiceStatus.Connected)
                
                val code = proxy.startProxy(preferences)
                
                // Когда startProxy() возвращается, прокси завершился
                if (code != 0) {
                    Log.e(TAG, "Proxy stopped with code $code")
                    updateStatus(ServiceStatus.Failed)
                } else {
                    Log.i(TAG, "Proxy stopped normally (code 0)")
                    updateStatus(ServiceStatus.Disconnected)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена job - это нормальное завершение при остановке
                Log.i(TAG, "Proxy job was cancelled (normal shutdown)")
                // НЕ обновляем статус на Failed при нормальной отмене
                // Статус уже обновлен в stop() на Disconnected
            } catch (e: Exception) {
                Log.e(TAG, "Error in proxy execution", e)
                updateStatus(ServiceStatus.Failed)
            } finally {
                // Останавливаем сервис после завершения прокси
                // Это нужно, чтобы сервис не оставался висеть после завершения прокси
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy(jobToStop: Job? = null) {
        Log.i(TAG, "=== STOP_PROXY() CALLED ===")
        Log.i(TAG, "Job to stop: $jobToStop")

        // ВАЖНО: Получаем proxyJob БЕЗ блокировки mutex, как в VPN сервисе
        val currentJob = jobToStop ?: mutex.withLock { proxyJob }
        
        Log.i(TAG, "Current proxyJob: $currentJob")

        try {
            // ВАЖНО: Сначала пытаемся остановить нативный прокси через JNI
            // Всегда вызываем, даже если status уже Disconnected или proxyJob null,
            // чтобы убедиться, что native proxy действительно остановлен
            try {
                Log.i(TAG, "Calling proxy.stopProxy() to stop native proxy")
                proxy.stopProxy()
                Log.i(TAG, "proxy.stopProxy() completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop proxy via JNI, trying force close", e)
                try {
                    Log.i(TAG, "Calling proxy.jniForceClose()")
                    proxy.jniForceClose()
                    Log.i(TAG, "proxy.jniForceClose() completed")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to force close proxy", e2)
                }
            }

            // Затем отменяем job, если он существует
            if (currentJob != null) {
                Log.i(TAG, "Cancelling proxyJob")
                currentJob.cancel()
                
                // Ждем завершения job с таймаутом
                Log.i(TAG, "Waiting for proxyJob to complete (timeout 5s)")
                val completed = withTimeoutOrNull(5000) {
                    currentJob.join()
                    true
                }

                if (completed == null) {
                    Log.w(TAG, "proxyJob did not finish in time, force closing...")
                    try {
                        proxy.jniForceClose()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to force close proxy", e)
                    }
                } else {
                    Log.i(TAG, "proxyJob completed successfully")
                }
            } else {
                Log.i(TAG, "proxyJob is null, skipping job cancellation")
            }

            // Очищаем proxyJob в mutex
            mutex.withLock {
                Log.i(TAG, "Clearing proxyJob")
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
                Log.i(TAG, "Clearing proxyJob after error")
                proxyJob = null
            }
        }

        Log.i(TAG, "=== STOP_PROXY() COMPLETE ===")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.i(TAG, "=== UPDATE_STATUS() CALLED ===")
        Log.i(TAG, "Proxy status changed from $status to $newStatus")

        status = newStatus

        val appStatus = when (newStatus) {
            ServiceStatus.Connected -> AppStatus.Running
            ServiceStatus.Disconnected,
            ServiceStatus.Failed -> {
                proxyJob = null
                AppStatus.Halted
            }
        }
        
        Log.i(TAG, "Setting appStatus to: $appStatus, mode: Proxy")
        setStatus(appStatus, Mode.Proxy)
        Log.i(TAG, "After setStatus, current appStatus: ${io.github.dovecoteescapee.byedpi.services.appStatus}")

        val broadcastAction = when (newStatus) {
            ServiceStatus.Connected -> STARTED_BROADCAST
            ServiceStatus.Disconnected -> STOPPED_BROADCAST
            ServiceStatus.Failed -> FAILED_BROADCAST
        }
        
        Log.i(TAG, "Creating broadcast intent with action: $broadcastAction")
        val intent = Intent(broadcastAction)
        intent.putExtra(SENDER, Sender.Proxy.ordinal)
        Log.i(TAG, "Sending broadcast: action=$broadcastAction, sender=${Sender.Proxy.ordinal}")
        sendBroadcast(intent)
        Log.i(TAG, "Broadcast sent successfully")
        Log.i(TAG, "=== UPDATE_STATUS() COMPLETE ===")
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            ByeDpiProxyService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiProxyService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }
}
