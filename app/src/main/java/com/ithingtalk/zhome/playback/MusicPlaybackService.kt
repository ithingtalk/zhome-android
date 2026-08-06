package com.ithingtalk.zhome.playback

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ithingtalk.zhome.ui.screens.media.ResolvedAudioTrack
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import com.ithingtalk.zhome.ui.screens.media.AudioPlayerActivity
import okhttp3.Credentials
import java.util.UUID

/**
 * Foreground [MediaSessionService]: drives lock screen, notification shade, headset / BT / Wear
 * with live title, duration, and prev/next/play (system media controls).
 */
@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory
    private var lastLoopbackSessionIds: List<String> = emptyList()

    private fun unregisterAllLoopbackSessions() {
        lastLoopbackSessionIds.forEach { sid ->
            runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
        }
        lastLoopbackSessionIds = emptyList()
    }

    override fun onCreate() {
        super.onCreate()
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Zhome-Android")

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpDataSourceFactory)
            )
            .build()

        val launchIntent = Intent(this, AudioPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                unregisterAllLoopbackSessions()
                mediaSession?.run {
                    player.stop()
                    player.clearMediaItems()
                }
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOAD -> {
                AudioPlaybackQueue.takePending()?.let { (tracks, startIndex) ->
                    applyTracks(tracks, startIndex)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun applyTracks(tracks: List<ResolvedAudioTrack>, startIndex: Int) {
        val session = mediaSession ?: return
        val player = session.player

        unregisterAllLoopbackSessions()
        lastLoopbackSessionIds = tracks.mapNotNull { it.resolution.loopbackSessionId }

        httpDataSourceFactory.setDefaultRequestProperties(emptyMap())
        val first = tracks.first()
        if (!first.resolution.httpUser.isNullOrEmpty() && !first.resolution.httpPass.isNullOrEmpty()) {
            val auth = Credentials.basic(first.resolution.httpUser, first.resolution.httpPass)
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to auth))
        }

        val mediaItems = tracks.map { t ->
            val encodedUrl = NasUrl.playbackUrlWithBasicAuth(t.resolution.uri!!, null, null)
            val name = t.remotePath.substringAfterLast("/")
            MediaItem.Builder()
                .setUri(encodedUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name)
                        .setArtist("Zhome")
                        .build()
                )
                .build()
        }

        AudioPlaybackQueue.setNowPlaying(tracks.map { it.remotePath }, startIndex)

        player.setMediaItems(
            mediaItems,
            startIndex.coerceIn(0, mediaItems.lastIndex),
            C.TIME_UNSET
        )
        player.repeatMode = if (tracks.size <= 1) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        unregisterAllLoopbackSessions()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOAD = "com.ithingtalk.zhome.playback.ACTION_LOAD"
        const val ACTION_STOP = "com.ithingtalk.zhome.playback.ACTION_STOP"

        /** Stops ExoPlayer and tears down the foreground session (call when leaving the audio player UI). */
        fun stopPlayback(context: Context) {
            context.startService(
                Intent(context, MusicPlaybackService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
