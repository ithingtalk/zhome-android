@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)

package com.ithingtalk.zhome.ui.screens.media

import kotlin.OptIn
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.playback.AudioPlaybackQueue
import com.ithingtalk.zhome.playback.MusicPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * Native audio: playlist UI bound to [MusicPlaybackService] via [MediaController].
 * Lock screen / notification / BT use the same [androidx.media3.session.MediaSession] for
 * live title, duration, prev/next/play/pause.
 */
@Composable
fun AudioPlayerScreen(
    remotePaths: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val prefs = remember { ZhomeApp.instance.prefs }
    val deviceRepo = remember { ZhomeApp.instance.deviceRepo }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<ResolvedAudioTrack>?>(null) }
    var playStartIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(remotePaths, startIndex) {
        loading = true
        loadError = null
        tracks = null
        val (resolved, err) = resolveNasAudioPlaylist(remotePaths, prefs, deviceRepo)
        if (resolved.isEmpty()) {
            loadError = err ?: "No playable audio"
            loading = false
            return@LaunchedEffect
        }
        tracks = resolved
        playStartIndex = remapPlaylistStartIndex(remotePaths, startIndex, resolved)
        loading = false
    }

    var barTitle by remember(remotePaths, startIndex) {
        mutableStateOf(
            remotePaths.getOrNull(startIndex.coerceIn(remotePaths.indices))
                ?.substringAfterLast("/")
                ?: remotePaths.firstOrNull()?.substringAfterLast("/").orEmpty()
        )
    }

    BackHandler {
        MusicPlaybackService.stopPlayback(appContext)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        MusicPlaybackService.stopPlayback(appContext)
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212))
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadError != null -> Text(
                    loadError!!,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                tracks != null -> NativeAudioPlayerContent(
                    tracks = tracks!!,
                    startIndex = playStartIndex,
                    onFatalError = { err -> loadError = err },
                    onTrackTitleChanged = { barTitle = it }
                )
            }
        }
    }
}


@Composable
private fun NativeAudioPlayerContent(
    tracks: List<ResolvedAudioTrack>,
    startIndex: Int,
    onFatalError: (String) -> Unit,
    onTrackTitleChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val onErrorState by rememberUpdatedState(onFatalError)
    val onTitleState by rememberUpdatedState(onTrackTitleChanged)

    val sessionToken = remember {
        SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
    }

    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var currentTitle by remember { mutableStateOf("") }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_ALL) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var sliderPendingFraction by remember { mutableFloatStateOf(-1f) }

    val player = mediaController

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(32)
            rotation = (rotation + 1.8f) % 360f
        }
    }

    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (isActive) {
            delay(400)
            val d = p.duration
            if (d > 0 && d != C.TIME_UNSET) {
                durationMs = d
            }
            if (sliderPendingFraction < 0f) {
                positionMs = p.currentPosition
            }
        }
    }

    LaunchedEffect(tracks, startIndex) {
        mediaController?.release()
        mediaController = null
        AudioPlaybackQueue.setPending(tracks, startIndex)
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_LOAD
            }
        )
        delay(150)
        try {
            val c = MediaController.Builder(appContext, sessionToken).buildAsync().await()
            mediaController = c
        } catch (e: Exception) {
            onErrorState(e.message ?: "Media controller failed")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaController?.release()
            mediaController = null
            MusicPlaybackService.stopPlayback(appContext)
        }
    }

    DisposableEffect(mediaController, tracks, startIndex) {
        val c = mediaController ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                onErrorState(error.message ?: "Playback error")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val t = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                currentTitle = t
                onTitleState(t)
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }
        }
        c.addListener(listener)
        val initial = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: tracks.getOrNull(startIndex.coerceIn(tracks.indices))
                ?.remotePath?.substringAfterLast("/").orEmpty()
        currentTitle = initial
        onTitleState(initial)
        repeatMode = c.repeatMode
        onDispose {
            c.removeListener(listener)
        }
    }

    val dur = max(durationMs, 1L)
    val sliderValue = if (sliderPendingFraction >= 0f) {
        sliderPendingFraction
    } else {
        (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E1E))
                        .rotate(rotation)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A0A0A))
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color(0xFF444444))
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                currentTitle.ifBlank { tracks.first().remotePath.substringAfterLast("/") },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatMediaTimeMs(positionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                IconButton(
                    onClick = {
                        mediaController?.let { p ->
                            val next = when (p.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                else -> Player.REPEAT_MODE_OFF
                            }
                            p.repeatMode = next
                        }
                    }
                ) {
                    val tint = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Color.Gray
                        else -> Color.White
                    }
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat mode",
                        tint = tint
                    )
                }
                Text(
                    formatMediaTimeMs(durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderPendingFraction = it },
                onValueChangeFinished = {
                    mediaController?.let { p ->
                        val d = p.duration
                        if (d > 0 && d != C.TIME_UNSET) {
                            p.seekTo((sliderPendingFraction * d).toLong())
                        }
                    }
                    sliderPendingFraction = -1f
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFF6F6F6),
                    activeTrackColor = Color(0xFF21BE2B),
                    inactiveTrackColor = Color(0xFFBDBEBF)
                )
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { mediaController?.seekToPreviousMediaItem() },
                    enabled = mediaController != null
                ) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                }
                FilledIconButton(
                    onClick = {
                        mediaController?.let { p ->
                            if (p.isPlaying) p.pause() else p.play()
                        }
                    },
                    enabled = mediaController != null,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = { mediaController?.seekToNextMediaItem() },
                    enabled = mediaController != null
                ) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                }
            }
        }
    }
}

