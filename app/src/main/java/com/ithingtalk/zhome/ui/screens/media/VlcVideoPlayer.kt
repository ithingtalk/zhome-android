package com.ithingtalk.zhome.ui.screens.media

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val TAG = "VlcVideoPlayer"

/**
 * Embeds libVLC [VLCVideoLayout] in Compose and plays [mediaUri] (http(s), file, content, etc.).
 */
@Composable
fun VlcVideoPlayer(
    mediaUri: String,
    modifier: Modifier = Modifier,
    httpUser: String? = null,
    httpPass: String? = null,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

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
        modifier = modifier,
        update = { },
        onRelease = {
            videoLayout = null
        }
    )

    DisposableEffect(videoLayout, mediaUri, httpUser, httpPass) {
        val layout = videoLayout
        if (layout == null) {
            return@DisposableEffect onDispose { }
        }

        val options = ArrayList<String>().apply {
            add("--network-caching=3000")
        }
        val libVlc = LibVLC(context, options)
        val player = MediaPlayer(libVlc)

        try {
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e(TAG, "VLC error playing $mediaUri")
                        onError?.invoke("Playback error")
                    }
                }
            }
            player.attachViews(layout, null, false, false)

            val playUrl = NasUrl.playbackUrlWithBasicAuth(mediaUri.trim(), httpUser, httpPass)
            val uri = Uri.parse(playUrl)
            if (uri.scheme.isNullOrBlank()) {
                onError?.invoke("Invalid URL (needs http:// or file://)")
            } else {
                val media = Media(libVlc, uri)
                media.addOption(":network-caching=3000")
                player.media = media
                media.release()
                player.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VLC", e)
            onError?.invoke(e.message ?: "Playback failed")
        }

        onDispose {
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
}
