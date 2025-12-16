package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.setStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

class BestStrategyActivity : BaseActivity() {

    private lateinit var inputEditText: EditText
    private lateinit var findStrategyButton: Button
    private lateinit var progressTextView: TextView
    private lateinit var resultScrollView: ScrollView
    private lateinit var resultTitle: TextView
    private lateinit var resultInfo: TextView
    private lateinit var resultStrategy: TextView
    private lateinit var applyStrategyButton: Button

    private lateinit var siteChecker: SiteCheckUtils
    private lateinit var cmdHistoryUtils: HistoryUtils
    private var sites: List<String> = emptyList()
    private lateinit var cmds: List<String>
    private val successfulCmds: MutableList<Triple<String, Int, Int>> = mutableListOf()

    private var testJob: Job? = null
    private var isTesting: Boolean = false

    private val prefs by lazy { getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_best_strategy)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.best_strategy_title)

        val ip = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1080)

        siteChecker = SiteCheckUtils(ip, port)
        cmdHistoryUtils = HistoryUtils(this)

        inputEditText = findViewById(R.id.inputEditText)
        findStrategyButton = findViewById(R.id.findStrategyButton)
        progressTextView = findViewById(R.id.progressTextView)
        resultScrollView = findViewById(R.id.resultScrollView)
        resultTitle = findViewById(R.id.resultTitle)
        resultInfo = findViewById(R.id.resultInfo)
        resultStrategy = findViewById(R.id.resultStrategy)
        applyStrategyButton = findViewById(R.id.applyStrategyButton)

        findStrategyButton.setOnClickListener {
            startTesting()
        }

        resultStrategy.setOnClickListener {
            copyStrategyToClipboard()
        }

        applyStrategyButton.setOnClickListener {
            applyStrategy()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        // Принудительно останавливаем прокси при выходе из активности
        if (isProxyRunning()) {
            try {
                ServiceManager.stop(this)
                // Даем время на обновление статуса через broadcast
                Thread.sleep(1000)
                // Явно устанавливаем статус в Halted с режимом из настроек пользователя
                // Это гарантирует, что MainActivity получит правильный статус
                if (isProxyRunning()) {
                    val preferredMode = prefs.mode()
                    setStatus(AppStatus.Halted, preferredMode)
                }
            } catch (e: Exception) {
                // При ошибке все равно устанавливаем статус в Halted с режимом из настроек
                val preferredMode = prefs.mode()
                setStatus(AppStatus.Halted, preferredMode)
            }
        }
    }

    private fun startTesting() {
        val inputText = inputEditText.text.toString().trim()
        if (inputText.isEmpty()) {
            Toast.makeText(this, R.string.best_strategy_empty_input, Toast.LENGTH_SHORT).show()
            return
        }

        // Обрабатываем ввод: если это название сервиса (например, "youtube"), подставляем домены
        val inputLines = inputText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        // Показываем прогресс загрузки доменов
        progressTextView.visibility = View.VISIBLE
        progressTextView.text = getString(R.string.best_strategy_loading_domains)
        
        testJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                sites = ServiceDomainsUtils.processMultiLineInput(this@BestStrategyActivity, inputLines).distinct()
                
                withContext(Dispatchers.Main) {
                    progressTextView.visibility = View.GONE
                    
                    if (sites.isEmpty()) {
                        Toast.makeText(this@BestStrategyActivity, R.string.best_strategy_empty_input, Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    
                    // Ограничиваем количество доменов для быстрой проверки
                    val domainsCount = sites.size
                    if (domainsCount > 25) {
                        // Берем только первые 25 доменов (обычно это основные домены)
                        sites = sites.take(25)
                        Toast.makeText(
                            this@BestStrategyActivity,
                            "Найдено $domainsCount доменов, будет проверено 25 основных",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@BestStrategyActivity,
                            "Найдено доменов: $domainsCount",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                    // Запускаем тестирование
                    startTestingInternal()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressTextView.visibility = View.GONE
                    findStrategyButton.isEnabled = true
                    Toast.makeText(
                        this@BestStrategyActivity,
                        "Ошибка при загрузке доменов: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun startTestingInternal() {

        cmds = loadCmds()

        if (cmds.isEmpty()) {
            Toast.makeText(this, R.string.best_strategy_no_result, Toast.LENGTH_SHORT).show()
            return
        }

        isTesting = true
        findStrategyButton.isEnabled = false
        progressTextView.visibility = View.VISIBLE
        progressTextView.text = getString(R.string.best_strategy_testing)
        resultScrollView.visibility = View.GONE
        applyStrategyButton.visibility = View.GONE

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bestStrategyResult = findBestStrategy()
                withContext(Dispatchers.Main) {
                    displayResult(bestStrategyResult)
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@BestStrategyActivity,
                        getString(R.string.best_strategy_no_result),
                        Toast.LENGTH_LONG
                    ).show()
                    progressTextView.visibility = View.GONE
                }
            } finally {
                // ВАЖНО: Останавливаем прокси после завершения тестирования
                withContext(Dispatchers.IO) {
                    if (isProxyRunning()) {
                        ServiceManager.stop(this@BestStrategyActivity)
                        // Ждем полной остановки прокси
                        var attempts = 0
                        while (attempts < 20 && isProxyRunning()) {
                            delay(200)
                            attempts++
                        }
                        // Явно устанавливаем статус в Halted с режимом из настроек пользователя
                        if (isProxyRunning()) {
                            val preferredMode = prefs.mode()
                            setStatus(AppStatus.Halted, preferredMode)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isTesting = false
                    findStrategyButton.isEnabled = true
                }
            }
        }
    }

    private suspend fun findBestStrategy(): Triple<String, Int, Int>? {
        // Агрессивно оптимизированные значения для максимальной скорости
        val delaySec = 0 // Убрали задержку между проверками
        val requestsCount = 1 // Только 1 запрос на домен (быстро)
        val requestTimeout = 2L // Таймаут 2 секунды (быстро)
        val fullLog = false

        successfulCmds.clear()
        var bestStrategy: Triple<String, Int, Int>? = null

        // Ограничиваем количество проверяемых стратегий (первые 40 для лучшего покрытия)
        val strategiesToTest = cmds.take(40)

        for ((index, cmd) in strategiesToTest.withIndex()) {
            if (coroutineContext[Job]?.isActive != true) break

            val cmdIndex = index + 1

            withContext(Dispatchers.Main) {
                progressTextView.text = getString(R.string.test_process, cmdIndex, strategiesToTest.size)
            }

            updateCmdArgs(cmd)

            if (isProxyRunning()) {
                ServiceManager.stop(this@BestStrategyActivity)
                delay(200) // Минимальная задержка
            } else {
                ServiceManager.start(this@BestStrategyActivity, Mode.Proxy)
            }

            if (!waitForProxyStatus(AppStatus.Running)) {
                continue
            }

            delay(100) // Минимальная задержка перед проверкой

            val totalRequests = sites.size * requestsCount
            val checkResults = siteChecker.checkSitesAsync(
                sites = sites,
                requestsCount = requestsCount,
                requestTimeout = requestTimeout,
                fullLog = fullLog,
                onSiteChecked = null
            )

            val successfulCount = checkResults.sumOf { it.second }
            val successPercentage = (successfulCount * 100) / totalRequests

            // Сохраняем все проверенные стратегии, не только с 50%+
            val cmdResult = Triple(cmd, successfulCount, totalRequests)
            successfulCmds.add(cmdResult)
            
            // Обновляем лучшую стратегию (лучшая по количеству успешных запросов)
            if (bestStrategy == null || successfulCount > bestStrategy.second) {
                bestStrategy = cmdResult
            }
            
            // Ранняя остановка при отличном результате (80%+)
            if (successPercentage >= 80 && successfulCount >= totalRequests * 0.8) {
                // Если нашли хорошую стратегию, останавливаемся после проверки минимум 3 стратегий
                if (index >= 2) {
                    break
                }
            }

            // Минимальные задержки
            if (isProxyRunning()) {
                ServiceManager.stop(this@BestStrategyActivity)
            }

            // Не ждем полной остановки, продолжаем сразу
            delay(100) // Минимальная задержка между стратегиями
        }

        // ВАЖНО: Останавливаем прокси после завершения всех тестов
        if (isProxyRunning()) {
            ServiceManager.stop(this@BestStrategyActivity)
            // Ждем остановки прокси
            var attempts = 0
            while (attempts < 20 && isProxyRunning()) {
                delay(200)
                attempts++
            }
            // Явно устанавливаем статус в Halted с режимом из настроек пользователя
            if (isProxyRunning()) {
                val preferredMode = prefs.mode()
                setStatus(AppStatus.Halted, preferredMode)
            }
        }

        // Находим стратегию с наилучшим результатом (всегда возвращаем лучшую, даже если < 50%)
        return bestStrategy ?: successfulCmds.maxByOrNull { it.second }
    }

    private fun displayResult(strategyResult: Triple<String, Int, Int>?) {
        progressTextView.visibility = View.GONE

        if (strategyResult == null || successfulCmds.isEmpty()) {
            Toast.makeText(this, R.string.best_strategy_no_result, Toast.LENGTH_LONG).show()
            return
        }

        val (strategy, successfulCount, totalRequests) = strategyResult
        val successPercentage = (successfulCount * 100) / totalRequests

        // Формируем текст с процентом успеха
        val resultText = if (successPercentage >= 50) {
            "$strategy\n\n✅ Успешность: $successfulCount/$totalRequests ($successPercentage%)"
        } else {
            "$strategy\n\n⚠️ Успешность: $successfulCount/$totalRequests ($successPercentage%)\n(Лучшая из проверенных стратегий)"
        }

        resultStrategy.text = resultText
        resultScrollView.visibility = View.VISIBLE
        applyStrategyButton.visibility = View.VISIBLE

        // Сохраняем стратегию для возможности применения
        savedStrategy = strategy
    }

    private fun applyStrategy() {
        val strategy = savedStrategy ?: return

        // ВАЖНО: Добавляем домены из списка пользователя в стратегию для split tunneling
        val finalStrategy = if (sites.isNotEmpty()) {
            addDomainsToStrategy(strategy, sites)
        } else {
            strategy
        }

        // Сохраняем текущую стратегию в историю (если она есть и отличается от новой)
        val currentStrategy = prefs.getString("byedpi_cmd_args", null)
        if (currentStrategy != null && currentStrategy.isNotBlank() && currentStrategy != finalStrategy) {
            cmdHistoryUtils.addCommand(currentStrategy)
        }

        // Сохраняем новую стратегию с доменами в настройки командной строки
        prefs.edit().putString("byedpi_cmd_args", finalStrategy).apply()

        // Добавляем новую стратегию с доменами в историю (она будет в начале списка)
        cmdHistoryUtils.addCommand(finalStrategy)

        // Включаем режим командной строки, если он еще не включен
        if (!prefs.getBoolean("byedpi_enable_cmd_settings", false)) {
            prefs.edit().putBoolean("byedpi_enable_cmd_settings", true).apply()
        }

        // Останавливаем сервис, если он был запущен для тестирования
        // Это нужно, чтобы пользователь мог запустить его заново с новой стратегией
        lifecycleScope.launch(Dispatchers.IO) {
            if (isProxyRunning()) {
                ServiceManager.stop(this@BestStrategyActivity)
                // Ждем, чтобы сервис остановился и статус обновился
                var attempts = 0
                while (attempts < 20 && isProxyRunning()) {
                    delay(200)
                    attempts++
                }
                // Явно устанавливаем статус в Halted с режимом из настроек пользователя
                if (isProxyRunning()) {
                    val preferredMode = prefs.mode()
                    setStatus(AppStatus.Halted, preferredMode)
                }
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@BestStrategyActivity,
                    getString(R.string.best_strategy_applied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun copyStrategyToClipboard() {
        val strategy = savedStrategy ?: return

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Strategy", strategy)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, R.string.best_strategy_copy, Toast.LENGTH_SHORT).show()
    }

    private fun updateCmdArgs(cmd: String) {
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")
        val processedCmd = cmd.replace("{sni}", sniValue)
        val args = shellSplit(processedCmd)

        prefs.edit().putString("byedpi_cmd_args", processedCmd).apply()
    }

    /**
     * Добавляет домены в стратегию для split tunneling
     * Формат: -H:домен1 домен2 домен3 (домены разделены пробелами, как в документации)
     */
    private fun addDomainsToStrategy(strategy: String, domains: List<String>): String {
        if (domains.isEmpty()) {
            return strategy
        }

        // Убираем дубликаты и ограничиваем количество доменов
        val uniqueDomains = domains.distinct().take(50) // Ограничиваем до 50 доменов
        
        // Формируем строку доменов через пробел (как указано в документации)
        val domainsStr = uniqueDomains.joinToString(" ")
        
        // Разбиваем стратегию на аргументы
        val args = shellSplit(strategy).toMutableList()
        
        // Удаляем старый -H, если есть
        args.removeAll { it.startsWith("-H") }
        
        // Добавляем новый -H с доменами (формат: -H:домен1 домен2 домен3)
        args.add("-H:$domainsStr")
        
        // Добавляем -Kt для протокола TLS, если его еще нет
        val hasProto = args.any { it.startsWith("-K") }
        if (!hasProto) {
            // Ищем позицию для вставки -Kt (после -H)
            val hIndex = args.indexOfFirst { it.startsWith("-H") }
            if (hIndex >= 0) {
                args.add(hIndex + 1, "-Kt")
            } else {
                args.add("-Kt")
            }
        }
        
        // Объединяем обратно в строку
        return args.joinToString(" ")
    }

    private fun loadCmds(): List<String> {
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")

        val content = try {
            assets.open("proxytest_strategies.list").bufferedReader().readText()
        } catch (e: Exception) {
            return emptyList()
        }

        return content.replace("{sni}", sniValue)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private suspend fun waitForProxyStatus(targetStatus: AppStatus): Boolean {
        var attempts = 0
        val maxAttempts = 10 // Уменьшено до 10 попыток (2 секунды)

        while (attempts < maxAttempts && coroutineContext[Job]?.isActive == true) {
            if (appStatus.first == targetStatus) {
                return true
            }
            delay(200)
            attempts++
        }

        return false
    }

    private fun isProxyRunning(): Boolean {
        return appStatus.first == AppStatus.Running && appStatus.second == Mode.Proxy
    }

    companion object {
        private var savedStrategy: String? = null
    }
}

