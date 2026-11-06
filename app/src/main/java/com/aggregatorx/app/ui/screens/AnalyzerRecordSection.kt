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
                configJson = config
            )
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
    if (record.recon.isNotEmpty()) {
        item {
            AnalysisSection(title = "🛰️ Recon evidence", color = CyberPurple) {
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
