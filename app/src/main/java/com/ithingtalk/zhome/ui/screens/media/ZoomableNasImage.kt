package com.ithingtalk.zhome.ui.screens.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.Credentials

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 3f

@Composable
fun ZoomableNasImage(
    imageUrl: String,
    httpUser: String?,
    httpPass: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val request = remember(imageUrl, httpUser, httpPass) {
        val b = ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
        if (!httpUser.isNullOrBlank() || !httpPass.isNullOrBlank()) {
            b.addHeader("Authorization", Credentials.basic(httpUser.orEmpty(), httpPass.orEmpty()))
        }
        b.build()
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingerCount = event.changes.count { it.pressed }

                        if (fingerCount >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (newScale <= 1f) Offset.Zero else offset + pan
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            val pan = event.calculatePan()
                            offset += pan
                            event.changes.forEach { it.consume() }
                        }
                        // scale ~1 + single finger → don't consume → Pager swipes
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
    }
}
