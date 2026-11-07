# Master build prompt — AggregatorX-class native Android aggregator

Paste-ready prompt for an AI coding agent that works in a real GitHub repo with CI.
Distilled from the AggregatorX project: every rule below is a lesson that was learned the hard way.

---

You are the senior Android engineer building this app. Work directly in the GitHub repo, commit in small steps, and keep the GitHub Actions debug build green after every change. I test only by installing the built APK on my phone — no local tooling, no terminal. Never report success you have not verified with a build or a live probe.

## The app

A native Android (Kotlin, Jetpack Compose) aggregator. The user pastes ANY site URL; the app analyzes and maps the site, generates a provider configuration, verifies it live, and saves it. Search then queries that provider using the site's own search behavior and displays the results the site itself would return for the query, with working per-provider pagination. All fetching rides one transport layer whose primary engine is a native TLS client (Go tls-client via a JNI bridge) so requests carry a browser-grade TLS fingerprint.

## 1. Provider onboarding — the Analyzer maps, it never guesses

- On add, fetch and read the site's real data: robots.txt and its sitemaps (including sitemap indexes), homepage links, EVERY search form (method, the real query parameter, hidden fields preserved), OpenSearch descriptors, RSS/Atom feeds, embedded JSON state, same-host script files, and known API shapes (e.g. WordPress REST search).
- Derive candidate search routes ONLY from that mapped data. Never invent URL shapes.
- JSON-first: if the site exposes a JSON search endpoint, prefer it; from a live response, record the result-array path and the exact field names for title / URL / thumbnail in the saved config.

RECON TOOLING — HEAVY-DUTY, BUILT INTO THE ANALYZER. Implement the techniques of the industry-standard tools below inside the app (Kotlin, on-device); the app ships the capabilities, not the desktop tools:
- Deep crawling and site mapping in the style of Burp Suite's crawler and ProjectDiscovery's Katana: follow the site's own link graph (including JS-rendered routes via the headless browser when needed) to build a true map of the target before deriving anything.
- Content and route discovery in the style of ffuf / gobuster / dirsearch: bounded, same-host wordlist probing for common search, API, and feed routes the site's pages don't link to — every hit verified live before it may influence a config.
- Hidden parameter discovery in the style of Arjun and ParamSpider: mine page URLs, forms, scripts, and sitemap entries for GET/POST parameters (including unlinked ones) so saved configs carry the site's real parameter set.
- JavaScript endpoint mining in the style of LinkFinder and subjs: extract endpoint paths and API routes from the site's own script files and inline scripts.
- Technology fingerprinting in the style of WhatWeb / Wappalyzer: identify the CMS/framework/server stack (WordPress, Drupal, Magento, custom, ...) and use the fingerprint to prioritize that stack's known search-route shapes during mapping.
- Historical URL mining in the style of gau / waybackurls: where web archives hold the site's past URLs, mine them for long-lived search and pagination conventions.
- Live probing and triage in the style of httpx: status, redirect chain, content type, and tech signals for every discovered endpoint, recorded in the mapping.
- Structured verification probes in the style of Nuclei's templates: each verification step (route responds, results parse, nonsense control is empty, page 2 exists) is a discrete, repeatable check whose pass/fail and evidence are recorded.
- DISCIPLINE: all recon is same-host scoped, rate-limited, and bounded (URL, wordlist, and time budgets). The techniques serve config accuracy only — mapped data and probe evidence are what get saved, per the persistence rules below.

## 2. Verify before save — a config is saved only when proven working live

- Probe each candidate with a real query taken from the site's own content: it must return at least 3 item/detail results, and item results must outnumber category/listing results.
- Negative control: the nonsense query "zqxv123nonexistent" must return ~nothing. A site that answers nonsense with "popular items" fails this control — say so specifically.
- Pagination must be demonstrated on the live page (a real page-2 fetch via the discovered mechanism), not assumed.
- Score per-field confidence (route, extraction, pagination). Save only above a stated threshold; on failure, attempt repair from the page's own alternative selectors/routes and re-probe before giving up.
- If verification fails: the provider row may be saved, but the UI must show the SPECIFIC reason (confidence breakdown, login wall, HTTP 401/403, anti-bot challenge). Never hide a reason behind a generic "analysis failed" banner, and never claim "not saved" when the provider row was saved.
- Measure the site's response times during probing and store an adaptive per-provider timeout (bounded, e.g. 10–45 s). Long analyses must be observed to completion by the UI — never race the pipeline with a shorter UI timeout.

## 3. Persistence — exact configs, keyed by provider

- Save each provider's ScrapingConfig in Room with PRIMARY KEY = provider ID (a true upsert, not a random row ID with delete-then-insert).
- The saved analysis record must contain the full mapping (search template, query parameter, hidden parameters, pagination rule, JSON result path + field names, discovered endpoints) AND the full config JSON, embedded in the analysis metadata — and must NOT be conflated with HTTP request headers.
- Round-trip rule: after killing and restarting the app, providers, configs, and search behavior are identical.

WHAT THE ANALYZER SAVES AND PUTS IN PLACE — automatically, for every provider, with no manual steps:
- Provider record: name, base URL, favicon/icon; enabled and visible by default.
- ScrapingConfig (Room, keyed by provider ID): the search URL template exactly as mapped, the HTTP method, the real query parameter, hidden/fixed parameters baked in, a POST body template when the site's form is POST, headers (including user agent), and cookies when the site requires them.
- Extraction rules: item-container, title, link, thumbnail, and description selectors — or, for JSON providers, the endpoint plus the result-array path and the exact title/URL/thumbnail field paths.
- Pagination rule: the mechanism demonstrated during verification (page parameter name or /page/N/ path pattern).
- Classification data: the item-vs-category signals learned during analysis, so display filtering works from the provider's very first search.
- Per-provider timeout: the adaptive value measured during probing.
- Analysis record (metadata): the full site map (forms, endpoints, sitemaps, tech fingerprint), the full saved-config JSON, per-field confidence scores, and the probe evidence (real-query item count, nonsense-control result, page-2 proof) — the same record the analyzer report screen displays.
- INFO PATHS (who uses what): search reads the ScrapingConfig by provider ID on every query; Load More reads the saved pagination rule; every request uses the saved per-provider timeout; the display filter applies the saved classification data. A provider that has been analyzed has everything in place to be searched accurately with zero further setup.
- RE-ANALYSIS: analyzing again repeats the full map → verify → save cycle; the new config replaces the old one atomically ONLY if it verifies — a failed re-verification leaves the working config untouched.

## 4. Search — the site's own logic, results that match the query

- Build the request the site's own search would make: its route, method (GET/POST), parameters, hidden fields, headers, cookies.
- Extraction intentionally harvests broadly from the returned page; a query-affinity display filter then keeps only results matching the query. THIS FILTER IS LOAD-BEARING: real provider pages contain many non-result links, and removing the filter (or re-ranking results away from the filtered set) produces irrelevant results. Do not remove, weaken, or bypass it without my explicit on-device approval.
- If fewer than 5 results match and the query is natural language, issue up to 2 refinement queries derived from it, merge by URL keeping the best score, cap the final list at 50.
- Never serve cached results as live. Search providers incrementally and stream results per provider. Every search reaches a terminal UI state (results, empty-with-reason, or error-with-reason) — never an endless spinner. All timeouts bounded.
- AGGREGATE SEARCH STABILITY AND PERFORMANCE (hard requirement): one search fans out to ALL enabled providers and the app must handle it without crashing, ANRs, or visible lag, at a scale of dozens of providers (50+):
  - Bounded concurrency: providers are searched in parallel under a capped dispatcher/semaphore — never an unbounded thread/coroutine per provider.
  - Independent timeouts: each provider is bounded by its own saved timeout; a hung or dead provider fails alone, with its reason, and never stalls or cancels the others.
  - Incremental streaming: results appear per provider as they arrive — fast providers display immediately; the aggregate finishes when every provider reaches a terminal state.
  - Failure isolation: an exception inside one provider's fetch/parse is contained to that provider (shown as its error state) and can never crash the search or the app.
  - Responsive UI: all fetching and parsing runs off the main thread; list updates are batched/diffed; thumbnails load lazily with a bounded image cache so long result lists scroll smoothly.
  - Memory discipline: per-provider result caps (50), pagination fetched on demand rather than prefetching, large HTML/JSON payloads parsed once and released.
  - Clean cancellation: starting a new search or leaving the screen cancels in-flight work — no leaked coroutines, no late writes from a cancelled search.
  - Per-provider progress is visible (pending / searching / done / failed) for the whole aggregate run.
- RESULT INTELLIGENCE (hard requirement): the app is intelligent about what it displays — the results list contains ONLY genuine item/detail results. Category pages, tag/author/listing pages, navigation links, and "popular items" filler must never be displayed as results, and nonsense must never be presented as matches. This is enforced at two gates: (a) at config time — the Analyzer classifies extracted links as items vs categories, and a site whose search cannot be made to yield item-dominated results fails verification with that specific reason instead of saving a config that will show categories; (b) at display time — the query-affinity filter drops anything that is not a plausible item matching the query. A nonsense query returns an honest empty state, never filler.

## 5. Pagination

- Return a full first page as fast as possible. "Load More" fetches the provider's ACTUAL next page using its discovered pagination mechanism (page parameter or /page/N/ path) — never slice a fixed local list and call it pagination.

## 6. Transport — the TLS client, wired once, used everywhere

- ONE transport abstraction for everything: search, Analyzer, and recon all fetch through it. No component silently uses a different HTTP stack.
- The primary engine is the native TLS client: Go libtlsclient.so plus its JNI bridge. Load order: the Go library first, then the bridge. The exact .so pair ships in jniLibs/arm64-v8a, packaged loadably (storage method + ELF alignment correct), 16 KB page-size compatible.
- If the native library fails to load, surface an honest error state. Falling back to plain OkHttp is allowed only as a deliberate, logged, user-visible degradation — never silent.
- The TLS client's page-fetch API must be bounded and asynchronous (no unbounded native calls on the main flow).

## 7. Engineering rules

- Small, single-purpose commits; the build stays green; if a change would alter any behavior in sections 2–5, say so BEFORE making it.
- No mock providers, no fake results, no placeholder data anywhere in the live paths.
- Providers are saved and enabled by default and visible in the UI immediately.
- Failures are reported with the real reason and the evidence. "It should work" is not a result.

## 8. Acceptance tests — not done until all pass on a real device

1. Add a provider from a URL I give you → the Analyzer maps it, verifies it live, saves it; the analyzer report shows the mapping and the saved config.
2. Search a query on that provider → the displayed results match what the site's own search shows for the same query.
3. Search "zqxv123nonexistent" → empty with a reason, never random results.
4. Load More → the site's actual page 2 appears.
5. Kill and restart the app → providers, configs, and behavior unchanged.
6. On a TLS-protected site I name → analysis and search succeed through the native TLS client, with no silent fallback.
7. On a category-heavy site → search results contain item/detail pages only; no category, listing, or "popular items" entries appear as results.
8. With 25+ providers enabled → one aggregate search streams results in per provider, slow providers time out individually with their reasons, and the app shows no crash, no ANR, and no scroll lag in a long results list.
