package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.model.ScrapingConfig
import com.aggregatorx.app.engine.analyzer.CandidateLedgerEntry
import com.aggregatorx.app.engine.analyzer.ReconMap
import com.aggregatorx.app.engine.analyzer.SearchConfigVerifier
import com.aggregatorx.app.engine.analyzer.SiteRecon
import com.aggregatorx.app.engine.analyzer.VerificationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Builds the analysis record stored in SiteAnalysis.analysisMetadata (spec §3):
 *
 *  - "mapping":       search template, method, query param, hidden params,
 *                     pagination rule, JSON result path + field names, selectors
 *  - "siteMap":       sitemaps, URL inventory size, candidates (forms, OpenSearch,
 *                     endpoints, wordlist hits ...), pagination evidence, timing
 *  - "recon":         tech fingerprint, crawl, mined params, JS endpoints, archive,
 *                     wordlist probes, httpx triage, per-technique checks
 *  - "config":        the full saved ScrapingConfig JSON
 *  - "verification":  status, reason(s), per-field confidence, discrete checks,
 *                     probe evidence (real-query items, nonsense control, page 2)
 *
 * Keys written by the SiteAnalyzerEngine (e.g. "capabilityReport") are kept.
 * This is the same record the analyzer report screen renders.
 */
object AnalysisRecord {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun verified(outcome: VerificationOutcome.Verified, probeQuery: String?): JsonObject {
        val v = outcome.verdict
        return buildJsonObject {
            put("losingCandidates", buildJsonArray {
                outcome.losingReasons.forEach { add(JsonPrimitive(it)) }
            })
            put("status", JsonPrimitive("VERIFIED"))
            put("reason", JsonPrimitive(v.reason))
            put("sourceLabel", JsonPrimitive(v.sourceLabel))
            put("probedUrl", JsonPrimitive(v.probedUrl))
            put("probeQuery", JsonPrimitive(probeQuery))
            put("itemCount", JsonPrimitive(v.itemCount))
            put("categoryCount", JsonPrimitive(v.categoryCount))
            put("nonsenseQuery", JsonPrimitive(SearchConfigVerifier.NONSENSE_QUERY))
            put("nonsenseItemCount", JsonPrimitive(v.nonsenseItemCount))
            put("page2Proven", JsonPrimitive(v.paginationProven))
            put("page2Url", JsonPrimitive(v.page2Url))
            put("page2NewItems", JsonPrimitive(v.page2NewItems))
            put("paginationRule", JsonPrimitive(outcome.config.paginationRule))
            put("rendered", JsonPrimitive(v.rendered))
            put("repaired", JsonPrimitive(outcome.repaired))
            put("responseMs", JsonPrimitive(v.responseMs))
            put("slowestProbeMs", JsonPrimitive(v.slowestProbeMs))
            put("confidence", JsonPrimitive(v.confidence))
            put("confidenceDetail", JsonPrimitive(v.confidenceDetail))
            put("confidenceThreshold", JsonPrimitive(SearchConfigVerifier.MIN_AUTOSAVE_CONFIDENCE))
            put("fieldConfidence", buildJsonObject { v.fieldConfidence.forEach { (k, value) -> put(k, JsonPrimitive(value)) } })
            put("checks", buildJsonArray {
                v.checks.forEach { c ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(c.id))
                        put("passed", JsonPrimitive(c.passed))
                        put("evidence", JsonPrimitive(c.evidence))
                    })
                }
            })
        }
    }

    fun failed(reasons: List<String>, probeQuery: String?, previousConfigKept: Boolean): JsonObject = buildJsonObject {
        put("status", JsonPrimitive("FAILED"))
        put("reason", JsonPrimitive(reasons.joinToString("; ")))
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("probeQuery", JsonPrimitive(probeQuery))
        put("nonsenseQuery", JsonPrimitive(SearchConfigVerifier.NONSENSE_QUERY))
        put("confidenceThreshold", JsonPrimitive(SearchConfigVerifier.MIN_AUTOSAVE_CONFIDENCE))
        put("previousConfigKept", JsonPrimitive(previousConfigKept))
    }

    /**
     * Resolve the recon map's candidate ledger against what verification
     * actually did: the winner is marked won, every candidate whose label the
     * verifier reported a reason for is marked lost with that reason, and the
     * rest stay "not reached" (verification stops at the first winner).
     */
    private fun resolveLedger(recon: SiteRecon?, winner: ScrapingConfig?, reasons: List<String>): List<CandidateLedgerEntry> {
        val ledger = recon?.reconMap?.candidateLedger ?: return emptyList()
        return ledger.map { entry ->
            val template = entry.template.substringBefore(" [POST")
            val won = winner != null && template == winner.searchUrlTemplate
            val lost = reasons.firstOrNull { reason ->
                // The verifier prefixes each reason with the candidate's label.
                reason.contains(template.take(80), ignoreCase = true)
            }
            when {
                won -> entry.copy(outcome = "won", reason = "verified live and saved as the provider's config")
                lost != null -> entry.copy(outcome = "lost", reason = lost)
                else -> entry
            }
        }
    }

    fun build(
        previous: String,
        recon: SiteRecon?,
        config: ScrapingConfig?,
        verification: JsonObject
    ): String {
        val base: Map<String, JsonElement> = runCatching {
            (json.parseToJsonElement(previous) as? JsonObject)?.toMap()
        }.getOrNull() ?: emptyMap()
        val merged = LinkedHashMap(base)
        merged["mapping"] = config?.let { mapping(it) } ?: JsonNull
        merged["siteMap"] = recon?.let { siteMap(it) } ?: JsonNull
        // The recon map, with the candidate ledger resolved to real outcomes.
        val reasons = buildList {
            (verification["reasons"] as? JsonArray)?.forEach { r -> (r as? JsonPrimitive)?.content?.let { add(it) } }
            (verification["losingCandidates"] as? JsonArray)?.forEach { r -> (r as? JsonPrimitive)?.content?.let { add(it) } }
        }
        merged["recon"] = recon?.let { r ->
            json.encodeToJsonElement(
                ReconMap.serializer(),
                r.reconMap.copy(candidateLedger = resolveLedger(r, config, reasons))
            )
        } ?: JsonNull
        merged["config"] = config?.let { json.encodeToJsonElement(ScrapingConfig.serializer(), it) } ?: JsonNull
        merged["verification"] = verification
        merged["recordedAt"] = JsonPrimitive(System.currentTimeMillis())
        return JsonObject(merged).toString()
    }

    private fun mapping(c: ScrapingConfig): JsonObject = buildJsonObject {
        put("searchUrlTemplate", JsonPrimitive(c.searchUrlTemplate))
        put("method", JsonPrimitive(c.method))
        put("queryParam", JsonPrimitive(c.queryParam))
        put("hiddenParams", runCatching { json.parseToJsonElement(c.hiddenParams) }.getOrDefault(JsonObject(emptyMap())))
        put("postData", JsonPrimitive(c.postData))
        put("paginationRule", JsonPrimitive(c.paginationRule))
        put("isJson", JsonPrimitive(c.isJson))
        put("jsonResultPath", JsonPrimitive(c.jsonResultPath))
        put("jsonTitleField", JsonPrimitive(c.jsonTitleField))
        put("jsonUrlField", JsonPrimitive(c.jsonUrlField))
        put("jsonThumbnailField", JsonPrimitive(c.jsonThumbnailField))
        put("resultSelector", JsonPrimitive(c.resultSelector))
        put("titleSelector", JsonPrimitive(c.titleSelector))
        put("urlSelector", JsonPrimitive(c.urlSelector))
        put("thumbnailSelector", JsonPrimitive(c.thumbnailSelector))
        put("descriptionSelector", JsonPrimitive(c.descriptionSelector))
        put("timeoutMs", JsonPrimitive(c.timeout))
        put("verified", JsonPrimitive(c.verified))
    }

    private fun siteMap(r: SiteRecon): JsonObject = buildJsonObject {
        put("baseUrl", JsonPrimitive(r.baseUrl))
        put("sitemapsRead", JsonArray(r.sitemapsRead.map { JsonPrimitive(it) }))
        put("sitemapUrlCount", JsonPrimitive(r.sitemapUrlCount))
        put("mappedUrlCount", JsonPrimitive(r.mappedUrlCount))
        put("probeQuery", JsonPrimitive(r.probeQuery))
        put("paginationParam", JsonPrimitive(r.paginationParam))
        put("paginationPathStyle", JsonPrimitive(r.paginationPathStyle))
        put("medianResponseMs", JsonPrimitive(r.medianResponseMs))
        put("suggestedTimeoutMs", JsonPrimitive(r.suggestedTimeoutMs))
        put("sampleItemUrls", JsonArray(r.sampleItemUrls.take(10).map { JsonPrimitive(it) }))
        put("candidates", buildJsonArray {
            r.searchCandidates.forEach { c ->
                add(buildJsonObject {
                    put("template", JsonPrimitive(c.urlTemplate))
                    put("postData", JsonPrimitive(c.postData))
                    put("source", JsonPrimitive(c.source.name))
                    put("evidence", JsonPrimitive(c.evidence))
                    put("queryParam", JsonPrimitive(c.queryParam))
                    put("isJson", JsonPrimitive(c.isJson))
                    put("jsonResultPath", JsonPrimitive(c.jsonResultPath))
                    put("jsonTitleField", JsonPrimitive(c.jsonTitleField))
                    put("jsonUrlField", JsonPrimitive(c.jsonUrlField))
                    put("jsonThumbnailField", JsonPrimitive(c.jsonThumbnailField))
                })
            }
        })
    }
}
