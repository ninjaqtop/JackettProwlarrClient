@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

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
import com.aggregatorx.app.data.model.ProviderSearchStatus
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.media.DownloadState
import com.aggregatorx.app.engine.media.DownloadStatus
import com.aggregatorx.app.ui.VideoPlayerActivity
import com.aggregatorx.app.ui.WebViewActivity
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import com.aggregatorx.app.ui.viewmodel.VideoExtractionState
import com.aggregatorx.app.ui.viewmodel.VideoPreviewResult
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntOffset
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat

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
    val loadingProviderIds   by viewModel.loadingProviderIds.collectAsState()
    val tokenResults         by viewModel.tokenResults.collectAsState()
    val myAiResults          by viewModel.myAiResults.collectAsState()
    val videoExtractionState by viewModel.videoExtractionState.collectAsState()
    val downloads            by viewModel.downloads.collectAsState()
    val context              = LocalContext.current
    val listState            = rememberLazyListState()
    val scope                = rememberCoroutineScope()
    val activeDownload = downloads.values.lastOrNull {
        it.status == DownloadStatus.EXTRACTING ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.PAUSED
    }

    // When true the next extraction Success should launch VideoPlayerActivity
    // instead of showing the in-screen dialog (set by the "In App" button).
    var pendingInAppLaunch by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(TAB_TOP) }

    val hasSearchActivity = providerResults.any { it.status != ProviderSearchStatus.READY }
    val hasResults = providerResults.any { it.status == ProviderSearchStatus.RESULTS }

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
        targetValue   = if (headerVisible) {
            if (activeDownload != null) 205.dp else 152.dp
        } else 0.dp,
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

                hasSearchActivity -> {
                    ResultsFeed(
                        activeTab                = activeTab,
                        providerResults          = providerResults,
                        topResults               = uiState.aggregatedResults?.topResults ?: emptyList(),
                        myAiResults              = myAiResults,
                        tokenResults             = tokenResults,
                        listState                = listState,
                        likedUrls                = likedUrls,
                        providerPages            = providerPages,
                        loadingProviderIds        = loadingProviderIds,
                        onWatch                  = { result -> viewModel.extractVideoUrl(result) },
                        onDownload               = { result ->
                            viewModel.downloadResult(result)
                            Toast.makeText(context, "Downloading: ${result.title}", Toast.LENGTH_SHORT).show()
                        },
                        onBrowser                = { result ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                        },
                        onInApp                  = { result ->
                            val options = ActivityOptionsCompat.makeCustomAnimation(
                                context,
                                com.aggregatorx.app.R.anim.slide_in_right,
                                android.R.anim.fade_out
                            )
                            ContextCompat.startActivity(
                                context,
                                WebViewActivity.intent(context, result.url, result.providerId),
                                options.toBundle()
                            )
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
                activeTab          = activeTab,
                providerResults    = providerResults,
                loadingProviderIds = loadingProviderIds,
                onRefreshProvider  = { id -> viewModel.refreshProvider(id) },
                onTabSelected      = { tab ->
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
            AnimatedVisibility(visible = activeDownload != null) {
                activeDownload?.let { download ->
                    DownloadProgressRow(download = download, onCancel = { viewModel.cancelDownload(download.id) })
                }
            }
        }

    }
}

@Composable
private fun DownloadProgressRow(download: DownloadState, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCard)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                download.title,
                color = TextPrimary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (download.progress >= 0) "${download.progress}%" else "Downloading",
                color = CyberCyan,
                fontSize = 10.sp
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Cancel download", tint = TextTertiary, modifier = Modifier.size(14.dp))
            }
        }
        if (download.progress >= 0) {
            LinearProgressIndicator(
                progress = { download.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = CyberCyan,
                trackColor = DarkCardHover
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = CyberCyan)
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
    loadingProviderIds: Set<String>,
    onRefreshProvider: (String) -> Unit,
    onTabSelected: (String) -> Unit
) {
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
        items(providerResults, key = { it.provider.id }) { pr ->
            QuickTab(
                label     = pr.provider.name.take(12).uppercase(),
                tabId     = pr.provider.id.toString(),
                activeTab = activeTab,
                onSelect  = onTabSelected,
                count     = pr.results.size,
                isLoading = pr.status == ProviderSearchStatus.SEARCHING || pr.provider.id in loadingProviderIds,
                status    = pr.status,
                onRefresh = { onRefreshProvider(pr.provider.id) }
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
    count: Int = 0,
    isLoading: Boolean = false,
    status: ProviderSearchStatus? = null,
    onRefresh: (() -> Unit)? = null
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
            } else if (!isLoading && status != null) {
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                ProviderSearchStatus.READY -> TextTertiary
                                ProviderSearchStatus.EMPTY -> AccentOrange
                                ProviderSearchStatus.FAILED, ProviderSearchStatus.TIMED_OUT -> AccentRed
                                ProviderSearchStatus.RESULTS -> AccentGreen
                                ProviderSearchStatus.SEARCHING -> CyberCyan
                            }
                        )
                )
            }
            if (onRefresh != null) {
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isLoading) { onRefresh() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = textColor,
                            strokeWidth = 1.5.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh ${label.lowercase()}",
                            tint = textColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
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
    loadingProviderIds: Set<String>,
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
    val successProviders = providerResults.filter { it.status == ProviderSearchStatus.RESULTS && it.results.isNotEmpty() }
    val pendingOrEmptyProviders = providerResults.filter {
        it.status != ProviderSearchStatus.RESULTS && it.status != ProviderSearchStatus.READY
    }

    // For provider-specific tabs, filter to that provider only
    val displayProviders = when (activeTab) {
        TAB_TOP, TAB_MY_AI, TAB_TOKENS -> successProviders
        else -> successProviders.filter { it.provider.id == activeTab }
    }
    val visibleStatuses = when (activeTab) {
        TAB_TOP -> pendingOrEmptyProviders
        TAB_MY_AI, TAB_TOKENS -> emptyList()
        else -> pendingOrEmptyProviders.filter { it.provider.id == activeTab }
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

        if (visibleStatuses.isNotEmpty()) {
            item(key = "provider_status_header_$activeTab") {
                Text(
                    "PROVIDER STATUS",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            items(visibleStatuses, key = { "state_${it.provider.id}" }) { providerResult ->
                ProviderStatusCard(
                    providerResult = providerResult,
                    isRefreshing = providerResult.provider.id in loadingProviderIds,
                    onRefresh = { onRefreshProvider(providerResult.provider.id) }
                )
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
                    isLoading    = providerId in loadingProviderIds,
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

            item(key = "more_$providerId") {
                ProviderLoadMoreFooter(
                    canLoadMore = pr.hasMore || pr.results.size >= PAGE_SIZE,
                    isEnd = !pr.hasMore && pr.results.size < PAGE_SIZE,
                    isLoading = providerId in loadingProviderIds,
                    onLoadMore = { onNextPage(providerId) }
                )
            }

            item(key = "sp_$providerId") { Spacer(Modifier.height(8.dp)) }
        }

    }
}

@Composable
private fun ProviderStatusCard(
    providerResult: ProviderSearchResults,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val statusColor = when (providerResult.status) {
        ProviderSearchStatus.READY -> TextTertiary
        ProviderSearchStatus.SEARCHING -> CyberCyan
        ProviderSearchStatus.EMPTY -> AccentOrange
        ProviderSearchStatus.FAILED, ProviderSearchStatus.TIMED_OUT -> AccentRed
        ProviderSearchStatus.RESULTS -> AccentGreen
    }
    val statusText = when (providerResult.status) {
        ProviderSearchStatus.READY -> "Ready to search"
        ProviderSearchStatus.SEARCHING -> "Searching live site"
        ProviderSearchStatus.EMPTY -> providerResult.errorMessage ?: "No results for this query"
        ProviderSearchStatus.TIMED_OUT -> "Timed out; other providers continued"
        ProviderSearchStatus.FAILED -> providerResult.errorMessage ?: "Provider search failed"
        ProviderSearchStatus.RESULTS -> "${providerResult.results.size} results"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        color = DarkCard,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (providerResult.status == ProviderSearchStatus.SEARCHING || isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = statusColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    when (providerResult.status) {
                        ProviderSearchStatus.READY -> Icons.Default.Dns
                        ProviderSearchStatus.EMPTY -> Icons.Default.SearchOff
                        ProviderSearchStatus.FAILED, ProviderSearchStatus.TIMED_OUT -> Icons.Default.ErrorOutline
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    providerResult.provider.name,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(statusText, color = TextTertiary, fontSize = 10.sp, maxLines = 2)
            }
            if (providerResult.status != ProviderSearchStatus.SEARCHING) {
                IconButton(onClick = onRefresh, enabled = !isRefreshing, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "Retry provider", tint = statusColor, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun ProviderLoadMoreFooter(
    canLoadMore: Boolean,
    isEnd: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = NeonGreen, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading more", color = TextSecondary, fontSize = 10.sp)
            }
        } else if (canLoadMore) {
            OutlinedButton(
                onClick = onLoadMore,
                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
            ) {
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Load More", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else if (isEnd) {
            Text("End of results", color = TextTertiary, fontSize = 10.sp)
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
    isLoading: Boolean,
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
                if (isLoading) {
                    CircularProgressIndicator(color = NeonGreen, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, "Refresh", tint = NeonGreen.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp))
                }
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
                    refererUrl       = result.url,
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
        loadingProviderIds        = emptySet(),
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
