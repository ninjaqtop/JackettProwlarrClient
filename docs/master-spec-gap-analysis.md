# AggregatorX — gap analysis vs. master build spec

Base: `origin/feat/analyzer-recon` @ 5829be6 (CI green). Work branch: `feat/master-spec-build`.
Spec: `/workspace/uploads/AggregatorX master build prompt.md` (sections 1–8).
Legend: **Present** = already satisfied on the base branch (left as is) · **Gap** = missing/wrong on the base branch · **Done** = implemented on this branch · **Open** = not done / unverifiable without a device.

---

## §6 Transport (assessed first — everything else rides on it)

| Requirement | Base branch | Gap / evidence | This branch |
|---|---|---|---|
| Native pair, 16 KB-compatible | libtlsclient.so + prebuilt libtlsclientbridge.so present; all LOAD segments 0x4000-aligned | **Gap (critical):** the prebuilt bridge's `DT_NEEDED` was an absolute Windows build-host path (the Go lib had no `DT_SONAME`), so `System.loadLibrary("tlsclientbridge")` failed on every device. | **Done (built from source):** no `.so` is checked in any more. CI builds `libtlsclient.so` from `app/src/main/go/tlsclient` (bogdanfinn/tls-client v1.15.1, Go 1.25 c-shared, NDK 27 clang, explicit `-soname libtlsclient.so`, 16 KB pages) for arm64-v8a, armeabi-v7a and x86_64, and CMake compiles `native-lib.cpp` into `libtlsclientbridge.so` linked by plain soname. The earlier in-place `.dynstr` patch is gone. `scripts/native_pair_check.py` checks the APK per ABI. |
| JNI binding | `TlsClient.requestNative` in `com.aggregatorx.app.engine.network` | **Gap (critical):** the prebuilt bridge exported `Java_com_aggregatorx_apq_..._requestNative` (`apq`). | **Done at source:** one package, `com.aggregatorx.app.engine.network.TlsClient` (`private external fun requestNative`), matching `native-lib.cpp`'s `Java_com_aggregatorx_app_engine_network_TlsClient_requestNative`. The `com.aggregatorx.apq` shim is deleted. CI derives the expected symbol from the Kotlin source and fails on a missing symbol or a JNI export for any other class. |
| Load order Go lib first, then bridge | Only `loadLibrary("tlsclientbridge")` | **Gap** | **Done:** `tlsclient` then `tlsclientbridge`, each failure recorded separately; JNI bind failure marks the engine unavailable. |
| Packaged loadably (stored + aligned) | Not checked | Unknown | **Done/verified in CI:** APK stores both .so uncompressed at 16 KB-aligned offsets (`native_pair_check.py --apk`). |
| ONE transport for search, Analyzer, recon | Search raced native TLS against Jsoup/HttpURLConnection and kept the longer body; Analyzer/discovery/navigation used `Jsoup.connect`; recon/verifier called `TlsClient.execute` directly (no fallback, unbounded) | **Gap** | **Done:** `HttpTransport` (native primary) + `TransportConnection` (Jsoup-shaped facade). All search/Analyzer/recon fetch sites migrated (ScrapingEngine, SiteReconEngine, SearchConfigVerifier, ProviderConfigValidator, SiteAnalyzerEngine, EndpointDiscoveryEngine, SmartNavigationEngine, FallbackEngine, UnifiedContentEngine, CloudflareBypassEngine). WebView headless phases remain (a browser engine, not an HTTP stack). Media/playback/download engines are out of scope and unchanged. |
| Honest, logged, user-visible degradation | Silent | **Gap** | **Done:** every OkHttp fallback is logged (`HttpTransport` `DEGRADED`), counted, stamped on the response, published as `TransportHealth`; Search screen banner shows it with the reason. |
| Bounded, async native page fetch | Unbounded blocking `execute` | **Gap** | **Done:** capped 16-thread native pool, hard deadline (timeout + 2 s) on every native call, suspend `fetch()` and IO-thread `fetchBlocking()`. (Ported intent of fedcb4d.) |

## §1 Provider onboarding / recon

| Technique | Base branch | This branch |
|---|---|---|
| robots.txt + sitemaps (+ indexes), homepage links, search forms (method, real param, hidden fields), OpenSearch, feeds, embedded JSON state, same-host scripts, WP REST | **Present** (SiteReconEngine) — but its fetches went through the dead native path (see §6), so on device most recon returned nothing | Now carried by HttpTransport |
| Candidate routes only from mapped data | Present in recon; **gap in search runtime** (guessed shapes added even for verified configs) | Verified configs now use only their mapped request (§4) |
| JSON-first: result path + field names recorded | Recorded only inside selector fields | **Done:** `isJson`, `jsonResultPath`, `jsonTitle/Url/ThumbnailField` columns; runtime extracts with them first |
| Deep crawl (Katana/Burp) | Homepage only | **Done:** bounded BFS (depth 2, 20 pages, same-host), inner-page forms harvested; crawled pages that are JS app shells are re-read through the WebView (max 3, 10 s each, `js-rendered-crawl` check) |
| Wordlist route discovery (ffuf/dirsearch) | None | **Done:** ≤14 same-host routes, stack-prioritized, hit = 2xx + probe term echoed or JSON; hits still must pass full verification |
| Parameter mining (Arjun/ParamSpider) | Search keys only | **Done:** names from URLs, forms, scripts (recorded; search-like flagged) |
| JS endpoint mining (LinkFinder/subjs) | Search-param literals only | **Done:** LinkFinder-style extraction of same-host endpoint paths |
| Tech fingerprint (WhatWeb/Wappalyzer) | EndpointDiscovery CMS probes | **Done:** headers/cookies/generator/markup; prioritizes stack search routes |
| Historical URLs (gau/waybackurls) | None | **Done:** Wayback CDX for the site's host, same-host URLs only (the only off-host request: it asks the archive about the host) |
| httpx triage | None | **Done:** status, redirect, content type, size, timing, engine, tech for every recon fetch |
| Discrete recorded checks | None for recon | **Done:** per-technique `ReconCheck` list |
| Discipline (same-host, rate-limited, bounded) | Bounded | **Done:** 150 ms min gap, 32 s recon budget, per-technique caps |

## §2 Verify before save

| Requirement | Base branch | This branch |
|---|---|---|
| ≥3 item results, items outnumber categories | **Present** | kept; category URL test extended (root/nav, author, leading browse/page/search) |
| Nonsense control "zqxv123nonexistent" | **Present** | failure text now names "answers nonsense with filler/popular items" |
| Live page-2 fetch | **Gap:** "proven" = a next-page link seen on page 1 | **Done:** real page-2 request via the template `{page}` or a template derived from the live next-page link; proven only if new items appear; rule saved (`param:<name>` / `path:<pattern>`) |
| Per-field confidence, threshold | **Present** (route/extraction/pagination, 0.70) | per-field map persisted; pagination: proven 1.0 / link seen 0.5 / inventory 0.4 / none 0.3 |
| Repair from alternative selectors/routes | **Present** (selector repair, rendered phase) | kept |
| Specific failure reasons; row saved; never "not saved" when saved | Reasons present; generic catch-all paths existed ("Analysis could not finish…", config-step crash reported as success) | **Done:** failures recorded in the analysis record with reasons; config-step crash surfaces as a failure with its real exception; report sheet shows the record for failed adds too |
| Adaptive per-provider timeout, UI observes to completion | **Present** | probes now use the measured timeout (8–20 s) |
| Discrete Nuclei-style checks | **Gap** | **Done:** route-responds, results-parse, items-outnumber-categories, nonsense-control-empty, page-2-exists with evidence |

## §3 Persistence

| Requirement | Base branch | This branch |
|---|---|---|
| ScrapingConfig PK = provider ID (true upsert) | **Gap:** random UUID PK, delete-then-insert in a transaction | **Done:** `@PrimaryKey providerId`, REPLACE upsert; Room v3→v4 migration keeps existing rows; CI checks migration SQL against Room's exported schema (ports 6fee33e properly) |
| Full mapping + full config JSON in analysis metadata, not HTTP headers | **Gap:** capability report stored inside `headers` JSON; evidence partly in `apiEndpoints` | **Done:** `analysisMetadata` column (mapping, siteMap, recon, config, verification); capability report moved out of `headers` (ports 7b9cc23/9731477 intent) |
| Config fields: method, query param, hidden params, POST body, headers/cookies, pagination rule, classification, timeout | Template/POST body/selectors only | **Done** (new columns) |
| Provider: name, base URL, icon, enabled+visible | name/baseUrl/enabled present; icon never set | **Done:** icon from the page's own `<link rel*=icon>` |
| Re-analysis replaces only on successful verification | **Present** | kept (now a single upsert) |
| Round-trip after kill/restart | Room-backed but `fallbackToDestructiveMigration()` would wipe on any schema bump | real migration; destructive only from v1/v2 and on downgrade. **Device test #5 open.** |

## §4 Search

| Requirement | Base branch | This branch |
|---|---|---|
| Site's own request (route, method, params, hidden fields, headers, cookies) | Config template + POST body + headers; but guessed URL shapes always added | verified configs: exact mapped request only |
| Query-affinity display filter KEPT | **Present** | **unchanged**; cap raised 20→50; a classification gate runs after it |
| ≤2 refinement queries for NL queries with <5 matches, merge by URL, cap 50 | **Present** | kept; first page only |
| Never cached results as live | UI path already bypassed cache | kept |
| Bounded concurrency | Semaphore(4) but one coroutine per provider | **Done:** 6-worker pool draining a queue |
| Independent per-provider timeouts | fixed 25 s | **Done:** saved timeout (10–45 s) + 3 s |
| Streaming per provider | **Present** | + SEARCHING event per provider |
| Failure isolation | Exceptions caught | + StackOverflowError contained |
| Clean cancellation | Cancellation converted into a late "Cancelled" emission | **Done:** rethrown |
| Status pending/searching/done/failed | SEARCHING/RESULTS/EMPTY/FAILED/TIMED_OUT | **Done:** PENDING added; honest EMPTY no longer shown as FAILED |
| Off-main-thread, diffed lists, lazy thumbnails, bounded cache | IO dispatchers, keyed LazyColumn, Coil | Coil memory cache explicitly bounded (15%) |
| Result intelligence (a) config time / (b) display | (a) items>categories gate present; (b) title heuristics only | **Done:** classification learned at verify time, saved, applied at display |

### Follow-up audit (this branch, later commits)
| Area | Finding | Fix |
|---|---|---|
| JS-heavy sites at search time | The verifier could save a config proven only after WebView rendering (rendered route or the site's form in the WebView), but search fetched every verified config over raw HTTP and never rendered, so those providers came back empty. | `ScrapingConfig.renderMode` (RAW / RENDER / FORM, Room v4→v5). Search repeats the proven path. A RAW-verified route that yields nothing parseable while raw HTTP got no usable page or a JS shell is rendered in the WebView and extracted from the rendered DOM. Every fallback is logged and shown on the provider (`fallbackNote`). |
| Nonsense / filler | `SearchResultFilter.filterResultsForDisplay` fell back to "everything scoring ≥ 5" when nothing matched the query, so nonsense queries and "popular" filler reached the screen. | Fallback removed: no query-affinity match means an honest empty result. |
| Load More / per-provider refresh | `AggregatorRepository.searchProviderPage` called `searchProviderSmart` directly, which skipped the affinity filter and the classification gate on page 2+ and on retries. | Goes through the same gates (`ScrapingEngine.searchProviderPage` → `safeSearchProvider`). |
| ScrapingConfig save → read | Verified: one add path (`analyzeNewUrl` → `analyzeProvider` → `generateScrapingConfig`) upserts by provider ID only after verification. Search, timeout budget, classification gate and Load More all read the config by provider ID. | No change needed. Re-verifying a FORM config now runs as a form route. |

## §5 Pagination

| Requirement | Base branch | This branch |
|---|---|---|
| Load More fetches the real next page via the discovered rule | Re-ran the candidate list with page N (including guessed URLs), plus background auto-fill prefetching up to 2 pages | **Done:** verified providers use the saved rule; unproven pagination → "no next page" with reason; auto-fill prefetch removed |

## §7 Engineering rules
Small commits; head CI green per push (intermediate commits within a push were not individually built). No mock providers or fake data added.

## §8 Acceptance tests
All 8 require a real device; none can be executed from CI. See the PR body for the checklist.

## Ported from the parallel lines
- 6fee33e (config keyed by provider ID) → re-implemented as a real schema change + migration.
- 7b9cc23 / 9731477 (site map/config JSON in analysis metadata, not headers) → re-implemented as the `analysisMetadata` column + `AnalysisRecord`.
- fedcb4d (bounded async native page fetch) → re-implemented as `HttpTransport`.
- Superseded: the in-place repair of the prebuilt pair. Both libraries are now built from source in CI and no prebuilt `.so` is tracked (CI refuses one).
