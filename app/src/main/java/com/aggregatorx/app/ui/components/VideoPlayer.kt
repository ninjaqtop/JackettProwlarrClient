@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.aggregatorx.app.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.ui.PlayerView
import com.aggregatorx.app.engine.media.RecoveryStrategy
import com.aggregatorx.app.engine.util.EngineUtils
import com.aggregatorx.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Media type detection ──────────────────────────────────────────────────────

private enum class MediaType { HLS, DASH, PROGRESSIVE, SMOOTH_STREAMING, UNKNOWN }

private fun detectMediaType(url: String): MediaType {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") || lower.contains("/hls/") ||
        lower.contains("master.m3u8") || lower.contains("index.m3u8") ||
        lower.contains("playlist.m3u8") || lower.contains("chunklist") ||
        lower.contains("hls_playlist") || lower.contains("type=m3u8") ||
        lower.contains("format=m3u8") -> MediaType.HLS

        lower.contains(".mpd") || lower.contains("/dash/") ||
        lower.contains("manifest.mpd") || lower.contains("stream.mpd") ||
        lower.contains("type=mpd") || lower.contains("format=mpd") -> MediaType.DASH

        (lower.contains("/manifest") && (lower.contains("ism") || lower.contains("smooth"))) ||
        lower.endsWith("/manifest") || lower.contains(".ism/manifest") -> MediaType.SMOOTH_STREAMING

        lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
        lower.contains(".m4v") || lower.contains(".mov") || lower.contains(".avi") ||
        lower.contains(".ts")  || lower.contains(".flv") || lower.contains(".wmv") ||
        lower.contains(".3gp") || lower.contains(".f4v") || lower.contains(".ogv") ||
        lower.contains(".mp3") || lower.contains(".aac") || lower.contains(".ogg") ||
        lower.contains(".flac")|| lower.contains(".wav") || lower.contains(".m4a") -> MediaType.PROGRESSIVE

        lower.contains("videoplayback") || lower.contains("/get_video") ||
        lower.contains("/dl/") || lower.contains("googlevideo.com") ||
        lower.contains("akamaized.net") || lower.contains("cloudfront.net") ||
        lower.contains("cdn.streamtape") || lower.contains("dood.") ||
        lower.contains("filemoon.") || lower.contains("streamwish.") ||
        lower.contains("mixdrop.") || lower.contains("voe.sx") ||
        lower.contains("upstream.to") || lower.contains("streamlare.") ||
        lower.contains("vidplay.") || lower.contains("mp4upload.") ||
        lower.contains("sendvid.") || lower.contains("streamable.") -> MediaType.PROGRESSIVE

        else -> MediaType.UNKNOWN
    }
}

private val FORMAT_RETRY_ORDER = listOf(
    MediaType.PROGRESSIVE, MediaType.HLS, MediaType.DASH, MediaType.SMOOTH_STREAMING
)

private fun buildMediaSource(
    uri: Uri,
    type: MediaType,
    factory: DefaultHttpDataSource.Factory
): androidx.media3.exoplayer.source.MediaSource = when (type) {
    MediaType.HLS ->
        HlsMediaSource.Factory(factory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(uri))
    MediaType.DASH ->
        DashMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(uri))
    MediaType.SMOOTH_STREAMING ->
        SsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(uri))
    MediaType.PROGRESSIVE, MediaType.UNKNOWN ->
        ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(uri))
}

private fun mapErrorToRecovery(code: Int): RecoveryStrategy? = when (code) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS            -> RecoveryStrategy.USE_NETHERLANDS_PROXY
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED  -> RecoveryStrategy.TRY_PROXY
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED    -> RecoveryStrategy.TRY_ALTERNATE_SOURCE
    else                                                        -> RecoveryStrategy.TRY_ALL_METHODS
}

private fun isNetworkError(code: Int) = code in listOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
)

private fun friendlyError(code: Int, raw: String?): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED  -> "Network connection failed"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timed out"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS            -> "Stream unavailable (HTTP error)"
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND             -> "Stream not found (404)"
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE  -> "Invalid content type"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED    -> "Malformed stream manifest"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED  -> "Unsupported manifest format"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Unsupported video container"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED   -> "Corrupted video data"
    PlaybackException.ERROR_CODE_DECODING_FAILED               -> "Decoding failed"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED           -> "Decoder unavailable"
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW            -> "Behind live window — restarting"
    else -> raw?.take(120) ?: "Playback error ($code)"
}

// ── VideoPlayerDialog ─────────────────────────────────────────────────────────

@Composable
fun VideoPlayerDialog(
    videoUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit = {},
    onOpenExternal: () -> Unit = {},
    headers: Map<String, String>? = null,
    onStreamError: ((String, RecoveryStrategy?) -> Unit)? = null
) {
    val context = LocalContext.current

    var isPlaying       by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration        by remember { mutableStateOf(0L) }
    var isBuffering     by remember { mutableStateOf(true) }
    var bufferPercent   by remember { mutableStateOf(0) }
    var hasError        by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var showControls    by remember { mutableStateOf(true) }
    var retryCount      by remember { mutableStateOf(0) }
    var triedFormats    by remember { mutableStateOf(setOf<MediaType>()) }
    var formatOverride  by remember { mutableStateOf<MediaType?>(null) }
    var playbackSpeed   by remember { mutableStateOf(1.0f) }
    var showSpeedMenu   by remember { mutableStateOf(false) }

    val detectedType = remember(videoUrl) { detectMediaType(videoUrl) }
    val activeType   = formatOverride ?: detectedType

    val httpFactory = remember(videoUrl, headers) {
        val ua = headers?.get("User-Agent") ?: EngineUtils.DEFAULT_USER_AGENT
        DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(40_000)
            .setAllowCrossProtocolRedirects(true)
            .apply { headers?.let { setDefaultRequestProperties(it) } }
    }

    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 60_000, 600, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .build()
    }

    val exoPlayer = remember(videoUrl, retryCount, activeType) {
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                val source = buildMediaSource(Uri.parse(videoUrl), activeType, httpFactory)
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering   = state == Player.STATE_BUFFERING
                bufferPercent = exoPlayer.bufferedPercentage
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    hasError = false
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                onStreamError?.invoke(error.message ?: "Playback error", mapErrorToRecovery(error.errorCode))
                triedFormats = triedFormats + activeType
                val next = FORMAT_RETRY_ORDER.firstOrNull { it !in triedFormats }
                if (next != null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(400); formatOverride = next; hasError = false; retryCount++
                    }
                } else if (retryCount < 2 && isNetworkError(error.errorCode)) {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2_000); triedFormats = emptySet(); formatOverride = null; retryCount++
                    }
                } else {
                    hasError = true
                    errorMessage = friendlyError(error.errorCode, error.message)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            bufferPercent   = exoPlayer.bufferedPercentage
            delay(500)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) { delay(3_500); showControls = false }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showControls = !showControls }
        ) {
            if (hasError) {
                Column(
                    modifier = Modifier.fillMaxSize().background(DarkBackground).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(80.dp)
                            .background(AccentRed.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = AccentRed,
                            modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Playback Error", style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary)
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                triedFormats = emptySet(); formatOverride = null
                                hasError = false; retryCount++
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan, contentColor = DarkBackground)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                        Button(
                            onClick = onOpenExternal,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen, contentColor = DarkBackground)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Browser")
                        }
                        OutlinedButton(onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                            Text("Close")
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isBuffering) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = CyberCyan,
                                modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("$bufferPercent%", color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(
                                Color.Black.copy(alpha = 0.75f), Color.Transparent,
                                Color.Transparent, Color.Black.copy(alpha = 0.75f)
                            ))
                        )
                    ) {
                        // Top bar
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, "Close", tint = Color.White,
                                    modifier = Modifier.size(26.dp))
                            }
                            Text(title, color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Box {
                                IconButton(onClick = { showSpeedMenu = true }) {
                                    Text(
                                        if (playbackSpeed == 1.0f) "1×" else "${playbackSpeed}×",
                                        color = CyberCyan, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                DropdownMenu(expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false },
                                    modifier = Modifier.background(DarkCard)) {
                                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                                        .forEach { speed ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (speed == 1.0f) "Normal" else "${speed}×",
                                                        color = if (speed == playbackSpeed) CyberCyan else TextPrimary
                                                    )
                                                },
                                                onClick = {
                                                    playbackSpeed = speed
                                                    exoPlayer.setPlaybackSpeed(speed)
                                                    showSpeedMenu = false
                                                }
                                            )
                                        }
                                }
                            }
                            IconButton(onClick = onDownload) {
                                Icon(Icons.Default.Download, "Download", tint = CyberCyan,
                                    modifier = Modifier.size(24.dp))
                            }
                        }

                        // Centre transport
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TransportBtn(48.dp, {
                                exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 30_000L))
                            }) { Icon(Icons.Default.Replay30, "-30s", tint = Color.White,
                                modifier = Modifier.size(26.dp)) }
                            TransportBtn(48.dp, {
                                exoPlayer.seekTo(maxOf(0L, exoPlayer.currentPosition - 10_000L))
                            }) { Icon(Icons.Default.Replay10, "-10s", tint = Color.White,
                                modifier = Modifier.size(26.dp)) }
                            TransportBtn(68.dp, {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (isPlaying) "Pause" else "Play",
                                    tint = Color.White, modifier = Modifier.size(42.dp)
                                )
                            }
                            TransportBtn(48.dp, {
                                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                exoPlayer.seekTo(minOf(dur, exoPlayer.currentPosition + 10_000L))
                            }) { Icon(Icons.Default.Forward10, "+10s", tint = Color.White,
                                modifier = Modifier.size(26.dp)) }
                            TransportBtn(48.dp, {
                                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                exoPlayer.seekTo(minOf(dur, exoPlayer.currentPosition + 30_000L))
                            }) { Icon(Icons.Default.Forward30, "+30s", tint = Color.White,
                                modifier = Modifier.size(26.dp)) }
                        }

                        // Bottom seek + time
                        Column(
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { bufferPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = Color.White.copy(alpha = 0.3f),
                                trackColor = Color.Transparent
                            )
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                                onValueChange = { exoPlayer.seekTo((it * duration).toLong()) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = CyberCyan,
                                    activeTrackColor = CyberCyan,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatDuration(currentPosition), color = Color.White,
                                    fontSize = 12.sp)
                                Text(formatDuration(duration), color = Color.White,
                                    fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportBtn(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val s = millis / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
