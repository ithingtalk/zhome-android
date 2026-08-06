@file:OptIn(ExperimentalMaterial3Api::class)

package com.ithingtalk.zhome.ui.screens.media

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.remote.nas.NasHttpDownload
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ImagePreviewScreen(
    remotePaths: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val prefs = remember { ZhomeApp.instance.prefs }
    val deviceRepo = remember { ZhomeApp.instance.deviceRepo }
    var originalOverrides by remember { mutableStateOf(setOf<String>()) }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (remotePaths.size - 1).coerceAtLeast(0)),
        pageCount = { remotePaths.size },
    )

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val currentRemotePath = remember(currentPage, remotePaths) { remotePaths.getOrNull(currentPage).orEmpty() }
    val title = remember(currentPage, remotePaths) {
        remotePaths.getOrNull(currentPage)?.substringAfterLast("/").orEmpty()
    }
    val currentForceOriginal = currentRemotePath in originalOverrides
    var currentUseSd by remember(currentRemotePath, currentForceOriginal) { mutableStateOf(false) }
    val counter =
        if (remotePaths.size > 1) stringResource(R.string.media_page_counter, currentPage + 1, remotePaths.size)
        else ""
    var refreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentRemotePath, currentForceOriginal) {
        currentUseSd = shouldUseSdImagePreview(currentRemotePath, currentForceOriginal, deviceRepo)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (currentUseSd) {
                        TextButton(onClick = { originalOverrides = originalOverrides + currentRemotePath }) {
                            Text(text = stringResource(R.string.image_open_original), color = Color.White)
                        }
                    }
                    IconButton(
                        onClick = {
                            if (currentRemotePath.isBlank()) return@IconButton
                            val dir = File(ZhomeApp.instance.cacheDir, "image_preview")
                            val baseName = imagePreviewCacheBaseName(currentRemotePath, currentUseSd)
                            File(dir, "$baseName.bin").delete()
                            File(dir, "$baseName.tmp").delete()
                            refreshNonce++
                        }
                    ) { Icon(Icons.Default.Refresh, stringResource(R.string.media_cd_refresh)) }
                    if (counter.isNotEmpty()) {
                        Text(counter, modifier = Modifier.padding(end = 16.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { remotePaths[it] },
            ) { page ->
                ImagePage(
                    remotePath = remotePaths[page],
                    forceOriginal = remotePaths[page] in originalOverrides,
                    refreshNonce = refreshNonce,
                    prefs = prefs,
                    deviceRepo = deviceRepo,
                )
            }
        }
    }
}

@Composable
private fun ImagePage(
    remotePath: String,
    forceOriginal: Boolean,
    refreshNonce: Int,
    prefs: com.ithingtalk.zhome.data.local.prefs.LocalPrefs,
    deviceRepo: com.ithingtalk.zhome.data.repository.DeviceRepository,
) {
    val appCtx = LocalContext.current.applicationContext
    var useSdVariant by remember(remotePath, forceOriginal) { mutableStateOf(false) }
    var resolvedRemotePath by remember(remotePath, forceOriginal) { mutableStateOf(remotePath) }
    val cacheBaseName = imagePreviewCacheBaseName(remotePath, useSdVariant)
    var resolved by remember(resolvedRemotePath, refreshNonce) { mutableStateOf<NasMediaResolution?>(null) }
    var cacheFile by remember(cacheBaseName, refreshNonce) { mutableStateOf<File?>(null) }
    var downloading by remember(cacheBaseName, refreshNonce) { mutableStateOf(false) }
    var progressText by remember(cacheBaseName, refreshNonce) { mutableStateOf<String?>(null) }
    var downloadError by remember(cacheBaseName, refreshNonce) { mutableStateOf<String?>(null) }
    val alive = remember(cacheBaseName, refreshNonce) { AtomicBoolean(true) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var loopbackSessionId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(resolvedRemotePath, refreshNonce) {
        alive.set(true)
        onDispose {
            alive.set(false)
            loopbackSessionId?.let { sid ->
                runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
            }
            loopbackSessionId = null
        }
    }

    LaunchedEffect(resolvedRemotePath, refreshNonce) {
        loopbackSessionId?.let { sid ->
            runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
        }
        loopbackSessionId = null
        val targetPath = resolveImagePreviewTargetPath(remotePath, forceOriginal, prefs, deviceRepo)
        useSdVariant = targetPath != remotePath
        resolvedRemotePath = targetPath
        val r = resolveNasMediaUrl(resolvedRemotePath, prefs, deviceRepo)
        resolved = r
        loopbackSessionId = r.loopbackSessionId
    }

    val r = resolved

    LaunchedEffect(r?.uri, r?.httpUser, r?.httpPass) {
        val uri = r?.uri ?: return@LaunchedEffect
        if (r.error != null) return@LaunchedEffect
        // Only treat the final file as cache-hit (tmp files don't count).
        if (cacheFile?.isFile == true) return@LaunchedEffect
        if (downloading) return@LaunchedEffect
        if (!alive.get()) return@LaunchedEffect

        try {
            downloading = true
            downloadError = null
            progressText = appCtx.getString(R.string.media_bytes_zero)

            val app = ZhomeApp.instance
            val dir = File(app.cacheDir, "image_preview").apply { mkdirs() }
            val finalFile = File(dir, "$cacheBaseName.bin")
            val tmpFile = File(dir, "$cacheBaseName.tmp")
            // If we have a complete cached file already, use it.
            if (finalFile.isFile && finalFile.length() > 0L) {
                cacheFile = finalFile
                return@LaunchedEffect
            }
            // No complete cache → always restart download from scratch.
            if (tmpFile.exists()) tmpFile.delete()

            val res = NasHttpDownload.downloadAuthenticated(
                url = uri,
                user = r.httpUser.orEmpty(),
                pass = r.httpPass.orEmpty(),
                dest = tmpFile,
                onProgress = { transferred, total ->
                    if (!alive.get()) return@downloadAuthenticated
                    val text = if (total > 0L) {
                        val pct = ((transferred * 100L) / total).toInt().coerceIn(0, 100)
                        String.format(
                            Locale.US,
                            "%d%%  (%s / %s)",
                            pct,
                            formatBytes(transferred),
                            formatBytes(total),
                        )
                    } else {
                        String.format(Locale.US, "%s", formatBytes(transferred))
                    }
                    mainHandler.post {
                        if (alive.get()) progressText = text
                    }
                },
            )
            res.getOrThrow()
            if (!tmpFile.renameTo(finalFile)) {
                // Fall back to keeping tmp if rename fails, but don't cache-hit partial file.
                throw IllegalStateException(appCtx.getString(R.string.media_cache_save_failed))
            }
            cacheFile = finalFile
        } catch (e: Exception) {
            if (alive.get()) downloadError = e.message ?: appCtx.getString(R.string.media_download_failed)
        } finally {
            if (alive.get()) downloading = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            r == null -> CircularProgressIndicator(color = Color.White)
            r.error != null -> Text(r.error, color = Color.White, modifier = Modifier.padding(24.dp))
            downloadError != null -> Text(
                downloadError ?: appCtx.getString(R.string.media_download_failed),
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
            downloading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    progressText?.let {
                        Text(it, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            cacheFile != null && cacheFile!!.isFile -> ZoomableNasImage(
                imageUrl = cacheFile!!.toURI().toString(),
                httpUser = null,
                httpPass = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            else -> Unit
        }
    }
}

private fun imagePreviewCacheBaseName(remotePath: String, useSdVariant: Boolean): String {
    val variant = if (useSdVariant) "sd" else "original"
    return "img_${(remotePath + "#" + variant).hashCode().toUInt().toString(16)}"
}

private fun formatBytes(n: Long): String {
    if (n <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    return if (idx == 0) "${v.toLong()} ${units[idx]}" else String.format(Locale.US, "%.1f %s", v, units[idx])
}
