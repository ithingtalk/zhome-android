package com.ithingtalk.zhome.ui.screens.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.remote.nas.NasHttpDownload
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(remotePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val prefs = remember { ZhomeApp.instance.prefs }
    val deviceRepo = remember { ZhomeApp.instance.deviceRepo }

    var phase by remember { mutableStateOf<DocPhase>(DocPhase.Loading) }
    var localFile by remember { mutableStateOf<File?>(null) }
    var loopbackSessionId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(remotePath) {
        onDispose {
            loopbackSessionId?.let { sid ->
                runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
            }
            loopbackSessionId = null
        }
    }

    LaunchedEffect(remotePath) {
        loopbackSessionId?.let { sid ->
            runCatching { NasLanLoopbackHttpServer.unregister(UUID.fromString(sid)) }
        }
        loopbackSessionId = null
        phase = DocPhase.Loading
        localFile = null
        val r = resolveNasMediaUrl(remotePath, prefs, deviceRepo)
        if (r.error != null) {
            phase = DocPhase.Error(r.error)
            return@LaunchedEffect
        }
        loopbackSessionId = r.loopbackSessionId
        val url = r.uri ?: run {
            phase = DocPhase.Error(appCtx.getString(R.string.doc_err_no_url))
            return@LaunchedEffect
        }
        val u = r.httpUser.orEmpty()
        val p = r.httpPass.orEmpty()

        val ext = remotePath.substringAfterLast('.', "").lowercase()
        val safeName = "${remotePath.hashCode() and 0x7FFF_FFFF}.$ext"
        val dest = File(context.cacheDir, "doc_view").apply { mkdirs() }.resolve(safeName)

        NasHttpDownload.downloadAuthenticated(url, u, p, dest).fold(
            onSuccess = {
                localFile = dest
                phase = DocPhase.Ready(ext)
            },
            onFailure = { e ->
                phase = DocPhase.Error(e.message ?: appCtx.getString(R.string.doc_download_failed))
            }
        )
    }

    val title = remotePath.substringAfterLast("/")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val p = phase) {
                is DocPhase.Loading -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.doc_loading),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is DocPhase.Error -> Text(
                    p.message,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
                is DocPhase.Ready -> {
                    val file = localFile
                    if (file == null || !file.exists()) {
                        Text(stringResource(R.string.doc_file_missing), Modifier.align(Alignment.Center))
                    } else {
                        when (p.ext) {
                            "pdf" -> PdfScrollView(file)
                            "txt", "log", "csv", "md", "json", "xml", "htm", "html" -> TextFileView(file)
                            else -> OpenExternallyPrompt(file, p.ext)
                        }
                    }
                }
            }
        }
    }
}

private sealed class DocPhase {
    data object Loading : DocPhase()
    data class Error(val message: String) : DocPhase()
    data class Ready(val ext: String) : DocPhase()
}

@Composable
private fun PdfScrollView(file: File) {
    val renderer = remember(file) {
        runCatching {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }.getOrNull()
    }
    if (renderer == null) {
        Text(stringResource(R.string.doc_cannot_open_pdf), modifier = Modifier.padding(24.dp))
        return
    }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    val pageCount = renderer.pageCount
    LazyColumn(Modifier.fillMaxSize()) {
        items(
            count = pageCount,
            key = { it }
        ) { pageIndex ->
            val bitmap = remember(pageIndex, file) {
                val page = renderer.openPage(pageIndex)
                val maxW = 1200
                val w = page.width
                val h = page.height
                val scale = minOf(1f, maxW.toFloat() / w)
                val bw = (w * scale).toInt().coerceAtLeast(1)
                val bh = (h * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bmp
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.doc_pdf_page, pageIndex + 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun TextFileView(file: File) {
    val appCtx = LocalContext.current.applicationContext
    val text = remember(file) {
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { e -> e.message ?: appCtx.getString(R.string.doc_read_error) }
    }
    SelectionContainer {
        Text(
            text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun OpenExternallyPrompt(file: File, ext: String) {
    val context = LocalContext.current
    val mime = mimeForExtension(ext)
    val openDocTitle = stringResource(R.string.doc_open_document)
    val openExternalLabel = stringResource(R.string.doc_open_external)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, openDocTitle))
            } catch (_: ActivityNotFoundException) {
            }
        }) {
            Text(openExternalLabel)
        }
    }
}

private fun mimeForExtension(ext: String): String = when (ext) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}
