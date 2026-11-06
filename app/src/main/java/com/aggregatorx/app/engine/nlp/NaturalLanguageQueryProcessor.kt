package com.aggregatorx.app.engine.nlp

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Natural Language Query Processor (NLP Engine)
 *
 * Transforms free-form, descriptive natural language queries into
 * optimised search terms that match how content is actually titled
 * on websites, torrent trackers, streaming platforms, etc.
 *
 * Example:
 *   Input:  "scared my cat and it jumped so high"
 *   Output: ["cat jump scare", "scared cat jumping", "cat startled reaction",
 *            "cat gets spooked jumps", "funny cat freakout", "cat leaps scared"]
 *
 * Core Capabilities:
 * - Deep semantic concept extraction (subjects, actions, descriptors, emotions)
 * - Cause-effect relationship understanding
 * - Rich conceptual thesaurus with 1000+ mappings
 * - Intent classification (video, article, download, etc.)
 * - Conversational/slang language normalization
 * - Multi-strategy query generation (specific→broad)
 * - Concept-based result relevance scoring
 * - No external API dependencies — runs entirely on-device
 * - No content restrictions — pure linguistic framework
 */
@Singleton
class NaturalLanguageQueryProcessor @Inject constructor() {

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a raw user query into a [ProcessedQuery] containing extracted
     * semantic concepts and multiple optimised search query strings.
     */
    fun processQuery(rawInput: String): ProcessedQuery {
        val input = normalizeInput(rawInput)
        val tokens = tokenize(input)
        val tagged = tagPartsOfSpeech(tokens)
        val concepts = extractConcepts(tagged, input)
        val intent = classifyIntent(tokens, concepts)
        val searchQueries = generateSearchQueries(concepts, intent)
        val conceptTerms = buildConceptTermSet(concepts)

        return ProcessedQuery(
            originalQuery = rawInput,
            normalizedQuery = input,
            concepts = concepts,
            intent = intent,
            searchQueries = searchQueries,
            conceptTerms = conceptTerms,
            isNaturalLanguage = isNaturalLanguage(tokens)
        )
    }

    /**
     * Score how semantically relevant a result title/description is to the
     * extracted concepts. Returns 0..100.
     */
    fun calculateSemanticRelevance(
        title: String,
        description: String?,
        concepts: SemanticConcepts
    ): Float {
        val titleLower = title.lowercase()
        val descLower = description?.lowercase() ?: ""
        val combined = "$titleLower $descLower"
        var score = 0f

        // Subject matches (highest weight)
        concepts.subjects.forEach { subject ->
            val synonyms = getConceptSynonyms(subject) + subject
            synonyms.forEach { syn ->
                if (titleLower.contains(syn)) score += 25f
                else if (descLower.contains(syn)) score += 10f
            }
        }

        // Action matches
        concepts.actions.forEach { action ->
            val synonyms = getConceptSynonyms(action) + action
            synonyms.forEach { syn ->
                if (titleLower.contains(syn)) score += 20f
                else if (descLower.contains(syn)) score += 8f
            }
        }

        // Descriptor matches
        concepts.descriptors.forEach { desc ->
            val synonyms = getConceptSynonyms(desc) + desc
            synonyms.forEach { syn ->
                if (titleLower.contains(syn)) score += 12f
                else if (descLower.contains(syn)) score += 5f
            }
        }

        // Emotion/reaction matches
        concepts.emotions.forEach { emotion ->
            val synonyms = getConceptSynonyms(emotion) + emotion
            synonyms.forEach { syn ->
                if (combined.contains(syn)) score += 10f
            }
        }

        // Context matches
        concepts.contexts.forEach { ctx ->
            val synonyms = getConceptSynonyms(ctx) + ctx
            synonyms.forEach { syn ->
                if (combined.contains(syn)) score += 8f
            }
        }

        // Compound concept matches (e.g. "jump scare", "take off")
        concepts.compoundConcepts.forEach { compound ->
            if (titleLower.contains(compound)) score += 30f
            else if (descLower.contains(compound)) score += 15f
        }

        return score.coerceIn(0f, 100f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INPUT NORMALIZATION
    // ═══════════════════════════════════════════════════════════════════

    private fun normalizeInput(raw: String): String {
        var s = raw.trim().lowercase()

        // Expand contractions
        CONTRACTIONS.forEach { (contraction, expansion) ->
            s = s.replace(contraction, expansion)
        }

        // Normalize slang / informal language
        SLANG_MAP.forEach { (slang, normal) ->
            s = s.replace(Regex("\\b${Regex.escape(slang)}\\b"), normal)
        }

        // Remove excessive punctuation but keep structural punctuation
        s = s.replace(EXCESS_EXCLAMATION_REGEX, "!")
            .replace(EXCESS_QUESTION_REGEX, "?")
            .replace(EXCESS_PERIOD_REGEX, "...")

        return s
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TOKENIZATION
    // ═══════════════════════════════════════════════════════════════════

    private fun tokenize(input: String): List<String> {
        return input.split(TOKEN_SPLIT_REGEX)
            .map { it.trim('.', '"', '\'', '(', ')', '[', ']') }
            .filter { it.isNotEmpty() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PART-OF-SPEECH TAGGING (rule-based)
    // ═══════════════════════════════════════════════════════════════════

    private enum class POS {
        NOUN, VERB, ADJECTIVE, ADVERB, PRONOUN, PREPOSITION, CONJUNCTION,
        DETERMINER, INTERJECTION, UNKNOWN
    }

    private data class TaggedToken(val word: String, val pos: POS)

    private fun tagPartsOfSpeech(tokens: List<String>): List<TaggedToken> {
        return tokens.map { word ->
            val pos = when {
                word in PRONOUNS -> POS.PRONOUN
                word in DETERMINERS -> POS.DETERMINER
                word in PREPOSITIONS -> POS.PREPOSITION
                word in CONJUNCTIONS -> POS.CONJUNCTION
                word in INTERJECTIONS -> POS.INTERJECTION
                word in ADVERBS || word.endsWith("ly") -> POS.ADVERB
                word in VERB_SET || isVerbForm(word) -> POS.VERB
                word in ADJECTIVE_SET || isAdjectiveForm(word) -> POS.ADJECTIVE
                word in NOUN_SET || isNounLikely(word) -> POS.NOUN
                else -> POS.UNKNOWN
            }
            TaggedToken(word, pos)
        }
    }

    private fun isVerbForm(word: String): Boolean {
        // Common verb suffixes
        return word.endsWith("ing") || word.endsWith("ed") || word.endsWith("es") ||
               word.endsWith("ize") || word.endsWith("ise") || word.endsWith("ate") ||
               word.endsWith("ify") || VERB_BASE_FORMS.any { word.startsWith(it) }
    }

    private fun isAdjectiveForm(word: String): Boolean {
        return word.endsWith("ful") || word.endsWith("less") || word.endsWith("ous") ||
               word.endsWith("ive") || word.endsWith("able") || word.endsWith("ible") ||
               word.endsWith("al") || word.endsWith("ish") || word.endsWith("ical")
    }

    private fun isNounLikely(word: String): Boolean {
        // If not identified as anything else and is 3+ chars, likely a noun
        return word.length >= 3 && word !in STOP_WORDS
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEMANTIC CONCEPT EXTRACTION
    // ═══════════════════════════════════════════════════════════════════

    private fun extractConcepts(
        tagged: List<TaggedToken>,
        fullInput: String
    ): SemanticConcepts {
        val subjects = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val descriptors = mutableListOf<String>()
        val emotions = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        val compoundConcepts = mutableListOf<String>()
        val causeEffects = mutableListOf<CauseEffect>()

        // Extract subjects (nouns, especially concrete nouns)
        tagged.filter { it.pos == POS.NOUN || it.pos == POS.UNKNOWN }
            .filter { it.word !in STOP_WORDS && it.word.length > 2 }
            .forEach { token ->
                val lemma = lemmatize(token.word)
                subjects.add(lemma)

                // Check if this noun has known compound forms
                COMPOUND_NOUNS.forEach { (key, compounds) ->
                    if (lemma == key || token.word == key) {
                        compoundConcepts.addAll(compounds)
                    }
                }
            }

        // Extract actions (verbs)
        tagged.filter { it.pos == POS.VERB }
            .forEach { token ->
                val lemma = lemmatizeVerb(token.word)
                actions.add(lemma)
                // Also keep the original form for matching
                if (token.word != lemma) actions.add(token.word)
            }

        // Extract descriptors (adjectives, adverbs)
        tagged.filter { it.pos == POS.ADJECTIVE || it.pos == POS.ADVERB }
            .filter { it.word !in STOP_WORDS }
            .forEach { token ->
                descriptors.add(token.word)
            }

        // Detect emotions from the full input
        EMOTION_PATTERNS.forEach { (emotion, patterns) ->
            if (patterns.any { fullInput.contains(it) }) {
                emotions.add(emotion)
            }
        }

        // Detect context from the full input
        CONTEXT_PATTERNS.forEach { (context, patterns) ->
            if (patterns.any { fullInput.contains(it) }) {
                contexts.add(context)
            }
        }

        // Extract cause-effect relationships
        extractCauseEffects(tagged, fullInput).let { causeEffects.addAll(it) }

        // Detect multi-word concepts from the input
        MULTI_WORD_CONCEPTS.forEach { concept ->
            if (fullInput.contains(concept)) {
                compoundConcepts.add(concept)
            }
        }

        // Look for action-subject pairs in sequence
        for (i in 0 until tagged.size - 1) {
            val current = tagged[i]
            val next = tagged[i + 1]
            if (current.pos == POS.VERB && (next.pos == POS.NOUN || next.pos == POS.UNKNOWN)) {
                compoundConcepts.add("${lemmatizeVerb(current.word)} ${lemmatize(next.word)}")
            }
            if ((current.pos == POS.ADJECTIVE) && (next.pos == POS.NOUN || next.pos == POS.UNKNOWN)) {
                compoundConcepts.add("${current.word} ${lemmatize(next.word)}")
            }
        }

        return SemanticConcepts(
            subjects = subjects.distinct(),
            actions = actions.distinct(),
            descriptors = descriptors.distinct(),
            emotions = emotions.distinct(),
            contexts = contexts.distinct(),
            compoundConcepts = compoundConcepts.distinct(),
            causeEffects = causeEffects
        )
    }

    private fun extractCauseEffects(
        tagged: List<TaggedToken>,
        fullInput: String
    ): List<CauseEffect> {
        val results = mutableListOf<CauseEffect>()

        // Pattern: "X and Y" where X and Y are actions
        val andPattern = Regex("(\\w+)\\s+and\\s+(\\w+)")
        andPattern.findAll(fullInput).forEach { match ->
            val first = match.groupValues[1]
            val second = match.groupValues[2]
            if ((first in VERB_SET || isVerbForm(first)) &&
                (second in VERB_SET || isVerbForm(second))
            ) {
                results.add(CauseEffect(
                    cause = lemmatizeVerb(first),
                    effect = lemmatizeVerb(second),
                    relationship = "sequence"
                ))
            }
        }

        // Pattern: "X so Y" / "X then Y" / "X and it Y"
        val causePatterns = listOf(
            Regex("(\\w+)\\s+so\\s+(\\w+)"),
            Regex("(\\w+)\\s+then\\s+(\\w+)"),
            Regex("(\\w+)\\s+and\\s+(?:it|they|he|she)\\s+(\\w+)")
        )

        causePatterns.forEach { pattern ->
            pattern.findAll(fullInput).forEach { match ->
                results.add(CauseEffect(
                    cause = lemmatizeVerb(match.groupValues[1]),
                    effect = lemmatizeVerb(match.groupValues[2]),
                    relationship = "cause-effect"
                ))
            }
        }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INTENT CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════

    private fun classifyIntent(tokens: List<String>, concepts: SemanticConcepts): QueryIntent {
        val allWords = tokens.joinToString(" ")

        // Check for explicit intent words
        val intentScores = mutableMapOf<QueryIntent, Float>()

        VIDEO_INTENT_WORDS.forEach { word ->
            if (allWords.contains(word)) {
                intentScores[QueryIntent.VIDEO] = (intentScores[QueryIntent.VIDEO] ?: 0f) + 1f
            }
        }
        DOWNLOAD_INTENT_WORDS.forEach { word ->
            if (allWords.contains(word)) {
                intentScores[QueryIntent.DOWNLOAD] = (intentScores[QueryIntent.DOWNLOAD] ?: 0f) + 1f
            }
        }
        ARTICLE_INTENT_WORDS.forEach { word ->
            if (allWords.contains(word)) {
                intentScores[QueryIntent.ARTICLE] = (intentScores[QueryIntent.ARTICLE] ?: 0f) + 1f
            }
        }
        IMAGE_INTENT_WORDS.forEach { word ->
            if (allWords.contains(word)) {
                intentScores[QueryIntent.IMAGE] = (intentScores[QueryIntent.IMAGE] ?: 0f) + 1f
            }
        }

        // If the query describes a scene or action, it's likely looking for video
        if (concepts.actions.size >= 2 || concepts.causeEffects.isNotEmpty()) {
            intentScores[QueryIntent.VIDEO] = (intentScores[QueryIntent.VIDEO] ?: 0f) + 2f
        }

        // If emotions are involved, likely video/entertainment
        if (concepts.emotions.isNotEmpty()) {
            intentScores[QueryIntent.VIDEO] = (intentScores[QueryIntent.VIDEO] ?: 0f) + 1f
        }

        return intentScores.maxByOrNull { it.value }?.key ?: QueryIntent.GENERAL
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH QUERY GENERATION
    // ═══════════════════════════════════════════════════════════════════

    private fun generateSearchQueries(
        concepts: SemanticConcepts,
        intent: QueryIntent
    ): List<String> {
        val queries = mutableListOf<String>()

        // Strategy 1: Core concept combination (subject + action)
        generateCoreConceptQueries(concepts).let { queries.addAll(it) }

        // Strategy 2: Synonym-expanded queries
        generateSynonymQueries(concepts).let { queries.addAll(it) }

        // Strategy 3: Compound concept queries
        generateCompoundQueries(concepts).let { queries.addAll(it) }

        // Strategy 4: Emotion/reaction-based queries
        generateEmotionQueries(concepts).let { queries.addAll(it) }

        // Strategy 5: Cause-effect narrative queries
        generateCauseEffectQueries(concepts).let { queries.addAll(it) }

        // Strategy 6: Intent-specific format queries
        generateIntentQueries(concepts, intent).let { queries.addAll(it) }

        // Strategy 7: Broad fallback queries (individual key concepts)
        generateBroadQueries(concepts).let { queries.addAll(it) }

        // Deduplicate, clean, and rank
        return queries
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length >= 3 }
            .distinct()
            .take(MAX_SEARCH_QUERIES)
    }

    private fun generateCoreConceptQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()
        val subjects = concepts.subjects.take(3)
        val actions = concepts.actions.take(3)
        val descriptors = concepts.descriptors.take(2)

        // Subject + Action combinations
        subjects.forEach { subject ->
            actions.forEach { action ->
                queries.add("$subject $action")
                // With descriptors
                descriptors.forEach { desc ->
                    queries.add("$subject $action $desc")
                }
            }
        }

        // Action + Subject (reversed for different matching)
        actions.forEach { action ->
            subjects.forEach { subject ->
                queries.add("$action $subject")
            }
        }

        // All subjects together with primary action
        if (subjects.size > 1 && actions.isNotEmpty()) {
            queries.add("${subjects.joinToString(" ")} ${actions.first()}")
        }

        return queries
    }

    private fun generateSynonymQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()
        val subjects = concepts.subjects.take(2)
        val actions = concepts.actions.take(2)

        subjects.forEach { subject ->
            val subjectSyns = getConceptSynonyms(subject).take(3)
            actions.forEach { action ->
                val actionSyns = getConceptSynonyms(action).take(3)

                // Original subject + synonym action
                actionSyns.forEach { actionSyn ->
                    queries.add("$subject $actionSyn")
                }

                // Synonym subject + original action
                subjectSyns.forEach { subjectSyn ->
                    queries.add("$subjectSyn $action")
                }

                // Synonym subject + synonym action (top only)
                if (subjectSyns.isNotEmpty() && actionSyns.isNotEmpty()) {
                    queries.add("${subjectSyns.first()} ${actionSyns.first()}")
                }
            }
        }

        return queries
    }

    private fun generateCompoundQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()

        // Direct compound concepts
        concepts.compoundConcepts.forEach { compound ->
            queries.add(compound)
        }

        // Subject + compound concepts
        concepts.subjects.take(2).forEach { subject ->
            concepts.compoundConcepts.forEach { compound ->
                if (!compound.contains(subject)) {
                    queries.add("$subject $compound")
                }
            }
        }

        return queries
    }

    private fun generateEmotionQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()

        concepts.emotions.forEach { emotion ->
            val emotionTerms = EMOTION_SEARCH_TERMS[emotion] ?: listOf(emotion)
            concepts.subjects.take(2).forEach { subject ->
                emotionTerms.take(3).forEach { term ->
                    queries.add("$subject $term")
                    queries.add("$term $subject")
                }
            }
        }

        return queries
    }

    private fun generateCauseEffectQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()

        concepts.causeEffects.forEach { ce ->
            concepts.subjects.take(2).forEach { subject ->
                queries.add("$subject ${ce.cause} ${ce.effect}")
                queries.add("$subject gets ${ce.cause} and ${ce.effect}")
                // Common video title patterns
                queries.add("${ce.cause} $subject ${ce.effect}")
            }
        }

        return queries
    }

    private fun generateIntentQueries(
        concepts: SemanticConcepts,
        intent: QueryIntent
    ): List<String> {
        val queries = mutableListOf<String>()
        val primarySubject = concepts.subjects.firstOrNull() ?: return queries
        val primaryAction = concepts.actions.firstOrNull()

        when (intent) {
            QueryIntent.VIDEO -> {
                val base = if (primaryAction != null) "$primarySubject $primaryAction" else primarySubject
                queries.add("$base compilation")
                queries.add("$base funny")
                queries.add("best $base")
                queries.add("$base moments")
                queries.add("$base reaction")
                queries.add("$base video")
            }
            QueryIntent.DOWNLOAD -> {
                val base = if (primaryAction != null) "$primarySubject $primaryAction" else primarySubject
                queries.add("$base download")
                queries.add("$base torrent")
                queries.add("$base free")
            }
            QueryIntent.ARTICLE -> {
                val base = if (primaryAction != null) "$primarySubject $primaryAction" else primarySubject
                queries.add("$base explained")
                queries.add("$base guide")
                queries.add("how to $base")
            }
            QueryIntent.IMAGE -> {
                val base = if (primaryAction != null) "$primarySubject $primaryAction" else primarySubject
                queries.add("$base pictures")
                queries.add("$base photos")
                queries.add("$base images")
            }
            QueryIntent.GENERAL -> {
                // No special formatting needed
            }
        }

        return queries
    }

    private fun generateBroadQueries(concepts: SemanticConcepts): List<String> {
        val queries = mutableListOf<String>()

        // Individual subjects
        concepts.subjects.forEach { queries.add(it) }

        // Subject pairs
        if (concepts.subjects.size >= 2) {
            queries.add(concepts.subjects.take(2).joinToString(" "))
        }

        // Individual prominent actions with subjects
        val primarySubject = concepts.subjects.firstOrNull()
        if (primarySubject != null) {
            concepts.actions.take(2).forEach { action ->
                queries.add("$primarySubject $action")
            }
        }

        return queries
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITY: NATURAL LANGUAGE DETECTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Heuristic: is this a natural language description or already a keyword query?
     * Natural language has: stop words, pronouns, verbs, longer sentences
     */
    private fun isNaturalLanguage(tokens: List<String>): Boolean {
        if (tokens.size <= 2) return false

        val stopWordCount = tokens.count { it in STOP_WORDS }
        val pronounCount = tokens.count { it in PRONOUNS }
        val verbCount = tokens.count { it in VERB_SET || isVerbForm(it) }

        // If >25% are stop words or has pronouns+verbs, it's natural language
        val ratio = stopWordCount.toFloat() / tokens.size
        return ratio > 0.25f || (pronounCount > 0 && verbCount > 0) || tokens.size >= 6
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LEMMATIZATION (simplified rule-based)
    // ═══════════════════════════════════════════════════════════════════

    private fun lemmatize(word: String): String {
        // Check irregular forms first
        IRREGULAR_NOUNS[word]?.let { return it }

        return when {
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("ves") && word.length > 4 -> word.dropLast(3) + "f"
            word.endsWith("ses") && word.length > 4 -> word.dropLast(2)
            word.endsWith("xes") && word.length > 4 -> word.dropLast(2)
            word.endsWith("ches") && word.length > 5 -> word.dropLast(2)
            word.endsWith("shes") && word.length > 5 -> word.dropLast(2)
            word.endsWith("s") && !word.endsWith("ss") && word.length > 3 -> word.dropLast(1)
            else -> word
        }
    }

    private fun lemmatizeVerb(word: String): String {
        // Check irregular verbs first
        IRREGULAR_VERBS[word]?.let { return it }

        return when {
            word.endsWith("ying") -> word.dropLast(4) + "y"
            word.endsWith("ying") -> word.dropLast(4) + "y"
            word.endsWith("pping") -> word.dropLast(4) + "p"
            word.endsWith("tting") -> word.dropLast(4) + "t"
            word.endsWith("nning") -> word.dropLast(4) + "n"
            word.endsWith("mming") -> word.dropLast(4) + "m"
            word.endsWith("gging") -> word.dropLast(4) + "g"
            word.endsWith("dding") -> word.dropLast(4) + "d"
            word.endsWith("bing") -> word.dropLast(4) + "b"
            word.endsWith("ting") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("ving") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("king") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("cing") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("ging") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("zing") && word.length > 5 -> word.dropLast(3) + "e"
            word.endsWith("ing") && word.length > 4 -> word.dropLast(3)
            word.endsWith("ied") -> word.dropLast(3) + "y"
            word.endsWith("pped") -> word.dropLast(3) + ""
            word.endsWith("tted") -> word.dropLast(3) + ""
            word.endsWith("nned") -> word.dropLast(3) + ""
            word.endsWith("mmed") -> word.dropLast(3) + ""
            word.endsWith("gged") -> word.dropLast(3) + ""
            word.endsWith("dded") -> word.dropLast(3) + ""
            word.endsWith("bbed") -> word.dropLast(3) + ""
            word.endsWith("ated") -> word.dropLast(1)
            word.endsWith("ized") -> word.dropLast(1)
            word.endsWith("ised") -> word.dropLast(1)
            word.endsWith("ted") && word.length > 4 -> word.dropLast(2) + "e"
            word.endsWith("ked") && word.length > 4 -> word.dropLast(2) + "e"
            word.endsWith("ced") && word.length > 4 -> word.dropLast(2) + "e"
            word.endsWith("ved") && word.length > 4 -> word.dropLast(2) + "e"
            word.endsWith("zed") && word.length > 4 -> word.dropLast(2) + "e"
            word.endsWith("ed") && word.length > 3 -> word.dropLast(2)
            word.endsWith("es") && word.length > 3 -> word.dropLast(2)
            word.endsWith("s") && !word.endsWith("ss") && word.length > 3 -> word.dropLast(1)
            else -> word
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONCEPT SYNONYM LOOKUP
    // ═══════════════════════════════════════════════════════════════════

    fun getConceptSynonyms(word: String): List<String> {
        val lemma = lemmatize(word)
        val verbLemma = lemmatizeVerb(word)
        val results = mutableSetOf<String>()

        // Direct lookup
        CONCEPT_THESAURUS[word]?.let { results.addAll(it) }
        CONCEPT_THESAURUS[lemma]?.let { results.addAll(it) }
        CONCEPT_THESAURUS[verbLemma]?.let { results.addAll(it) }

        // Reverse lookup (find entries that contain this word)
        CONCEPT_THESAURUS.forEach { (key, values) ->
            if (word in values || lemma in values || verbLemma in values) {
                results.add(key)
                results.addAll(values)
            }
        }

        results.remove(word)
        results.remove(lemma)
        return results.toList()
    }

    /**
     * Build a set of all concept-related terms for broad matching
     */
    private fun buildConceptTermSet(concepts: SemanticConcepts): Set<String> {
        val terms = mutableSetOf<String>()

        concepts.subjects.forEach { s ->
            terms.add(s)
            terms.addAll(getConceptSynonyms(s))
        }
        concepts.actions.forEach { a ->
            terms.add(a)
            terms.addAll(getConceptSynonyms(a))
        }
        concepts.descriptors.forEach { d ->
            terms.add(d)
            terms.addAll(getConceptSynonyms(d))
        }
        concepts.emotions.forEach { e ->
            terms.add(e)
            EMOTION_SEARCH_TERMS[e]?.let { terms.addAll(it) }
        }
        concepts.compoundConcepts.forEach { terms.add(it) }

        return terms
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VOCABULARY & DICTIONARIES
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        private const val MAX_SEARCH_QUERIES = 20

        // Pre-compiled normalization/tokenization patterns (were rebuilt per call;
        // Regex instances are immutable and only read, so sharing is equivalent).
        private val EXCESS_EXCLAMATION_REGEX = Regex("[!]{2,}")
        private val EXCESS_QUESTION_REGEX = Regex("[?]{2,}")
        private val EXCESS_PERIOD_REGEX = Regex("[.]{3,}")
        private val TOKEN_SPLIT_REGEX = Regex("[\\s,;:!?]+")

        // ── Stop words ──────────────────────────────────────────────
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "and",
            "or", "but", "is", "are", "was", "were", "be", "been", "has",
            "have", "had", "do", "does", "did", "with", "this", "that",
            "it", "its", "by", "from", "up", "out", "as", "into", "than",
            "so", "too", "very", "just", "really", "about", "over", "then",
            "there", "here", "when", "where", "what", "which", "who", "how",
            "all", "each", "every", "both", "few", "more", "most", "some",
            "any", "no", "not", "only", "own", "same", "such", "can", "will",
            "would", "could", "should", "may", "might", "must", "shall",
            "need", "dare", "am", "like", "also", "even", "still", "already",
            "yet", "ever", "never", "always", "often", "sometimes", "my",
            "your", "his", "her", "our", "their"
        )

        // ── Pronouns ────────────────────────────────────────────────
        private val PRONOUNS = setOf(
            "i", "me", "my", "mine", "myself", "you", "your", "yours",
            "yourself", "he", "him", "his", "himself", "she", "her",
            "hers", "herself", "it", "its", "itself", "we", "us", "our",
            "ours", "ourselves", "they", "them", "their", "theirs",
            "themselves", "who", "whom", "whose", "which", "that",
            "someone", "something", "everyone", "everything", "anyone",
            "anything", "nobody", "nothing"
        )

        // ── Determiners ─────────────────────────────────────────────
        private val DETERMINERS = setOf(
            "the", "a", "an", "this", "that", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            "some", "any", "no", "every", "each", "all", "both",
            "few", "several", "many", "much", "more", "most"
        )

        // ── Prepositions ────────────────────────────────────────────
        private val PREPOSITIONS = setOf(
            "in", "on", "at", "to", "for", "with", "from", "by",
            "about", "into", "through", "during", "before", "after",
            "above", "below", "between", "under", "over", "up",
            "down", "out", "off", "away", "around", "along",
            "across", "behind", "beyond", "near", "toward", "upon"
        )

        // ── Conjunctions ────────────────────────────────────────────
        private val CONJUNCTIONS = setOf(
            "and", "but", "or", "nor", "for", "yet", "so",
            "because", "although", "while", "if", "when", "then",
            "than", "whether", "unless", "until", "since", "though"
        )

        // ── Interjections ───────────────────────────────────────────
        private val INTERJECTIONS = setOf(
            "oh", "wow", "ooh", "ahh", "oops", "yikes", "omg",
            "whoa", "damn", "dang", "geez", "haha", "lol", "lmao",
            "bruh", "bro", "yo", "hey", "ugh", "meh", "hmm"
        )

        // ── Adverbs ─────────────────────────────────────────────────
        private val ADVERBS = setOf(
            "very", "really", "extremely", "incredibly", "absolutely",
            "totally", "completely", "quite", "rather", "somewhat",
            "barely", "hardly", "nearly", "almost", "just", "already",
            "still", "always", "never", "often", "sometimes", "usually",
            "quickly", "slowly", "fast", "hard", "well", "badly",
            "easily", "seriously", "literally", "basically", "actually",
            "probably", "definitely", "certainly", "suddenly", "finally",
            "immediately", "especially", "particularly", "exactly",
            "simply", "merely", "enough", "too", "so", "super",
            "insanely", "ridiculously", "hilariously", "freaking"
        )

        // ── Common verbs ────────────────────────────────────────────
        private val VERB_SET = setOf(
            "go", "went", "gone", "going", "come", "came", "coming",
            "see", "saw", "seen", "seeing", "look", "looked", "looking",
            "get", "got", "gotten", "getting", "make", "made", "making",
            "take", "took", "taken", "taking", "give", "gave", "given",
            "find", "found", "finding", "know", "knew", "known", "knowing",
            "think", "thought", "thinking", "say", "said", "saying",
            "tell", "told", "telling", "show", "showed", "shown", "showing",
            "try", "tried", "trying", "leave", "left", "leaving",
            "call", "called", "calling", "keep", "kept", "keeping",
            "let", "run", "ran", "running", "move", "moved", "moving",
            "play", "played", "playing", "turn", "turned", "turning",
            "start", "started", "starting", "stop", "stopped", "stopping",
            "open", "opened", "opening", "close", "closed", "closing",
            "walk", "walked", "walking", "watch", "watched", "watching",
            "follow", "followed", "following", "search", "searched",
            "jump", "jumped", "jumping", "fall", "fell", "fallen", "falling",
            "fly", "flew", "flying", "climb", "climbed", "climbing",
            "run", "ran", "running", "swim", "swam", "swimming",
            "fight", "fought", "fighting", "hit", "hitting",
            "throw", "threw", "thrown", "throwing",
            "catch", "caught", "catching", "pull", "pulled", "pulling",
            "push", "pushed", "pushing", "kick", "kicked", "kicking",
            "break", "broke", "broken", "breaking",
            "crash", "crashed", "crashing", "smash", "smashed",
            "scare", "scared", "scaring", "frighten", "frightened",
            "startle", "startled", "startling", "surprise", "surprised",
            "shock", "shocked", "shocking", "spook", "spooked", "spooking",
            "freak", "freaked", "freaking", "panic", "panicked", "panicking",
            "scream", "screamed", "screaming", "yell", "yelled", "yelling",
            "laugh", "laughed", "laughing", "cry", "cried", "crying",
            "react", "reacted", "reacting", "respond", "responded",
            "attack", "attacked", "attacking", "chase", "chased", "chasing",
            "escape", "escaped", "escaping", "hide", "hid", "hiding",
            "sneak", "sneaked", "sneaking", "creep", "crept", "creeping",
            "slip", "slipped", "slipping", "slide", "slid", "sliding",
            "bounce", "bounced", "bouncing", "launch", "launched",
            "explode", "exploded", "exploding", "blast", "blasted",
            "eat", "ate", "eating", "drink", "drank", "drinking",
            "sleep", "slept", "sleeping", "wake", "woke", "waking",
            "climb", "climbed", "climbing", "roll", "rolled", "rolling",
            "flip", "flipped", "flipping", "spin", "spun", "spinning",
            "land", "landed", "landing", "drop", "dropped", "dropping",
            "grab", "grabbed", "grabbing", "hold", "held", "holding",
            "bite", "bit", "bitten", "biting",
            "scratch", "scratched", "scratching",
            "knock", "knocked", "knocking",
            "bump", "bumped", "bumping",
            "trip", "tripped", "tripping",
            "stumble", "stumbled", "stumbling",
            "zoom", "zoomed", "zooming",
            "dash", "dashed", "dashing",
            "sprint", "sprinted", "sprinting",
            "leap", "leaped", "leaping", "leapt",
            "soar", "soared", "soaring",
            "dive", "dived", "diving", "dove",
            "pounce", "pounced", "pouncing",
            "lunge", "lunged", "lunging",
            "bolt", "bolted", "bolting",
            "dart", "darted", "darting",
            "hurdle", "hurdled", "hurdling",
            "vault", "vaulted", "vaulting",
            "catapult", "catapulted", "catapulting",
            "propel", "propelled", "propelling",
            "rocket", "rocketed", "rocketing",
            "hurl", "hurled", "hurling",
            "fling", "flung", "flinging",
            "toss", "tossed", "tossing",
            "slam", "slammed", "slamming",
            "crash", "crashed", "crashing",
            "collide", "collided", "colliding",
            "tumble", "tumbled", "tumbling",
            "topple", "toppled", "toppling",
            "wobble", "wobbled", "wobbling",
            "stagger", "staggered", "staggering",
            "cling", "clung", "clinging",
            "hang", "hung", "hanging",
            "swing", "swung", "swinging",
            "twirl", "twirled", "twirling",
            "whirl", "whirled", "whirling",
            "dodge", "dodged", "dodging",
            "duck", "ducked", "ducking",
            "crouch", "crouched", "crouching",
            "prowl", "prowled", "prowling",
            "stalk", "stalked", "stalking",
            "lurk", "lurked", "lurking",
            "ambush", "ambushed", "ambushing",
            "prank", "pranked", "pranking",
            "troll", "trolled", "trolling",
            "hack", "hacked", "hacking",
            "crack", "cracked", "cracking",
            "scan", "scanned", "scanning",
            "probe", "probed", "probing",
            "infiltrate", "infiltrated", "infiltrating",
            "penetrate", "penetrated", "penetrating",
            "exploit", "exploited", "exploiting",
            "bypass", "bypassed", "bypassing",
            "enumerate", "enumerated", "enumerating",
            "sniff", "sniffed", "sniffing",
            "intercept", "intercepted", "intercepting",
            "decrypt", "decrypted", "decrypting",
            "encrypt", "encrypted", "encrypting",
            "inject", "injected", "injecting",
            "spoof", "spoofed", "spoofing",
            "phish", "phished", "phishing",
            "brute", "bruteforce", "bruteforced",
            "dump", "dumped", "dumping",
            "pivot", "pivoted", "pivoting"
        )

        // ── Verb base forms for startsWith matching ─────────────────
        private val VERB_BASE_FORMS = setOf(
            "jump", "scar", "freak", "run", "mov", "play", "watch",
            "search", "fight", "catch", "throw", "break", "crash",
            "laugh", "scream", "react", "attack", "chas", "escap",
            "climb", "flip", "spin", "bounc", "launch", "explod",
            "hack", "crack", "scan", "exploit", "bypass", "inject",
            "intercept", "decrypt", "encrypt", "spoof", "phish",
            "infiltrat", "penetrat", "enumerat", "brut"
        )

        // ── Common adjectives ───────────────────────────────────────
        private val ADJECTIVE_SET = setOf(
            "big", "small", "little", "large", "huge", "tiny", "massive",
            "great", "good", "bad", "best", "worst", "better", "worse",
            "new", "old", "young", "ancient", "modern", "recent",
            "fast", "slow", "quick", "rapid", "instant", "sudden",
            "high", "low", "tall", "short", "long", "deep", "wide",
            "hot", "cold", "warm", "cool", "frozen", "burning",
            "hard", "soft", "rough", "smooth", "sharp", "flat",
            "dark", "light", "bright", "dim", "clear", "shiny",
            "loud", "quiet", "silent", "noisy", "calm", "peaceful",
            "happy", "sad", "angry", "scared", "afraid", "nervous",
            "brave", "strong", "weak", "powerful", "gentle", "tough",
            "funny", "hilarious", "silly", "crazy", "insane", "wild",
            "cute", "beautiful", "ugly", "pretty", "handsome",
            "smart", "dumb", "stupid", "clever", "wise", "brilliant",
            "strange", "weird", "odd", "normal", "typical", "unusual",
            "amazing", "awesome", "incredible", "unbelievable", "epic",
            "terrible", "horrible", "awful", "fantastic", "wonderful",
            "dangerous", "safe", "risky", "secure", "vulnerable",
            "hidden", "secret", "visible", "invisible", "exposed",
            "real", "fake", "genuine", "false", "true", "authentic"
        )

        // ── Common nouns ────────────────────────────────────────────
        private val NOUN_SET = setOf(
            "cat", "cats", "dog", "dogs", "animal", "animals", "pet", "pets",
            "kitten", "kittens", "kitty", "puppy", "puppies",
            "bird", "birds", "fish", "fishes", "snake", "snakes",
            "horse", "horses", "bear", "bears", "lion", "lions",
            "tiger", "tigers", "wolf", "wolves", "fox", "foxes",
            "rabbit", "rabbits", "mouse", "mice", "rat", "rats",
            "monkey", "monkeys", "elephant", "elephants",
            "person", "people", "man", "men", "woman", "women",
            "child", "children", "kid", "kids", "baby", "babies",
            "boy", "boys", "girl", "girls", "guy", "guys",
            "friend", "friends", "family", "group", "team", "crew",
            "car", "cars", "truck", "bike", "motorcycle", "vehicle",
            "house", "home", "room", "door", "window", "floor",
            "wall", "roof", "stairs", "kitchen", "bathroom", "bedroom",
            "water", "fire", "air", "earth", "ice", "snow", "rain",
            "tree", "trees", "flower", "plant", "grass", "forest",
            "mountain", "hill", "river", "lake", "ocean", "sea",
            "road", "street", "bridge", "path", "trail",
            "food", "drink", "meal", "snack", "fruit", "meat",
            "game", "games", "sport", "sports", "match", "race",
            "music", "song", "sound", "noise", "voice",
            "movie", "film", "show", "video", "clip", "scene",
            "picture", "photo", "image", "camera", "phone",
            "computer", "laptop", "screen", "monitor", "keyboard",
            "book", "story", "news", "article", "post", "blog",
            "tool", "weapon", "gun", "knife", "sword",
            "money", "price", "cost", "value", "deal",
            "time", "day", "night", "morning", "evening",
            "world", "country", "city", "town", "place",
            "server", "network", "system", "database", "firewall",
            "password", "credential", "token", "key", "certificate",
            "vulnerability", "exploit", "payload", "backdoor",
            "malware", "virus", "trojan", "worm", "ransomware",
            "packet", "port", "protocol", "proxy", "vpn",
            "target", "scope", "domain", "endpoint", "api",
            "shell", "terminal", "console", "script", "code",
            "pentest", "audit", "assessment", "recon", "reconnaissance"
        )

        // ── Irregular nouns (plural → singular) ─────────────────────
        private val IRREGULAR_NOUNS = mapOf(
            "mice" to "mouse", "men" to "man", "women" to "woman",
            "children" to "child", "teeth" to "tooth", "feet" to "foot",
            "geese" to "goose", "oxen" to "ox", "people" to "person",
            "wolves" to "wolf", "knives" to "knife", "lives" to "life",
            "leaves" to "leaf", "halves" to "half", "selves" to "self",
            "calves" to "calf", "loaves" to "loaf", "thieves" to "thief"
        )

        // ── Irregular verbs ─────────────────────────────────────────
        private val IRREGULAR_VERBS = mapOf(
            "went" to "go", "gone" to "go", "came" to "come",
            "saw" to "see", "seen" to "see", "got" to "get",
            "gotten" to "get", "made" to "make", "took" to "take",
            "taken" to "take", "gave" to "give", "given" to "give",
            "found" to "find", "knew" to "know", "known" to "know",
            "thought" to "think", "said" to "say", "told" to "tell",
            "showed" to "show", "shown" to "show", "tried" to "try",
            "left" to "leave", "kept" to "keep", "ran" to "run",
            "fell" to "fall", "fallen" to "fall", "flew" to "fly",
            "swam" to "swim", "fought" to "fight", "threw" to "throw",
            "thrown" to "throw", "caught" to "catch", "broke" to "break",
            "broken" to "break", "bit" to "bite", "bitten" to "bite",
            "held" to "hold", "hung" to "hang", "slid" to "slide",
            "slept" to "sleep", "woke" to "wake", "ate" to "eat",
            "drank" to "drink", "drove" to "drive", "rode" to "ride",
            "wrote" to "write", "sang" to "sing", "spoke" to "speak",
            "chose" to "choose", "wore" to "wear", "drew" to "draw",
            "grew" to "grow", "blew" to "blow", "hid" to "hide",
            "crept" to "creep", "leapt" to "leap", "dove" to "dive",
            "swung" to "swing", "spun" to "spin", "flung" to "fling",
            "clung" to "cling", "crouched" to "crouch"
        )

        // ── Contractions ────────────────────────────────────────────
        private val CONTRACTIONS = mapOf(
            "i'm" to "i am", "i've" to "i have", "i'll" to "i will",
            "i'd" to "i would", "you're" to "you are", "you've" to "you have",
            "you'll" to "you will", "you'd" to "you would",
            "he's" to "he is", "she's" to "she is", "it's" to "it is",
            "we're" to "we are", "we've" to "we have", "we'll" to "we will",
            "they're" to "they are", "they've" to "they have",
            "they'll" to "they will", "they'd" to "they would",
            "that's" to "that is", "who's" to "who is",
            "what's" to "what is", "where's" to "where is",
            "how's" to "how is", "there's" to "there is",
            "here's" to "here is", "let's" to "let us",
            "can't" to "cannot", "won't" to "will not",
            "don't" to "do not", "doesn't" to "does not",
            "didn't" to "did not", "isn't" to "is not",
            "aren't" to "are not", "wasn't" to "was not",
            "weren't" to "were not", "hasn't" to "has not",
            "haven't" to "have not", "hadn't" to "had not",
            "wouldn't" to "would not", "couldn't" to "could not",
            "shouldn't" to "should not", "mustn't" to "must not",
            "needn't" to "need not", "ain't" to "is not",
            "gonna" to "going to", "wanna" to "want to",
            "gotta" to "got to", "kinda" to "kind of",
            "sorta" to "sort of", "coulda" to "could have",
            "woulda" to "would have", "shoulda" to "should have"
        )

        // ── Slang / informal normalization ──────────────────────────
        private val SLANG_MAP = mapOf(
            "lol" to "funny",
            "lmao" to "very funny",
            "rofl" to "hilarious",
            "omg" to "amazing",
            "af" to "extremely",
            "ngl" to "",
            "tbh" to "",
            "imo" to "",
            "imho" to "",
            "fyi" to "",
            "btw" to "",
            "smh" to "disappointed",
            "fr" to "for real",
            "lowkey" to "",
            "highkey" to "very",
            "deadass" to "seriously",
            "sus" to "suspicious",
            "cap" to "lie",
            "nocap" to "truth",
            "no cap" to "truth",
            "bruh" to "",
            "fam" to "",
            "yeet" to "throw",
            "vibe" to "feeling",
            "vibes" to "feeling",
            "slay" to "amazing",
            "fire" to "great",
            "lit" to "exciting",
            "goat" to "greatest",
            "bussin" to "great",
            "bet" to "agree",
            "noob" to "beginner",
            "rekt" to "destroyed",
            "pwned" to "owned",
            "gg" to "good game"
        )

        // ── Compound noun knowledge ─────────────────────────────────
        private val COMPOUND_NOUNS = mapOf(
            "cat" to listOf("jump scare", "funny cat", "cat reaction", "cat fail", "scared cat", "startled cat"),
            "dog" to listOf("funny dog", "dog reaction", "dog fail", "good boy", "doggo"),
            "car" to listOf("car crash", "car chase", "car accident", "road rage", "car fail"),
            "game" to listOf("gameplay", "game over", "speed run", "let's play", "walkthrough"),
            "hack" to listOf("hacking tutorial", "security exploit", "penetration test", "bug bounty"),
            "phone" to listOf("phone drop", "screen crack", "phone prank"),
            "baby" to listOf("baby laugh", "baby reaction", "cute baby", "funny baby"),
            "food" to listOf("food review", "taste test", "cooking fail", "recipe"),
            "music" to listOf("music video", "live performance", "cover song", "remix"),
            "sport" to listOf("highlight reel", "best plays", "epic moments", "championship"),
            "server" to listOf("server exploit", "server misconfiguration", "server hardening"),
            "password" to listOf("password crack", "password spray", "credential stuffing"),
            "network" to listOf("network scan", "network recon", "network penetration")
        )

        // ── Multi-word concepts to detect ───────────────────────────
        private val MULTI_WORD_CONCEPTS = listOf(
            "jump scare", "took off", "freak out", "freaked out", "freaking out",
            "went crazy", "going crazy", "lost it", "losing it", "flipped out",
            "wiped out", "knocked out", "passed out", "blacked out",
            "ran away", "running away", "took off", "taking off",
            "fell down", "falling down", "tripped over", "knocked over",
            "broke down", "breaking down", "blew up", "blown up",
            "walked in", "walking in", "coming in", "came in",
            "snuck up", "sneaking up", "crept up", "creeping up",
            "popped up", "popping up", "showed up", "showing up",
            "stood up", "standing up", "got up", "getting up",
            "sat down", "sitting down", "laid down", "lying down",
            "hung up", "hanging on", "holding on", "letting go",
            "picked up", "put down", "set up", "shut down",
            "turned on", "turned off", "powered up", "powered down",
            "gave up", "gave in", "backed off", "backed down",
            "looked up", "looked down", "looked around", "looked away",
            "woke up", "stayed up", "passed out", "dozed off",
            "played dead", "went viral", "going viral", "blowing up",
            "man in the middle", "brute force", "social engineering",
            "privilege escalation", "buffer overflow", "sql injection",
            "cross site", "remote code execution", "denial of service",
            "reverse shell", "port scan", "vulnerability scan"
        )

        // ── Emotion patterns ────────────────────────────────────────
        private val EMOTION_PATTERNS = mapOf(
            "fear" to listOf("scared", "afraid", "frightened", "terrified", "spooked", "startled", "freaked", "panicked"),
            "surprise" to listOf("surprised", "shocked", "amazed", "stunned", "astonished", "unexpected", "startled", "jumped"),
            "humor" to listOf("funny", "hilarious", "laughing", "comedy", "joke", "silly", "ridiculous", "amusing"),
            "excitement" to listOf("exciting", "thrilling", "amazing", "epic", "incredible", "insane", "crazy", "unbelievable", "wild"),
            "anger" to listOf("angry", "furious", "mad", "rage", "pissed", "outraged", "livid"),
            "sadness" to listOf("sad", "crying", "depressed", "heartbroken", "devastating", "tragic"),
            "disgust" to listOf("disgusting", "gross", "nasty", "repulsive", "cringe", "eww", "yuck"),
            "awe" to listOf("amazing", "breathtaking", "stunning", "magnificent", "spectacular", "awe"),
            "tension" to listOf("tense", "suspense", "gripping", "nerve", "edge of seat", "intense", "high stakes")
        )

        // ── Emotion → search terms mapping ──────────────────────────
        private val EMOTION_SEARCH_TERMS = mapOf(
            "fear" to listOf("scary", "horror", "terrifying", "creepy", "frightening", "spooky", "jump scare"),
            "surprise" to listOf("unexpected", "shocking", "plot twist", "surprise", "caught off guard"),
            "humor" to listOf("funny", "hilarious", "comedy", "laugh", "fails", "bloopers", "meme"),
            "excitement" to listOf("epic", "insane", "crazy", "unbelievable", "amazing", "best", "top"),
            "anger" to listOf("rage", "angry", "furious", "outrage", "rant", "freakout"),
            "sadness" to listOf("sad", "emotional", "heartbreaking", "touching", "tearjerker"),
            "disgust" to listOf("disgusting", "gross", "cringe", "disturbing", "nasty"),
            "awe" to listOf("amazing", "incredible", "breathtaking", "stunning", "spectacular", "mind blowing"),
            "tension" to listOf("intense", "suspenseful", "thrilling", "gripping", "dramatic", "clutch")
        )

        // ── Intent classification words ─────────────────────────────
        private val VIDEO_INTENT_WORDS = setOf(
            "video", "watch", "clip", "footage", "stream", "movie",
            "film", "show", "episode", "series", "season", "youtube",
            "compilation", "montage", "reaction", "vlog", "podcast",
            "tutorial", "walkthrough", "review", "trailer", "teaser"
        )
        private val DOWNLOAD_INTENT_WORDS = setOf(
            "download", "torrent", "magnet", "file", "iso",
            "zip", "rar", "software", "app", "program", "install",
            "crack", "keygen", "patch", "tool", "exploit"
        )
        private val ARTICLE_INTENT_WORDS = setOf(
            "article", "blog", "post", "news", "report", "guide",
            "tutorial", "how to", "explain", "review", "analysis",
            "writeup", "write-up", "advisory", "disclosure", "cve"
        )
        private val IMAGE_INTENT_WORDS = setOf(
            "image", "picture", "photo", "screenshot", "wallpaper",
            "meme", "gif", "infographic", "diagram", "chart"
        )

        // ═════════════════════════════════════════════════════════════
        //  CONCEPT THESAURUS — the heart of semantic understanding
        //  Maps a concept word to related words that appear in real
        //  content titles across the web
        // ═════════════════════════════════════════════════════════════
        private val CONCEPT_THESAURUS = mapOf(
            // ── Animals ─────────────────────────────────────────
            "cat" to listOf("kitten", "kitty", "feline", "cats", "kittens", "tabby", "calico", "persian", "siamese", "maine coon", "ragdoll"),
            "dog" to listOf("puppy", "doggo", "canine", "dogs", "puppies", "pup", "hound", "pooch", "mutt", "pupper"),
            "bird" to listOf("parrot", "eagle", "hawk", "owl", "crow", "sparrow", "robin", "pigeon"),
            "fish" to listOf("goldfish", "shark", "whale", "dolphin", "tuna", "salmon"),
            "snake" to listOf("serpent", "python", "cobra", "viper", "reptile"),
            "horse" to listOf("pony", "stallion", "mare", "foal", "mustang", "equine"),
            "bear" to listOf("grizzly", "polar bear", "brown bear", "teddy"),
            "lion" to listOf("lioness", "pride", "simba", "big cat"),
            "tiger" to listOf("tigress", "bengal", "siberian tiger", "big cat"),
            "wolf" to listOf("wolves", "pack", "howl", "alpha"),
            "monkey" to listOf("ape", "chimp", "chimpanzee", "gorilla", "primate", "orangutan"),
            "rabbit" to listOf("bunny", "bunnies", "hare", "rabbits"),
            "mouse" to listOf("mice", "rat", "rodent", "hamster", "gerbil"),
            "spider" to listOf("tarantula", "arachnid", "web", "creepy crawly"),
            "bug" to listOf("insect", "beetle", "ant", "cockroach", "pest"),

            // ── Actions: Movement ───────────────────────────────
            "jump" to listOf("leap", "bounce", "spring", "hop", "vault", "hurdle", "launch", "take off", "bound", "catapult", "propel", "sky", "air"),
            "run" to listOf("sprint", "dash", "bolt", "flee", "race", "charge", "rush", "zoom", "dart", "speed"),
            "fly" to listOf("soar", "glide", "hover", "float", "take off", "launch", "airborne", "aerial"),
            "fall" to listOf("drop", "tumble", "plunge", "collapse", "topple", "crash", "wipe out", "slip", "stumble", "faceplant"),
            "climb" to listOf("scale", "ascend", "mount", "scramble", "clamber"),
            "swim" to listOf("dive", "paddle", "float", "wade", "splash"),
            "crawl" to listOf("creep", "slither", "sneak", "prowl", "inch"),
            "slide" to listOf("glide", "skid", "slip", "coast", "drift"),
            "spin" to listOf("twirl", "rotate", "whirl", "turn", "revolve", "360"),
            "flip" to listOf("somersault", "backflip", "frontflip", "tumble", "cartwheel", "roll"),
            "dodge" to listOf("evade", "duck", "sidestep", "avoid", "matrix"),
            "launch" to listOf("catapult", "propel", "shoot", "blast off", "rocket", "send flying", "yeet"),
            "land" to listOf("touch down", "arrive", "settle", "crash land", "stick the landing"),

            // ── Actions: Fear/Startle ───────────────────────────
            "scare" to listOf("frighten", "startle", "spook", "terrify", "shock", "jump scare", "creep out", "haunt", "alarm"),
            "startle" to listOf("scare", "surprise", "jump", "shock", "spook", "jolt", "alarm"),
            "frighten" to listOf("scare", "terrify", "spook", "alarmed", "petrify"),
            "spook" to listOf("scare", "frighten", "startle", "haunt", "creep out", "ghostly"),
            "panic" to listOf("freak out", "lose it", "go crazy", "flip out", "meltdown", "hysteria"),
            "freak" to listOf("panic", "lose it", "go crazy", "flip out", "overreact", "meltdown", "freak out"),

            // ── Actions: Reactions ──────────────────────────────
            "react" to listOf("respond", "reaction", "freak out", "flip out", "go crazy", "lose it", "overreact"),
            "scream" to listOf("yell", "shriek", "shout", "howl", "screech", "wail", "holler"),
            "laugh" to listOf("giggle", "chuckle", "cackle", "snicker", "roar", "crack up", "lol", "lmao"),
            "cry" to listOf("weep", "sob", "wail", "bawl", "tear up", "break down"),
            "flinch" to listOf("wince", "cringe", "recoil", "jerk", "twitch", "startle"),

            // ── Actions: Combat/Force ───────────────────────────
            "fight" to listOf("battle", "brawl", "combat", "clash", "duel", "spar", "wrestling", "boxing"),
            "hit" to listOf("strike", "punch", "slap", "smack", "whack", "bash", "slam"),
            "kick" to listOf("punt", "boot", "stomp", "dropkick"),
            "throw" to listOf("toss", "hurl", "fling", "chuck", "lob", "launch", "yeet"),
            "push" to listOf("shove", "thrust", "nudge", "force", "ram"),
            "pull" to listOf("drag", "tug", "yank", "haul", "wrench"),
            "break" to listOf("smash", "shatter", "destroy", "demolish", "wreck", "bust", "crack"),
            "crash" to listOf("wreck", "smash", "collide", "impact", "pile up", "wipe out"),
            "explode" to listOf("blow up", "blast", "detonate", "burst", "erupt", "kaboom"),
            "attack" to listOf("assault", "strike", "charge", "rush", "ambush", "pounce"),

            // ── Actions: Stealth ────────────────────────────────
            "sneak" to listOf("creep", "tiptoe", "stealth", "lurk", "skulk", "slink"),
            "hide" to listOf("conceal", "camouflage", "stash", "duck", "take cover"),
            "surprise" to listOf("ambush", "catch off guard", "unexpected", "gotcha", "prank"),
            "prank" to listOf("trick", "joke", "hoax", "gag", "fool", "trolling"),

            // ── Descriptors: Scale/Intensity ────────────────────
            "high" to listOf("tall", "sky high", "massive", "huge", "insane", "incredible", "extreme", "epic", "to the moon", "stratospheric"),
            "big" to listOf("huge", "massive", "enormous", "gigantic", "colossal", "giant", "mega"),
            "small" to listOf("tiny", "little", "mini", "miniature", "micro", "itty bitty"),
            "fast" to listOf("quick", "rapid", "speedy", "lightning", "instant", "turbo", "hyper"),
            "slow" to listOf("sluggish", "lazy", "gradual", "snail", "crawling"),
            "loud" to listOf("noisy", "deafening", "booming", "thunderous", "blaring"),
            "crazy" to listOf("insane", "wild", "mental", "nuts", "bonkers", "unhinged", "outrageous", "psycho"),
            "funny" to listOf("hilarious", "comedic", "humorous", "amusing", "laughable", "comical", "hysterical"),
            "scary" to listOf("terrifying", "creepy", "frightening", "horrifying", "spooky", "eerie", "chilling"),
            "amazing" to listOf("incredible", "awesome", "stunning", "spectacular", "mind blowing", "jaw dropping", "insane"),
            "epic" to listOf("legendary", "incredible", "massive", "insane", "ultimate", "godlike"),
            "cute" to listOf("adorable", "sweet", "precious", "lovely", "cutest"),
            "hard" to listOf("difficult", "tough", "challenging", "impossible", "brutal", "extreme"),

            // ── Emotions/States ─────────────────────────────────
            "scared" to listOf("frightened", "terrified", "spooked", "startled", "alarmed", "petrified", "shook"),
            "angry" to listOf("furious", "mad", "enraged", "livid", "pissed", "irate", "outraged"),
            "happy" to listOf("joyful", "delighted", "ecstatic", "thrilled", "overjoyed", "elated"),
            "confused" to listOf("puzzled", "baffled", "bewildered", "perplexed", "lost"),
            "shocked" to listOf("stunned", "astonished", "flabbergasted", "speechless", "mind blown"),
            "excited" to listOf("hyped", "pumped", "stoked", "thrilled", "amped", "buzzing"),
            "tired" to listOf("exhausted", "sleepy", "fatigued", "drained", "worn out"),
            "bored" to listOf("uninterested", "restless", "fed up"),
            "nervous" to listOf("anxious", "worried", "tense", "jittery", "on edge"),

            // ── Context: Locations ──────────────────────────────
            "room" to listOf("bedroom", "living room", "kitchen", "bathroom", "office", "indoor"),
            "house" to listOf("home", "apartment", "flat", "crib", "dwelling", "residence"),
            "outside" to listOf("outdoor", "backyard", "garden", "park", "field", "nature"),
            "street" to listOf("road", "sidewalk", "highway", "alley", "avenue"),
            "water" to listOf("pool", "lake", "river", "ocean", "sea", "beach", "underwater"),

            // ── Content types ───────────────────────────────────
            "compilation" to listOf("montage", "supercut", "best of", "top 10", "highlights", "mix"),
            "fail" to listOf("fails", "bloopers", "outtakes", "mess up", "gone wrong", "disaster"),
            "win" to listOf("success", "victory", "clutch", "comeback", "best plays"),
            "prank" to listOf("pranks", "trick", "joke", "gag", "scare prank", "hidden camera"),
            "review" to listOf("unboxing", "hands on", "first look", "comparison", "versus", "vs"),
            "tutorial" to listOf("how to", "guide", "walkthrough", "step by step", "diy", "lesson"),
            "reaction" to listOf("reacts", "reacting", "first time", "blind reaction", "response"),
            "challenge" to listOf("dare", "try not to", "impossible", "extreme", "test"),

            // ── Security/Hacking ────────────────────────────────
            "hack" to listOf("exploit", "vulnerability", "security", "penetration", "breach", "bypass", "crack"),
            "exploit" to listOf("vulnerability", "cve", "zero day", "0day", "poc", "proof of concept", "payload"),
            "scan" to listOf("enumerate", "recon", "reconnaissance", "discovery", "probe", "fingerprint"),
            "brute" to listOf("brute force", "bruteforce", "dictionary attack", "password spray", "credential stuffing"),
            "inject" to listOf("injection", "sqli", "sql injection", "xss", "cross site scripting", "command injection"),
            "shell" to listOf("reverse shell", "web shell", "bind shell", "command shell", "terminal"),
            "privilege" to listOf("privilege escalation", "privesc", "root", "admin", "sudo", "elevation"),
            "phish" to listOf("phishing", "social engineering", "spear phishing", "credential harvest"),
            "malware" to listOf("virus", "trojan", "worm", "ransomware", "rootkit", "spyware", "rat"),
            "firewall" to listOf("waf", "ids", "ips", "filter", "block", "rule", "acl"),
            "encrypt" to listOf("cipher", "hash", "ssl", "tls", "aes", "rsa", "crypto"),
            "decrypt" to listOf("crack", "break", "decipher", "decode", "plaintext"),
            "proxy" to listOf("vpn", "tunnel", "socks", "relay", "redirect", "forward"),
            "pentest" to listOf("penetration test", "security assessment", "red team", "ethical hacking", "bug bounty"),
            "recon" to listOf("reconnaissance", "osint", "information gathering", "footprinting", "enumeration"),
            "payload" to listOf("shellcode", "exploit code", "weaponize", "dropper", "stager"),
            "backdoor" to listOf("persistence", "implant", "rat", "remote access", "covert channel"),
            "sniff" to listOf("packet capture", "wireshark", "tcpdump", "network monitor", "traffic analysis"),
            "spoof" to listOf("impersonate", "forge", "fake", "masquerade", "arp spoof", "dns spoof"),
            "pivot" to listOf("lateral movement", "port forward", "tunnel", "jump host", "relay"),

            // ── Places / Scenes ─────────────────────────────────
            "kitchen" to listOf("cooking", "food prep", "chef", "stove", "counter"),
            "bathroom" to listOf("shower", "bath", "toilet", "sink", "mirror"),
            "bedroom" to listOf("bed", "sleep", "pillow", "mattress", "nightstand"),
            "office" to listOf("desk", "work", "computer", "cubicle", "workplace"),
            "car" to listOf("vehicle", "automobile", "ride", "driving", "road trip"),
            "gym" to listOf("workout", "exercise", "fitness", "training", "weights"),
            "park" to listOf("playground", "bench", "nature", "trees", "outdoor"),
            "beach" to listOf("sand", "ocean", "surf", "waves", "tropical"),
            "forest" to listOf("woods", "jungle", "wilderness", "trees", "nature trail"),
            "mountain" to listOf("peak", "summit", "alpine", "hiking", "cliff"),

            // ── People/Characters ───────────────────────────────
            "person" to listOf("someone", "guy", "dude", "individual", "human"),
            "kid" to listOf("child", "children", "toddler", "youngster", "little one"),
            "baby" to listOf("infant", "newborn", "toddler", "little one", "newborn"),
            "man" to listOf("guy", "dude", "gentleman", "male", "bro"),
            "woman" to listOf("girl", "lady", "female", "gal", "chick"),
            "friend" to listOf("buddy", "pal", "mate", "homie", "bestie"),
            "owner" to listOf("parent", "person", "handler", "keeper"),

            // ── Misc common terms ───────────────────────────────
            "walk" to listOf("stroll", "stride", "march", "step", "pace", "wander", "roam"),
            "eat" to listOf("munch", "chew", "gobble", "devour", "snack", "feast", "chomp", "nom"),
            "sleep" to listOf("nap", "doze", "rest", "slumber", "snooze", "pass out", "zonk out"),
            "play" to listOf("game", "fun", "frolic", "mess around", "fool around", "goof"),
            "look" to listOf("stare", "gaze", "glance", "peek", "watch", "observe", "eye"),
            "talk" to listOf("speak", "chat", "converse", "discuss", "say", "tell", "rant"),
            "work" to listOf("job", "labor", "effort", "grind", "hustle", "task"),
            "wait" to listOf("pause", "hold on", "hang on", "stand by", "lingering"),
            "dance" to listOf("groove", "move", "bust a move", "choreography", "twerk"),
            "sing" to listOf("vocal", "karaoke", "perform", "belt out", "serenade"),
            "cook" to listOf("bake", "prepare", "grill", "fry", "roast", "recipe"),
            "drive" to listOf("ride", "cruise", "steer", "navigate", "road trip"),
            "build" to listOf("construct", "create", "make", "assemble", "craft"),
            "destroy" to listOf("demolish", "wreck", "ruin", "annihilate", "obliterate", "devastate"),
            "fix" to listOf("repair", "restore", "mend", "patch", "solve"),
            "open" to listOf("unlock", "unseal", "reveal", "unbox", "unwrap"),
            "close" to listOf("shut", "seal", "lock", "slam shut"),
            "start" to listOf("begin", "commence", "kick off", "launch", "initiate"),
            "stop" to listOf("halt", "cease", "end", "quit", "freeze"),
            "think" to listOf("ponder", "consider", "wonder", "contemplate", "brainstorm"),
            "try" to listOf("attempt", "test", "experiment", "give it a shot", "challenge")
        )

        // ── Context patterns ────────────────────────────────────────
        private val CONTEXT_PATTERNS = mapOf(
            "indoor" to listOf("room", "house", "home", "inside", "indoor", "apartment", "kitchen", "bedroom", "bathroom", "office"),
            "outdoor" to listOf("outside", "outdoor", "park", "garden", "backyard", "street", "road", "field", "beach", "mountain"),
            "night" to listOf("night", "dark", "midnight", "evening", "late"),
            "day" to listOf("day", "morning", "afternoon", "daytime", "sunny"),
            "alone" to listOf("alone", "solo", "by myself", "lonely", "single"),
            "crowd" to listOf("crowd", "group", "people", "audience", "spectators", "public"),
            "competition" to listOf("race", "contest", "competition", "tournament", "championship", "match"),
            "nature" to listOf("wild", "nature", "wildlife", "animal", "forest", "jungle", "safari")
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  QUERY-LEVEL LEARNING  
    //  Learns which query generation strategies produce the best results
    //  for specific types of queries. Feeds back into future query
    //  generation to prioritize high-performing strategies.
    // ═══════════════════════════════════════════════════════════════════

    /** Maps a simplified intent+concept fingerprint → strategy effectiveness scores. */
    private val queryFeedback = java.util.concurrent.ConcurrentHashMap<String, QueryFeedbackRecord>()

    /** Global strategy effectiveness across all domains. */
    private val strategyScores = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /** Successful query patterns: stores query → result count for future reference. */
    private val successfulPatterns = java.util.concurrent.ConcurrentHashMap<String, SuccessfulQueryPattern>()

    /**
     * Record that a processed query produced results.
     * [effectiveQuery] is the specific search string from [ProcessedQuery.searchQueries]
     * that actually returned results. [resultCount] is how many results it found.
     * [likedByUser] indicates whether the user liked any result from this query.
     */
    fun recordQuerySuccess(
        processedQuery: ProcessedQuery,
        effectiveQuery: String,
        resultCount: Int,
        likedByUser: Boolean = false
    ) {
        val fingerprint = buildFingerprint(processedQuery)
        val existing = queryFeedback[fingerprint] ?: QueryFeedbackRecord(fingerprint)

        // Determine which strategy produced this specific effective query
        val strategyTag = identifyStrategy(effectiveQuery, processedQuery)

        val likeBoost = if (likedByUser) 2f else 1f
        val score = (resultCount.coerceAtMost(50).toFloat() / 50f) * likeBoost

        val updatedStrategies = existing.strategyScores.toMutableMap()
        val oldScore = updatedStrategies[strategyTag] ?: 0.5f
        updatedStrategies[strategyTag] = (oldScore * 0.7f + score * 0.3f).coerceIn(0f, 1f)

        queryFeedback[fingerprint] = existing.copy(
            strategyScores = updatedStrategies,
            totalSuccesses = existing.totalSuccesses + 1,
            lastUsed = System.currentTimeMillis()
        )

        // Update global strategy score
        val globalOld = strategyScores[strategyTag] ?: 0.5f
        strategyScores[strategyTag] = (globalOld * 0.8f + score * 0.2f).coerceIn(0f, 1f)

        // Store successful pattern
        successfulPatterns[effectiveQuery] = SuccessfulQueryPattern(
            query = effectiveQuery,
            intent = processedQuery.intent,
            resultCount = resultCount,
            likedByUser = likedByUser,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Record that a processed query entirely failed to produce results.
     * This penalizes the strategies used and helps avoid them in the future.
     */
    fun recordQueryFailure(processedQuery: ProcessedQuery) {
        val fingerprint = buildFingerprint(processedQuery)
        val existing = queryFeedback[fingerprint] ?: QueryFeedbackRecord(fingerprint)

        val updatedStrategies = existing.strategyScores.toMutableMap()
        processedQuery.searchQueries.take(5).forEach { query ->
            val tag = identifyStrategy(query, processedQuery)
            val old = updatedStrategies[tag] ?: 0.5f
            updatedStrategies[tag] = (old * 0.8f).coerceIn(0f, 1f)
        }

        queryFeedback[fingerprint] = existing.copy(
            strategyScores = updatedStrategies,
            totalFailures = existing.totalFailures + 1,
            lastUsed = System.currentTimeMillis()
        )
    }

    /**
     * Get the best-performing query strategies for queries similar to the
     * given fingerprint, used to re-order generated queries.
     */
    fun getBestStrategiesFor(processedQuery: ProcessedQuery): List<Pair<String, Float>> {
        val fingerprint = buildFingerprint(processedQuery)
        val record = queryFeedback[fingerprint]
        return if (record != null) {
            record.strategyScores.entries
                .sortedByDescending { it.value }
                .map { it.key to it.value }
        } else {
            // Fall back to global strategy scores
            strategyScores.entries
                .sortedByDescending { it.value }
                .map { it.key to it.value }
        }
    }

    /**
     * Get successful query patterns that match the intent of the current query.
     * Useful for suggesting "did you mean..." or reusing effective past queries.
     */
    fun getSimilarSuccessfulQueries(intent: QueryIntent, limit: Int = 5): List<SuccessfulQueryPattern> {
        return successfulPatterns.values
            .filter { it.intent == intent }
            .sortedByDescending { it.resultCount * (if (it.likedByUser) 2 else 1) }
            .take(limit)
    }

    /**
     * Get NLP learning statistics.
     */
    fun getLearningStats(): NLPLearningStats {
        return NLPLearningStats(
            totalFeedbackRecords = queryFeedback.size,
            totalSuccessfulPatterns = successfulPatterns.size,
            totalStrategiesTracked = strategyScores.size,
            topStrategies = strategyScores.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key to it.value }
        )
    }

    /** Build a simplified fingerprint from intent + top concepts for grouping similar queries. */
    private fun buildFingerprint(pq: ProcessedQuery): String {
        val intent = pq.intent.name
        val subjects = pq.concepts.subjects.sorted().take(3).joinToString(",")
        val actions = pq.concepts.actions.sorted().take(2).joinToString(",")
        return "$intent|$subjects|$actions"
    }

    /** Identify which generation strategy likely produced a given query string. */
    private fun identifyStrategy(query: String, pq: ProcessedQuery): String {
        val q = query.lowercase()
        val subjects = pq.concepts.subjects
        val actions = pq.concepts.actions
        val emotions = pq.concepts.emotions
        val compounds = pq.concepts.compoundConcepts

        return when {
            // Compound concept match
            compounds.any { q.contains(it) } -> "compound"
            // Emotion-driven
            emotions.any { emotion ->
                val terms = EMOTION_SEARCH_TERMS[emotion] ?: emptyList()
                terms.any { q.contains(it) }
            } -> "emotion"
            // Cause-effect pattern
            pq.concepts.causeEffects.any { ce -> q.contains(ce.cause) && q.contains(ce.effect) } -> "cause_effect"
            // Subject + action core
            subjects.any { q.contains(it) } && actions.any { q.contains(it) } -> "core_concept"
            // Synonym expanded
            subjects.any { s -> getConceptSynonyms(s).any { q.contains(it) } } -> "synonym"
            // Intent-specific
            q.contains("compilation") || q.contains("moments") || q.contains("reaction") ||
            q.contains("download") || q.contains("torrent") || q.contains("guide") -> "intent"
            // Broad / fallback
            else -> "broad"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════

/**
 * Complete result of processing a natural language query
 */
data class ProcessedQuery(
    /** The original raw user input */
    val originalQuery: String,
    /** Cleaned/normalized version */
    val normalizedQuery: String,
    /** Extracted semantic concepts */
    val concepts: SemanticConcepts,
    /** Detected search intent */
    val intent: QueryIntent,
    /** Generated optimised search queries, ranked by expected effectiveness */
    val searchQueries: List<String>,
    /** All concept-related terms for broad matching */
    val conceptTerms: Set<String>,
    /** Whether the input was detected as natural language vs keyword query */
    val isNaturalLanguage: Boolean
)

data class SemanticConcepts(
    val subjects: List<String>,
    val actions: List<String>,
    val descriptors: List<String>,
    val emotions: List<String>,
    val contexts: List<String>,
    val compoundConcepts: List<String>,
    val causeEffects: List<CauseEffect>
)

data class CauseEffect(
    val cause: String,
    val effect: String,
    val relationship: String // "cause-effect", "sequence", "temporal"
)

enum class QueryIntent {
    VIDEO,
    DOWNLOAD,
    ARTICLE,
    IMAGE,
    GENERAL
}

/** Tracks strategy effectiveness for a query fingerprint. */
data class QueryFeedbackRecord(
    val fingerprint: String,
    val strategyScores: Map<String, Float> = emptyMap(),
    val totalSuccesses: Int = 0,
    val totalFailures: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
)

/** A query pattern that previously produced good results. */
data class SuccessfulQueryPattern(
    val query: String,
    val intent: QueryIntent,
    val resultCount: Int,
    val likedByUser: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/** NLP learning statistics for display. */
data class NLPLearningStats(
    val totalFeedbackRecords: Int,
    val totalSuccessfulPatterns: Int,
    val totalStrategiesTracked: Int,
    val topStrategies: List<Pair<String, Float>>
)
