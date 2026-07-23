# AggregatorX Full Repair Specification

## Objective

Make the Android app usable end to end for the current intended workflow:

1. A user adds one or more websites/URLs in Settings Analyzer.
2. The app saves those entries as enabled providers.
3. The Search screen shows enabled provider state and runs a fresh live search every time.
4. Results appear as soon as each provider returns parseable items, while slower providers continue in the background.
5. Result cards show title, URL, provider, thumbnail when available, and working actions for Watch, Download, Browser, In App, and Like.
6. Native TLS, model-assisted provider memory, WebView, video playback, download handling, and pagination coexist without blocking basic search/display.

The current reported failure is that after adding three providers and running a query, the app stays on a no-results/loading page and provider quick tabs do not appear under the search bar. The implementation must treat this as a full search pipeline failure until proven otherwise.

## Non-Negotiable Requirements

- Do not serve cached search results as live search results. Every Search button press must make live provider/network attempts.
- Provider memory and model learning may use persisted state, but that state must never substitute for live search results.
- Provider Analyzer must save usable, enabled provider records and reset the input field after success.
- Added providers must be visible in provider management UI and must be eligible for search by default.
- Search results must be displayed incrementally by completed provider.
- A slow, broken, blocked, empty, JavaScript-heavy, or malformed provider must not prevent other providers from displaying.
- The app must never sit in an endless searching state. Every search session needs bounded per-provider timeouts and a terminal UI state.
- Quick tabs/provider sections must reflect provider state clearly:
  - providers with results show provider tabs/sections,
  - providers searched but empty/failed show a visible failed/empty status,
  - the UI should not look like no providers exist when providers were searched.
- Native TLS shared libraries must remain packaged and 16 KB page-size compatible.
- The llama/model stack must not block search display. It may improve parsing/normalization asynchronously.
- WebView In App button must open the result URL inside the app and return to the exact results screen state.
- Watch button must make a best-effort direct playable media resolution and open an in-app player when playable media is found.
- Download button must download resolvable media or the result resource with clear progress/error state.
- Debug APK must build successfully and be installable on arm64-v8a Android 14/15 devices.

## Constraints

- Target ABI: `arm64-v8a`.
- Do not remove the Go `tls-client` native library or C++ JNI bridge.
- Do not remove existing Kotlin networking, scraper, analyzer, WebView, or media systems unless replacing a broken path with a working equivalent.
- Keep Android 16 KB page-size compatibility:
  - all packaged arm64 native `.so` files must have `LOAD` alignment `0x4000`,
  - APK must pass `zipalign -c -P 16 -v 4`.
- Keep search cache disabled for UI-initiated live searches.
- Do not add mock providers, mock results, placeholder parsing, or fake media URLs.
- Avoid blocking the UI thread with network, parsing, model inference, video resolution, or download work.
- Any model, TLS, headless, parser, or extractor failure must degrade gracefully and log a diagnostic rather than crash or stall search.
- The app currently uses Kotlin, Jetpack Compose, Room, Hilt, WorkManager, OkHttp/Jsoup, Media3, native CMake, Go shared library, and llama-android. Prefer fixing current integration before adding new dependencies.

## Current Architecture Summary

### App Startup

- `AggregatorXApp` initializes:
  - `HeadlessBrowserHelper`
  - `ProviderMemoryStore`
  - `ProviderPaginationManager`
  - `AnalysisHelper`
  - `ModelDownloadManager`
  - `LlamaService`

### Provider Add/Analyzer Flow

- UI: `SettingsScreen` + `SettingsViewModel`
- Repository: `AggregatorRepository.analyzeNewUrl(url)`
- Persistence:
  - `ProviderDao.insertProvider`
  - `SiteAnalysisDao.insertAnalysis`
  - `ScrapingConfigDao.insertConfig`
- Analysis:
  - `SiteAnalyzerEngine`
  - fallback `SiteAnalysis` when analysis times out/fails

### Search Flow

- UI: `SearchScreen`
- State: `SearchViewModel`
- Repository: `AggregatorRepository.searchAllProviders(query, pages)`
- Engine: `ScrapingEngine.searchAllProviders`
- Parsing:
  - `scrapeWithConfig`
  - `scrapeWithAnalysis`
  - `scrapeGeneric`
  - `scrapeWithSmartNavigation`
  - `scrapeWithTabCrawl`
  - `HeadlessBrowserHelper`
  - `UniversalFormatParser`
- Normalization:
  - `ResultNormalizer`
  - `ProviderMemoryStore`
  - `AnalysisHelper`

### Result Display

- `SearchScreen.ResultsFeed`
- `QuickTabsRow`
- `ProviderSectionHeader`
- `ProviderLoadMoreFooter`
- `ShieldedResultCard`
- `InlineThumbnailPreview`
- Provider quick tabs currently show only successful providers with non-empty result lists.

### Native TLS

- Go: `app/src/main/go/tlsclient/tlsclient.go`
- C++ JNI: `app/src/main/cpp/native-lib.cpp`
- Kotlin API: `TlsClient.kt`
- Status/metadata: `TlsFingerprintEngine.kt`
- Shared libs:
  - `libtlsclient.so`
  - `libtlsclientbridge.so`

### Model/Memory

- Download: `ModelDownloadManager`
- Foreground service: `LlamaService`
- Runtime: `LlamaRuntime`
- Native wrapper: `LlamaModelManager`
- Persistent provider memory:
  - Room DB under `ProviderMemoryDatabase`
  - JSON mirrors under app `filesDir/providers`
- Search display must not depend on model readiness.

### WebView and Media

- Full-screen result page: `WebViewActivity`
- Video playback: `VideoPlayerActivity`, `VideoPlayer.kt`, Media3
- Extraction:
  - `AdvancedVideoExtractorEngine`
  - `VideoExtractorEngine`
  - `VideoStreamResolver`
- Download:
  - `DownloadManager`

## Primary Failure Hypotheses To Verify

1. Providers are being saved but not enabled, not returned by `getEnabledProvidersSync`, or not visible due to Room/state collection timing.
2. Provider `baseUrl`/`url` normalization creates unusable search URLs for newly analyzed custom providers.
3. Generated `ScrapingConfig.searchUrlTemplate` defaults to `baseUrl/search?q={query}` even when the provider does not support that path.
4. `SiteAnalysis` fallback saves generic selectors and search templates that produce empty results for many sites.
5. `ScrapingEngine` returns empty/failed `ProviderSearchResults`, so quick tabs are hidden because tabs are filtered to successful non-empty providers.
6. Search UI does not clearly show searched-empty providers, making the app look like no providers were searched.
7. Model/normalizer/background learning may still consume time or fail silently, masking provider parsing failures.
8. Native TLS is present but not actually used by the standard provider fetch path, so sites that need browser-like TLS still fail under Jsoup/OkHttp fallback.
9. Headless/browser fallback may be too late, too slow, or not integrated into initial provider fetch for JavaScript-heavy providers.
10. Video action buttons may be wired, but media resolution may not reliably choose playable stream URLs before opening player/downloader.

## Implementation Plan

### Phase 1: Instrumentation and Observability

- Add structured search diagnostics for one search session:
  - provider count loaded from Room,
  - provider IDs/names/base URLs/enabled state,
  - selected search URL candidates,
  - transport used: Jsoup, native TLS, Cloudflare/TLS, headless, API/parser,
  - HTTP status/final URL/body length,
  - parser selector used,
  - raw candidate count,
  - normalized result count,
  - failure reason.
- Surface a concise per-provider status in UI:
  - Searching
  - Results count
  - Empty
  - Failed with short reason
  - Timed out
- Keep detailed diagnostics in logcat and optionally an in-app debug panel gated to development builds.

### Phase 2: Provider Persistence and Analyzer Repair

- Verify `SettingsViewModel.analyzeCustomUrl` calls `AggregatorRepository.analyzeNewUrl` and that the resulting provider is:
  - inserted once,
  - enabled,
  - visible through `getAllProviders`,
  - returned by `getEnabledProvidersSync`,
  - associated with latest `SiteAnalysis`,
  - associated with a usable `ScrapingConfig`.
- Repair `addProvider` duplicate detection:
  - match by normalized base URL as well as full URL,
  - re-enable existing disabled provider,
  - clear stale config/analysis only when needed.
- Make analyzer success resilient:
  - if deep analysis fails, still save provider with a broad but usable generic search strategy,
  - input clears only after provider is actually saved,
  - user-facing message includes provider name and enabled state.

### Phase 3: Search URL Discovery and Live Fetch Repair

- Rework provider search candidate generation so a custom provider tries multiple live URL patterns quickly:
  - discovered form action from analysis,
  - learned provider memory pagination/search fields,
  - common query routes such as `/search?q=`, `/?s=`, `/?q=`, `/search/{query}`,
  - homepage crawl when no search route works,
  - direct API endpoints when analyzer found APIs.
- Never rely on a single generated `baseUrl/search?q={query}` template.
- For each candidate, fetch with a bounded timeout and stop as soon as parseable results are found.
- Use native TLS transport for compatible document fetch attempts before expensive headless fallback:
  - expose a `fetchHtml(url, headers, profile)` path through `TlsClient`,
  - parse returned HTML with Jsoup,
  - preserve final URL and status diagnostics.
- Use headless/WebView fallback for JavaScript-heavy pages only after fast HTTP/TLS attempts fail or analysis marks `requiresJavaScript`.

### Phase 4: Result Parsing Repair

- Build a parser cascade for every fetched HTML/API response:
  - stored `ScrapingConfig` selectors,
  - latest `SiteAnalysis` selectors,
  - `UniversalFormatParser`,
  - schema.org/JSON-LD extraction,
  - OpenGraph/Twitter card extraction for pages with content links,
  - generic anchor/card/table/list extraction.
- Normalize all candidate results:
  - absolute URLs,
  - absolute thumbnails,
  - remove `javascript:`/data-only results,
  - de-duplicate by canonical URL,
  - keep title length and URL validity constraints.
- Do not discard all results solely because query relevance is weak. For custom sites, display structurally valid results with lower relevance if exact query matching fails.
- Return partial valid results immediately; continue pagination/load-more separately.

### Phase 5: Incremental UI Display Repair

- Change search state so every enabled provider appears under the search bar or provider status area once a search starts.
- Quick tabs should include:
  - providers with results,
  - currently searching providers,
  - searched providers with empty/failed status.
- `ResultsFeed` should display:
  - result cards for successful providers,
  - compact empty/failed provider rows for providers without results,
  - loading spinner only for providers still searching.
- Ensure `uiState.isSearching` resets after bounded completion even if all providers fail.
- Ensure `SearchStatsBar` reflects providers searched, successful, failed, and total results.

### Phase 6: Model and Provider Memory Integration Repair

- Keep model inference off the critical path for initial display.
- Use model/provider memory for:
  - background schema learning,
  - category corrections,
  - pagination pattern learning,
  - URL repair hints.
- If model is not downloaded, not ready, or inference fails:
  - search still works with deterministic parsing,
  - no provider is marked failed solely due to model state.
- Verify `ModelDownloadManager` checksum behavior and make failed model download non-blocking after the one-time loading screen is dismissed or failed.

### Phase 7: Pagination and 50 Result Behavior

- Initial search should display first valid provider results as soon as possible.
- After initial provider results display, background pagination may continue trying to reach 50 results per provider.
- If provider cannot supply 50 results, display whatever valid results are found.
- Load More must:
  - call provider-specific live pagination,
  - append without losing scroll position,
  - show inline status,
  - stop/hide when provider is exhausted.
- Provider refresh must:
  - bypass cache,
  - refresh one provider only,
  - preserve other provider sections.

### Phase 8: Result Actions Repair

- Watch:
  - resolve direct playable media via `AdvancedVideoExtractorEngine`, `VideoExtractorEngine`, then `VideoStreamResolver`,
  - open `VideoPlayerActivity` only when a playable URL is found,
  - display a clear error if no playable stream is found,
  - keep close/back button behavior returning to results.
- Download:
  - use resolved media URL when available,
  - otherwise download the original URL only if it is a valid downloadable resource,
  - show progress/failure state.
- Browser:
  - open external browser with result URL.
- In App:
  - open `WebViewActivity` full screen,
  - keep redirects internal,
  - preserve results screen state,
  - support fullscreen video,
  - log URL mismatch corrections through provider memory.
- Like:
  - persist liked result and update preference ranking without blocking UI.

### Phase 9: Native TLS and Build Verification

- Verify `TlsClient.isAvailable == true` on app startup where supported.
- Add a native TLS self-test path in diagnostics:
  - simple HTTPS GET,
  - status code,
  - body length,
  - error if unavailable.
- Confirm C++ JNI memory ownership remains correct:
  - Kotlin passes JSON string,
  - C++ releases input chars,
  - Go returns `C.CString`,
  - C++ frees returned pointer.
- Rebuild and verify:
  - `./gradlew :app:compileDebugKotlin`,
  - `./gradlew :app:assembleDebug`,
  - native libs packaged in `lib/arm64-v8a`,
  - `readelf` `LOAD` alignments are `0x4000`,
  - `zipalign -c -P 16 -v 4 app-debug.apk`.

### Phase 10: End-to-End Validation

- Test with at least three user-added providers.
- Test at least one ordinary static HTML site.
- Test at least one JavaScript-heavy site.
- Test at least one site with no search form, requiring homepage/category crawl.
- Test a query that should produce results and a query that should produce none.
- Verify:
  - providers appear as searched providers,
  - successful providers show result tabs/sections,
  - empty providers show empty state,
  - failed providers show failure state,
  - search ends without endless spinner,
  - WebView opens result URL,
  - Watch handles playable and non-playable URLs gracefully,
  - Download starts or reports a useful error,
  - thumbnails render when source exists.

## Success Criteria

- After adding three providers through Settings Analyzer, all three are visible as enabled providers.
- Pressing Search with a non-empty query starts a fresh live search and shows provider status within one second.
- The first provider with parseable results displays those results without waiting for all providers.
- If all providers fail or return empty, the app shows explicit empty/failed provider rows and exits loading state.
- Provider quick tabs/status row no longer disappears in a way that suggests providers were not searched.
- App does not crash or close during search, normalization, model learning, TLS fetch, WebView, video extraction, or download attempts.
- Native TLS library is available and verified in app diagnostics.
- Llama/model stack is initialized when possible but never blocks or prevents search result display.
- Debug APK builds and passes 16 KB compatibility checks.
- `main` and `fix-provider-search-results` are pushed after implementation and verification.

## Implementation Guardrails

- Implement in small, verifiable slices:
  1. diagnostics,
  2. provider persistence verification,
  3. search/fetch repair,
  4. parser/display repair,
  5. actions repair,
  6. build/device validation.
- Run `./gradlew :app:compileDebugKotlin` after Kotlin wiring changes.
- Run `./gradlew :app:assembleDebug` before final commit.
- Do not push until the debug APK builds and native compatibility checks pass.
- Avoid broad visual redesign unless required to expose provider status and errors.
- Preserve current app identity, theme, navigation, and existing user data schema unless a Room migration is required.
