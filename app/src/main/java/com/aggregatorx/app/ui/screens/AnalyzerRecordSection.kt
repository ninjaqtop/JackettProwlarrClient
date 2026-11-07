package com.aggregatorx.app.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import com.aggregatorx.app.data.model.SiteAnalysis
import com.aggregatorx.app.ui.theme.AccentGreen
import com.aggregatorx.app.ui.theme.AccentOrange
import com.aggregatorx.app.ui.theme.AccentRed
import com.aggregatorx.app.ui.theme.AccentYellow
import com.aggregatorx.app.ui.theme.CyberBlue
import com.aggregatorx.app.ui.theme.CyberCyan
import com.aggregatorx.app.ui.theme.CyberPurple
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only view of the stored analysis record (SiteAnalysis.analysisMetadata):
 * the verification outcome with its specific reason, per-field confidence and
 * discrete checks, the mapping and full saved config, and the recon evidence.
 */
class AnalyzerRecordView private constructor(
    val status: String,
    val reasonLines: List<String>,
    val verification: List<Pair<String, String>>,
    val confidence: List<Pair<String, String>>,
    val checks: List<Pair<String, String>>,
    val mapping: List<Pair<String, String>>,
    val siteMap: List<Pair<String, String>>,
    val recon: List<Pair<String, String>>,
    /** Per-tool sections: title -> rows, in the order the tools ran. */
    val toolSections: List<Pair<String, List<Pair<String, String>>>>,
    val candidateLedger: List<Pair<String, String>>,
    val configJson: String?
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun parse(analysis: SiteAnalysis): AnalyzerRecordView? {
            val root = runCatching { json.parseToJsonElement(analysis.analysisMetadata) as? JsonObject }.getOrNull()
                ?: return null
            val v = root["verification"] as? JsonObject
            if (v == null && analysis.verificationStatus == "NOT_RUN") return null
            val reasons = (v?.get("reasons") as? JsonArray)?.mapNotNull { it.text() }
                ?: listOfNotNull(analysis.verificationReason ?: v?.get("reason")?.text())
            val verification = v?.let { o ->
                listOf(
                    "Probe query" to o["probeQuery"].text(),
                    "Route" to o["sourceLabel"].text(),
                    "Probed URL" to o["probedUrl"].text(),
                    "Item results" to o["itemCount"].text(),
                    "Category links" to o["categoryCount"].text(),
                    "Nonsense control" to o["nonsenseQuery"].text()?.let { q -> "\"$q\" → ${o["nonsenseItemCount"].text() ?: "n/a"} items" },
                    "Page 2" to when (o["page2Proven"].text()) {
                        "true" -> "proven live: ${o["page2NewItems"].text()} new items (${o["page2Url"].text()})"
                        "false" -> "not proven"
                        else -> null
                    },
                    "Pagination rule" to o["paginationRule"].text(),
                    "Rendered" to o["rendered"].text(),
                    "Repaired" to o["repaired"].text(),
                    "Slowest probe" to o["slowestProbeMs"].text()?.let { "$it ms" },
                    "Previous config kept" to o["previousConfigKept"].text()
                ).mapNotNull { (k, value) -> value?.let { k to it } }
            }.orEmpty()
            val confidence = buildList {
                (v?.get("fieldConfidence") as? JsonObject)?.forEach { (k, value) -> value.text()?.let { add(k to it.take(5)) } }
                v?.get("confidenceThreshold").text()?.let { add("save threshold" to it) }
                v?.get("confidenceDetail").text()?.let { add("detail" to it) }
            }
            val checks = (v?.get("checks") as? JsonArray)?.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val pass = if (o["passed"].text() == "true") "PASS" else "FAIL"
                "$pass ${o["id"].text()}" to (o["evidence"].text() ?: "")
            }.orEmpty()
            val mapping = (root["mapping"] as? JsonObject)?.entries
                ?.mapNotNull { (k, value) -> value.text()?.let { k to it } }
                .orEmpty()
            val siteMap = (root["siteMap"] as? JsonObject)?.let { o ->
                listOf(
                    "Sitemaps read" to (o["sitemapsRead"] as? JsonArray)?.size?.toString(),
                    "Sitemap URLs" to o["sitemapUrlCount"].text(),
                    "Mapped same-host URLs" to o["mappedUrlCount"].text(),
                    "Page param seen" to o["paginationParam"].text(),
                    "/page/N/ style" to o["paginationPathStyle"].text(),
                    "Median response" to o["medianResponseMs"].text()?.let { "$it ms" },
                    "Adaptive timeout" to o["suggestedTimeoutMs"].text()?.let { "$it ms" }
                ).mapNotNull { (k, value) -> value?.let { k to it } } +
                    ((o["candidates"] as? JsonArray)?.mapIndexedNotNull { i, c ->
                        val co = c as? JsonObject ?: return@mapIndexedNotNull null
                        "Candidate ${i + 1} (${co["source"].text()})" to (co["template"].text() ?: "")
                    }.orEmpty())
            }.orEmpty()
            val recon = (root["recon"] as? JsonObject)?.let { o ->
                buildList {
                    (o["techFingerprint"] as? JsonArray)?.let { add("Tech fingerprint" to it.mapNotNull { e -> e.text() }.joinToString().ifBlank { "none" }) }
                    (o["crawledPages"] as? JsonArray)?.let { add("Crawled pages" to it.size.toString()) }
                    (o["searchParameters"] as? JsonArray)?.let { add("Search params" to it.mapNotNull { e -> e.text() }.joinToString().ifBlank { "none" }) }
                    (o["minedParameters"] as? JsonObject)?.let { add("Mined params" to it.keys.take(20).joinToString()) }
                    (o["jsEndpoints"] as? JsonArray)?.let { add("JS endpoints" to it.size.toString()) }
                    o["archiveUrlCount"].text()?.let { add("Archived URLs" to it) }
                    o["wordlistProbed"].text()?.let { add("Wordlist routes probed" to it) }
                    (o["wordlistHits"] as? JsonArray)?.forEach { h ->
                        (h as? JsonObject)?.let { add("Wordlist hit" to "${it["template"].text()} — ${it["evidence"].text()}") }
                    }
                    (o["triage"] as? JsonArray)?.let { add("Endpoints triaged" to it.size.toString()) }
                    (o["checks"] as? JsonArray)?.forEach { c ->
                        (c as? JsonObject)?.let { add("${it["technique"].text()} [${it["status"].text()}]" to (it["evidence"].text() ?: "")) }
                    }
                }
            }.orEmpty()
            val reconObject = root["recon"] as? JsonObject
            val toolSections = buildToolSections(reconObject)
            val ledger = (reconObject?.get("candidateLedger") as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val outcome = (o["outcome"].text() ?: "").uppercase()
                val mark = when (outcome) {
                    "WON" -> "✅"
                    "LOST" -> "✖"
                    else -> "·"
                }
                "$mark #${o["rank"].text()} ${o["source"].text()}" to
                    ((o["template"].text() ?: "") + (o["reason"].text()?.let { "\n$it" } ?: ""))
            }.orEmpty()
            val config = root["config"]?.takeIf { it !is JsonNull }?.let { json.encodeToString(JsonElement.serializer(), it) }
            return AnalyzerRecordView(
                status = analysis.verificationStatus,
                reasonLines = reasons,
                verification = verification,
                confidence = confidence,
                checks = checks,
                mapping = mapping,
                siteMap = siteMap,
                recon = recon,
                toolSections = toolSections,
                candidateLedger = ledger,
                configJson = config
            )
        }

        /**
         * One section per recon tool technique, each showing what that tool
         * found on this site (spec §1/§3: the report displays the full recon
         * map, sections per tool).
         */
        private fun buildToolSections(o: JsonObject?): List<Pair<String, List<Pair<String, String>>>> {
            if (o == null) return emptyList()
            fun arr(key: String) = o[key] as? JsonArray
            fun rows(vararg pairs: Pair<String, String?>) =
                pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }
            val sections = mutableListOf<Pair<String, List<Pair<String, String>>>>()

            // Wappalyzer / WhatWeb
            sections += "🔎 Fingerprint (WhatWeb/Wappalyzer)" to rows(
                "Stack" to arr("stackLabels")?.mapNotNull { it.text() }?.joinToString(),
                "Raw signals" to arr("techFingerprint")?.mapNotNull { it.text() }?.joinToString()
            )

            // Burp-style live capture
            val captured = arr("capturedRequests")
            val liveRequests = arr("liveCaptureRequests")
            sections += "🎯 Live search capture (Burp-style)" to (rows(
                "What happened" to o["liveCaptureDetail"].text(),
                "Requests intercepted" to captured?.size?.toString()
            ) + (liveRequests?.mapIndexedNotNull { i, e ->
                val r = e as? JsonObject ?: return@mapIndexedNotNull null
                "Captured request ${i + 1}" to
                    "${r["method"].text()} ${r["urlTemplate"].text()}" +
                        (r["bodyTemplate"].text()?.let { "\nbody: $it" } ?: "") +
                        (r["queryParam"].text()?.let { "\nquery param: $it" } ?: "")
            }.orEmpty()))

            // Indexer definitions
            val def = o["definitionMatch"] as? JsonObject
            sections += "📚 Indexer definition (Jackett/Prowlarr)" to rows(
                "Matched" to (def?.get("indexerName").text() ?: "no Cardigann definition matched this host"),
                "Source" to def?.get("source").text(),
                "Definition" to def?.get("definitionUrl").text(),
                "Search path" to def?.get("searchPath").text(),
                "Query param" to def?.get("queryParam").text(),
                "Rows selector" to def?.get("rowsSelector").text(),
                "Needs login" to def?.get("requiresLogin").text()
            )

            // Katana / Burp crawler
            sections += "🕸️ Crawler (Katana/Burp)" to rows(
                "Pages crawled" to arr("crawledPages")?.size?.toString(),
                "Depth reached" to o["crawlDepth"].text(),
                "Sample" to arr("crawledPages")?.take(6)?.mapNotNull { it.text() }?.joinToString("\n")
            )

            // ffuf / gobuster / dirsearch
            val soft = o["softNotFound"] as? JsonObject
            sections += "📂 Route discovery (ffuf/gobuster/dirsearch)" to (rows(
                "Routes probed" to o["wordlistProbed"].text(),
                "Soft-404 baseline" to soft?.get("evidence").text()
            ) + (arr("wordlistHits")?.mapNotNull { h ->
                val r = h as? JsonObject ?: return@mapNotNull null
                "Hit" to "${r["template"].text()} — ${r["evidence"].text()}"
            }.orEmpty()) + rows(
                "Autocomplete endpoints (not used as search)" to
                    arr("suggestEndpoints")?.mapNotNull { it.text() }?.joinToString("\n")
            ))

            // Arjun / ParamSpider
            sections += "🔑 Parameter discovery (Arjun/ParamSpider)" to (rows(
                "Search-like params" to arr("searchParameters")?.mapNotNull { it.text() }?.joinToString(),
                "All mined params" to (o["minedParameters"] as? JsonObject)?.keys?.take(30)?.joinToString()
            ) + (arr("activeParams")?.mapNotNull { a ->
                val r = a as? JsonObject ?: return@mapNotNull null
                "Proved live: ${r["param"].text()}" to (r["evidence"].text() ?: "")
            }.orEmpty()))

            // LinkFinder / subjs
            sections += "📜 JS endpoint mining (LinkFinder/subjs)" to (rows(
                "Endpoints found" to arr("jsEndpoints")?.size?.toString(),
                "Source maps read" to o["sourceMapsRead"].text(),
                "Sample" to arr("jsEndpoints")?.take(8)?.mapNotNull { it.text() }?.joinToString("\n")
            ) + (arr("hostedSearch")?.mapNotNull { h ->
                val r = h as? JsonObject ?: return@mapNotNull null
                "Hosted search: ${r["service"].text()}" to (r["evidence"].text() ?: "")
            }.orEmpty()))

            // gau / waybackurls
            sections += "🏛️ Archive mining (gau/waybackurls)" to rows(
                "Archived URLs" to o["archiveUrlCount"].text(),
                "From CommonCrawl" to o["commonCrawlCount"].text(),
                "Search-shaped samples" to arr("archiveSamples")?.take(6)?.mapNotNull { it.text() }?.joinToString("\n")
            )

            // httpx
            sections += "📡 Endpoint triage (httpx)" to (arr("triage")?.takeLast(12)?.mapNotNull { t ->
                val r = t as? JsonObject ?: return@mapNotNull null
                "${r["status"].text()} ${r["purpose"].text()}" to
                    "${r["url"].text()} (${r["bytes"].text()} bytes, ${r["elapsedMs"].text()} ms, ${r["engine"].text()})"
            }.orEmpty())

            // Nuclei-style per-technique checks
            sections += "🧪 Technique checks (Nuclei-style)" to (arr("checks")?.mapNotNull { c ->
                val r = c as? JsonObject ?: return@mapNotNull null
                "[${r["status"].text()}] ${r["technique"].text()}" to (r["evidence"].text() ?: "")
            }.orEmpty())

            return sections.filter { it.second.isNotEmpty() }
        }

        private fun JsonElement?.text(): String? = when (this) {
            null, is JsonNull -> null
            is JsonPrimitive -> content
            else -> toString()
        }
    }
}

/** Adds the analysis-record sections to the analyzer report sheet. */
fun LazyListScope.analyzerRecordItems(record: AnalyzerRecordView?) {
    if (record == null) return
    item {
        val ok = record.status == "VERIFIED"
        AnalysisSection(
            title = if (ok) "✅ Verified live — config saved" else "⚠️ Verification ${record.status.lowercase()} — provider saved",
            color = if (ok) AccentGreen else AccentRed
        ) {
            record.reasonLines.forEach { AnalysisDetailRow("Reason", it.take(600)) }
            record.verification.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
        }
    }
    if (record.confidence.isNotEmpty() || record.checks.isNotEmpty()) {
        item {
            AnalysisSection(title = "📏 Confidence & checks", color = AccentYellow) {
                record.confidence.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
                record.checks.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
            }
        }
    }
    if (record.mapping.isNotEmpty()) {
        item {
            AnalysisSection(title = "🗺️ Saved search mapping", color = CyberCyan) {
                record.mapping.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
            }
        }
    }
    if (record.siteMap.isNotEmpty()) {
        item {
            AnalysisSection(title = "🧭 Site map", color = CyberBlue) {
                record.siteMap.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
            }
        }
    }
    if (record.candidateLedger.isNotEmpty()) {
        item {
            AnalysisSection(title = "🏁 Candidate ranking — what won and why the rest lost", color = AccentYellow) {
                record.candidateLedger.forEach { (k, value) -> AnalysisDetailRow(k, value.take(600)) }
            }
        }
    }
    record.toolSections.forEach { (title, rows) ->
        item {
            AnalysisSection(title = title, color = CyberPurple) {
                rows.forEach { (k, value) -> AnalysisDetailRow(k, value.take(600)) }
            }
        }
    }
    if (record.recon.isNotEmpty()) {
        item {
            AnalysisSection(title = "🛰️ Recon summary", color = CyberPurple) {
                record.recon.forEach { (k, value) -> AnalysisDetailRow(k, value.take(300)) }
            }
        }
    }
    record.configJson?.let { config ->
        item {
            AnalysisSection(title = "🧾 Saved config JSON", color = AccentOrange) {
                AnalysisDetailRow("config", config.take(4_000))
            }
        }
    }
}
