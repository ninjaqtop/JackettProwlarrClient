package com.aggregatorx.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aggregatorx.app.ui.screens.*
import com.aggregatorx.app.ui.theme.*
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AggregatorXTheme {
                MainScreen()
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Search : Screen("search", "SEARCH", Icons.Filled.Search, Icons.Outlined.Search)
    object Providers : Screen("providers", "PROVIDERS", Icons.Filled.Dns, Icons.Outlined.Dns)
    object Settings : Screen("settings", "SETTINGS", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Shared SearchViewModel
    val searchViewModel: SearchViewModel = hiltViewModel()
    val isDiscoveryPaused by searchViewModel.isDiscoveryPaused.collectAsState()

    val screens = listOf(Screen.Search, Screen.Providers, Screen.Settings)

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            MissionControlTopBar(
                isPaused = isDiscoveryPaused,
                onPanicRefresh = { searchViewModel.panicRefresh() },
                onTogglePause = { searchViewModel.toggleDiscoveryPause() }
            )
        },
        bottomBar = {
            FuturisticBottomBar(
                screens = screens,
                currentDestination = currentDestination,
                onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Search.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(
                route = Screen.Search.route,
                enterTransition = { fadeIn(tween(250)) + slideInHorizontally { -it / 4 } },
                exitTransition  = { fadeOut(tween(250)) + slideOutHorizontally { -it / 4 } }
            ) {
                // FIXED: Wrapped correctly in lambda context
                SearchScreen(viewModel = searchViewModel)
            }
            composable(
                route = Screen.Providers.route,
                enterTransition = { fadeIn(tween(250)) + slideInHorizontally { it / 4 } },
                exitTransition  = { fadeOut(tween(250)) + slideOutHorizontally { it / 4 } }
            ) {
                ProvidersScreen()
            }
            composable(
                route = Screen.Settings.route,
                enterTransition = { fadeIn(tween(250)) + slideInHorizontally { it / 4 } },
                exitTransition  = { fadeOut(tween(250)) + slideOutHorizontally { it / 4 } }
            ) {
                SettingsScreen()
            }
        }
    }
}

// ── MISSION CONTROL TOP BAR ─────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionControlTopBar(
    isPaused: Boolean,
    onPanicRefresh: () -> Unit,
    onTogglePause: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topbar_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    TopAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = NeonGreen.copy(alpha = glowAlpha),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end   = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkBackground,
            titleContentColor = NeonGreen
        ),
        title = {
            Text(
                text = "AGGREGATORX",
                color = NeonGreen,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                letterSpacing = 3.sp
            )
        },
        actions = {
            val pauseColor = if (isPaused) AccentOrange else NeonGreen
            val pauseIcon  = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause
            val pauseLabel = if (isPaused) "▶ PLAY" else "⏸ PAUSE"

            TextButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .border(
                        width = 1.dp,
                        color = pauseColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = pauseIcon,
                    contentDescription = pauseLabel,
                    tint = pauseColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = pauseLabel,
                    color = pauseColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            TextButton(
                onClick = onPanicRefresh,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .border(
                        width = 1.dp,
                        color = AccentRed.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Panic Refresh",
                    tint = AccentRed,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "🔄 PANIC",
                    color = AccentRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    )
}

@Composable
fun FuturisticBottomBar(
    screens: List<Screen>,
    currentDestination: androidx.navigation.NavDestination?,
    onNavigate: (Screen) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, DarkBackground)
                )
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(36.dp),
            color = DarkCard.copy(alpha = 0.95f),
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    
                    FuturisticNavItem(
                        screen = screen,
                        selected = selected,
                        onClick = { onNavigate(screen) }
                    )
                }
            }
        }
    }
}

@Composable
fun FuturisticNavItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit
) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (selected) 0.5f else 0f,
        animationSpec = tween(300),
        label = "nav_glow"
    )
    
    val iconColor by animateColorAsState(
        targetValue = if (selected) CyberCyan else TextTertiary,
        animationSpec = tween(300),
        label = "nav_color"
    )
    
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                if (selected) {
                    drawRoundRect(
                        color = CyberCyan,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                        alpha = 0.1f
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(CyberCyan.copy(alpha = glowAlpha), Color.Transparent)
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                contentDescription = screen.title,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = screen.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberCyan,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
