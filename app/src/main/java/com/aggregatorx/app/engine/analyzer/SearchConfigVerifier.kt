package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.engine.network.HttpTransport

import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.RenderMode
import com.aggregatorx.app.data.model.ScrapingConfig
import com.aggregatorx.app.engine.ml.DeterministicAnalysisHelper
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.scraper.ResultClassification
import com.aggregatorx.app.engine.scraper.SearchResultFilter
import com.aggregatorx.app.engine.util.EngineUtils
import com.aggregatorx.app.engine.validator.ProviderConfigValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live verification for candidate scraping configs — the gate a config
 * must pass before it is saved for a provider, on first add AND on
 * re-analyze (an existing config is re-proven the same way, and only
 * replaced when a better candidate proves itself).
 *
 * A config is only trusted when the site's OWN search proves it:
 *
 *  1. A real probe query (a content term taken from the site's own
 *     inventory by [SiteReconEngine]) is run through the candidate's
 *     search route. What comes back is extracted the way the runtime
 *     will extract it and classified: item/detail results vs
 *     category/listing links. Categories dominating — or too few
 *     items — means the route or selectors are wrong.
 *  2. Selector repair: when the candidate's selectors extract nothing
 *     from the live probe page, the page's suggested selectors are
 *     tried (up to three), each scored by the same item-vs-category
 *     classification, and the best is kept. JSON-LD payloads count too.
 *  3. The negative control: the same route is run with the nonsense
 *     query [NONSENSE_QUERY]. A route that "finds" items for nonsense
 *     is not exercising the site's search at all and is rejected.
 *  4. Rendered verification: when raw HTTP sees only an app shell
 *     (JS-heavy site), the best near-miss candidate is re-probed
 *     through the app's headless browser — including submitting the
 *     site's real search form the way a user would — and must pass
 *     the same classification and nonsense control there.
 *  5. Per-field confidence: route, extraction (selectors / JSON
 *     paths), and pagination are each scored from live evidence
 *     (pagination must be *seen* — a next-page link or payload field
 *     matching the template's mechanism — to score fully; a proven
 *     pagination parameter can even repair the template's). Only a
 *     candidate at or above [MIN_AUTOSAVE_CONFIDENCE] auto-saves;
 *     weaker evidence is reported, never silently saved.
 *
 * Access walls are named, not hidden: a login redirect, an HTTP
 * 401/403, or an anti-bot challenge page produces that specific
 * reason, because no automation can prove a search it cannot reach.
 *
 * Everything is bounded (per-fetch timeouts, phase budgets) and
 * [verify] never throws for a verification outcome: failure is data —
 * a list of specific reasons. (Coroutine cancellation is the one
 * exception: it propagates, as it must, instead of being recorded
 * as a verification error.)
 */
@Singleton
class SearchConfigVerifier @Inject constructor() {

    suspend fun verify(
        provider: Provider,
        candidates: List<VerifierCandidate>,
        probeQuery: String?,
        paginationHint: PaginationHint = PaginationHint()
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        if (probeQuery.isNullOrBlank()) {
            return@withContext VerificationOutcome.Rejected(
                listOf("No probe term could be derived from the site's own pages, so its search could not be verified")
            )
        }
        if (candidates.isEmpty()) {
            return@withContext VerificationOutcome.Rejected(
                listOf("No candidate search route could be derived from the site's own data (no search form, sitemap route, or live endpoint found)")
            )
        }

        val reasons = mutableListOf<String>()
        val nearMisses = mutableListOf<NearMiss>()
        var anyHttpResponse = false
        val rawStartedAt = System.currentTimeMillis()

        // ── Phase 1: raw HTTP probes ──────────────────────────────────
        for ((index, candidate) in candidates.withIndex()) {
            if (System.currentTimeMillis() - rawStartedAt > RAW_BUDGET_MS) {
                for (remaining in candidates.drop(index)) {
                    reasons += "${remaining.sourceLabel}: verification time budget exhausted before this route could be probed"
                }
                break
            }
            val attempt = runCatching { verifyCandidateRaw(provider, candidate, probeQuery, paginationHint) }
            when (val result = attempt.getOrNull()) {
                is CandidateAttempt.Passed -> return@withContext VerificationOutcome.Verified(
                    config = result.config,
                    verdict = result.verdict,
                    repaired = result.repaired,
                    losingReasons = reasons.toList()
                )
                is CandidateAttempt.Failed -> {
                    reasons += result.reason
                    result.nearMiss?.let { nearMisses += it }
                    if (result.gotHttpResponse) anyHttpResponse = true
                }
                null -> {
                    val e = attempt.exceptionOrNull()
                    // Cancellation is not a verification outcome: the
                    // old code recorded it as a "verification error"
                    // reason and kept probing the remaining candidates
                    // inside the cancelled call. Propagate it instead.
                    if (e is CancellationException) throw e
                    reasons += "${candidate.sourceLabel}: verification error (${e?.javaClass?.simpleName ?: "unknown"}: ${e?.message ?: "no detail"})"
                }
            }
        }

        // ── Phase 2: rendered probes for JS-heavy sites ───────────────
        // Only when raw HTTP actually reached the site (a dead site
        // gains nothing from a browser) and nothing has verified yet.
        if (anyHttpResponse && nearMisses.isNotEmpty()) {
            val renderedCandidates = nearMisses
                .sortedWith(
                    compareByDescending<NearMiss> { it.candidate.isFormRoute }
                        .thenByDescending { it.items - it.categories }
                )
                .map { it.candidate }
                .distinct()
                .take(MAX_RENDERED_CANDIDATES)
            val renderStartedAt = System.currentTimeMillis()
            val formHtmlCache = FormHtmlCache(provider)
            for (candidate in renderedCandidates) {
                if (System.currentTimeMillis() - renderStartedAt > RENDER_BUDGET_MS) break
                val attempt = runCatching {
                    verifyCandidateRendered(provider, candidate, probeQuery, paginationHint, formHtmlCache)
                }
                when (val result = attempt.getOrNull()) {
                    is CandidateAttempt.Passed -> return@withContext VerificationOutcome.Verified(
                        config = result.config,
                        verdict = result.verdict,
                        repaired = result.repaired,
                        losingReasons = reasons.toList()
                    )
                    is CandidateAttempt.Failed -> reasons += result.reason
                    null -> {
                        val e = attempt.exceptionOrNull()
                        if (e is CancellationException) throw e
                        reasons += "${candidate.sourceLabel}: rendered verification error (${e?.javaClass?.simpleName ?: "unknown"}: ${e?.message ?: "no detail"})"
                    }
                }
            }
        }

        VerificationOutcome.Rejected(reasons)
    }

    private data class NearMiss(
        val candidate: VerifierCandidate,
        val items: Int,
        val categories: Int
    )

    private sealed class CandidateAttempt {
        data class Passed(val config: ScrapingConfig, val verdict: ProbeVerdict, val repaired: Boolean) : CandidateAttempt()
        data class Failed(
            val reason: String,
            val nearMiss: NearMiss? = null,
            val gotHttpResponse: Boolean = false
        ) : CandidateAttempt()
    }

    /** Lazily fetches (once per verify call) the headless form-submission
     *  pages for the real probe and the nonsense control. */
    private class FormHtmlCache(private val provider: Provider) {
        private var realFetched = false
        private var realPage: HeadlessBrowserHelper.SearchFormPage? = null
        private var nonsenseFetched = false
        private var nonsensePage: HeadlessBrowserHelper.SearchFormPage? = null
        /** How many times the nonsense form submission was attempted. */
        var nonsenseAttempts = 0
            private set

        private suspend fun submit(query: String): HeadlessBrowserHelper.SearchFormPage? = runCatching {
            HeadlessBrowserHelper.submitSearchForm(provider.baseUrl, query)
        }.getOrElse { e -> if (e is CancellationException) throw e; null }

        suspend fun real(probeQuery: String): HeadlessBrowserHelper.SearchFormPage? {
            if (!realFetched) {
                realFetched = true
                realPage = submit(probeQuery)
            }
            return realPage
        }

        /** The nonsense control, retried once when no page comes back. */
        suspend fun nonsense(): HeadlessBrowserHelper.SearchFormPage? {
            if (!nonsenseFetched) {
                nonsenseFetched = true
                for (attempt in 1..2) {
                    nonsenseAttempts = attempt
                    nonsensePage = submit(NONSENSE_QUERY)
                    if (nonsensePage != null) break
                }
            }
            return nonsensePage
        }
    }

    // ─── Phase 1: raw candidate verification ──────────────────────────

    private fun verifyCandidateRaw(
        provider: Provider,
        candidate: VerifierCandidate,
        probeQuery: String,
        hint: PaginationHint
    ): CandidateAttempt {
        val label = candidate.sourceLabel
        val config = candidate.config
        var slowest = 0L

        val probe = buildRequest(provider, config, probeQuery)
        val t0 = System.currentTimeMillis()
        val response = HttpTransport.executeTls(probe)
        val responseMs = System.currentTimeMillis() - t0
        slowest = maxOf(slowest, responseMs)

        val wall = detectAccessWall(response.statusCode, response.finalUrl, response.body)
        if (response.error != null || response.statusCode !in 200..399) {
            return CandidateAttempt.Failed(
                "$label: probe search failed (${wall ?: response.error ?: "HTTP ${response.statusCode}"})",
                // A wall (403/challenge) is exactly what the rendered
                // phase exists for — keep the candidate eligible for it.
                nearMiss = if (wall != null) NearMiss(candidate, 0, 0) else null,
                gotHttpResponse = response.statusCode > 0
            )
        }

        val extraction = extractFromBody(response.body, response.finalUrl, provider, config, hint, allowRepair = true)
        val (items, categories) = classify(extraction.pairs)
        var effectiveConfig = extraction.effectiveConfig
        var repaired = extraction.repaired

        // A proven pagination parameter on the live page repairs the
        // template's page parameter when they disagree.
        extraction.provenPaginationParam?.let { provenParam ->
            repairPaginationParam(effectiveConfig, provenParam)?.let { fixed ->
                effectiveConfig = fixed
                repaired = true
            }
        }

        if (extraction.pairs.isEmpty() && wall != null) {
            return CandidateAttempt.Failed(
                "$label: $wall — raw HTTP could not prove the search; the rendered check decides",
                nearMiss = NearMiss(candidate, 0, 0),
                gotHttpResponse = true
            )
        }
        if (items < MIN_ITEMS || items <= categories) {
            return CandidateAttempt.Failed(
                reason = countsFailureReason(label, items, categories),
                nearMiss = NearMiss(candidate, items, categories),
                gotHttpResponse = true
            )
        }

        // Negative control: the site's own search must find ~nothing for nonsense.
        // Retried once on a transport-level failure; still unavailable means
        // the control is inconclusive and the candidate does NOT pass.
        val nonsenseStart = System.currentTimeMillis()
        var nonsenseResponse = HttpTransport.executeTls(buildRequest(provider, effectiveConfig, NONSENSE_QUERY))
        if (nonsenseResponse.error != null || nonsenseResponse.statusCode == 0) {
            nonsenseResponse = HttpTransport.executeTls(buildRequest(provider, effectiveConfig, NONSENSE_QUERY))
        }
        slowest = maxOf(slowest, System.currentTimeMillis() - nonsenseStart)
        // A 404/410 page is the site's own "nothing found" answer: it is a page,
        // and its items are still counted below.
        val nonsenseEmptyStatus = nonsenseResponse.statusCode == 404 || nonsenseResponse.statusCode == 410
        if (nonsenseResponse.error != null || (nonsenseResponse.statusCode !in 200..399 && !nonsenseEmptyStatus)) {
            return CandidateAttempt.Failed(
                "$label: nonsense control inconclusive: no page returned " +
                    "(${nonsenseResponse.error ?: "HTTP ${nonsenseResponse.statusCode}"}, after a retry) — the route did not survive verification",
                gotHttpResponse = true
            )
        }
        val nonsenseExtraction = extractFromBody(
            nonsenseResponse.body, nonsenseResponse.finalUrl, provider, effectiveConfig, hint, allowRepair = false
        )
        val (nonsenseItems, _) = classify(nonsenseExtraction.pairs)
        if (!nonsensePasses(nonsenseItems, items)) {
            return CandidateAttempt.Failed(
                "$label: the site's search ignores the query — a nonsense search (\"$NONSENSE_QUERY\") returned " +
                    "$nonsenseItems items (real search: $items), i.e. it answers nonsense with filler/popular items, " +
                    "so this route is not the site's real search",
                gotHttpResponse = true
            )
        }

        // Live page-2 proof: fetch the real next page via the discovered
        // mechanism. Pagination is only recorded when page 2 actually
        // returns new item results — never assumed from a link alone.
        val firstPageUrls = extraction.pairs.map { it.second }.toSet()
        val page2 = provePage2(provider, effectiveConfig, probeQuery, firstPageUrls, response.body, response.finalUrl, hint)
        slowest = maxOf(slowest, page2.elapsedMs)
        effectiveConfig = page2.config

        val confidence = computeConfidence(
            routeScore = 1.0f,
            items = items,
            categories = categories,
            paginationProven = page2.proven,
            hint = hint,
            paginationSeen = extraction.paginationProven
        )
        val checks = listOf(
            VerificationCheck("route-responds", true, "HTTP ${response.statusCode} in ${responseMs} ms from ${probe.url.take(160)}"),
            VerificationCheck("results-parse", items >= MIN_ITEMS, "$items item results for the probe \"$probeQuery\" (need >= $MIN_ITEMS)"),
            VerificationCheck("items-outnumber-categories", items > categories, "$items items vs $categories category/listing links"),
            VerificationCheck("nonsense-control-empty", nonsensePasses(nonsenseItems, items), "\"$NONSENSE_QUERY\" returned $nonsenseItems items (real search: $items)"),
            VerificationCheck("page-2-exists", page2.proven, page2.detail)
        )
        if (confidence.overall < MIN_AUTOSAVE_CONFIDENCE) {
            return CandidateAttempt.Failed(
                "$label: results verified live but the evidence is too weak to auto-save " +
                    "(confidence ${formatScore(confidence.overall)} — ${confidence.detail}); " +
                    "the route was left unsaved rather than guessed",
                gotHttpResponse = true
            )
        }
        effectiveConfig = finalizeConfig(effectiveConfig, extraction.pairs, page2, confidence.overall)
            .copy(renderMode = RenderMode.RAW)

        val verdict = ProbeVerdict(
            sourceLabel = label,
            probedUrl = probe.url,
            itemCount = items,
            categoryCount = categories,
            nonsenseItemCount = nonsenseItems,
            responseMs = responseMs,
            slowestProbeMs = slowest,
            passed = true,
            rendered = false,
            paginationProven = page2.proven,
            confidence = confidence.overall,
            confidenceDetail = confidence.detail,
            reason = "Verified live: $items item results, $categories category links, " +
                "nonsense control returned $nonsenseItems, " +
                (if (page2.proven) "page 2 proven live (${page2.newItems} new items)" else "pagination not proven") +
                ", confidence ${formatScore(confidence.overall)}",
            checks = checks,
            fieldConfidence = confidence.fields,
            page2Url = page2.url,
            page2NewItems = page2.newItems,
            paginationRule = effectiveConfig.paginationRule
        )
        return CandidateAttempt.Passed(effectiveConfig, verdict, repaired)
    }

    // ─── Phase 2: rendered candidate verification ─────────────────────

    private suspend fun verifyCandidateRendered(
        provider: Provider,
        candidate: VerifierCandidate,
        probeQuery: String,
        hint: PaginationHint,
        formHtmlCache: FormHtmlCache
    ): CandidateAttempt {
        val label = candidate.sourceLabel
        val config = candidate.config
        var slowest = 0L

        // Source A: submit the site's real form in the headless browser.
        if (candidate.isFormRoute) {
            val formStart = System.currentTimeMillis()
            val formPage = formHtmlCache.real(probeQuery)
            slowest = maxOf(slowest, System.currentTimeMillis() - formStart)
            if (formPage != null) {
                val pageBase = formPage.finalUrl.ifBlank { provider.baseUrl }
                val extraction = extractFromBody(formPage.html, pageBase, provider, config, hint, allowRepair = true)
                val (items, categories) = classify(extraction.pairs)
                if (items >= MIN_ITEMS && items > categories) {
                    // Negative control (spec §2): it must actually run and pass.
                    // No page after a retry is inconclusive, and inconclusive
                    // is a failure — never a silent pass.
                    val nonsenseStart = System.currentTimeMillis()
                    val nonsensePage = formHtmlCache.nonsense()
                    slowest = maxOf(slowest, System.currentTimeMillis() - nonsenseStart)
                    if (nonsensePage == null) {
                        return CandidateAttempt.Failed(
                            "$label: nonsense control inconclusive: no page returned " +
                                "(the site's form was submitted for \"$NONSENSE_QUERY\" ${formHtmlCache.nonsenseAttempts} times " +
                                "and no results page came back), so the form search could not be proven",
                            gotHttpResponse = true
                        )
                    }
                    val nonsenseItems = classify(
                        extractFromBody(
                            nonsensePage.html, nonsensePage.finalUrl.ifBlank { provider.baseUrl },
                            provider, extraction.effectiveConfig, hint, allowRepair = false
                        ).pairs
                    ).first
                    if (!nonsensePasses(nonsenseItems, items)) {
                        return CandidateAttempt.Failed(
                            "$label: rendered form search ignores the query — a nonsense search (\"$NONSENSE_QUERY\") returned " +
                                "$nonsenseItems items (real search: $items), i.e. it answers nonsense with filler/popular items",
                            gotHttpResponse = true
                        )
                    }

                    // Live page-2 proof from the form's own results page.
                    val firstPageUrls = extraction.pairs.map { it.second }.toSet()
                    val page2 = proveFormPage2(provider, extraction.effectiveConfig, probeQuery, formPage, firstPageUrls, hint)
                    slowest = maxOf(slowest, page2.elapsedMs)

                    val confidence = computeConfidence(
                        routeScore = 0.9f,
                        items = items,
                        categories = categories,
                        paginationProven = page2.proven,
                        hint = hint,
                        paginationSeen = extraction.paginationProven
                    )
                    if (confidence.overall < MIN_AUTOSAVE_CONFIDENCE) {
                        return CandidateAttempt.Failed(
                            "$label: rendered form search verified but the evidence is too weak to auto-save " +
                                "(confidence ${formatScore(confidence.overall)} — ${confidence.detail})",
                            gotHttpResponse = true
                        )
                    }
                    val finalConfig = finalizeConfig(page2.config, extraction.pairs, page2, confidence.overall)
                        .copy(renderMode = RenderMode.FORM)
                    return CandidateAttempt.Passed(
                        config = finalConfig,
                        verdict = ProbeVerdict(
                            sourceLabel = label,
                            probedUrl = pageBase,
                            itemCount = items,
                            categoryCount = categories,
                            nonsenseItemCount = nonsenseItems,
                            responseMs = slowest,
                            slowestProbeMs = slowest,
                            passed = true,
                            rendered = true,
                            paginationProven = page2.proven,
                            confidence = confidence.overall,
                            confidenceDetail = confidence.detail,
                            reason = "Verified in the headless browser via the site's own form: " +
                                "$items item results, $categories category links, " +
                                "nonsense control returned $nonsenseItems, " +
                                (if (page2.proven) "page 2 proven live (${page2.newItems} new items)" else "pagination not proven") +
                                ", confidence ${formatScore(confidence.overall)}",
                            checks = listOf(
                                VerificationCheck("route-responds", true, "site's own form submitted (results page ${pageBase.take(160)})"),
                                VerificationCheck("results-parse", true, "$items item results for the probe \"$probeQuery\""),
                                VerificationCheck("items-outnumber-categories", true, "$items items vs $categories categories"),
                                VerificationCheck(
                                    "nonsense-control-empty", true,
                                    "\"$NONSENSE_QUERY\" returned $nonsenseItems items (real search: $items)" +
                                        if (formHtmlCache.nonsenseAttempts > 1) " after a retry" else ""
                                ),
                                VerificationCheck("page-2-exists", page2.proven, page2.detail)
                            ),
                            fieldConfidence = confidence.fields,
                            page2Url = page2.url,
                            page2NewItems = page2.newItems,
                            paginationRule = finalConfig.paginationRule
                        ),
                        repaired = extraction.repaired
                    )
                }
            }
        }

        // Source B: render the candidate's own probe URL.
        val probeUrl = buildRequest(provider, config, probeQuery).url
        val renderStart = System.currentTimeMillis()
        val renderedHtml = renderPage(probeUrl)
        slowest = maxOf(slowest, System.currentTimeMillis() - renderStart)
        if (renderedHtml == null) {
            return CandidateAttempt.Failed(
                "$label: the page could not be rendered in the headless browser either",
                gotHttpResponse = true
            )
        }
        val extraction = extractFromBody(renderedHtml, probeUrl, provider, config, hint, allowRepair = true)
        val (items, categories) = classify(extraction.pairs)
        if (items < MIN_ITEMS || items <= categories) {
            return CandidateAttempt.Failed(
                "$label: even after rendering, the probe found $items items and $categories category links — the content is not reachable as search results",
                gotHttpResponse = true
            )
        }
        // Nonsense control, rendered the same way search will run it (a raw
        // fetch of a JS route only sees the empty app shell, which "passes"
        // trivially). Retried once; no page means inconclusive = fail.
        val nonsenseUrl = buildRequest(provider, extraction.effectiveConfig, NONSENSE_QUERY).url
        val nonsenseStart = System.currentTimeMillis()
        var nonsenseAttempts = 1
        var nonsenseHtml = renderPage(nonsenseUrl)
        if (nonsenseHtml == null) {
            nonsenseAttempts = 2
            nonsenseHtml = renderPage(nonsenseUrl)
        }
        slowest = maxOf(slowest, System.currentTimeMillis() - nonsenseStart)
        if (nonsenseHtml == null) {
            return CandidateAttempt.Failed(
                "$label: nonsense control inconclusive: no page returned (rendering ${nonsenseUrl.take(140)} failed twice), " +
                    "so the rendered search could not be proven",
                gotHttpResponse = true
            )
        }
        val nonsenseItems = classify(
            extractFromBody(nonsenseHtml, nonsenseUrl, provider, extraction.effectiveConfig, hint, allowRepair = false).pairs
        ).first
        if (!nonsensePasses(nonsenseItems, items)) {
            return CandidateAttempt.Failed(
                "$label: rendered search ignores the query — a rendered nonsense search (\"$NONSENSE_QUERY\") returned " +
                    "$nonsenseItems items (real search: $items), i.e. it answers nonsense with filler/popular items",
                gotHttpResponse = true
            )
        }

        // Live page-2 proof, rendered: the template's {page} or the rendered
        // page's own next link.
        val firstPageUrls = extraction.pairs.map { it.second }.toSet()
        val page2 = proveRenderedPage2(provider, extraction.effectiveConfig, probeQuery, firstPageUrls, renderedHtml, probeUrl, hint)
        slowest = maxOf(slowest, page2.elapsedMs)

        val confidence = computeConfidence(
            routeScore = 0.8f,
            items = items,
            categories = categories,
            paginationProven = page2.proven,
            hint = hint,
            paginationSeen = extraction.paginationProven
        )
        if (confidence.overall < MIN_AUTOSAVE_CONFIDENCE) {
            return CandidateAttempt.Failed(
                "$label: rendered search verified but the evidence is too weak to auto-save " +
                    "(confidence ${formatScore(confidence.overall)} — ${confidence.detail})",
                gotHttpResponse = true
            )
        }
        val finalConfig = finalizeConfig(page2.config, extraction.pairs, page2, confidence.overall)
            .copy(renderMode = RenderMode.RENDER)
        return CandidateAttempt.Passed(
            config = finalConfig,
            verdict = ProbeVerdict(
                sourceLabel = label,
                probedUrl = probeUrl,
                itemCount = items,
                categoryCount = categories,
                nonsenseItemCount = nonsenseItems,
                responseMs = slowest,
                slowestProbeMs = slowest,
                passed = true,
                rendered = true,
                paginationProven = page2.proven,
                confidence = confidence.overall,
                confidenceDetail = confidence.detail,
                reason = "Verified after headless rendering: $items item results, $categories category links, " +
                    "rendered nonsense control returned $nonsenseItems, " +
                    (if (page2.proven) "page 2 proven live (${page2.newItems} new items)" else "pagination not proven") +
                    ", confidence ${formatScore(confidence.overall)}",
                checks = listOf(
                    VerificationCheck("route-responds", true, "rendered $probeUrl in the headless browser"),
                    VerificationCheck("results-parse", true, "$items item results for the probe \"$probeQuery\""),
                    VerificationCheck("items-outnumber-categories", true, "$items items vs $categories categories"),
                    VerificationCheck(
                        "nonsense-control-empty", true,
                        "rendered \"$NONSENSE_QUERY\" returned $nonsenseItems items (real search: $items)" +
                            if (nonsenseAttempts > 1) " after a retry" else ""
                    ),
                    VerificationCheck("page-2-exists", page2.proven, page2.detail)
                ),
                fieldConfidence = confidence.fields,
                page2Url = page2.url,
                page2NewItems = page2.newItems,
                paginationRule = finalConfig.paginationRule
            ),
            repaired = extraction.repaired
        )
    }

    /** Render [url] in the headless browser; null when no page came back. */
    private suspend fun renderPage(url: String): String? = runCatching {
        HeadlessBrowserHelper.fetchPageContent(url, RENDER_TIMEOUT_MS)
    }.getOrElse { e -> if (e is CancellationException) throw e; null }
        ?.takeIf { it.isNotBlank() }

    // ─── Confidence ───────────────────────────────────────────────────

    private data class Confidence(val overall: Float, val detail: String, val fields: Map<String, Float> = emptyMap())

    /**
     * Per-field confidence from live evidence. Route = the probe (and
     * its nonsense control) behaved like a real search. Extraction =
     * how item-dominated the classified results were. Pagination =
     * fully scored only when a next-page mechanism matching the
     * template was actually seen; inventory evidence alone scores
     * partially; nothing seen scores low.
     */
    private fun computeConfidence(
        routeScore: Float,
        items: Int,
        categories: Int,
        paginationProven: Boolean,
        hint: PaginationHint,
        paginationSeen: Boolean = false
    ): Confidence {
        val total = (items + categories).coerceAtLeast(1)
        val share = items.toFloat() / total
        var extractionScore = when {
            share >= 0.9f -> 1.0f
            share >= 0.75f -> 0.85f
            share >= 0.6f -> 0.7f
            else -> 0.5f
        }
        if (items >= 10) extractionScore = minOf(1.0f, extractionScore + 0.05f)
        // Pagination scores fully only when page 2 was fetched live and
        // returned new items; a next-page link merely seen on the page is
        // partial evidence; inventory hints less; nothing, least.
        val paginationScore = when {
            paginationProven -> 1.0f
            paginationSeen -> 0.5f
            hint.inventoryEvidence -> 0.4f
            else -> 0.3f
        }
        val overall = routeScore * 0.35f + extractionScore * 0.40f + paginationScore * 0.25f
        val detail = "route=${formatScore(routeScore)}, extraction=${formatScore(extractionScore)} " +
            "(item share ${formatScore(share)}), pagination=${formatScore(paginationScore)}" +
            if (paginationProven) " (page 2 fetched live)" else if (paginationSeen) " (next-page link seen, page 2 not proven)" else " (not proven)"
        return Confidence(
            overall,
            detail,
            mapOf("route" to routeScore, "extraction" to extractionScore, "pagination" to paginationScore, "overall" to overall)
        )
    }

    private fun formatScore(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    private fun countsFailureReason(label: String, items: Int, categories: Int): String =
        if (categories > 0 && categories >= items) {
            "$label: probe results were mostly category/listing links ($categories categories vs $items items) — this route does not lead to results"
        } else {
            "$label: probe search returned only $items item results (need at least $MIN_ITEMS) — the route or its selectors do not match the site's results page"
        }

    private fun nonsensePasses(nonsenseItems: Int, realItems: Int): Boolean =
        nonsenseItems == 0 || (nonsenseItems <= 1 && nonsenseItems < realItems)

    /** Name the wall when the site refuses to be probed, so the user
     *  hears "login wall / anti-bot challenge" instead of a bare failure. */
    private fun detectAccessWall(statusCode: Int, finalUrl: String, body: String): String? {
        if (statusCode == 401 || statusCode == 403) {
            return "the site refused the probe (HTTP $statusCode — login wall or anti-bot protection)"
        }
        val path = runCatching { java.net.URL(finalUrl).path.lowercase() }.getOrDefault("")
        if (path.contains("/login") || path.contains("/signin") || path.contains("/sign-in") || path.contains("/auth/")) {
            return "the probe landed on a login page — the search is behind a login wall"
        }
        val lower = body.lowercase()
        if (body.length < 30_000 && (
                lower.contains("just a moment") ||
                    lower.contains("checking your browser") ||
                    lower.contains("verify you are human") ||
                    lower.contains("are you a robot") ||
                    (lower.contains("cloudflare") && lower.contains("challenge"))
                )
        ) {
            return "the site answered with an anti-bot challenge instead of search results"
        }
        return null
    }

    // ─── Request building ─────────────────────────────────────────────

    private fun buildRequest(provider: Provider, config: ScrapingConfig, query: String, page: Int = 1): TlsRequest {
        val encoded = URLEncoder.encode(query, "UTF-8")
        fun fill(template: String): String = template
            .replace("{baseUrl}", provider.baseUrl)
            .replace("{query}", encoded)
            .replace("{page}", page.toString())

        val url = fill(config.searchUrlTemplate)
        val postData = config.postData
        return if (postData != null) {
            TlsRequest(
                url = url,
                method = "POST",
                headers = mapOf(
                    "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                body = fill(postData),
                timeoutMs = config.timeout.coerceIn(PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)
            )
        } else {
            TlsRequest(
                url = url,
                headers = mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT),
                timeoutMs = config.timeout.coerceIn(PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)
            )
        }
    }

    /** Swap the template's page-parameter name for the one the live
     *  page provably uses (its own next-page link). Returns null when
     *  the template has no {page} parameter or already uses it. */
    private fun repairPaginationParam(config: ScrapingConfig, provenParam: String): ScrapingConfig? {
        val match = PAGE_PARAM_REGEX.find(config.searchUrlTemplate) ?: return null
        val currentParam = match.groupValues[1]
        if (currentParam.equals(provenParam, ignoreCase = true)) return null
        val fixed = config.searchUrlTemplate.replace("$currentParam={page}", "$provenParam={page}")
        return if (fixed != config.searchUrlTemplate) config.copy(searchUrlTemplate = fixed) else null
    }

    // ─── Extraction ───────────────────────────────────────────────────

    private data class Extraction(
        val pairs: List<Pair<String, String>>,
        val effectiveConfig: ScrapingConfig,
        val repaired: Boolean,
        val paginationProven: Boolean = false,
        val provenPaginationParam: String? = null
    )

    private fun extractFromBody(
        body: String,
        finalUrl: String,
        provider: Provider,
        config: ScrapingConfig,
        hint: PaginationHint,
        allowRepair: Boolean
    ): Extraction {
        val pageBase = finalUrl.ifBlank { provider.baseUrl }
        val trimmed = body.trimStart()

        // JSON route: the payload itself is the results. Extraction is
        // the analysis pipeline's own (DeterministicAnalysisHelper),
        // with the broader key walker as a fallback.
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (root != null) {
                val helperPairs = runCatching {
                    DeterministicAnalysisHelper.extractResultsFromJson(body, provider.id)
                }.getOrDefault(emptyList()).map { it.title to it.url }
                val pairs = if (helperPairs.isNotEmpty()) {
                    helperPairs.distinctBy { it.second }
                } else {
                    extractJsonPairs(root, provider.baseUrl)
                }
                return Extraction(
                    pairs = pairs,
                    effectiveConfig = config,
                    repaired = false,
                    paginationProven = jsonPaginationProven(root)
                )
            }
        }

        val doc = runCatching { Jsoup.parse(body, pageBase) }.getOrNull()
            ?: return Extraction(emptyList(), config, repaired = false)
        val pagination = detectHtmlPagination(doc, hint)

        val bySelectors = extractHtmlPairs(doc, config.resultSelector, config.titleSelector, config.urlSelector)
        if (bySelectors.isNotEmpty()) {
            return Extraction(bySelectors, config, false, pagination.first, pagination.second)
        }

        // The runtime reads JSON-LD too — a page whose results live in
        // embedded JSON verifies with the candidate's config as-is.
        val byJsonLd = extractJsonLdPairs(doc, provider.baseUrl)
        if (byJsonLd.isNotEmpty()) {
            return Extraction(byJsonLd, config, false, pagination.first, pagination.second)
        }

        if (allowRepair) {
            // Rank the page's own suggested selectors by live
            // item-vs-category classification and keep the best.
            val suggestions = runCatching {
                ProviderConfigValidator.suggestSelectors(body, provider.baseUrl).resultSelectors
            }.getOrDefault(emptyList())
            var bestPairs: List<Pair<String, String>> = emptyList()
            var bestSelector: String? = null
            var bestScore = Int.MIN_VALUE
            for (suggestion in suggestions.take(MAX_REPAIR_SELECTORS)) {
                val pairs = extractHtmlPairs(doc, suggestion.selector, REPAIR_TITLE_SELECTOR, REPAIR_URL_SELECTOR)
                if (pairs.isEmpty()) continue
                val (sItems, sCategories) = classify(pairs)
                val score = (sItems - sCategories) * 100 + sItems
                if (score > bestScore) {
                    bestScore = score
                    bestPairs = pairs
                    bestSelector = suggestion.selector
                }
            }
            if (bestSelector != null) {
                val repairedConfig = config.copy(
                    resultSelector = bestSelector,
                    titleSelector = REPAIR_TITLE_SELECTOR,
                    urlSelector = REPAIR_URL_SELECTOR
                )
                return Extraction(bestPairs, repairedConfig, true, pagination.first, pagination.second)
            }
        }
        return Extraction(emptyList(), config, false, pagination.first, pagination.second)
    }

    private fun extractHtmlPairs(
        doc: org.jsoup.nodes.Document,
        resultSelector: String,
        titleSelector: String,
        urlSelector: String
    ): List<Pair<String, String>> {
        val items = runCatching { doc.select(resultSelector) }.getOrDefault(emptyList())
        val pairs = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val title = usableTitle(item, titleSelector) ?: continue
            val url = usableUrl(item, urlSelector) ?: continue
            pairs += title to url
        }
        return pairs.distinctBy { it.second }
    }

    private fun usableTitle(item: Element, titleSelector: String): String? {
        val candidates = mutableListOf<Element>()
        runCatching { item.select(titleSelector) }.getOrDefault(emptyList()).let { candidates += it }
        if (candidates.isEmpty()) {
            runCatching { item.select(TITLE_FALLBACK_SELECTOR) }.getOrDefault(emptyList()).let { candidates += it }
        }
        for (element in candidates) {
            for (raw in listOf(
                element.attr("data-title"),
                element.attr("title"),
                element.attr("aria-label"),
                element.attr("alt"),
                element.text()
            )) {
                val value = raw.replace(Regex("\\s+"), " ").trim()
                if (value.length >= 3 &&
                    !DURATION_REGEX.matches(value) &&
                    !MINUTES_REGEX.matches(value)
                ) {
                    return value
                }
            }
        }
        return null
    }

    private fun usableUrl(item: Element, urlSelector: String): String? {
        val candidates = mutableListOf<Element>()
        runCatching { item.select(urlSelector) }.getOrDefault(emptyList()).let { candidates += it }
        if (candidates.isEmpty() || candidates.none { it.hasAttr("href") }) {
            runCatching { item.select("a[href]") }.getOrDefault(emptyList()).let { candidates += it }
        }
        for (element in candidates) {
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            if (href.isNotBlank() &&
                !href.startsWith("javascript:", ignoreCase = true) &&
                !href.startsWith("mailto:", ignoreCase = true)
            ) {
                return href
            }
        }
        return null
    }

    // ─── Pagination proof ─────────────────────────────────────────────

    /** A next-page mechanism is "proven" when the live page links to
     *  page 2 in the template's own style (its page parameter or
     *  /page/2 paths), or exposes a rel="next" link. Returns
     *  (proven, provenParamNameIfAny). */
    private fun detectHtmlPagination(
        doc: org.jsoup.nodes.Document,
        hint: PaginationHint
    ): Pair<Boolean, String?> {
        var provenParam: String? = null
        var proven = false
        for (anchor in doc.select("a[href]")) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) continue
            if (href.contains("/page/2") || href.contains("/page=2")) {
                proven = true
                if (hint.pathStyle) provenParam = provenParam ?: hint.param
            }
            val paramMatch = PAGE2_PARAM_REGEX.find(href)
            if (paramMatch != null) {
                proven = true
                provenParam = paramMatch.groupValues[1].lowercase()
            }
            if (anchor.attr("rel").equals("next", ignoreCase = true)) {
                proven = true
            }
        }
        // Even without a page-2 link, the hinted parameter appearing on
        // the page's own links at any value is partial evidence — the
        // caller still needs the =2 form for parameter repair, so only
        // the proven flag is raised via rel=next / /page/2 above.
        return proven to provenParam
    }

    private fun jsonPaginationProven(root: JsonElement): Boolean {
        var found = false
        fun walk(element: JsonElement, depth: Int) {
            if (depth > 6 || found) return
            when (element) {
                is JsonObject -> {
                    for ((key, value) in element) {
                        val k = key.lowercase()
                        if (k in setOf("next", "next_page", "nextpage", "next_url") &&
                            value is JsonPrimitive && !value.contentOrNull.isNullOrBlank()
                        ) {
                            found = true
                        }
                        if (k in setOf("total_pages", "totalpages", "last_page", "page_count") &&
                            value is JsonPrimitive && (value.contentOrNull?.toIntOrNull() ?: 0) > 1
                        ) {
                            found = true
                        }
                        walk(value, depth + 1)
                    }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return found
    }

    // ─── JSON extraction (fallback walker; key conventions mirror
    //     ScrapingEngine.extractResultsFromJson) ───────────────────────

    private fun extractJsonPairs(root: JsonElement, baseUrl: String): List<Pair<String, String>> {
        val objects = mutableListOf<JsonObject>()
        fun walk(element: JsonElement, depth: Int) {
            if (depth > 8 || objects.size >= 500) return
            when (element) {
                is JsonObject -> {
                    objects += element
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)

        val pairs = mutableListOf<Pair<String, String>>()
        for (obj in objects) {
            val title = obj.elementFor(TITLE_KEYS).stringValue()?.trim().orEmpty()
            val rawUrl = obj.elementFor(URL_KEYS).stringValue()?.trim().orEmpty()
            if (title.length < 3 || rawUrl.isBlank()) continue
            val url = EngineUtils.normalizeUrl(rawUrl, baseUrl)
            if (!url.startsWith("http")) continue
            pairs += title to url
        }
        return pairs.distinctBy { it.second }
    }

    private fun extractJsonLdPairs(doc: org.jsoup.nodes.Document, baseUrl: String): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            pairs += extractJsonPairs(root, baseUrl)
        }
        return pairs.distinctBy { it.second }
    }

    private fun JsonObject.elementFor(keys: List<String>): JsonElement? = entries
        .firstOrNull { entry -> keys.any { it.equals(entry.key, ignoreCase = true) } }
        ?.value

    private fun JsonElement?.stringValue(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> elementFor(listOf("url", "src", "href"))?.stringValue()
        is JsonArray -> firstOrNull()?.stringValue()
        else -> null
    }

    // ─── Classification: items vs category/listing links ──────────────

    private fun classify(pairs: List<Pair<String, String>>): Pair<Int, Int> {
        var categories = 0
        for ((title, url) in pairs) {
            if (SearchResultFilter.isLikelyCategory(title) ||
                SearchResultFilter.isLikelyDuration(title) ||
                isCategoryLikeUrl(url)
            ) {
                categories++
            }
        }
        return (pairs.size - categories) to categories
    }

    private fun isCategoryLikeUrl(url: String): Boolean = ResultClassification.isCategoryLikeUrl(url)

    private fun isCategoryPair(title: String, url: String): Boolean =
        SearchResultFilter.isLikelyCategory(title) ||
            SearchResultFilter.isLikelyDuration(title) ||
            isCategoryLikeUrl(url)

    // ─── Live page-2 proof ────────────────────────────────────────────

    private data class Page2Proof(
        val proven: Boolean,
        val config: ScrapingConfig,
        val url: String?,
        val newItems: Int,
        val detail: String,
        val elapsedMs: Long
    )

    /**
     * Fetch the site's real page 2 for the probe query through the discovered
     * mechanism: the template's own {page} token, or — when the template has
     * none — a template derived from the live results page's own next-page
     * link. Proven only when page 2 returns item results not on page 1.
     */
    private fun provePage2(
        provider: Provider,
        config: ScrapingConfig,
        probeQuery: String,
        firstPageUrls: Set<String>,
        firstBody: String,
        firstFinalUrl: String,
        hint: PaginationHint
    ): Page2Proof {
        val started = System.currentTimeMillis()
        val attempts = mutableListOf<ScrapingConfig>()
        val hasToken = config.searchUrlTemplate.contains("{page}") || config.postData?.contains("{page}") == true
        if (hasToken) attempts += config
        if (config.postData == null) {
            deriveTemplateFromNextLink(firstBody, firstFinalUrl, probeQuery)
                ?.takeIf { it != config.searchUrlTemplate }
                ?.let { attempts += config.copy(searchUrlTemplate = it) }
        }
        if (attempts.isEmpty()) {
            return Page2Proof(false, config.copy(paginationRule = null), null, 0,
                "no page-2 mechanism on the live results page (no {page} parameter and no next-page link carrying the query)",
                System.currentTimeMillis() - started)
        }
        val failures = mutableListOf<String>()
        for (candidate in attempts) {
            val request = buildRequest(provider, candidate, probeQuery, page = candidate.paginationStart + 1)
            val response = HttpTransport.executeTls(request)
            if (response.error != null || response.statusCode !in 200..399) {
                failures += "page-2 fetch ${request.url.take(140)} failed (${response.error ?: "HTTP ${response.statusCode}"})"
                continue
            }
            val pairs = extractFromBody(response.body, response.finalUrl, provider, candidate, hint, allowRepair = false).pairs
            val newItems = pairs.count { (title, url) -> url !in firstPageUrls && !isCategoryPair(title, url) }
            if (newItems >= 1) {
                val rule = derivePaginationRule(candidate)
                return Page2Proof(
                    true,
                    candidate.copy(paginationRule = rule),
                    request.url,
                    newItems,
                    "page 2 fetched live (${request.url.take(160)}): $newItems new item results; rule ${rule ?: "template {page}"}",
                    System.currentTimeMillis() - started
                )
            }
            failures += "page-2 fetch ${request.url.take(140)} returned no new item results (${pairs.size} links, all on page 1 or categories)"
        }
        return Page2Proof(false, config.copy(paginationRule = null), null, 0,
            failures.joinToString("; "), System.currentTimeMillis() - started)
    }

    /** New item results on a page-2 body that were not on page 1. */
    private fun newItemsOn(
        body: String,
        pageUrl: String,
        provider: Provider,
        config: ScrapingConfig,
        hint: PaginationHint,
        seen: Set<String>
    ): Pair<Int, Int> {
        val pairs = extractFromBody(body, pageUrl, provider, config, hint, allowRepair = false).pairs
        return pairs.count { (title, url) -> url !in seen && !isCategoryPair(title, url) } to pairs.size
    }

    private fun fillLinkTemplate(template: String, query: String, page: Int): String =
        template.replace("{query}", URLEncoder.encode(query, "UTF-8")).replace("{page}", page.toString())

    /**
     * Page 2 for a FORM (site's own form, WebView) provider. The form's result
     * page has no {page} template, so the site's own next-page mechanism is
     * found on that page and exercised live:
     *  1. a next link (rel=next / page parameter / /page/N/) carrying the query —
     *     templated and fetched raw through the transport, then rendered in the
     *     WebView if raw shows no new items;
     *  2. otherwise the site's own next control: the form is submitted in the
     *     WebView and its next button/link is clicked; the WebView's page 1 must
     *     overlap the verified page 1 (same search) and page 2 must add items.
     * The demonstrated mechanism becomes the saved rule Load More replays:
     * "form-link-raw:<template>", "form-link-render:<template>" or
     * "form-click:<control descriptor>".
     */
    private suspend fun proveFormPage2(
        provider: Provider,
        config: ScrapingConfig,
        probeQuery: String,
        firstPage: HeadlessBrowserHelper.SearchFormPage,
        firstPageUrls: Set<String>,
        hint: PaginationHint
    ): Page2Proof {
        val started = System.currentTimeMillis()
        val failures = mutableListOf<String>()
        val firstBase = firstPage.finalUrl.ifBlank { provider.baseUrl }
        val linkConfig = config.copy(postData = null)

        val template = deriveTemplateFromNextLink(firstPage.html, firstBase, probeQuery)
        if (template != null) {
            val url = fillLinkTemplate(template, probeQuery, 2)
            val response = HttpTransport.executeTls(
                TlsRequest(
                    url = url,
                    headers = mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT, "Referer" to firstBase),
                    timeoutMs = config.timeout.coerceIn(PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)
                )
            )
            if (response.error == null && response.statusCode in 200..399) {
                val (newItems, links) = newItemsOn(response.body, response.finalUrl.ifBlank { url }, provider, linkConfig, hint, firstPageUrls)
                if (newItems >= 1) {
                    val rule = FORM_LINK_RAW_RULE + template
                    return Page2Proof(true, config.copy(paginationRule = rule), url, newItems,
                        "page 2 fetched live from the form results page's own next link (${url.take(160)}): $newItems new item results; rule form-link-raw",
                        System.currentTimeMillis() - started)
                }
                failures += "next link ${url.take(140)} fetched raw: no new item results ($links links)"
            } else {
                failures += "next link ${url.take(140)} fetched raw failed (${response.error ?: "HTTP ${response.statusCode}"})"
            }
            val rendered = renderPage(url)
            if (rendered != null) {
                val (newItems, links) = newItemsOn(rendered, url, provider, linkConfig, hint, firstPageUrls)
                if (newItems >= 1) {
                    val rule = FORM_LINK_RENDER_RULE + template
                    return Page2Proof(true, config.copy(paginationRule = rule), url, newItems,
                        "page 2 rendered live from the form results page's own next link (${url.take(160)}): $newItems new item results; rule form-link-render",
                        System.currentTimeMillis() - started)
                }
                failures += "next link rendered: no new item results ($links links)"
            } else {
                failures += "next link could not be rendered"
            }
        } else {
            failures += "no next link carrying the query on the form's results page"
        }

        // The site's own next control, clicked in the WebView.
        val webRun = runCatching {
            HeadlessBrowserHelper.renderFormSearchPages(provider.baseUrl, probeQuery, nextClicks = 1, timeout = FORM_PAGINATION_TIMEOUT_MS)
        }.getOrElse { e -> if (e is CancellationException) throw e; null }
        when {
            webRun == null -> { failures += "WebView unavailable for the next-control check" }
            webRun.pages.size < 2 -> { failures += "next control: ${webRun.stopReason ?: "page 2 not reached"}" }
            else -> {
                val webPage1 = webRun.pages[0]
                val webPage2 = webRun.pages[1]
                val page1Urls = extractFromBody(webPage1.html, webPage1.url, provider, config, hint, allowRepair = false)
                    .pairs.map { it.second }.toSet()
                if (page1Urls.none { it in firstPageUrls }) {
                    failures += "next control: the WebView's form submission did not reach the verified results page (no overlap with page 1)"
                } else {
                    val (newItems, links) = newItemsOn(webPage2.html, webPage2.url, provider, config, hint, firstPageUrls + page1Urls)
                    val control = webRun.nextControl
                    if (newItems >= 1 && control != null) {
                        val rule = FORM_CLICK_RULE + control
                        return Page2Proof(true, config.copy(paginationRule = rule), webPage2.url, newItems,
                            "page 2 reached live by clicking the site's own next control ($control) after submitting its form in the WebView: " +
                                "$newItems new item results; rule form-click",
                            System.currentTimeMillis() - started)
                    }
                    failures += "next control ($control) clicked: no new item results ($links links)"
                }
            }
        }
        return Page2Proof(false, config.copy(paginationRule = null), null, 0,
            "page 2 not proven for the form search: " + failures.joinToString("; "),
            System.currentTimeMillis() - started)
    }

    /**
     * Page 2 for a RENDER (JS route) provider: the template's own {page}, or a
     * template derived from the rendered page's next link — rendered live in the
     * WebView exactly as Load More will render it. The rule is the usual
     * param:/path: rule; the {page} template carries it.
     */
    private suspend fun proveRenderedPage2(
        provider: Provider,
        config: ScrapingConfig,
        probeQuery: String,
        firstPageUrls: Set<String>,
        firstHtml: String,
        firstUrl: String,
        hint: PaginationHint
    ): Page2Proof {
        val started = System.currentTimeMillis()
        val attempts = mutableListOf<ScrapingConfig>()
        if (config.searchUrlTemplate.contains("{page}")) attempts += config
        if (config.postData == null) {
            deriveTemplateFromNextLink(firstHtml, firstUrl, probeQuery)
                ?.takeIf { it != config.searchUrlTemplate }
                ?.let { attempts += config.copy(searchUrlTemplate = it) }
        }
        if (attempts.isEmpty()) {
            return Page2Proof(false, config.copy(paginationRule = null), null, 0,
                "no page-2 mechanism on the rendered results page (no {page} parameter and no next-page link carrying the query)",
                System.currentTimeMillis() - started)
        }
        val failures = mutableListOf<String>()
        for (candidate in attempts) {
            val url = buildRequest(provider, candidate, probeQuery, page = candidate.paginationStart + 1).url
            val html = renderPage(url)
            if (html == null) {
                failures += "page 2 ${url.take(140)} could not be rendered"
                continue
            }
            val (newItems, links) = newItemsOn(html, url, provider, candidate, hint, firstPageUrls)
            if (newItems >= 1) {
                val rule = derivePaginationRule(candidate)
                return Page2Proof(true, candidate.copy(paginationRule = rule), url, newItems,
                    "page 2 rendered live (${url.take(160)}): $newItems new item results; rule ${rule ?: "template {page}"}",
                    System.currentTimeMillis() - started)
            }
            failures += "page 2 ${url.take(140)} rendered with no new item results ($links links)"
        }
        return Page2Proof(false, config.copy(paginationRule = null), null, 0,
            failures.joinToString("; "), System.currentTimeMillis() - started)
    }

    /** Turn the live page's own next-page link into a {query}/{page} template. */
    private fun deriveTemplateFromNextLink(body: String, finalUrl: String, probeQuery: String): String? {
        val doc = runCatching { Jsoup.parse(body, finalUrl) }.getOrNull() ?: return null
        val encodedForms = listOf(
            URLEncoder.encode(probeQuery, "UTF-8"),
            URLEncoder.encode(probeQuery, "UTF-8").replace("+", "%20"),
            probeQuery.replace(" ", "-"),
            probeQuery
        ).distinct()
        val anchors = doc.select("a[rel=next][href], link[rel=next][href]") + doc.select("a[href]")
        for (anchor in anchors) {
            val href = anchor.absUrl("href")
            if (href.isBlank()) continue
            val pageMatch = NEXT_PARAM_REGEX.find(href)
            val pathMatch = NEXT_PATH_REGEX.find(href)
            if (pageMatch == null && pathMatch == null) continue
            val form = encodedForms.firstOrNull { href.contains(it, ignoreCase = true) } ?: continue
            var template = href.replace(form, "{query}", ignoreCase = true)
            template = if (pageMatch != null) {
                template.replace(NEXT_PARAM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}={page}${m.groupValues[4]}" }
            } else {
                template.replace(NEXT_PATH_REGEX) { m -> "${m.groupValues[1]}{page}${m.groupValues[2]}" }
            }
            if (template.contains("{query}") && template.contains("{page}")) return template
        }
        return null
    }

    /** "param:<name>" for a query/body parameter, "path:<path pattern>" for /page/N/ style. */
    private fun derivePaginationRule(config: ScrapingConfig): String? {
        PAGE_TOKEN_PARAM_REGEX.find(config.searchUrlTemplate)?.let { return "param:${it.groupValues[1]}" }
        config.postData?.let { body -> PAGE_TOKEN_BODY_REGEX.find(body)?.let { return "param:${it.groupValues[1]}" } }
        val path = config.searchUrlTemplate.substringBefore('?')
        if (path.contains("{page}")) return "path:" + path.substringAfter("://").substringAfter('/', "")
            .let { "/$it" }
        // A {page} token somewhere else in the template still drives page N.
        if (config.searchUrlTemplate.contains("{page}") || config.postData?.contains("{page}") == true) return "template:{page}"
        return null
    }

    /**
     * Stamp the verified config with everything search needs (spec §3): HTTP
     * method, real query parameter, hidden/fixed parameters, the demonstrated
     * pagination rule, learned classification data and the verification state.
     */
    private fun finalizeConfig(
        config: ScrapingConfig,
        pairs: List<Pair<String, String>>,
        page2: Page2Proof,
        confidence: Float
    ): ScrapingConfig {
        val isPost = config.postData != null
        val paramSource = if (isPost) config.postData.orEmpty() else config.searchUrlTemplate.substringAfter('?', "")
        val params = paramSource.split('&').mapNotNull { part ->
            val name = part.substringBefore('=').takeIf { it.isNotBlank() && part.contains('=') } ?: return@mapNotNull null
            runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name) to part.substringAfter('=')
        }
        val queryParam = params.firstOrNull { it.second.contains("{query}") }?.first
        val hidden = params.filter { !it.second.contains("{query}") && !it.second.contains("{page}") }
        val hiddenJson = kotlinx.serialization.json.buildJsonObject {
            hidden.forEach { (k, v) ->
                put(k, JsonPrimitive(runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)))
            }
        }.toString()
        val classification = ResultClassification.learn(pairs) { title, url -> isCategoryPair(title, url) }
        return config.copy(
            method = if (isPost) "POST" else "GET",
            queryParam = queryParam ?: config.queryParam,
            hiddenParams = hiddenJson,
            paginationRule = if (page2.proven) page2.config.paginationRule else null,
            classificationData = ResultClassification.encode(classification),
            verified = true,
            verifiedAt = System.currentTimeMillis(),
            confidence = confidence
        )
    }

    companion object {
        const val NONSENSE_QUERY = "zqxv123nonexistent"
        const val MIN_AUTOSAVE_CONFIDENCE = 0.7f
        private const val PROBE_TIMEOUT_MS = 8_000
        /** Slow sites get their measured (recon) timeout for probes, capped here. */
        private const val MAX_PROBE_TIMEOUT_MS = 20_000
        private const val RENDER_TIMEOUT_MS = 15_000
        private const val RAW_BUDGET_MS = 40_000L
        private const val RENDER_BUDGET_MS = 60_000L
        private const val MIN_ITEMS = 3
        private const val MAX_REPAIR_SELECTORS = 3
        private const val MAX_RENDERED_CANDIDATES = 2
        private const val FORM_PAGINATION_TIMEOUT_MS = 35_000

        /** Pagination rules for FORM providers, replayed by Load More (ScrapingEngine). */
        const val FORM_LINK_RAW_RULE = "form-link-raw:"
        const val FORM_LINK_RENDER_RULE = "form-link-render:"
        const val FORM_CLICK_RULE = "form-click:"

        private const val TITLE_FALLBACK_SELECTOR = "h1, h2, h3, h4, .title, .name, [class*='title'], a"
        private const val REPAIR_TITLE_SELECTOR = "a, h1, h2, h3, h4, .title, [class*='title']"
        private const val REPAIR_URL_SELECTOR = "a[href]"

        private val DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val MINUTES_REGEX = Regex("^\\d{1,3}\\s*(min|minutes?|hours?)$", RegexOption.IGNORE_CASE)
        private val PAGE_PARAM_REGEX = Regex("[?&](page|paged|p)=\\{page\\}", RegexOption.IGNORE_CASE)
        private val PAGE2_PARAM_REGEX = Regex("[?&](page|paged|p)=2(?:&|$)", RegexOption.IGNORE_CASE)
        private val NEXT_PARAM_REGEX = Regex("([?&])(page|paged|p|pg|pagenum|page_num)=(2)(&|$|#)", RegexOption.IGNORE_CASE)
        private val NEXT_PATH_REGEX = Regex("(/page/)2(/|$|\\?)", RegexOption.IGNORE_CASE)
        private val PAGE_TOKEN_PARAM_REGEX = Regex("[?&]([^=&?#]+)=\\{page\\}")
        private val PAGE_TOKEN_BODY_REGEX = Regex("(?:^|&)([^=&]+)=\\{page\\}")

        private val TITLE_KEYS = listOf("title", "name", "headline", "label")
        private val URL_KEYS = listOf("url", "link", "href", "permalink", "webpage_url", "canonicalUrl")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** A candidate config plus where it came from, for failure reporting. */
data class VerifierCandidate(
    val config: ScrapingConfig,
    val sourceLabel: String,
    /** True when this route is (or was derived from) the site's own
     *  search form — the rendered phase may then submit that form in
     *  the headless browser. */
    val isFormRoute: Boolean = false
)

/** What recon learned about the site's pagination, used to score (and
 *  repair) a candidate's pagination against live probe evidence. */
data class PaginationHint(
    val param: String? = null,
    val pathStyle: Boolean = false,
    val inventoryEvidence: Boolean = false
)

sealed class VerificationOutcome {
    data class Verified(
        val config: ScrapingConfig,
        val verdict: ProbeVerdict,
        val repaired: Boolean,
        /**
         * Why each candidate ranked ABOVE the winner lost, in rank order, so
         * the analyzer report can say what was tried and rejected rather than
         * only what succeeded.
         */
        val losingReasons: List<String> = emptyList()
    ) : VerificationOutcome()

    data class Rejected(
        val reasons: List<String>
    ) : VerificationOutcome()
}

/** The evidence from one candidate's live verification. */
data class ProbeVerdict(
    val sourceLabel: String,
    val probedUrl: String,
    val itemCount: Int,
    val categoryCount: Int,
    val nonsenseItemCount: Int,
    val responseMs: Long,
    val slowestProbeMs: Long,
    val passed: Boolean,
    val rendered: Boolean,
    val paginationProven: Boolean,
    val confidence: Float,
    val confidenceDetail: String,
    val reason: String,
    /** Discrete, repeatable checks (Nuclei-template style) with pass/fail and evidence. */
    val checks: List<VerificationCheck> = emptyList(),
    /** Per-field confidence: route, extraction, pagination, overall. */
    val fieldConfidence: Map<String, Float> = emptyMap(),
    /** The live page-2 URL fetched as pagination proof, if any. */
    val page2Url: String? = null,
    val page2NewItems: Int = 0,
    val paginationRule: String? = null
)

/** One discrete verification step and its recorded evidence. */
data class VerificationCheck(
    val id: String,
    val passed: Boolean,
    val evidence: String
)
