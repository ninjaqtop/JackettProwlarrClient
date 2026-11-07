package com.aggregatorx.app.engine.analyzer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-stage progress for a heavy analysis run, so the analyzer UI can observe a
 * 90–120 s pass to completion instead of racing it with a shorter timeout
 * (spec §2: "Long analyses must be observed to completion by the UI").
 *
 * The analyzer publishes a stage as it starts it and a one-line detail as it
 * finishes; the UI collects [state] and shows the current stage plus the trail
 * of finished ones. Nothing here affects the pipeline — it is pure reporting,
 * and a missing reporter is a no-op.
 */
object ReconProgress {

    /** One stage of the heavy recon pass. */
    enum class Stage(val label: String) {
        IDLE("Idle"),
        FETCH("Fetching the site"),
        FINGERPRINT("Fingerprinting the stack"),
        ROBOTS("Reading robots.txt and sitemaps"),
        CRAWL("Crawling the site's link graph"),
        SCRIPTS("Mining JavaScript for endpoints"),
        LIVE_CAPTURE("Capturing the site's own search in the browser"),
        INDEXER_DEFINITION("Looking up indexer definitions"),
        WORDLIST("Probing search routes"),
        PARAMS("Discovering query parameters"),
        ARCHIVE("Mining archived URLs"),
        TRIAGE("Triaging discovered endpoints"),
        RANK("Ranking candidates"),
        VERIFY("Verifying candidates live"),
        SAVE("Saving the proven config"),
        DONE("Finished")
    }

    data class StageRecord(
        val stage: Stage,
        val detail: String,
        val elapsedMs: Long
    )

    data class State(
        val providerId: String? = null,
        val running: Boolean = false,
        val stage: Stage = Stage.IDLE,
        val stageDetail: String = "",
        /** Finished stages with what each one found. */
        val completed: List<StageRecord> = emptyList(),
        val startedAt: Long = 0L,
        /** The whole run's budget, so the UI can show honest remaining time. */
        val budgetMs: Long = 0L
    ) {
        val elapsedMs: Long get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
        /** 0..1, by stage ordinal — monotonic, never a fake timer. */
        val fraction: Float
            get() = if (!running && stage == Stage.DONE) 1f
            else (stage.ordinal.toFloat() / (Stage.DONE.ordinal).toFloat()).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(providerId: String, budgetMs: Long) {
        _state.value = State(
            providerId = providerId,
            running = true,
            stage = Stage.FETCH,
            stageDetail = "",
            completed = emptyList(),
            startedAt = System.currentTimeMillis(),
            budgetMs = budgetMs
        )
    }

    /** Enter [stage]; the stage that was running is recorded as finished. */
    fun stage(stage: Stage, detail: String = "") {
        _state.update { current ->
            if (!current.running) return@update current
            val closed = if (current.stage != Stage.IDLE && current.stage != stage) {
                current.completed + StageRecord(current.stage, current.stageDetail, current.elapsedMs)
            } else current.completed
            current.copy(stage = stage, stageDetail = detail, completed = closed.takeLast(MAX_RECORDS))
        }
    }

    /** Replace the detail line of the stage that is running (no stage change). */
    fun detail(detail: String) {
        _state.update { if (it.running) it.copy(stageDetail = detail) else it }
    }

    fun finish(detail: String = "") {
        _state.update { current ->
            if (!current.running) return@update current
            current.copy(
                running = false,
                stage = Stage.DONE,
                stageDetail = detail,
                completed = (current.completed + StageRecord(current.stage, current.stageDetail, current.elapsedMs))
                    .takeLast(MAX_RECORDS)
            )
        }
    }

    fun reset() { _state.value = State() }

    private const val MAX_RECORDS = 24
}
