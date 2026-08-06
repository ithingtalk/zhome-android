@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ithingtalk.zhome.ui.screens.media

import android.net.Uri
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import com.ithingtalk.zhome.data.remote.nas.VideoTranscodeQuality
import com.ithingtalk.zhome.data.repository.DeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import kotlin.math.max
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val TAG = "VideoPlayerScreen"

private val VIDEO_SPEEDS = listOf(
    0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 5f, 9f
)

/** Title bar + video toolbar scrim (matches translucent top/bottom chrome). */
private val VideoChromeScrim = Color.Black.copy(alpha = 0.45f)

/** 最简实现：仅调用 LibVLC 官方缩放 API，不再引入额外窗口尺寸/方向纠偏机制。 */
private fun applyVideoScaleMode(player: MediaPlayer, cropToFill: Boolean) {
    try {
        player.aspectRatio = null
        player.scale = 0f
        if (cropToFill) {
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN)
        } else {
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        }
    } catch (e: Exception) {
        Log.w(TAG, "applyVideoScaleMode", e)
    }
}

/**
 * NAS video playback with Qt-style controls: seek row, speed, repeat, prev/next playlist,
 * optional audio/video/subtitle track menus (when LibVLC exposes multiple tracks), fullscreen,
 * tap to show/hide toolbar, center play overlay when paused.
 */
@Composable
fun PlayVideoScreen(
    remotePaths: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val prefs = remember { ZhomeApp.instance.prefs }
    val deviceRepo = remember { ZhomeApp.instance.deviceRepo }

    if (remotePaths.isEmpty()) {
        val context = LocalContext.current
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.video_title_default)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.video_cd_back))
                        }
                    }
                )
            }
        ) { padding ->
            Text(
                context.getString(R.string.video_no_playable),
                color = Color.White,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
                    .padding(24.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    var currentIndex by remember(remotePaths, startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, remotePaths.lastIndex))
    }

    val title = remember(remotePaths, currentIndex) {
        remotePaths.getOrNull(currentIndex)?.substringAfterLast("/").orEmpty()
    }

    VideoPlayerContent(
        tracks = remotePaths,
        title = title,
        currentIndex = currentIndex,
        onBack = onBack,
        prefs = prefs,
        deviceRepo = deviceRepo,
    )
}

@Composable
private fun VideoPlayerContent(
    tracks: List<String>,
    title: String,
    currentIndex: Int,
    onBack: () -> Unit,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val playbackError = stringResource(R.string.video_playback_error)
    val playerFailed = stringResource(R.string.video_player_failed)
    val cannotLoadVideo = stringResource(R.string.video_cannot_load)
    val loadFailed = stringResource(R.string.video_load_failed)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var session by remember { mutableStateOf<VlcVideoSession?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var sliderPendingFraction by remember { mutableFloatStateOf(-1f) }
    var playbackRate by remember { mutableFloatStateOf(1f) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var loadMediaError by remember { mutableStateOf<String?>(null) }

    var audioTrackChoices by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var spuTrackChoices by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var audioTrackUserSelected by remember { mutableStateOf(false) }
    var selectedAudioTrackId by remember { mutableStateOf<Int?>(null) }
    var spuTrackUserSelected by remember { mutableStateOf(false) }
    var selectedSpuTrackId by remember { mutableStateOf<Int?>(null) }
    var fillScreen by remember { mutableStateOf(false) }
    var videoQuality by remember { mutableStateOf(VideoTranscodeQuality.HD) }
    var defaultQualityApplied by remember { mutableStateOf(false) }
    var loopbackSessionId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            loopbackSessionId?.let { sid ->
                runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
            }
            loopbackSessionId = null
        }
    }

    val fillScreenRef = rememberUpdatedState(fillScreen)
    val isFullscreenRef = rememberUpdatedState(isFullscreen)

    val activity = context as? Activity

    /** Orientation + display cutout: short edges in landscape fullscreen so video can extend into cutouts (edge-to-edge). */
    DisposableEffect(isFullscreen, activity) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        act.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (isFullscreen) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = previousCutoutMode
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val exitPlayer: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onBack()
    }

    BackHandler { exitPlayer() }

    // Default quality: LAN → HD, remote / force P2P → SD; missing transcode falls back to original via check_file_exists.
    LaunchedEffect(Unit) {
        if (defaultQualityApplied) return@LaunchedEffect
        videoQuality = defaultVideoTranscodeQuality(deviceRepo)
        defaultQualityApplied = true
    }

    DisposableEffect(isFullscreen, activity, view) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        val controller = WindowCompat.getInsetsController(window, view)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(isPlaying, view) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(videoLayout, tracks) {
        val layout = videoLayout
        if (layout == null) {
            session = null
            return@DisposableEffect onDispose { }
        }
        val options = ArrayList<String>().apply {
            add("--network-caching=3000")
        }
        val libVlc = LibVLC(context, options)
        val player = MediaPlayer(libVlc)
        val s = VlcVideoSession(libVlc, player)
        var disposed = false

        fun postPlayerUi(block: () -> Unit) {
            if (disposed) return
            mainHandler.post {
                if (!disposed) block()
            }
        }

        try {
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> postPlayerUi {
                        isPlaying = player.isPlaying
                        refreshTrackLists(player)
                        audioTrackChoices = player.audioTracks?.toList().orEmpty()
                        spuTrackChoices = player.spuTracks?.toList().orEmpty()
                        applyVideoScaleMode(player, fillScreenRef.value && isFullscreenRef.value)
                    }
                    MediaPlayer.Event.Paused -> postPlayerUi {
                        isPlaying = false
                    }
                    MediaPlayer.Event.Stopped -> postPlayerUi {
                        isPlaying = false
                    }
                    MediaPlayer.Event.TimeChanged -> postPlayerUi {
                        durationMs = player.length.coerceAtLeast(0L)
                        if (sliderPendingFraction < 0f) {
                            positionMs = player.time.coerceAtLeast(0L)
                        }
                    }
                    MediaPlayer.Event.EndReached -> postPlayerUi {
                        // Video: play once then stop.
                        s.player.stop()
                        isPlaying = false
                    }
                    MediaPlayer.Event.EncounteredError -> postPlayerUi {
                        Log.e(TAG, "VLC error")
                        loadMediaError = playbackError
                        isPlaying = false
                    }
                }
            }
            player.attachViews(layout, null, false, false)
            session = s
        } catch (e: Exception) {
            Log.e(TAG, "VLC init failed", e)
            loadMediaError = e.message ?: playerFailed
            s.release()
            session = null
        }
        onDispose {
            disposed = true
            player.setEventListener(null)
            s.release()
            session = null
        }
    }

    LaunchedEffect(session, currentIndex, tracks, videoQuality) {
        val s = session ?: return@LaunchedEffect
        val t = tracks.getOrNull(currentIndex) ?: return@LaunchedEffect
        loadMediaError = null
        sliderPendingFraction = -1f
        audioTrackUserSelected = false
        selectedAudioTrackId = null
        spuTrackUserSelected = false
        selectedSpuTrackId = null
        audioTrackChoices = emptyList()
        spuTrackChoices = emptyList()
        try {
            loopbackSessionId?.let { sid ->
                runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
            }
            loopbackSessionId = null
            val logicalPath = resolveVideoPlaybackTargetPath(t, videoQuality, prefs, deviceRepo)
            val r = resolveNasMediaUrl(logicalPath, prefs, deviceRepo)
            if (r.uri == null) {
                loadMediaError = r.error ?: cannotLoadVideo
                return@LaunchedEffect
            }
            loopbackSessionId = r.loopbackSessionId
            s.loadMedia(r.uri!!, r.httpUser, r.httpPass)
            s.player.rate = playbackRate
            isPlaying = s.player.isPlaying
            durationMs = s.player.length.coerceAtLeast(0L)
            positionMs = 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadMedia", e)
            loadMediaError = e.message ?: loadFailed
        }
    }

    LaunchedEffect(session, isPlaying) {
        val s = session ?: return@LaunchedEffect
        while (isActive) {
            delay(400)
            if (!isPlaying) continue
            val len = s.player.length
            if (len > 0L) durationMs = len
            if (sliderPendingFraction < 0f) {
                positionMs = s.player.time.coerceAtLeast(0L)
            }
        }
    }

    var chromeAutoHideEpoch by remember { mutableIntStateOf(0) }
    val bumpChromeAutoHide by rememberUpdatedState({ chromeAutoHideEpoch++ })

    LaunchedEffect(toolbarVisible, chromeAutoHideEpoch) {
        if (!toolbarVisible) return@LaunchedEffect
        delay(5000)
        toolbarVisible = false
    }

    fun seekBy(deltaMs: Long) {
        val s = session ?: return
        val len = s.player.length.coerceAtLeast(0L)
        val cur = s.player.time.coerceAtLeast(0L)
        val target = (cur + deltaMs).coerceIn(0L, if (len > 0L) len else Long.MAX_VALUE)
        s.player.time = target
        positionMs = target
    }

    val dur = max(durationMs, 1L)
    val sliderValue = if (sliderPendingFraction >= 0f) {
        sliderPendingFraction
    } else {
        (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    }

    val toggleLandscape: () -> Unit = {
        if (isFullscreen) {
            isFullscreen = false
            toolbarVisible = true
        } else {
            isFullscreen = true
            toolbarVisible = true
        }
    }

    // Reset fill when exiting fullscreen
    LaunchedEffect(isFullscreen) {
        if (!isFullscreen && fillScreen) {
            fillScreen = false
        }
    }

    // 铺满全屏：仅使用 LibVLC 缩放函数。
    LaunchedEffect(fillScreen, session, isFullscreen) {
        val player = session?.player ?: return@LaunchedEffect
        applyVideoScaleMode(player, fillScreen && isFullscreen)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        // Video is full-bleed; title and bottom chrome are overlays with their own WindowInsets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        // One AndroidView for the session; toggling orientation must not switch factory/update branches.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        videoLayout = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isFullscreen) {
                        detectTapGestures(
                            onDoubleTap = { toggleLandscape() },
                            onTap = { toolbarVisible = !toolbarVisible }
                        )
                    },
                update = { },
                onRelease = { videoLayout = null }
            )

            AnimatedVisibility(
                visible = toolbarVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(VideoChromeScrim)
                    .then(if (!isFullscreen) Modifier.statusBarsPadding() else Modifier)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            bumpChromeAutoHide()
                        }
                    },
            ) {
                VideoTopChrome(
                    title = title,
                    landscapeFullscreen = isFullscreen,
                    onBack = exitPlayer,
                )
            }

            loadMediaError?.let { msg ->
                Text(
                    msg,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Box(Modifier.align(Alignment.Center)) {
                CenterPlayOverlay(
                    visible = session != null && !isPlaying,
                    onPlay = {
                        session?.let { s ->
                            // If we stopped at the end, restart from beginning.
                            val len = s.player.length
                            val t = s.player.time
                            if (len > 0L && t >= (len - 800L)) {
                                s.player.time = 0L
                            }
                            s.player.play()
                            isPlaying = s.player.isPlaying
                        }
                        toolbarVisible = true
                    }
                )
            }

            AnimatedVisibility(
                visible = toolbarVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            bumpChromeAutoHide()
                        }
                    },
            ) {
                VideoBottomToolbar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    sliderValue = sliderValue,
                    onSliderChange = { sliderPendingFraction = it },
                    onSliderFinished = {
                        session?.let { s ->
                            val d = s.player.length
                            if (d > 0L) {
                                s.player.time = (sliderPendingFraction * d).toLong()
                            }
                        }
                        sliderPendingFraction = -1f
                    },
                    isPlaying = isPlaying,
                    onPlayPause = {
                        session?.let { s ->
                            if (s.player.isPlaying) s.player.pause() else s.player.play()
                            isPlaying = s.player.isPlaying
                        }
                    },
                    onRewind = { seekBy(-10_000L) },
                    onForward = { seekBy(10_000L) },
                    playbackRate = playbackRate,
                    onPlaybackRateChange = { r ->
                        playbackRate = r
                        session?.let { it.player.rate = r }
                    },
                    audioTrackChoices = audioTrackChoices,
                    spuTrackChoices = spuTrackChoices,
                    audioTrackUserSelected = audioTrackUserSelected,
                    selectedAudioTrackId = selectedAudioTrackId,
                    spuTrackUserSelected = spuTrackUserSelected,
                    selectedSpuTrackId = selectedSpuTrackId,
                    onSelectAudio = { id ->
                        session?.player?.setAudioTrack(id)
                        audioTrackUserSelected = true
                        selectedAudioTrackId = id
                    },
                    onSelectSpu = { id ->
                        session?.player?.setSpuTrack(id)
                        spuTrackUserSelected = true
                        selectedSpuTrackId = id
                    },
                    onFullscreen = toggleLandscape,
                    fullscreenActive = isFullscreen,
                    fillScreenActive = fillScreen,
                    onFillScreen = {
                        fillScreen = !fillScreen
                        if (fillScreen) {
                            Log.i(TAG, "Force fill screen")
                        } else {
                            Log.i(TAG, "exit force fill screen")
                        }
                    },
                    videoQuality = videoQuality,
                    onVideoQualityChange = { videoQuality = it },
                )
            }
        }
    }
}

private fun refreshTrackLists(player: MediaPlayer) {
    // LibVLC populates track lists after Playing; getters refresh internal state.
    try {
        player.audioTracks
        player.videoTracks
        player.spuTracks
    } catch (_: Exception) {
    }
}

private class VlcVideoSession(
    val libVlc: LibVLC,
    val player: MediaPlayer,
) {
    fun loadMedia(mediaUri: String, httpUser: String?, httpPass: String?) {
        player.stop()
        val playUrl = NasUrl.playbackUrlWithBasicAuth(mediaUri.trim(), httpUser, httpPass)
        val uri = Uri.parse(playUrl)
        if (uri.scheme.isNullOrBlank()) {
            throw IllegalArgumentException("Invalid URL")
        }
        val media = Media(libVlc, uri)
        media.addOption(":network-caching=3000")
        player.media = media
        media.release()
        player.play()
    }

    fun release() {
        try {
            player.stop()
            player.detachViews()
            player.release()
        } catch (_: Exception) {
        }
        try {
            libVlc.release()
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun VideoTopChrome(
    title: String,
    landscapeFullscreen: Boolean,
    onBack: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (landscapeFullscreen) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                } else {
                    Modifier
                }
            ),
        title = {
            Text(
                title.ifBlank { stringResource(R.string.video_title_default) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.video_cd_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun CenterPlayOverlay(visible: Boolean, onPlay: () -> Unit) {
    AnimatedVisibility(visible = visible) {
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(88.dp),
            shape = CircleShape
        ) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.video_cd_play), Modifier.size(48.dp))
        }
    }
}

@Composable
private fun VideoBottomToolbar(
    positionMs: Long,
    durationMs: Long,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    playbackRate: Float,
    onPlaybackRateChange: (Float) -> Unit,
    audioTrackChoices: List<MediaPlayer.TrackDescription>,
    spuTrackChoices: List<MediaPlayer.TrackDescription>,
    audioTrackUserSelected: Boolean,
    selectedAudioTrackId: Int?,
    spuTrackUserSelected: Boolean,
    selectedSpuTrackId: Int?,
    onSelectAudio: (Int) -> Unit,
    onSelectSpu: (Int) -> Unit,
    onFullscreen: () -> Unit,
    fullscreenActive: Boolean = false,
    fillScreenActive: Boolean = false,
    onFillScreen: () -> Unit = {},
    videoQuality: VideoTranscodeQuality = VideoTranscodeQuality.HD,
    onVideoQualityChange: (VideoTranscodeQuality) -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(VideoChromeScrim)
            .navigationBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (fullscreenActive) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatMediaTimeMs(positionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE0E0E0)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    VideoQualityDropdown(
                        current = videoQuality,
                        onSelect = onVideoQualityChange,
                    )
                    // Show when 2+ real tracks (exclude VLC Disable id=-1), aligned with iOS.
                    if (spuTrackChoices.count { it.id != -1 } > 1) {
                        TrackDropdown(
                            defaultLabel = stringResource(R.string.video_track_subtitles),
                            tracks = spuTrackChoices,
                            userSelected = spuTrackUserSelected,
                            selectedId = selectedSpuTrackId,
                            onSelect = onSelectSpu,
                        )
                    }
                    if (audioTrackChoices.count { it.id != -1 } > 1) {
                        TrackDropdown(
                            defaultLabel = stringResource(R.string.video_track_audio),
                            tracks = audioTrackChoices,
                            userSelected = audioTrackUserSelected,
                            selectedId = selectedAudioTrackId,
                            onSelect = onSelectAudio,
                        )
                    }
                    SpeedDropdown(playbackRate, onPlaybackRateChange)
                    // Fill-screen button: only in landscape fullscreen — mirrors Qt arrows-left-right button
                    if (fullscreenActive) {
                        IconButton(
                            onClick = onFillScreen,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (fillScreenActive) {
                                    Icons.Default.FullscreenExit
                                } else {
                                    Icons.Default.Fullscreen
                                },
                                contentDescription = stringResource(
                                    if (fillScreenActive) {
                                        R.string.video_cd_keep_aspect
                                    } else {
                                        R.string.video_cd_fullscreen_fill
                                    }
                                ),
                                tint = if (fillScreenActive) Color.White else Color(0xFFBDBEBF),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Text(
                    formatMediaTimeMs(durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE0E0E0)
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFF6F6F6),
                    activeTrackColor = Color(0xFF21BE2B),
                    inactiveTrackColor = Color(0xFFBDBEBF)
                )
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onRewind) {
                        Icon(Icons.Default.Replay10, stringResource(R.string.video_cd_rewind), tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) {
                                stringResource(R.string.video_cd_pause)
                            } else {
                                stringResource(R.string.video_cd_play)
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = onForward) {
                        Icon(Icons.Default.Forward10, stringResource(R.string.video_cd_forward), tint = Color.White)
                    }
                }
                IconButton(onClick = onFullscreen) {
                    Icon(
                        imageVector = if (fullscreenActive) {
                            Icons.Default.StayCurrentPortrait
                        } else {
                            Icons.Default.StayCurrentLandscape
                        },
                        contentDescription = stringResource(
                            if (fullscreenActive) R.string.video_orientation_portrait
                            else R.string.video_orientation_landscape
                        ),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoQualityDropdown(
    current: VideoTranscodeQuality,
    onSelect: (VideoTranscodeQuality) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val orig = stringResource(R.string.video_quality_original)
    val hd = stringResource(R.string.video_quality_hd)
    val sd = stringResource(R.string.video_quality_sd)
    val label = when (current) {
        VideoTranscodeQuality.ORIGINAL -> orig
        VideoTranscodeQuality.HD -> hd
        VideoTranscodeQuality.SD -> sd
    }
    Box {
        Text(
            label,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(
                VideoTranscodeQuality.ORIGINAL to orig,
                VideoTranscodeQuality.HD to hd,
                VideoTranscodeQuality.SD to sd,
            ).forEach { (q, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(q)
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedDropdown(current: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val nearest = VIDEO_SPEEDS.minByOrNull { kotlin.math.abs(it - current) } ?: current
    // Default 1.0 → localized "Speed" / "倍速"; otherwise show rate (aligned with iOS).
    val label = if (kotlin.math.abs(nearest - 1f) < 0.001f) {
        stringResource(R.string.video_playback_speed)
    } else {
        "${nearest}x"
    }
    Box {
        Text(
            label,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            VIDEO_SPEEDS.forEach { r ->
                DropdownMenuItem(
                    text = { Text("${r}x") },
                    onClick = {
                        expanded = false
                        onSelect(r)
                    }
                )
            }
        }
    }
}

@Composable
private fun TrackDropdown(
    defaultLabel: String,
    tracks: List<MediaPlayer.TrackDescription>,
    userSelected: Boolean,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val buttonLabel = if (userSelected) {
        tracks.firstOrNull { it.id == selectedId }?.name?.takeIf { it.isNotBlank() }
            ?: selectedId?.toString()
            ?: defaultLabel
    } else {
        defaultLabel
    }
    Box {
        Text(
            buttonLabel,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name.ifBlank { "${t.id}" }) },
                    onClick = {
                        expanded = false
                        onSelect(t.id)
                    }
                )
            }
        }
    }
}

