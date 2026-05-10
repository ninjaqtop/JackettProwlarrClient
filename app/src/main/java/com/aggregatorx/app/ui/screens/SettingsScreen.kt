
package com.aggregatorx.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggregatorx.app.data.model.SiteAnalysis
import com.aggregatorx.app.ui.components.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SettingsViewModel
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.updateDownloadDirectory(uri.toString())
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    val providers by viewModel.providers.collectAsState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Add Custom URL Section
            item {
                SettingsCard(
                    title = "Add Custom Provider",
                    subtitle = "Analyze any website to add as a provider",
                    icon = Icons.Default.Add
                ) {
                    Column {
                        // URL Input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurfaceVariant)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(NeonGreen.copy(alpha = 0.5f), NeonGreenDim.copy(alpha = 0.3f))
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            BasicTextField(
                                value = uiState.customUrl,
                                onValueChange = viewModel::updateCustomUrl,
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(
                                    color = TextPrimary,
                                    fontSize = 16.sp
                                ),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (uiState.customUrl.isEmpty()) {
                                            Text(
                                                text = "Enter website URL...",
                                                color = TextTertiary,
                                                fontSize = 16.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Analyze Button
                        Button(
                            onClick = viewModel::analyzeCustomUrl,
                            enabled = uiState.customUrl.isNotEmpty() && !uiState.isAnalyzing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = DarkBackground,
                                disabledContainerColor = CyberCyan.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = DarkBackground,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyzing...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyze & Add Provider", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            // Refresh All Section
            item {
                SettingsCard(
                    title = "Refresh All Providers",
                    subtitle = "Re-analyze all configured providers at once",
                    icon = Icons.Default.Refresh
                ) {
                    Column {
                        Text(
                            text = "This will re-analyze ${providers.size} providers to update their configurations and detect any changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Progress indicator
                        AnimatedVisibility(visible = uiState.isRefreshingAll) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { uiState.refreshProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = CyberCyan,
                                    trackColor = DarkSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        
                        Button(
                            onClick = viewModel::refreshAllProviders,
                            enabled = providers.isNotEmpty() && !uiState.isRefreshingAll,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberPurple,
                                contentColor = TextPrimary,
                                disabledContainerColor = CyberPurple.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.isRefreshingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = TextPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refreshing...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refresh All (${providers.size})", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            // Last Analysis Results
            uiState.lastAnalysis?.let { analysis ->
                item {
                    AnalysisResultCard(
                        analysis = analysis,
                        providerName = uiState.lastProvider?.name ?: "Unknown",
                        onViewDetails = { viewModel.showAnalysisDetails(analysis) }
                    )
                }
            }
            
            // Advanced Site Analyzer capabilities
            item {
                SettingsCard(
                    title = "Advanced Site Analyzer",
                    subtitle = "Full-spectrum offensive analysis engine",
                    icon = Icons.Default.BugReport
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnalysisFeatureRow(Icons.Default.Security, "Security Analysis",
                            "SSL/TLS grade, CSP bypass vectors, HSTS, X-Frame-Options, SameSite cookie flags, CORS misconfig", AccentGreen)
                        AnalysisFeatureRow(Icons.Default.AccountTree, "DOM Structure",
                            "Element count, shadow DOM, iframe depth, form fields, hidden inputs, script sources", NeonGreen)
                        AnalysisFeatureRow(Icons.Default.Pattern, "Pattern Detection",
                            "Search forms, result lists, pagination, video players, lazy-load triggers, infinite scroll", NeonGreenDim)
                        AnalysisFeatureRow(Icons.Default.VideoLibrary, "Media Detection",
                            "HLS/DASH/MP4 streams, embedded players, CDN origins, DRM type, thumbnail URLs", CyberPurple)
                        AnalysisFeatureRow(Icons.Default.Api, "API & Endpoint Discovery",
                            "REST, GraphQL, JSON feeds, WebSocket endpoints, hidden API routes, versioned paths", AccentYellow)
                        AnalysisFeatureRow(Icons.Default.Speed, "Performance Metrics",
                            "TTFB, resource count, page weight, render-blocking assets, CDN detection", AccentOrange)
                        AnalysisFeatureRow(Icons.Default.VpnKey, "Token & Auth Harvesting",
                            "Bearer tokens, API keys, CSRF tokens, session IDs, Base64/Base44 blobs in JS bundles", AccentRed)
                        AnalysisFeatureRow(Icons.Default.Shield, "WAF Fingerprinting",
                            "Cloudflare, Akamai, Imperva, Sucuri detection — bypass strategy auto-selected", NeonGreen)
                        AnalysisFeatureRow(Icons.Default.Code, "JS Bundle Analysis",
                            "Webpack chunk extraction, obfuscated endpoint recovery, source-map parsing", NeonGreenDim)
                        AnalysisFeatureRow(Icons.Default.NetworkCheck, "Network Topology",
                            "IP geolocation, ASN, CDN provider, reverse proxy detection, DNS record enumeration", CyberPurple)
                        AnalysisFeatureRow(Icons.Default.Fingerprint, "Browser Fingerprint Evasion",
                            "Canvas noise, WebGL spoof, navigator override, TLS JA3 randomisation", AccentYellow)
                        AnalysisFeatureRow(Icons.Default.Visibility, "OCR Vision Scan",
                            "ML Kit OCR on thumbnails — extracts hidden text, watermarks, quality labels", AccentGreen)
                    }
                }
            }
            
            // About Section
            item {
                SettingsCard(
                    title = "About AggregatorX",
                    subtitle = "Version 1.0.0 — Shielded Build",
                    icon = Icons.Default.Info
                ) {
                    Column {
                        Text(
                            text = "AggregatorX Shielded — unrestricted multi-provider search aggregator with full-spectrum offensive site analysis, token harvesting, ML Kit vision, and WAF bypass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AboutStat(value = "∞", label = "Providers")
                            AboutStat(value = "AI", label = "Powered")
                            AboutStat(value = "100%", label = "Offline")
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
        
        // Analysis Details Bottom Sheet
        if (uiState.showAnalysisDetails && uiState.lastAnalysis != null) {
            AnalysisDetailsSheet(
                analysis = uiState.lastAnalysis!!,
                onDismiss = viewModel::hideAnalysisDetails
            )
        }
        
        // Messages
        uiState.message?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                containerColor = AccentGreen.copy(alpha = 0.9f),
                contentColor = DarkBackground,
                action = {
                    TextButton(onClick = viewModel::clearMessage) {
                        Text("OK", color = DarkBackground)
                    }
                }
            ) {
                Text(message)
            }
        }
        
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                containerColor = AccentRed.copy(alpha = 0.9f),
                contentColor = TextPrimary,
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = TextPrimary)
                    }
                }
            ) {
                Text(error)
            }
        }
    }

@Composable
fun SettingsCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(CyberCyan.copy(alpha = 0.2f), CyberBlue.copy(alpha = 0.2f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            content()
        }
    }
}

@Composable
fun AnalysisResultCard(
    analysis: SiteAnalysis,
    providerName: String,
    onViewDetails: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(AccentGreen.copy(alpha = 0.5f), CyberCyan.copy(alpha = 0.3f))
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Analysis Complete",
                            style = MaterialTheme.typography.titleSmall,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = providerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                
                SecurityScoreIndicator(score = analysis.securityScore)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Quick stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AnalysisQuickStat(
                    value = analysis.totalElements.toString(),
                    label = "Elements",
                    color = CyberCyan
                )
                AnalysisQuickStat(
                    value = analysis.linkCount.toString(),
                    label = "Links",
                    color = CyberBlue
                )
                AnalysisQuickStat(
                    value = "${analysis.loadTime}ms",
                    label = "Load Time",
                    color = AccentGreen
                )
                AnalysisQuickStat(
                    value = if (analysis.hasAPI) "Yes" else "No",
                    label = "Has API",
                    color = if (analysis.hasAPI) AccentYellow else TextTertiary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = onViewDetails,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = SolidColor(CyberCyan.copy(alpha = 0.5f))
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Full Analysis")
            }
        }
    }
}

@Composable
fun AnalysisQuickStat(
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

@Composable
fun AnalysisFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
fun AboutStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = CyberCyan,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisDetailsSheet(
    analysis: SiteAnalysis,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        contentColor = TextPrimary
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Full Analysis Report",
                    style = MaterialTheme.typography.headlineSmall,
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = analysis.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            
            // Security Section
            item {
                AnalysisSection(title = "🔒 Security", color = AccentGreen) {
                    AnalysisDetailRow("SSL/TLS", if (analysis.hasSSL) "✓ Enabled" else "✗ Disabled")
                    AnalysisDetailRow("CSP Header", if (analysis.hasCSP) "✓ Present" else "✗ Missing")
                    AnalysisDetailRow("X-Frame-Options", if (analysis.hasXFrameOptions) "✓ Set" else "✗ Not Set")
                    AnalysisDetailRow("HSTS", if (analysis.hasHSTS) "✓ Enabled" else "✗ Disabled")
                    AnalysisDetailRow("Security Score", "${analysis.securityScore.toInt()}/100")
                }
            }
            
            // Structure Section
            item {
                AnalysisSection(title = "🏗️ DOM Structure", color = CyberCyan) {
                    AnalysisDetailRow("Total Elements", analysis.totalElements.toString())
                    AnalysisDetailRow("Unique Tags", analysis.uniqueTags.toString())
                    AnalysisDetailRow("DOM Depth", analysis.domDepth.toString())
                    AnalysisDetailRow("Forms", analysis.formCount.toString())
                    AnalysisDetailRow("Links", analysis.linkCount.toString())
                    AnalysisDetailRow("Scripts", analysis.scriptCount.toString())
                    AnalysisDetailRow("Images", analysis.imageCount.toString())
                    AnalysisDetailRow("Videos", analysis.videoCount.toString())
                    AnalysisDetailRow("iFrames", analysis.iframeCount.toString())
                }
            }
            
            // Detected Selectors
            item {
                AnalysisSection(title = "🎯 Detected Selectors", color = CyberBlue) {
                    analysis.searchFormSelector?.let { AnalysisDetailRow("Search Form", it) }
                    analysis.searchInputSelector?.let { AnalysisDetailRow("Search Input", it) }
                    analysis.resultContainerSelector?.let { AnalysisDetailRow("Results Container", it) }
                    analysis.resultItemSelector?.let { AnalysisDetailRow("Result Item", it) }
                    analysis.paginationSelector?.let { AnalysisDetailRow("Pagination", it) }
                    analysis.titleSelector?.let { AnalysisDetailRow("Title", it) }
                    analysis.thumbnailSelector?.let { AnalysisDetailRow("Thumbnail", it) }
                }
            }
            
            // Media Detection
            item {
                AnalysisSection(title = "🎬 Media Detection", color = CyberPurple) {
                    analysis.videoPlayerType?.let { AnalysisDetailRow("Video Player", it) }
                    analysis.videoSourcePattern?.let { AnalysisDetailRow("Source Pattern", it) }
                    AnalysisDetailRow("Video Elements", analysis.videoCount.toString())
                }
            }
            
            // API Detection
            item {
                AnalysisSection(title = "🔌 API Detection", color = AccentYellow) {
                    AnalysisDetailRow("Has API", if (analysis.hasAPI) "Yes" else "No")
                    analysis.apiType?.let { AnalysisDetailRow("API Type", it) }
                }
            }
            
            // Performance
            item {
                AnalysisSection(title = "⚡ Performance", color = AccentOrange) {
                    AnalysisDetailRow("Load Time", "${analysis.loadTime}ms")
                    AnalysisDetailRow("Resources", analysis.resourceCount.toString())
                    AnalysisDetailRow("Page Size", formatBytes(analysis.totalSize))
                }
            }
            
            // Scraping Strategy
            item {
                AnalysisSection(title = "🤖 Scraping Configuration", color = AccentGreen) {
                    AnalysisDetailRow("Strategy", analysis.scrapingStrategy.name)
                    AnalysisDetailRow("Requires JS", if (analysis.requiresJavaScript) "Yes" else "No")
                    AnalysisDetailRow("Requires Auth", if (analysis.requiresAuth) "Yes" else "No")
                    AnalysisDetailRow("Rate Limit", "${analysis.rateLimit} req/min")
                }
            }
        }
    }
}

@Composable
fun AnalysisSection(
    title: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun AnalysisDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
