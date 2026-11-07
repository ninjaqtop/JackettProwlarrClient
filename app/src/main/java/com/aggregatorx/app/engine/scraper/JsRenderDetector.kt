package com.aggregatorx.app.engine.scraper

import org.jsoup.nodes.Document

/**
 * Detects a JavaScript app shell: a page whose content only exists after its
 * scripts run, so raw HTTP sees (almost) nothing. Search and recon use it to
 * decide when to render through the embedded WebView ([HeadlessBrowserHelper]).
 */
object JsRenderDetector {
    private const val MOUNT_NODES =
        "#root, #app, #__next, #__nuxt, #svelte, app-root, [data-reactroot], [ng-app], [ng-version]"

    fun looksJavaScriptRendered(doc: Document): Boolean {
        val body = doc.body() ?: return true
        val text = body.text().trim()
        val scripts = doc.select("script").size
        if (text.length < 300 && scripts >= 2) return true
        val mount = doc.selectFirst(MOUNT_NODES)
        if (mount != null && mount.text().trim().length < 200) return true
        val noscript = doc.select("noscript").text().lowercase()
        return text.length < 1500 && (
            "enable javascript" in noscript || "requires javascript" in noscript ||
                "javascript is required" in noscript || "turn on javascript" in noscript
            )
    }
}
