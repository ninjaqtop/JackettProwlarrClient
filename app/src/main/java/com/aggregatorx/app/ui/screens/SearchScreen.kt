package com.aggregatorx.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggregatorx.app.data.model.ProviderSearchResults
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.ui.VideoPlayerActivity
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import com.aggregatorx.app.ui.viewmodel.VideoExtractionState
import com.aggregatorx.app.ui.viewmodel.VideoPreviewResult
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntOffset

// Quick-tab sentinel IDs
private const val TAB_TOP    = "__TOP__"
private const val TAB_MY_AI  = "__MY_AI__"
private const val TAB_TOKENS = "__TOKENS__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState              by viewModel.uiState.collectAsState()
    val providerResults      by viewModel.providerResults.collectAsState()
    val likedUrls            by viewModel.likedUrls.collectAsState()
    val isPaused             by viewModel.isDiscoveryPaused.collectAsState()
    val providerPages        by viewModel.providerPages.collectAsState()
    val tokenResults         by viewModel.tokenResults.collectAsState()
    val myAiResults          by viewModel.myAiResults.collectAsState()
    val videoExtractionState by viewModel.videoExtractionState.collectAsState()
    val context              = LocalContext.current
    val listState            = rememberLazyListState()
    val scope                = rememberCoroutineScope()

    // When true the next extraction Success should launch VideoPlayerActivity
    // instead of showing the in-screen dialog (set by the "In App" button).
    var pendingInAppLaunch by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(TAB_TOP) }

    val hasResults = providerResults.isNotEmpty()

    // ── Scroll-direction tracking: hide header on scroll-down, show on scroll-up ──
    var prevFirstIndex   by remember { mutableStateOf(0) }
    var prevScrollOffset by remember { mutableStateOf(0) }
    var headerVisible    by remember { mutableStateOf(true) }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        if (hasResults) {
            when {
                idx > prevFirstIndex                  -> headerVisible = false
                idx < prevFirstIndex                  -> headerVisible = true
                off > prevScrollOffset + 12           -> headerVisible = false
                off < prevScrollOffset - 12           -> headerVisible = true
            }
        } else {
            headerVisible = true
        }
        prevFirstIndex   = idx
        prevScrollOffset = off
    }

    // Slide the header panel up/down
    val headerOffsetY by animateIntAsState(
        targetValue    = if (headerVisible) 0 else -400,
        animationSpec  = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label          = "header_slide"
    )
    // Shrink the top padding for the content area when header is hidden
    val contentTopPad by animateDpAsState(
        targetValue   = if (headerVisible) 152.dp else 0.dp,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label         = "content_top_pad"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── SCROLLABLE CONTENT ────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(contentTopPad))

            // ── CONTENT ──────────────────────────────────────────────────
            when {
                uiState.isSearching && providerResults.isEmpty() -> {
                    Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FuturisticLoader(size = 64.dp)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "SCANNING PROVIDERS...",
                                color = NeonGreen, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                            )
                        }
                    }
                }

                providerResults.isNotEmpty() -> {
                    ResultsFeed(
                        activeTab                = activeTab,
                        providerResults          = providerResults,
                        topResults               = uiState.aggregatedResults?.topResults ?: emptyList(),
                        myAiResults              = myAiResults,
                        tokenResults             = tokenResults,
                        listState                = listState,
                        likedUrls                = likedUrls,
                        providerPages            = providerPages,
                        onWatch                  = { result -> viewModel.extractVideoUrl(result) },
                        onDownload               = { result ->
                            viewModel.downloadResult(result)
                            Toast.makeText(context, "Downloading: ${result.title}", Toast.LENGTH_SHORT).show()
                        },
                        onBrowser                = { result ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                        },
                        onInApp                  = { result ->
                            // If extraction already succeeded for this result, launch immediately.
                            val currentState = viewModel.videoExtractionState.value
                            if (currentState is VideoExtractionState.Success &&
                                currentState.title == result.title
                            ) {
                                context.startActivity(
                                    VideoPlayerActivity.buildIntent(
                                        context  = context,
                                        videoUrl = currentState.videoUrl,
                                        title    = result.title,
                                        headers  = currentState.headers
                                    )
                                )
                                viewModel.resetVideoState()
                            } else {
                                // Kick off extraction; the LaunchedEffect below will
                                // auto-launch VideoPlayerActivity once it completes.
                                pendingInAppLaunch = true
                                viewModel.extractVideoUrl(result)
                                Toast.makeText(context, "Loading: ${result.title}…", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLike                   = { result -> viewModel.toggleLike(result) },
                        onNextPage               = { id -> viewModel.nextProviderPage(id) },
                        onPrevPage               = { id -> viewModel.prevProviderPage(id) },
                        onRefreshProvider        = { id -> viewModel.refreshProvider(id) },
                        onExtractVideoForPreview = { url -> viewModel.extractVideoForPreview(url) },
                        modifier                 = Modifier.weight(1f)
                    )
                }

                uiState.recentSearches.isNotEmpty() && !uiState.searchCompleted -> {
                    RecentSearches(
                        searches      = uiState.recentSearches,
                        onSearchClick = viewModel::searchFromHistory,
                        onClearAll    = viewModel::clearSearchHistory,
                        modifier      = Modifier.weight(1f)
                    )
                }

                else -> EmptySearchState(modifier = Modifier.fillMaxSize().weight(1f))
            }
        }

        uiState.error?.let { error ->
            Snackbar(
                modifier       = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = AccentRed.copy(alpha = 0.9f),
                contentColor   = TextPrimary,
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = TextPrimary)
                    }
                }
            ) { Text(error) }
        }

        // ── VIDEO EXTRACTION STATES ───────────────────────────────────────
        when (val vs = videoExtractionState) {

            // Extracting: show a non-blocking loading chip at the bottom
            is VideoExtractionState.Extracting -> {
                Surface(
                    modifier       = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp, start = 24.dp, end = 24.dp),
                    shape          = RoundedCornerShape(24.dp),
                    color          = DarkCard,
                    shadowElevation = 8.dp,
                    border         = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(18.dp),
                            color       = CyberCyan,
                            strokeWidth = 2.dp
                        )
                        Text(
                            "Extracting stream…",
                            color     = TextPrimary,
                            fontSize  = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = viewModel::resetVideoState) {
                            Text("Cancel", color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Success: either launch VideoPlayerActivity (In App) or show dialog (Watch)
            is VideoExtractionState.Success -> {
                if (pendingInAppLaunch) {
                    // "In App" path — launch the full-screen activity and clear state
                    LaunchedEffect(vs.videoUrl) {
                        pendingInAppLaunch = false
                        context.startActivity(
                            VideoPlayerActivity.buildIntent(
                                context  = context,
                                videoUrl = vs.videoUrl,
                                title    = vs.title,
                                headers  = vs.headers
                            )
                        )
                        viewModel.resetVideoState()
                    }
                } else {
                    // "Watch" path — show the in-screen VideoPlayerDialog
                    VideoPlayerDialog(
                        videoUrl       = vs.videoUrl,
                        title          = vs.title,
                        headers        = vs.headers.ifEmpty { null },
                        onDismiss      = viewModel::resetVideoState,
                        onDownload     = { viewModel.downloadVideoUrl(vs.videoUrl, vs.title) },
                        onOpenExternal = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vs.videoUrl)))
                            viewModel.resetVideoState()
                        }
                    )
                }
            }

            // Error: show a dismissible snackbar-style chip
            is VideoExtractionState.Error -> {
                Surface(
                    modifier       = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp, start = 16.dp, end = 16.dp),
                    shape          = RoundedCornerShape(12.dp),
                    color          = AccentRed.copy(alpha = 0.92f),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint   = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            vs.message,
                            color    = TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::resetVideoState) {
                            Text("✕", color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }

            else -> Unit // Idle — nothing to show
        }

        // ── FLOATING HEADER (slides up on scroll-down, back on scroll-up) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .offset { IntOffset(x = 0, y = headerOffsetY) }
                .background(DarkBackground.copy(alpha = 0.97f))
        ) {
            NeonSearchBar(
                query         = uiState.query,
                onQueryChange = viewModel::updateQuery,
                onSearch      = viewModel::search,
                isLoading     = uiState.isSearching,
                isPaused      = isPaused,
                modifier      = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            QuickTabsRow(
                activeTab       = activeTab,
                providerResults = providerResults,
                onTabSelected   = { tab ->
                    activeTab = tab
                    scope.launch { listState.animateScrollToItem(0) }
                }
            )
            AnimatedVisibility(visible = isPaused) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentOrange.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Pause, null, tint = AccentOrange, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "DISCOVERY PAUSED — feed frozen",
                        color = AccentOrange, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
            }
            AnimatedVisibility(visible = uiState.searchCompleted || uiState.isSearching) {
                SearchStatsBar(
                    totalResults        = uiState.totalResults,
                    successfulProviders = uiState.successfulProviders,
                    failedProviders     = uiState.failedProviders,
                    isSearching         = uiState.isSearching
                )
            }
        }

    }
}

@Composable
fun SearchStatsBar(
    totalResults: Int,
    successfulProviders: Int,
    failedProviders: Int,
    isSearching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(Icons.Default.Summarize, totalResults.toString(), "RESULTS", NeonGreen)
        StatItem(Icons.Default.CheckCircle, successfulProviders.toString(), "OK", AccentGreen)
        StatItem(Icons.Default.Error, failedProviders.toString(), "FAIL",
            if (failedProviders > 0) AccentRed else TextTertiary)
        if (isSearching) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = NeonGreen,
                strokeWidth = 2.dp
            )
        }
    }
}

// ── NEON SEARCH BAR ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeonSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isLoading: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "border_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "border_alpha"
    )
    val borderColor = if (isPaused) AccentOrange else NeonGreen

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor.copy(alpha = borderAlpha),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            },
        placeholder = {
            Text(
                "SEARCH TARGETS...",
                color = TextMuted, fontSize = 13.sp, letterSpacing = 1.sp
            )
        },
        leadingIcon = {
            Icon(Icons.Default.Search, null, tint = borderColor)
        },
        trailingIcon = {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = NeonGreen, strokeWidth = 2.dp
                )
            } else if (query.isNotEmpty()) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Send, "Search", tint = NeonGreen)
                }
            }
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { onSearch() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            cursorColor          = NeonGreen,
            focusedContainerColor   = DarkCard,
            unfocusedContainerColor = DarkCard
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ── QUICK-TABS ROW ────────────────────────────────────────────────────────────
@Composable
fun QuickTabsRow(
    activeTab: String,
    providerResults: List<ProviderSearchResults>,
    onTabSelected: (String) -> Unit
) {
    val successProviders = providerResults.filter { it.success && it.results.isNotEmpty() }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Fixed system tabs
        item { QuickTab("TOP",    TAB_TOP,    activeTab, onTabSelected) }
        item { QuickTab("MY AI",  TAB_MY_AI,  activeTab, onTabSelected) }
        item { QuickTab("TOKENS", TAB_TOKENS, activeTab, onTabSelected) }

        // Dynamic provider tabs
        items(successProviders) { pr ->
            QuickTab(
                label     = pr.provider.name.take(12).uppercase(),
                tabId     = pr.provider.id.toString(),
                activeTab = activeTab,
                onSelect  = onTabSelected,
                count     = pr.results.size
            )
        }
    }
}

@Composable
fun QuickTab(
    label: String,
    tabId: String,
    activeTab: String,
    onSelect: (String) -> Unit,
    count: Int = 0
) {
    val selected = activeTab == tabId
    val bg       = if (selected) NeonGreen.copy(alpha = 0.15f) else DarkCard
    val border   = if (selected) NeonGreen else DarkCardHover
    val textColor = if (selected) NeonGreen else TextTertiary

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable { onSelect(tabId) },
        color = bg,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = textColor, fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 0.8.sp)
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text("$count", color = textColor.copy(alpha = 0.7f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = TextTertiary, fontSize = 9.sp, letterSpacing = 0.5.sp)
    }
}

// ── RESULTS FEED ─────────────────────────────────────────────────────────────
@Composable
fun ResultsFeed(
    activeTab: String,
    providerResults: List<ProviderSearchResults>,
    topResults: List<SearchResult>,
    myAiResults: List<SearchResult>,
    tokenResults: List<SearchResult>,
    listState: LazyListState,
    likedUrls: Set<String>,
    providerPages: Map<String, Int>,
    onWatch: (SearchResult) -> Unit,
    onDownload: (SearchResult) -> Unit,
    onBrowser: (SearchResult) -> Unit,
    onInApp: (SearchResult) -> Unit,
    onLike: (SearchResult) -> Unit,
    onNextPage: (String) -> Unit,
    onPrevPage: (String) -> Unit,
    onRefreshProvider: (String) -> Unit,
    onExtractVideoForPreview: (suspend (String) -> VideoPreviewResult?)? = null,
    modifier: Modifier = Modifier
) {
    val PAGE_SIZE = 50
    val successProviders = providerResults.filter { it.success && it.results.isNotEmpty() }
    val failedProviders  = providerResults.filter { !it.success }

    // For provider-specific tabs, filter to that provider only
    val displayProviders = when (activeTab) {
        TAB_TOP, TAB_MY_AI, TAB_TOKENS -> successProviders
        else -> successProviders.filter { it.provider.id.toString() == activeTab }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── TOP tab ──────────────────────────────────────────────────────
        if (activeTab == TAB_TOP && topResults.isNotEmpty()) {
            item(key = "top_header") { SectionHeader("🏆 TOP RESULTS", topResults.size) }
            items(topResults.take(10), key = { "top_${it.url.hashCode()}" }) { result ->
                ShieldedResultCard(
                    result = result, isLiked = result.url in likedUrls,
                    onWatch = { onWatch(result) }, onDownload = { onDownload(result) },
                    onBrowser = { onBrowser(result) }, onInApp = { onInApp(result) },
                    onLike = { onLike(result) }, onExtractVideoForPreview = onExtractVideoForPreview
                )
            }
            item(key = "top_div") {
                HorizontalDivider(color = NeonGreen.copy(alpha = 0.15f),
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        // ── MY AI tab — preference-ranked results ─────────────────────────
        if (activeTab == TAB_MY_AI) {
            if (myAiResults.isNotEmpty()) {
                item(key = "ai_header") { SectionHeader("🤖 MY AI — PREFERENCE RANKED", myAiResults.size) }
                item(key = "ai_hint") {
                    Text(
                        "Results ranked by your liked content profile",
                        color = TextTertiary, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                items(myAiResults, key = { "ai_${it.url.hashCode()}" }) { result ->
                    ShieldedResultCard(
                        result = result, isLiked = result.url in likedUrls,
                        onWatch = { onWatch(result) }, onDownload = { onDownload(result) },
                        onBrowser = { onBrowser(result) }, onInApp = { onInApp(result) },
                        onLike = { onLike(result) }, onExtractVideoForPreview = onExtractVideoForPreview
                    )
                }
            } else {
                item(key = "ai_empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🤖", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No AI profile yet", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Like results with ♥ to train your preference profile",
                            color = TextTertiary, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // ── TOKENS tab — token-injection discovered results ───────────────
        if (activeTab == TAB_TOKENS) {
            if (tokenResults.isNotEmpty()) {
                item(key = "tok_header") { SectionHeader("🔑 TOKEN-DISCOVERED RESULTS", tokenResults.size) }
                item(key = "tok_hint") {
                    Text(
                        "Found via automated token injection, replay & mutation",
                        color = TextTertiary, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                items(tokenResults, key = { "tok_${it.url.hashCode()}" }) { result ->
                    ShieldedResultCard(
                        result = result, isLiked = result.url in likedUrls,
                        onWatch = { onWatch(result) }, onDownload = { onDownload(result) },
                        onBrowser = { onBrowser(result) }, onInApp = { onInApp(result) },
                        onLike = { onLike(result) }, onExtractVideoForPreview = onExtractVideoForPreview
                    )
                }
            } else {
                item(key = "tok_empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔑", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No token results yet", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Token discovery runs automatically after each search",
                            color = TextTertiary, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // Provider sections with pagination
        displayProviders.forEach { pr ->
            val providerId  = pr.provider.id.toString()
            val currentPage = providerPages[providerId] ?: 0
            val pageResults = pr.results.drop(currentPage * PAGE_SIZE).take(PAGE_SIZE)
            val totalPages  = (pr.results.size + PAGE_SIZE - 1) / PAGE_SIZE

            item(key = "hdr_$providerId") {
                ProviderSectionHeader(
                    name         = pr.provider.name,
                    resultCount  = pr.results.size,
                    currentPage  = currentPage,
                    totalPages   = totalPages,
                    onPrev       = { onPrevPage(providerId) },
                    onNext       = { onNextPage(providerId) },
                    onRefresh    = { onRefreshProvider(providerId) }
                )
            }

            items(pageResults, key = { "${providerId}_${it.url.hashCode()}" }) { result ->
                ShieldedResultCard(
                    result = result, isLiked = result.url in likedUrls,
                    onWatch = { onWatch(result) }, onDownload = { onDownload(result) },
                    onBrowser = { onBrowser(result) }, onInApp = { onInApp(result) },
                    onLike = { onLike(result) },
                    onExtractVideoForPreview = onExtractVideoForPreview,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            item(key = "sp_$providerId") { Spacer(Modifier.height(8.dp)) }
        }

        // Failed providers
        if (failedProviders.isNotEmpty()) {
            item(key = "fail_hdr") {
                Text("FAILED PROVIDERS", color = AccentRed, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            item(key = "fail_list") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    failedProviders.forEach { fp ->
                        Surface(shape = RoundedCornerShape(12.dp), color = DarkCard) {
                            Text(fp.provider.name, color = AccentRed.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── PROVIDER SECTION HEADER with pagination ───────────────────────────────────
@Composable
fun ProviderSectionHeader(
    name: String,
    resultCount: Int,
    currentPage: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Provider name + count
        Column(modifier = Modifier.weight(1f)) {
            Text(name.uppercase(), color = NeonGreen, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$resultCount results", color = TextTertiary, fontSize = 9.sp)
        }

        // Pagination controls — top-right of section header
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Refresh
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = NeonGreen.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp))
            }
            // Prev
            IconButton(
                onClick = onPrev,
                enabled = currentPage > 0,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, "<",
                    tint = if (currentPage > 0) NeonGreen else TextMuted,
                    modifier = Modifier.size(16.dp))
            }
            // Page indicator
            Text(
                "${currentPage + 1}/$totalPages",
                color = TextSecondary, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            // Next
            IconButton(
                onClick = onNext,
                enabled = currentPage < totalPages - 1,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.ChevronRight, ">",
                    tint = if (currentPage < totalPages - 1) NeonGreen else TextMuted,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── SHIELDED RESULT CARD ──────────────────────────────────────────────────────
@Composable
fun ShieldedResultCard(
    result: SearchResult,
    isLiked: Boolean,
    onWatch: () -> Unit,
    onDownload: () -> Unit,
    onBrowser: () -> Unit,
    onInApp: () -> Unit,
    onLike: () -> Unit,
    onExtractVideoForPreview: (suspend (String) -> VideoPreviewResult?)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Inline video player state
    var inlineVideoUrl   by remember(result.url) { mutableStateOf<String?>(null) }
    var inlineHeaders    by remember(result.url) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isExtracting     by remember(result.url) { mutableStateOf(false) }
    var showInlinePlayer by remember(result.url) { mutableStateOf(false) }

    // Thumbnail tap → full preview dialog
    var showThumbnailPreview by remember(result.url) { mutableStateOf(false) }

    // In-app browser (keeps user on results screen)
    var showInAppBrowser by remember(result.url) { mutableStateOf(false) }

    // Helper: extract video then show inline player
    fun extractAndPlayInline() {
        if (inlineVideoUrl != null) {
            showInlinePlayer = true
            return
        }
        if (isExtracting) return
        isExtracting = true
        scope.launch {
            val preview = onExtractVideoForPreview?.invoke(result.url)
            inlineVideoUrl = preview?.videoUrl
            inlineHeaders  = preview?.headers ?: emptyMap()
            isExtracting   = false
            if (!inlineVideoUrl.isNullOrEmpty()) showInlinePlayer = true
            else onWatch()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = DarkCard,
        border = BorderStroke(0.5.dp, NeonGreen.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── THUMBNAIL + TITLE ROW ─────────────────────────────────────
            Row(verticalAlignment = Alignment.Top) {

                // Thumbnail — always shown (placeholder when no URL available)
                InlineThumbnailPreview(
                    thumbnailUrl     = result.thumbnailUrl,
                    duration         = result.duration,
                    isExtracting     = isExtracting,
                    onTapPreview     = { showThumbnailPreview = true },
                    onHoldFullscreen = { extractAndPlayInline() },
                    modifier = Modifier
                        .width(120.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))

                // Title + provider + quality badge
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                result.title,
                                color = TextPrimary, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                result.providerName.ifEmpty { result.url },
                                color = TextTertiary, fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        val quality = result.quality ?: ""
                        if (quality.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = getQualityColor(quality).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, getQualityColor(quality))
                            ) {
                                Text(
                                    quality.uppercase(),
                                    color = getQualityColor(quality),
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    val meta = buildList {
                        result.duration?.let { add("⏱ $it") }
                        result.size?.let { add("💾 $it") }
                        result.seeders?.let { if (it > 0) add("🌱 $it") }
                    }
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            meta.forEach { m -> Text(m, color = TextTertiary, fontSize = 10.sp) }
                        }
                    }
                }
            }

            // ── INLINE VIDEO PLAYER ───────────────────────────────────────
            AnimatedVisibility(
                visible = showInlinePlayer && !inlineVideoUrl.isNullOrEmpty(),
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                val url = inlineVideoUrl ?: ""
                if (url.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        InlineExoPlayerView(
                            videoUrl = url,
                            headers  = inlineHeaders,
                            modifier = Modifier.fillMaxSize()
                        )
                        IconButton(
                            onClick = { showInlinePlayer = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White,
                                modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = onWatch,
                            modifier = Modifier
                                .align(Alignment.TopStart).padding(4.dp).size(28.dp)
                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Fullscreen, "Fullscreen", tint = CyberCyan,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = NeonGreen.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            // ── ACTION ROW ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionBtn("▶", "Watch",    NeonGreen,    onWatch)
                ActionBtn("⬇", "Download", NeonGreenDim, onDownload)
                ActionBtn("↑", "Browser",  TextSecondary, onBrowser)
                ActionBtn("👁", "In App",  CyberPurple,  onInApp)
                IconButton(onClick = onLike, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "Favourite",
                        tint = if (isLiked) AccentRed else TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // Thumbnail preview dialog (tap on thumbnail)
    if (showThumbnailPreview) {
        ThumbnailPreviewDialog(
            thumbnailUrl = result.thumbnailUrl,
            title        = result.title,
            duration     = result.duration,
            onDismiss    = { showThumbnailPreview = false },
            onWatch      = { showThumbnailPreview = false; onWatch() },
            onBrowser    = { showThumbnailPreview = false; showInAppBrowser = true }
        )
    }

    // In-app browser dialog — stays on results screen
    if (showInAppBrowser) {
        InAppBrowserDialog(
            url      = result.url,
            title    = result.title,
            onDismiss = { showInAppBrowser = false }
        )
    }
}

@Composable
private fun ActionBtn(
    emoji: String,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(emoji, fontSize = 13.sp)
        Spacer(Modifier.width(3.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Lightweight embedded ExoPlayer for inline card preview.
 * Supports HLS, DASH, progressive, and smooth-streaming with custom headers.
 * Auto-retries with alternate format on playback error.
 */
@Composable
private fun InlineExoPlayerView(
    videoUrl: String,
    headers: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val context     = androidx.compose.ui.platform.LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var retryCount  by remember { mutableStateOf(0) }
    var formatIndex by remember { mutableStateOf(0) }  // 0=auto-detect, 1=HLS, 2=DASH, 3=progressive

    val httpFactory = remember(videoUrl, headers) {
        val ua = headers["User-Agent"] ?: com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
    }

    val exoPlayer = remember(videoUrl, retryCount, formatIndex) {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 30_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(4 * 1024 * 1024)
            .build()

        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                val uri   = android.net.Uri.parse(videoUrl)
                val lower = videoUrl.lowercase()
                val source = when {
                    // Explicit format override after error
                    formatIndex == 1 ->
                        androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    formatIndex == 2 ->
                        androidx.media3.exoplayer.dash.DashMediaSource.Factory(httpFactory)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    formatIndex == 3 ->
                        androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    // Auto-detect from URL
                    lower.contains(".m3u8") || lower.contains("/hls/") ||
                    lower.contains("master.m3u8") || lower.contains("index.m3u8") ->
                        androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    lower.contains(".mpd") || lower.contains("/dash/") ||
                    lower.contains("manifest.mpd") ->
                        androidx.media3.exoplayer.dash.DashMediaSource.Factory(httpFactory)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    lower.contains(".mp4") || lower.contains(".webm") ||
                    lower.contains(".mkv") || lower.contains(".m4v") ||
                    lower.contains(".mov") || lower.contains(".avi") ||
                    lower.contains(".ts") || lower.contains(".flv") ->
                        androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                    else ->
                        // Unknown — try progressive first, listener will retry HLS/DASH on error
                        androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                }
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == androidx.media3.common.Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Cycle through formats on error: progressive → HLS → DASH → give up
                if (formatIndex < 3) { formatIndex++; retryCount++ }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player        = exoPlayer
                    useController = false
                    layoutParams  = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isBuffering) {
            CircularProgressIndicator(
                color       = CyberCyan,
                modifier    = Modifier.size(32.dp).align(Alignment.Center),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = AccentYellow, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.weight(1f))
        Text("$count", color = TextTertiary, fontSize = 10.sp)
    }
}

// ── LEGACY ALIAS kept so existing callers in Components.kt still compile ──────
@Composable
fun ProviderResultsList(
    providerResults: List<ProviderSearchResults>,
    topResults: List<SearchResult>,
    listState: LazyListState,
    onResultClick: (SearchResult) -> Unit,
    onDownload: (SearchResult) -> Unit = {},
    onOpenExternal: (SearchResult) -> Unit = {},
    onLike: (SearchResult) -> Unit = {},
    likedUrls: Set<String> = emptySet(),
    onExtractVideoUrl: (suspend (String) -> String?)? = null,
    onExtractVideoForPreview: (suspend (String) -> VideoPreviewResult?)? = null,
    modifier: Modifier = Modifier
) {
    ResultsFeed(
        activeTab                = TAB_TOP,
        providerResults          = providerResults,
        topResults               = topResults,
        myAiResults              = emptyList(),
        tokenResults             = emptyList(),
        listState                = listState,
        likedUrls                = likedUrls,
        providerPages            = emptyMap(),
        onWatch                  = { onResultClick(it) },
        onDownload               = { onDownload(it) },
        onBrowser                = { onOpenExternal(it) },
        onInApp                  = { onResultClick(it) },
        onLike                   = { onLike(it) },
        onNextPage               = {},
        onPrevPage               = {},
        onRefreshProvider        = {},
        onExtractVideoForPreview = onExtractVideoForPreview,
        modifier                 = modifier
    )
}

@Composable
fun ProviderTabChip(
    name: String,
    count: Int,
    isSelected: Boolean,
    isError: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isError -> AccentRed.copy(alpha = 0.2f)
        isSelected -> CyberCyan.copy(alpha = 0.3f)
        else -> DarkCard
    }
    val textColor = when {
        isError -> AccentRed
        isSelected -> CyberCyan
        else -> TextSecondary
    }
    
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = name,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            if (count > 0) {
                Surface(
                    shape = CircleShape,
                    color = textColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = count.toString(),
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FailedProviderCard(
    providerName: String,
    errorMessage: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentRed.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = AccentRed.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun RecentSearches(
    searches: List<com.aggregatorx.app.data.model.SearchHistoryEntry>,
    onSearchClick: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Searches",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            TextButton(onClick = onClearAll) {
                Text("Clear All", color = CyberCyan)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(searches) { search ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearchClick(search.query) },
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = search.query,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${search.resultCount} results from ${search.providersSearched} providers",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,


                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySearchState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = TextTertiary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Start searching",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter a search term to find content\nacross all your configured providers",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Feature highlights
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FeatureChip(
                icon = Icons.Default.Speed,
                text = "Fast",
                color = AccentGreen
            )
            FeatureChip(
                icon = Icons.Default.Hub,
                text = "Multi-provider",
                color = CyberCyan
            )
            FeatureChip(
                icon = Icons.Default.AutoAwesome,
                text = "Smart Ranking",
                color = CyberPurple
            )
        }
    }
}

@Composable
fun FeatureChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
