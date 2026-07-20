package com.aggregatorx.app.engine.network

import com.aggregatorx.app.engine.util.EngineUtils
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browser-like TLS profile rotation for OkHttp-backed requests.
 *
 * Android does not expose ClientHello extension ordering or arbitrary JA3
 * construction through OkHttp/WebView APIs. This engine implements the parts
 * available in-app: TLS version/cipher profile selection, HTTP/2-capable
 * connection specs, timeout/pool defaults, and matching request headers.
 */
@Singleton
class TlsFingerprintEngine @Inject constructor() {

    enum class Profile {
        CHROME,
        FIREFOX,
        SAFARI,
        ANDROID_WEBVIEW
    }

    data class ProfileInfo(
        val profile: Profile,
        val userAgent: String,
        val description: String
    )

    data class NativeImpersonationInfo(
        val available: Boolean,
        val defaultProfile: String,
        val supportedProfiles: List<String>,
        val unavailableReason: String?
    )

    private val random = SecureRandom()

    fun randomProfile(): Profile = Profile.values()[random.nextInt(Profile.values().size)]

    fun defaultProfileInfo(): ProfileInfo = describe(Profile.CHROME)

    fun describe(profile: Profile): ProfileInfo = when (profile) {
        Profile.CHROME -> ProfileInfo(
            profile = profile,
            userAgent = EngineUtils.DEFAULT_USER_AGENT,
            description = "TLS 1.3/1.2 with Chrome-like cipher preference and browser headers"
        )
        Profile.FIREFOX -> ProfileInfo(
            profile = profile,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            description = "TLS 1.3/1.2 with Firefox-like cipher preference and browser headers"
        )
        Profile.SAFARI -> ProfileInfo(
            profile = profile,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
            description = "TLS 1.3/1.2 with Safari-like browser headers"
        )
        Profile.ANDROID_WEBVIEW -> ProfileInfo(
            profile = profile,
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.83 Mobile Safari/537.36",
            description = "Android WebView/Chrome Mobile TLS and header profile"
        )
    }

    fun apply(builder: OkHttpClient.Builder, profile: Profile = randomProfile()): OkHttpClient.Builder {
        return builder
            .connectionSpecs(listOf(connectionSpec(profile), ConnectionSpec.CLEARTEXT))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val info = describe(profile)
                val request = chain.request().newBuilder()
                    .header("User-Agent", info.userAgent)
                    .header("Accept", acceptHeader(profile))
                    .header("Accept-Language", acceptLanguage(profile))
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("DNT", "1")
                    .apply {
                        if (profile == Profile.CHROME || profile == Profile.ANDROID_WEBVIEW) {
                            header("sec-ch-ua", "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"")
                            header("sec-ch-ua-mobile", if (profile == Profile.ANDROID_WEBVIEW) "?1" else "?0")
                            header("sec-ch-ua-platform", if (profile == Profile.ANDROID_WEBVIEW) "\"Android\"" else "\"Windows\"")
                            header("Sec-Fetch-Dest", "document")
                            header("Sec-Fetch-Mode", "navigate")
                            header("Sec-Fetch-Site", "none")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
    }

    fun newClient(profile: Profile = randomProfile()): OkHttpClient = apply(OkHttpClient.Builder(), profile).build()

    fun nativeImpersonationInfo(): NativeImpersonationInfo = NativeImpersonationInfo(
        available = TlsClient.isAvailable,
        defaultProfile = TlsClient.DEFAULT_PROFILE,
        supportedProfiles = TlsClient.supportedProfiles,
        unavailableReason = TlsClient.unavailableReason
    )

    fun nativeProfileFor(profile: Profile = Profile.CHROME): String = when (profile) {
        Profile.CHROME -> "chrome_133"
        Profile.FIREFOX -> "firefox_135"
        Profile.SAFARI -> "safari_ios_18_5"
        Profile.ANDROID_WEBVIEW -> "okhttp4_android_13"
    }

    fun headers(profile: Profile = Profile.CHROME): Map<String, String> {
        val info = describe(profile)
        return buildMap {
            put("User-Agent", info.userAgent)
            put("Accept", acceptHeader(profile))
            put("Accept-Language", acceptLanguage(profile))
            put("Accept-Encoding", "gzip, deflate, br")
            put("Upgrade-Insecure-Requests", "1")
            if (profile == Profile.CHROME || profile == Profile.ANDROID_WEBVIEW) {
                put("sec-ch-ua", "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"")
                put("sec-ch-ua-mobile", if (profile == Profile.ANDROID_WEBVIEW) "?1" else "?0")
                put("sec-ch-ua-platform", if (profile == Profile.ANDROID_WEBVIEW) "\"Android\"" else "\"Windows\"")
            }
        }
    }

    private fun connectionSpec(profile: Profile): ConnectionSpec {
        val cipherSuites = when (profile) {
            Profile.FIREFOX -> firefoxCipherSuites
            Profile.SAFARI -> safariCipherSuites
            Profile.ANDROID_WEBVIEW -> chromeCipherSuites
            Profile.CHROME -> chromeCipherSuites
        }

        return ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(*cipherSuites.toTypedArray())
            .build()
    }

    private fun acceptHeader(profile: Profile): String = when (profile) {
        Profile.FIREFOX -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        else -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    }

    private fun acceptLanguage(profile: Profile): String = when (profile) {
        Profile.FIREFOX -> "en-US,en;q=0.5"
        else -> "en-US,en;q=0.9"
    }

    private val chromeCipherSuites = listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
    )

    private val firefoxCipherSuites = listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    )

    private val safariCipherSuites = listOf(
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
    )
}
