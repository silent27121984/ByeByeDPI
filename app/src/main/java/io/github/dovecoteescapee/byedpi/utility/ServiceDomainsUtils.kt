package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ServiceDomainsUtils {
    private var domainsCache: Map<String, List<String>>? = null
    private val TAG = "ServiceDomainsUtils"
    
    // Стандартные поддомены, которые часто используются
    private val commonSubdomains = listOf(
        "www", "api", "cdn", "static", "img", "images", "media", "assets",
        "js", "css", "fonts", "video", "videos", "stream", "live",
        "mobile", "m", "app", "apps", "web", "www2", "www3",
        "secure", "ssl", "login", "auth", "account", "accounts",
        "mail", "email", "smtp", "imap", "pop",
        "ftp", "sftp", "file", "files", "download", "downloads",
        "blog", "news", "forum", "forums", "community",
        "shop", "store", "buy", "cart", "checkout",
        "admin", "dashboard", "panel", "control",
        "dev", "develop", "development", "staging", "test", "testing",
        "beta", "alpha", "demo", "sandbox",
        "status", "monitor", "monitoring", "health",
        "docs", "documentation", "help", "support",
        "blog", "news", "press", "about", "contact",
        "i", "i1", "i2", "i3", "i4", "i5", "i9",
        "yt", "yt1", "yt2", "yt3", "yt4",
        "edge", "origin", "origin-", "edge-",
        "lb", "loadbalancer", "lb1", "lb2",
        "ns", "ns1", "ns2", "dns", "dns1", "dns2",
        "mx", "mx1", "mx2", "mail1", "mail2"
    )

    /**
     * Загружает домены для указанного сервиса (из локальной базы или генерирует автоматически)
     * @param context Контекст приложения
     * @param serviceName Название сервиса (например, "youtube", "roblox")
     * @return Список доменов для сервиса
     */
    suspend fun getDomainsForService(context: Context, serviceName: String): List<String> = withContext(Dispatchers.IO) {
        val normalizedName = serviceName.lowercase().trim()
        
        // Сначала проверяем локальную базу
        val cache = domainsCache ?: loadDomainsMap(context)
        domainsCache = cache
        
        val cachedDomains = cache[normalizedName]
        if (cachedDomains != null && cachedDomains.isNotEmpty()) {
            return@withContext cachedDomains
        }
        
        // Если не найдено в базе, генерируем домены автоматически
        generateDomainsForService(normalizedName)
    }
    
    /**
     * Генерирует список доменов для сервиса на основе стандартных паттернов
     * @param serviceName Название сервиса
     * @return Список сгенерированных доменов
     */
    private fun generateDomainsForService(serviceName: String): List<String> {
        val domains = mutableSetOf<String>()
        
        // Основной домен
        val mainDomain = if (serviceName.contains('.')) {
            serviceName
        } else {
            "$serviceName.com"
        }
        
        domains.add(mainDomain)
        
        // Извлекаем базовое имя домена (без TLD)
        val baseName = if (mainDomain.contains('.')) {
            mainDomain.substringBeforeLast('.')
        } else {
            mainDomain
        }
        
        val tld = if (mainDomain.contains('.')) {
            mainDomain.substringAfterLast('.')
        } else {
            "com"
        }
        
        // Добавляем стандартные поддомены
        for (subdomain in commonSubdomains) {
            domains.add("$subdomain.$baseName.$tld")
            domains.add("$subdomain.$mainDomain")
        }
        
        // Добавляем варианты с разными TLD
        val commonTlds = listOf("com", "net", "org", "io", "co", "app", "dev")
        for (tldVariant in commonTlds) {
            if (tldVariant != tld) {
                domains.add("$baseName.$tldVariant")
                domains.add("www.$baseName.$tldVariant")
                domains.add("api.$baseName.$tldVariant")
                domains.add("cdn.$baseName.$tldVariant")
            }
        }
        
        // Специальные паттерны для популярных сервисов
        when (serviceName) {
            "youtube" -> {
                domains.addAll(listOf(
                    "1e100.net",
                    "ggpht.com",
                    "googlevideo.com",
                    "googleapis.com",
                    "googleusercontent.com",
                    "i.ytimg.com",
                    "i9.ytimg.com",
                    "l.google.com",
                    "nhacmp3youtube.com",
                    "play.google.com",
                    "wide-youtube.l.google.com",
                    "youtu.be",
                    "youtube.com",
                    "youtubei.googleapis.com",
                    "yt3.ggpht.com",
                    "yt3.googleusercontent.com",
                    "ytimg.com"
                ))
            }
            "roblox" -> {
                domains.addAll(listOf(
                    "rbxcdn.com",
                    "robloxdev.com",
                    "rbxinfra.com"
                ))
            }
            "discord" -> {
                domains.addAll(listOf(
                    "airhorn.solutions",
                    "airhornbot.com",
                    "bigbeans.solutions",
                    "canary.discord.com",
                    "cdn.discordapp.com",
                    "click.discord.com",
                    "dis.gd",
                    "discord-activities.com",
                    "discord.app",
                    "discord.co",
                    "discord.com",
                    "discord.design",
                    "discord.dev",
                    "discord.gg",
                    "discord.gift",
                    "discord.gifts",
                    "discord.media",
                    "discord.new",
                    "discord.store",
                    "discord.tools",
                    "discordactivities.com",
                    "discordapp.com",
                    "discordapp.io",
                    "discordapp.net",
                    "discord-attachments-uploads-prd.storage.googleapis.com",
                    "discordcdn.com",
                    "discordmerch.com",
                    "discordpartygames.com",
                    "discordsays.com",
                    "discordsez.com",
                    "discordstatus.com",
                    "hammerandchisel.ssl.zendesk.com",
                    "images-ext-1.discordapp.net",
                    "media.discordapp.net",
                    "ptb.discord.com",
                    "ptb.discordapp.com",
                    "stable.dl2.discordapp.net",
                    "storage.googleapis.com",
                    "watchanimeattheoffice.com"
                ))
            }
            "google" -> {
                domains.addAll(listOf(
                    "google.com",
                    "googleads.g.doubleclick.net",
                    "googleapis.com",
                    "gmailpostmastertools.googleapis.com",
                    "gstatic.com",
                    "manifests.googlevideo.com",
                    "mtalk.google.com",
                    "NS1.google.com",
                    "NS2.google.com",
                    "NS3.google.com",
                    "NS4.google.com",
                    "play.google.com",
                    "youtube.googleapis.com"
                ))
            }
            "cloudflare" -> {
                domains.addAll(listOf(
                    "argotunnel.com",
                    "cf-china.info",
                    "cf-ipfs.com",
                    "cf-ns.com",
                    "cf-ns.net",
                    "cf-ns.site",
                    "cf-ns.tech",
                    "cfargotunnel.com",
                    "cfl.re",
                    "cftest5.cn",
                    "cftest6.cn",
                    "cftest7.com",
                    "cftest8.com",
                    "cloudflare-cn.com",
                    "cloudflare-dns.com",
                    "cloudflare-ech.com",
                    "cloudflare-esni.com",
                    "cloudflare-gateway.com",
                    "cloudflare-ipfs.com",
                    "cloudflare-quic.com",
                    "cloudflare.com",
                    "cloudflare.net",
                    "cloudflare.tv",
                    "cloudflareaccess.com",
                    "cloudflareanycast.net",
                    "cloudflareapps.com",
                    "cloudflarebolt.com",
                    "cloudflarechina.cn",
                    "cloudflareclient.com",
                    "cloudflarecn.net",
                    "cloudflareglobal.net",
                    "cloudflareinsights-cn.com",
                    "cloudflareinsights.com",
                    "cloudflareok.com",
                    "cloudflarepartners.com",
                    "cloudflareperf.com",
                    "cloudflareportal.com",
                    "cloudflarepreview.com",
                    "cloudflareprod.com",
                    "cloudflareregistrar.com",
                    "cloudflareresolve.com",
                    "cloudflaressl.com",
                    "cloudflarestaging.com",
                    "cloudflarestatus.com",
                    "cloudflarestorage.com",
                    "cloudflarestoragegw.com",
                    "cloudflarestream.com",
                    "cloudflaretest.com",
                    "cloudflarewarp.com",
                    "every1dns.net",
                    "isbgpsafeyet.com",
                    "one.one.one",
                    "one.one.one.one",
                    "pacloudflare.com",
                    "pages.dev",
                    "trycloudflare.com",
                    "videodelivery.net",
                    "warp.plus",
                    "workers.dev"
                ))
            }
            "instagram" -> {
                domains.addAll(listOf(
                    "cdninstagram.com",
                    "igcdn-photos-e-a.akamaihd.net",
                    "instagram.com",
                    "instagramstatic.com",
                    "scontent-hel3-1.cdninstagram.com",
                    "scontent-lhr6-1.cdninstagram.com",
                    "scontent-lhr6-2.cdninstagram.com",
                    "static.cdninstagram.com"
                ))
            }
            "soundcloud" -> {
                domains.addAll(listOf(
                    "a-v2.sndcdn.com",
                    "ams-pageview-public.s3.amazonaws.com",
                    "artists.soundcloud.com",
                    "cdn.cookielaw.org",
                    "sndcdn.com",
                    "soundcloud.app.goo.gl",
                    "soundcloud.com"
                ))
            }
        }
        
        return domains.toList().sorted()
    }

    /**
     * Обрабатывает ввод пользователя: если это название сервиса - подставляет домены,
     * если это домен - генерирует связанные домены
     * @param context Контекст приложения
     * @param input Ввод пользователя (может быть названием сервиса или доменом)
     * @return Список доменов
     */
    suspend fun processUserInput(context: Context, input: String): List<String> = withContext(Dispatchers.IO) {
        val normalizedInput = input.trim().lowercase()
        
        // Если это уже домен (содержит точку), генерируем связанные домены
        if (normalizedInput.contains('.') && !normalizedInput.contains(' ')) {
            // Извлекаем базовое имя из домена
            val baseName = normalizedInput.substringBeforeLast('.')
            val tld = normalizedInput.substringAfterLast('.')
            
            // Генерируем связанные домены
            val relatedDomains = mutableSetOf<String>()
            relatedDomains.add(normalizedInput) // Основной домен
            
            // Добавляем стандартные поддомены
            for (subdomain in commonSubdomains.take(20)) { // Ограничиваем для производительности
                relatedDomains.add("$subdomain.$normalizedInput")
            }
            
            return@withContext relatedDomains.toList()
        }
        
        // Если это не домен, считаем это названием сервиса и генерируем домены
        getDomainsForService(context, normalizedInput)
    }

    /**
     * Обрабатывает многострочный ввод пользователя
     * @param context Контекст приложения
     * @param inputLines Строки ввода
     * @return Список уникальных доменов
     */
    suspend fun processMultiLineInput(context: Context, inputLines: List<String>): List<String> = withContext(Dispatchers.IO) {
        val allDomains = mutableSetOf<String>()
        
        for (line in inputLines) {
            val processed = processUserInput(context, line)
            allDomains.addAll(processed)
        }
        
        return@withContext allDomains.toList()
    }

    /**
     * Получает список доступных сервисов
     * @param context Контекст приложения
     * @return Список названий сервисов
     */
    fun getAvailableServices(context: Context): List<String> {
        val cache = domainsCache ?: loadDomainsMap(context)
        domainsCache = cache
        return cache.keys.sorted()
    }

    private fun loadDomainsMap(context: Context): Map<String, List<String>> {
        return try {
            val jsonString = context.assets.open("service_domains.json")
                .bufferedReader()
                .use { it.readText() }
            
            val json = JSONObject(jsonString)
            val map = mutableMapOf<String, List<String>>()
            
            json.keys().forEach { key ->
                val domainsArray = json.getJSONArray(key)
                val domains = mutableListOf<String>()
                for (i in 0 until domainsArray.length()) {
                    domains.add(domainsArray.getString(i))
                }
                map[key.lowercase()] = domains
            }
            
            map
        } catch (e: IOException) {
            android.util.Log.e("ServiceDomainsUtils", "Failed to load service domains", e)
            emptyMap()
        } catch (e: Exception) {
            android.util.Log.e("ServiceDomainsUtils", "Error parsing service domains", e)
            emptyMap()
        }
    }
}

