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
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import com.aggregatorx.app.ui.viewmodel.VideoPreviewResult
import kotlinx.coroutines.launch

// Quick-tab sentinel IDs
private const val TAB_TOP    = "__TOP__"
private const val TAB_MY_AI  = "__MY_AI__"
private const val TAB_TOKENS = "__TOKENS__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val providerResults by viewModel.providerResults.collectAsState()
    val likedUrls       by viewModel.likedUrls.collectAsState()
    val isPaused        by viewModel.isDiscoveryPaused.collectAsState()
    val providerPages   by viewModel.providerPages.collectAsState()
    val context         = LocalContext.current
    val listState       = rememberLazyListState()
    val scope           = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(TAB_TOP) }

    val isScrolled = remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60
    }}
    val hasResults     = providerResults.isNotEmpty()
    val collapseHeader = hasResults && isScrolled.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── FIXED NEON SEARCH BAR ────────────────────────────────────
            NeonSearchBar(
                query         = uiState.query,
                onQueryChange = viewModel::updateQuery,
                onSearch      = viewModel::search,
                isLoading     = uiState.isSearching,
                isPaused      = isPaused,
                modifier      = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // ── QUICK-TABS ROW ───────────────────────────────────────────
            QuickTabsRow(
                activeTab       = activeTab,
                providerResults = providerResults,
                onTabSelected   = { tab ->
                    activeTab = tab
                    scope.launch { listState.animateScrollToItem(0) }
                }
            )

            // ── PAUSE BANNER ─────────────────────────────────────────────
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

            // ── STATS BAR ────────────────────────────────────────────────
            AnimatedVisibility(
                visible = (uiState.searchCompleted || uiState.isSearching) && !collapseHeader
            ) {
                SearchStatsBar(
                    totalResults        = uiState.totalResults,
                    successfulProviders = uiState.successfulProviders,
                    failedProviders     = uiState.failedProviders,
                    isSearching         = uiState.isSearching
                )
            }

            Spacer(Modifier.height(4.dp))

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
                        activeTab             = activeTab,
                        providerResults       = providerResults,
                        topResults            = uiState.aggregatedResults?.topResults ?: emptyList(),
                        listState             = listState,
                        likedUrls             = likedUrls,
                        providerPages         = providerPages,
                        onWatch               = { result -> viewModel.extractVideoUrl(result) },
                        onDownload            = { result ->
                            viewModel.downloadResult(result)
                            Toast.makeText(context, "Downloading: ${result.title}", Toast.LENGTH_SHORT).show()
                        },
                        onBrowser             = { result ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                        },
                        onInApp               = { result -> viewModel.extractVideoUrl(result) },
                        onLike                = { result -> viewModel.toggleLike(result) },
                        onNextPage            = { id -> viewModel.nextProviderPage(id) },
                        onPrevPage            = { id -> viewModel.prevProviderPage(id) },
                        onRefreshProvider     = { id -> viewModel.refreshProvider(id) },
                        onExtractVideoForPreview = { url -> viewModel.extractVideoForPreview(url) },
                        modifier              = Modifier.weight(1f)
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
    val PAGE_SIZE = 20
    val successProviders = providerResults.filter { it.success && it.results.isNotEmpty() }
    val failedProviders  = providerResults.filter { !it.success }

    // Filter results by active tab
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
        // Top results section (shown for TOP / MY AI tabs)
        if (activeTab == TAB_TOP || activeTab == TAB_MY_AI) {
            if (topResults.isNotEmpty()) {
                item(key = "top_header") {
                    SectionHeader("🏆 TOP RESULTS", topResults.size)
                }
                items(topResults.take(10), key = { "top_${it.url.hashCode()}" }) { result ->
                    ShieldedResultCard(
                        result = result,
                        isLiked = result.url in likedUrls,
                        onWatch = { onWatch(result) },
                        onDownload = { onDownload(result) },
                        onBrowser = { onBrowser(result) },
                        onInApp = { onInApp(result) },
                        onLike = { onLike(result) },
                        onExtractVideoForPreview = onExtractVideoForPreview
                    )
                }
                item(key = "top_div") {
                    HorizontalDivider(color = NeonGreen.copy(alpha = 0.15f),
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }

        // TOKENS tab — show token-bearing results
        if (activeTab == TAB_TOKENS) {
            val tokenResults = successProviders.flatMap { it.results }
                .filter { r -> r.url.contains("token=", ignoreCase = true)
                        || r.url.contains("key=", ignoreCase = true)
                        || r.url.contains("auth=", ignoreCase = true) }
            item(key = "tok_header") { SectionHeader("🔑 TOKEN RESULTS", tokenResults.size) }
            items(tokenResults, key = { "tok_${it.url.hashCode()}" }) { result ->
                ShieldedResultCard(
                    result = result, isLiked = result.url in likedUrls,
                    onWatch = { onWatch(result) }, onDownload = { onDownload(result) },
                    onBrowser = { onBrowser(result) }, onInApp = { onInApp(result) },
                    onLike = { onLike(result) },
                    onExtractVideoForPreview = onExtractVideoForPreview
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = DarkCard,
        border = BorderStroke(0.5.dp, NeonGreen.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // Title + quality badge
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
                        result.sourceTitle ?: result.url,
                        color = TextTertiary, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                // Quality badge
                val quality = result.quality ?: ""
                if (quality.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
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

            // Metadata row: duration, size, seeds
            val meta = buildList {
                result.duration?.let { add("⏱ $it") }
                result.fileSize?.let { add("💾 $it") }
                result.seeders?.let { if (it > 0) add("🌱 $it") }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    meta.forEach { m ->
                        Text(m, color = TextTertiary, fontSize = 10.sp)
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
                ActionBtn("▶", "Watch",    NeonGreen,   onWatch)
                ActionBtn("⬇", "Download", NeonGreenDim, onDownload)
                ActionBtn("↑", "Browser",  TextSecondary, onBrowser)
                ActionBtn("👁", "In App",  CyberPurple,  onInApp)
                // Favourite
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
        activeTab             = TAB_TOP,
        providerResults       = providerResults,
        topResults            = topResults,
        listState             = listState,
        likedUrls             = likedUrls,
        providerPages         = emptyMap(),
        onWatch               = { onResultClick(it) },
        onDownload            = { onDownload(it) },
        onBrowser             = { onOpenExternal(it) },
        onInApp               = { onResultClick(it) },
        onLike                = { onLike(it) },
        onNextPage            = {},
        onPrevPage            = {},
        onRefreshProvider     = {},
        onExtractVideoForPreview = onExtractVideoForPreview,
        modifier              = modifier
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
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
