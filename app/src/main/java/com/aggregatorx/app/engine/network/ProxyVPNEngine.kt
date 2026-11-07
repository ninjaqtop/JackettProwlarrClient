package com.aggregatorx.app.engine.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.Connection
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import com.aggregatorx.app.engine.util.EngineUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggregatorX Advanced Proxy/VPN Engine
 * 
 * Features:
 * - Netherlands-based free proxy rotation
 * - SOCKS5 and HTTP proxy support
 * - Automatic proxy health checking and rotation
 * - Smart failover with multiple proxy sources
 * - Geo-location spoofing for content access
 * - DNS-over-HTTPS for privacy
 * - Auto-refresh of proxy list
 * - Integrated with OkHttp and Jsoup
 */
@Singleton
class ProxyVPNEngine @Inject constructor() {
    
    companion object {
        private val USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
        
        // Netherlands country code for geo-filtering
        private const val TARGET_COUNTRY = "NL"
        private const val TARGET_COUNTRY_FULL = "Netherlands"
        
        // Proxy sources (free proxy lists)
        private val PROXY_SOURCES = listOf(
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=NL&ssl=all&anonymity=all",
            "https://www.proxy-list.download/api/v1/get?type=http&country=NL",
            "https://api.openproxylist.xyz/http.txt",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
            "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt",
            "https://raw.githubusercontent.com/sunny9577/proxy-scraper/master/proxies.txt"
        )
        
        // Hardcoded Netherlands proxies (backup)
        private val NETHERLANDS_PROXY_BACKUP = listOf(
            ProxyConfig("45.140.143.77", 8080, ProxyType.HTTP),
            ProxyConfig("82.196.11.105", 1080, ProxyType.SOCKS5),
            ProxyConfig("185.153.198.226", 8118, ProxyType.HTTP),
            ProxyConfig("89.39.107.49", 8080, ProxyType.HTTP),
            ProxyConfig("45.137.65.74", 8080, ProxyType.HTTP),
            ProxyConfig("93.158.214.138", 8080, ProxyType.HTTP),
            ProxyConfig("145.239.85.47", 9300, ProxyType.HTTP),
            ProxyConfig("51.15.242.216", 8888, ProxyType.HTTP),
            ProxyConfig("178.62.193.19", 8080, ProxyType.HTTP),
            ProxyConfig("212.112.113.178", 8080, ProxyType.HTTP)
        )
        
        // DNS over HTTPS servers for privacy
        private val DOH_SERVERS = listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/dns-query",
            "https://dns.quad9.net/dns-query"
        )
    }
    
    // Active proxy pool (Netherlands focused)
    private val proxyPool = mutableListOf<ProxyConfig>()
    private var currentProxyIndex = 0
    private var lastProxyRefresh = 0L
    private val proxyHealthMap = mutableMapOf<String, ProxyHealth>()
    
    // VPN mode simulation headers
    private val vpnHeaders = mapOf(
        "X-Forwarded-For" to "185.107.56.37", // NL IP
        "CF-IPCountry" to "NL",
        "Accept-Language" to "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Real-IP" to "185.107.56.37"
    )
    
    // Feature flags
    private var proxyEnabled = true
    private var rotateOnFailure = true
    private var useNetherlandsOnly = true
    
    /**
     * Initialize the proxy engine - fetch initial proxy list
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        refreshProxyList()
    }
    
    /**
     * Refresh the proxy list from multiple sources
     */
    suspend fun refreshProxyList() = withContext(Dispatchers.IO) {
        val newProxies = mutableListOf<ProxyConfig>()
        
        // Fetch from online sources
        for (source in PROXY_SOURCES) {
            try {
                val proxies = fetchProxiesFromSource(source)
                newProxies.addAll(proxies)
            } catch (e: Exception) {
                // Continue to next source
            }
        }
        
        // Filter for Netherlands if possible
        if (useNetherlandsOnly) {
            val nlProxies = newProxies.filter { proxy ->
                isNetherlandsProxy(proxy)
            }
            if (nlProxies.isNotEmpty()) {
                proxyPool.clear()
                proxyPool.addAll(nlProxies)
            }
        }
        
        // Add backup proxies if pool is too small
        if (proxyPool.size < 5) {
            proxyPool.addAll(NETHERLANDS_PROXY_BACKUP)
        }
        
        // Validate and sort by health
        val validatedProxies = validateProxies(proxyPool.take(50))
        proxyPool.clear()
        proxyPool.addAll(validatedProxies.sortedByDescending { 
            proxyHealthMap[it.toKey()]?.score ?: 0f 
        })
        
        lastProxyRefresh = System.currentTimeMillis()
    }
    
    /**
     * Fetch proxies from a source URL
     */
    private suspend fun fetchProxiesFromSource(sourceUrl: String): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val proxies = mutableListOf<ProxyConfig>()
        
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            
            // Parse proxy list (format: ip:port)
            val lines = body.split("\n", "\r\n", "\r")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+"))) {
                    val parts = trimmed.split(":")
                    if (parts.size == 2) {
                        proxies.add(ProxyConfig(
                            host = parts[0],
                            port = parts[1].toIntOrNull() ?: 8080,
                            type = ProxyType.HTTP
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // Silently continue
        }
        
        proxies
    }
    
    /**
     * Check if a proxy is Netherlands-based (geo-check)
     */
    private suspend fun isNetherlandsProxy(proxy: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = createProxyClient(proxy)
            val request = Request.Builder()
                .url("http://ip-api.com/json/")
                .header("User-Agent", USER_AGENT)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            
            body.contains("\"countryCode\":\"NL\"") || body.contains("Netherlands")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate proxies by testing connectivity
     */
    private suspend fun validateProxies(proxies: List<ProxyConfig>): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val validProxies = mutableListOf<ProxyConfig>()
        
        for (proxy in proxies.take(30)) {
            try {
                val startTime = System.currentTimeMillis()
                val client = createProxyClient(proxy)
                
                val request = Request.Builder()
                    .url("https://www.google.com/generate_204")
                    .header("User-Agent", USER_AGENT)
                    .build()
                
                val response = client.newCall(request).execute()
                val latency = System.currentTimeMillis() - startTime
                
                if (response.isSuccessful || response.code == 204) {
                    validProxies.add(proxy)
                    proxyHealthMap[proxy.toKey()] = ProxyHealth(
                        lastCheck = System.currentTimeMillis(),
                        latency = latency,
                        successRate = 1f,
                        score = 100f - (latency / 100f).coerceAtMost(50f)
                    )
                }
            } catch (e: Exception) {
                proxyHealthMap[proxy.toKey()] = ProxyHealth(
                    lastCheck = System.currentTimeMillis(),
                    latency = Long.MAX_VALUE,
                    successRate = 0f,
                    score = 0f
                )
            }
        }
        
        validProxies
    }
    
    /**
     * Get current active proxy
     */
    fun getCurrentProxy(): ProxyConfig? {
        if (!proxyEnabled || proxyPool.isEmpty()) return null
        return proxyPool.getOrNull(currentProxyIndex % proxyPool.size)
    }
    
    /**
     * Rotate to next proxy
     */
    fun rotateProxy() {
        if (proxyPool.isNotEmpty()) {
            currentProxyIndex = (currentProxyIndex + 1) % proxyPool.size
        }
    }
    
    /**
     * Mark current proxy as failed and rotate
     */
    fun markProxyFailed() {
        getCurrentProxy()?.let { proxy ->
            val health = proxyHealthMap.getOrPut(proxy.toKey()) { 
                ProxyHealth(System.currentTimeMillis(), Long.MAX_VALUE, 0f, 0f) 
            }
            proxyHealthMap[proxy.toKey()] = health.copy(
                successRate = (health.successRate * 0.5f),
                score = health.score * 0.5f
            )
        }
        if (rotateOnFailure) {
            rotateProxy()
        }
    }
    
    /**
     * Mark current proxy as successful
     */
    fun markProxySuccess(latency: Long) {
        getCurrentProxy()?.let { proxy ->
            val health = proxyHealthMap.getOrPut(proxy.toKey()) {
                ProxyHealth(System.currentTimeMillis(), latency, 1f, 50f)
            }
            proxyHealthMap[proxy.toKey()] = health.copy(
                lastCheck = System.currentTimeMillis(),
                latency = latency,
                successRate = (health.successRate * 0.9f + 0.1f).coerceAtMost(1f),
                score = (health.score + 5f).coerceAtMost(100f)
            )
        }
    }
    
    /**
     * Create an OkHttpClient with proxy configured
     */
    fun createProxyClient(proxyConfig: ProxyConfig? = getCurrentProxy()): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        
        if (proxyConfig != null && proxyEnabled) {
            val proxyType = if (proxyConfig.type == ProxyType.SOCKS5) {
                Proxy.Type.SOCKS
            } else {
                Proxy.Type.HTTP
            }
            
            val proxy = Proxy(proxyType, InetSocketAddress(proxyConfig.host, proxyConfig.port))
            builder.proxy(proxy)
            
            // Add auth if available
            if (proxyConfig.username != null && proxyConfig.password != null) {
                builder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(proxyConfig.username, proxyConfig.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }
        
        // Add Netherlands VPN-style headers
        builder.addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", USER_AGENT)
            
            // Add geo-spoofing headers
            vpnHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            
            chain.proceed(requestBuilder.build())
        }
        
        return builder.build()
    }
    
    /**
     * Create a Jsoup connection with proxy and VPN headers.
     *
     * Deliberately NOT on HttpTransport: it routes through a third-party HTTP
     * proxy, which the native TLS client does not support. Its only caller chain
     * is media playback (VideoStreamResolver.fetchDocumentWithProxy) and the
     * unwired UnifiedContentEngine — never search, the Analyzer, recon or
     * verification (spec §6 scope).
     */
    fun createProxyConnection(url: String): Connection {
        val proxy = getCurrentProxy()
        
        val connection = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(30000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
        
        // Add VPN headers
        vpnHeaders.forEach { (key, value) ->
            connection.header(key, value)
        }
        
        // Additional Netherlands-specific headers
        connection.header("Accept-Language", "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7")
        connection.header("CF-IPCountry", "NL")
        
        if (proxy != null && proxyEnabled) {
            connection.proxy(proxy.host, proxy.port)
        }
        
        return connection
    }
    
    /**
     * Fetch URL with automatic proxy fallback
     */
    suspend fun fetchWithProxy(url: String): ProxyFetchResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        var attempts = 0
        val maxAttempts = 3
        
        // Auto-refresh proxy list if stale
        if (System.currentTimeMillis() - lastProxyRefresh > 30 * 60 * 1000) {
            try {
                refreshProxyList()
            } catch (e: Exception) { /* ignore */ }
        }
        
        while (attempts < maxAttempts) {
            val startTime = System.currentTimeMillis()
            val proxy = getCurrentProxy()
            
            try {
                val client = createProxyClient(proxy)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        vpnHeaders.forEach { (key, value) ->
                            header(key, value)
                        }
                    }
                    .build()
                
                val response = client.newCall(request).execute()
                val latency = System.currentTimeMillis() - startTime
                
                if (response.isSuccessful) {
                    markProxySuccess(latency)
                    return@withContext ProxyFetchResult(
                        success = true,
                        body = response.body?.string(),
                        statusCode = response.code,
                        latency = latency,
                        proxyUsed = proxy
                    )
                } else if (response.code in 400..499) {
                    // Client error - might not be proxy's fault
                    return@withContext ProxyFetchResult(
                        success = false,
                        error = "HTTP ${response.code}",
                        statusCode = response.code
                    )
                }
            } catch (e: Exception) {
                lastError = e
                markProxyFailed()
            }
            
            attempts++
            delay(500L * attempts)
        }
        
        ProxyFetchResult(
            success = false,
            error = lastError?.message ?: "Failed after $maxAttempts attempts"
        )
    }
    
    /**
     * Fetch HTML document with proxy support
     */
    suspend fun fetchDocumentWithProxy(url: String): org.jsoup.nodes.Document? = withContext(Dispatchers.IO) {
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            val proxy = getCurrentProxy()
            
            try {
                val connection = createProxyConnection(url)
                val document = connection.get()
                
                markProxySuccess(0)
                return@withContext document
            } catch (e: Exception) {
                markProxyFailed()
            }
            
            attempts++
            delay(300L * attempts)
        }
        
        // Final attempt without proxy: no proxy is involved any more, so it
        // rides the app's one transport (native TLS primary, logged
        // degradation) instead of a separate Jsoup/HttpURLConnection stack.
        try {
            return@withContext HttpTransport.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15000)
                .get()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Enable/disable proxy
     */
    fun setProxyEnabled(enabled: Boolean) {
        proxyEnabled = enabled
    }
    
    fun isProxyEnabled(): Boolean = proxyEnabled
    
    /**
     * Get proxy status info
     */
    fun getProxyStatus(): ProxyStatus {
        val current = getCurrentProxy()
        return ProxyStatus(
            enabled = proxyEnabled,
            currentProxy = current?.toString(),
            poolSize = proxyPool.size,
            currentHealth = current?.let { proxyHealthMap[it.toKey()] },
            country = if (proxyEnabled) TARGET_COUNTRY_FULL else "Direct"
        )
    }
    
    /**
     * Add custom proxy
     */
    fun addCustomProxy(host: String, port: Int, type: ProxyType = ProxyType.HTTP) {
        proxyPool.add(0, ProxyConfig(host, port, type))
    }
}

/**
 * Proxy configuration
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val type: ProxyType = ProxyType.HTTP,
    val username: String? = null,
    val password: String? = null
) {
    fun toKey() = "$type://$host:$port"
    override fun toString() = "$type://$host:$port"
}

enum class ProxyType {
    HTTP, HTTPS, SOCKS5
}

/**
 * Proxy health tracking
 */
data class ProxyHealth(
    val lastCheck: Long,
    val latency: Long,
    val successRate: Float,
    val score: Float
)

/**
 * Proxy status info
 */
data class ProxyStatus(
    val enabled: Boolean,
    val currentProxy: String?,
    val poolSize: Int,
    val currentHealth: ProxyHealth?,
    val country: String
)

/**
 * Result of proxy fetch operation
 */
data class ProxyFetchResult(
    val success: Boolean,
    val body: String? = null,
    val statusCode: Int? = null,
    val latency: Long? = null,
    val proxyUsed: ProxyConfig? = null,
    val error: String? = null
)
