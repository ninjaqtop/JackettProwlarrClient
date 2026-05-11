package com.aggregatorx.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.engine.util.EngineUtils
import com.aggregatorx.app.ui.theme.*
import kotlinx.coroutines.delay

class VideoPlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VIDEO_URL = "VIDEO_URL"
        const val EXTRA_TITLE = "TITLE"
        const val EXTRA_HEADERS_PREFIX = "HEADERS_"

        fun buildIntent(
            context: Context, videoUrl: String, title: String = "",
            headers: Map<String, String> = emptyMap()
        ): Intent = Intent(context, VideoPlayerActivity::class.java).apply {
            putExtra(EXTRA_VIDEO_URL, videoUrl)
            putExtra(EXTRA_TITLE, title)
            headers.forEach { (k, v) -> putExtra("$EXTRA_HEADERS_PREFIX$k", v) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val headers = buildMap<String, String> {
            intent.extras?.keySet()?.filter { it.startsWith(EXTRA_HEADERS_PREFIX) }?.forEach { key ->
                intent.getStringExtra(key)?.let { put(key.removePrefix(EXTRA_HEADERS_PREFIX), it) }
            }
        }
        setContent {
            AggregatorXPlayerTheme {
                FullScreenPlayer(
                    videoUrl = videoUrl, title = title, headers = headers,
                    onClose = { finish() },
                    onEnterPip = { enterPipMode() }
                )
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPipMode()
    }
}

@Composable
private fun AggregatorXPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = DarkBackground, surface = DarkCard, primary = CyberCyan),
        content = content
    )
}

private enum class MediaType { HLS, DASH, PROGRESSIVE, UNKNOWN }

private fun detectMediaType(url: String): MediaType {

    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") || lower.contains("/hls/") -> MediaType.HLS
        lower.contains(".mpd") || lower.contains("/dash/") -> MediaType.DASH
        lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
        lower.contains(".m4v") || lower.contains(".mov") -> MediaType.PROGRESSIVE
        else -> MediaType.UNKNOWN
    }
}

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

@Composable
private fun FullScreenPlayer(
    videoUrl: String, title: String, headers: Map<String, String>,
    onClose: () -> Unit, onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPos by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var retryCount by remember { mutableStateOf(0) }
    var formatOverride by remember { mutableStateOf<MediaType?>(null) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showSeekIndicator by remember { mutableStateOf(false) }
    var seekIndicatorText by remember { mutableStateOf("") }
    var seekIndicatorForward by remember { mutableStateOf(true) }
    var brightnessLevel by remember { mutableStateOf(0.5f) }
    var volumeLevel by remember { mutableStateOf(
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    ) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var bufferingPercent by remember { mutableStateOf(0) }

    val detectedType = remember(videoUrl) { detectMediaType(videoUrl) }
    val activeType = formatOverride ?: detectedType

    val httpFactory = remember(videoUrl, headers) {
        val ua = headers["User-Agent"] ?: EngineUtils.DEFAULT_USER_AGENT
        DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(40_000)
            .setAllowCrossProtocolRedirects(true)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
    }

    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 120_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
    }

    val exoPlayer = remember(videoUrl, retryCount, activeType) {
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                val uri = Uri.parse(videoUrl)
                val source = when (activeType) {
                    MediaType.HLS -> HlsMediaSource.Factory(httpFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(MediaItem.fromUri(uri))
                    MediaType.DASH -> DashMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                    else -> ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                }
                setMediaSource(source)
                prepare()
                playWhenReady = true
                setPlaybackParameters(PlaybackParameters(playbackSpeed))
            }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying && !isLocked) { delay(4_000); showControls = false }
    }
    LaunchedEffect(showSeekIndicator) {
        if (showSeekIndicator) { delay(800); showSeekIndicator = false }
    }
    LaunchedEffect(showBrightnessIndicator) {
        if (showBrightnessIndicator) { delay(1200); showBrightnessIndicator = false }
    }
    LaunchedEffect(showVolumeIndicator) {
        if (showVolumeIndicator) { delay(1200); showVolumeIndicator = false }
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPos = exoPlayer.currentPosition
            duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            bufferingPercent = exoPlayer.bufferedPercentage
            delay(500)
        }
    }
    LaunchedEffect(playbackSpeed) {
        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed))
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    hasError = false
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                errorMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network error"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Stream not found (404)"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Unsupported format"
                    else -> "Playback error (${error.errorCode})"
                }
                if (formatOverride == null && activeType != MediaType.PROGRESSIVE) {
                    formatOverride = MediaType.PROGRESSIVE; hasError = false; retryCount++
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                if (isLocked) {
                    detectTapGestures { showControls = true }
                } else {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            val isRight = offset.x > size.width / 2
                            if (isRight) {
                                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                exoPlayer.seekTo(minOf(dur, exoPlayer.currentPosition + 10_000L))
                                seekIndicatorText = "+10s"; seekIndicatorForward = true
                            } else {
                                exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 10_000L))
                                seekIndicatorText = "-10s"; seekIndicatorForward = false
                            }
                            showSeekIndicator = true
                        }
                    )
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isLocked) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        brightnessLevel = (brightnessLevel - dragAmount / 1000f).coerceIn(0f, 1f)
                        showBrightnessIndicator = true
                        try {
                            val lp = (context as? VideoPlayerActivity)?.window?.attributes
                            if (lp != null) { lp.screenBrightness = brightnessLevel; (context as VideoPlayerActivity).window.attributes = lp }
                        } catch (_: Exception) {}
                    }
                })
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterEnd)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volumeLevel = (volumeLevel - dragAmount / 1000f).coerceIn(0f, 1f)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeLevel * maxVol).toInt(), 0)
                        showVolumeIndicator = true
                    }
                })
            if (duration > 0) {
                Box(modifier = Modifier.fillMaxSize().pointerInput(duration) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        val seekMs = (dragAmount * duration / size.width).toLong()
                        exoPlayer.seekTo((exoPlayer.currentPosition + seekMs).coerceIn(0L, duration))
                        seekIndicatorText = if (seekMs > 0) "+${seekMs / 1000}s" else "${seekMs / 1000}s"
                        seekIndicatorForward = seekMs > 0
                        showSeekIndicator = true
                    }
                })
            }
        }

        if (isBuffering && !hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("$bufferingPercent%", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }

        AnimatedVisibility(
            visible = showSeekIndicator, enter = fadeIn() + scaleIn(), exit = fadeOut(),
            modifier = Modifier.align(if (seekIndicatorForward) Alignment.CenterEnd else Alignment.CenterStart)
        ) {
            Box(modifier = Modifier.padding(horizontal = 32.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (seekIndicatorForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                        null, tint = CyberCyan, modifier = Modifier.size(24.dp))
                    Text(seekIndicatorText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(visible = showBrightnessIndicator, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)) {
            SideIndicator(Icons.Default.Brightness6, brightnessLevel, "${(brightnessLevel * 100).toInt()}%",
                Modifier.padding(start = 16.dp))
        }
        AnimatedVisibility(visible = showVolumeIndicator, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)) {
            SideIndicator(
                if (volumeLevel > 0.5f) Icons.Default.VolumeUp else if (volumeLevel > 0f) Icons.Default.VolumeDown else Icons.Default.VolumeOff,
                volumeLevel, "${(volumeLevel * 100).toInt()}%", Modifier.padding(end = 16.dp))
        }

        if (hasError) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = AccentRed, modifier = Modifier.size(56.dp))
                    Text(errorMessage, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { hasError = false; formatOverride = null; retryCount++ },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkBackground)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text("Browser") }
                        OutlinedButton(onClick = onClose,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text("Close") }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showControls && !hasError, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(
                title = title, videoUrl = videoUrl, isPlaying = isPlaying, isLocked = isLocked,
                currentPos = currentPos, duration = duration, playbackSpeed = playbackSpeed,
                showSpeedMenu = showSpeedMenu, exoPlayer = exoPlayer,
                onClose = onClose, onEnterPip = onEnterPip,
                onLockToggle = { isLocked = !isLocked },
                onSpeedMenuToggle = { showSpeedMenu = !showSpeedMenu },
                onSpeedSelected = { speed -> playbackSpeed = speed; showSpeedMenu = false },
                onSeek = { frac -> exoPlayer.seekTo((frac * duration).toLong()) }
            )
        }

        if (isLocked) {
            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                IconButton(onClick = { isLocked = false; showControls = true }) {
                    Icon(Icons.Default.Lock, "Unlock", tint = CyberCyan, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SideIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float, label: String, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = CyberCyan, modifier = Modifier.size(24.dp))
        LinearProgressIndicator(progress = { value }, modifier = Modifier.width(4.dp).height(80.dp),
            color = CyberCyan, trackColor = Color.White.copy(alpha = 0.2f))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun PlayerControls(
    title: String, videoUrl: String, isPlaying: Boolean, isLocked: Boolean,
    currentPos: Long, duration: Long, playbackSpeed: Float, showSpeedMenu: Boolean,
    exoPlayer: ExoPlayer, onClose: () -> Unit, onEnterPip: () -> Unit,
    onLockToggle: () -> Unit, onSpeedMenuToggle: () -> Unit,
    onSpeedSelected: (Float) -> Unit, onSeek: (Float) -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.8f)))
    )) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Box {
                IconButton(onClick = onSpeedMenuToggle) {
                    Text(if (playbackSpeed == 1.0f) "1x" else "${playbackSpeed}x", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = showSpeedMenu, onDismissRequest = onSpeedMenuToggle, modifier = Modifier.background(DarkCard)) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        DropdownMenuItem(
                            text = { Text(if (speed == 1.0f) "Normal (1x)" else "${speed}x", color = if (speed == playbackSpeed) CyberCyan else TextPrimary) },
                            onClick = { onSpeedSelected(speed) }
                        )
                    }
                }
            }
            IconButton(onClick = onLockToggle) {
                Icon(if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock",
                    tint = if (isLocked) CyberCyan else Color.White, modifier = Modifier.size(22.dp))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                IconButton(onClick = onEnterPip) {
                    Icon(Icons.Default.Fullscreen, "PiP", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))) }) {
                Icon(Icons.Default.OpenInBrowser, "Browser", tint = CyberCyan, modifier = Modifier.size(22.dp))
            }
        }

        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(52.dp, { exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 30_000L)) }) {
                Icon(Icons.Default.Replay30, "-30s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            TransportButton(52.dp, { exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 10_000L)) }) {
                Icon(Icons.Default.Replay10, "-10s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            TransportButton(72.dp, { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp))
            }
            TransportButton(52.dp, {
                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                exoPlayer.seekTo(minOf(dur, exoPlayer.currentPosition + 10_000L))
            }) { Icon(Icons.Default.Forward10, "+10s", tint = Color.White, modifier = Modifier.size(28.dp)) }
            TransportButton(52.dp, {
                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                exoPlayer.seekTo(minOf(dur, exoPlayer.currentPosition + 30_000L))
            }) { Icon(Icons.Default.Forward30, "+30s", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }

        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Slider(
                value = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f,
                onValueChange = onSeek, modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(currentPos), color = Color.White, fontSize = 12.sp)
                Text(formatDuration(duration), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TransportButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center, content = content)
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val s = millis / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
