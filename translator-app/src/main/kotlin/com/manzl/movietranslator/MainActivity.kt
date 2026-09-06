package com.manzl.movietranslator

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: MovieTranslatorViewModel = viewModel()
                MovieTranslatorApp(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovieTranslatorApp(viewModel: MovieTranslatorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPlayer by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.displayName(uri))
    }
    val saveSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) viewModel.exportSrt(uri)
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = { Text("مترجم الأفلام") },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Text(
                                "تركي ← عربي",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "ترجمة أفلام تركية بضغطة واحدة",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "الفيديو يبقى على جوالك. نجهّز صوتًا صغيرًا فقط، نترجم الحوار في السحابة، ثم تشاهد الفيلم الأصلي بترجمة عربية احترافية.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeaturePill("لا رفع للفيديو")
                    FeaturePill("حتى 3 ساعات")
                    FeaturePill("SRT مباشر")
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(28.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.videoName.ifBlank { "اختر فيلمك" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                )
                                val meta = if (state.videoUri == null) {
                                    "MP4 • MKV • MOV وغيرها"
                                } else {
                                    buildString {
                                        append(formatClock(state.videoDurationMs))
                                        if (state.partCount > 0) append(" • ${state.partCount} ${if (state.partCount == 1) "مسار صوت" else "مساري صوت"}")
                                    }
                                }
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(
                            onClick = { videoPicker.launch(arrayOf("video/*")) },
                            enabled = !state.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.videoUri == null) "اختيار فيلم" else "تغيير الفيلم")
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            when {
                                state.isRunning -> "جاري صنع الترجمة"
                                state.srtFile != null -> "الترجمة جاهزة"
                                else -> "جاهز عندك"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(state.stage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (state.isRunning || state.progress > 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${(state.progress * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                                if (state.uploadedBytes > 0L) Text("رفع ${formatBytes(state.uploadedBytes)}", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (state.isRunning) {
                            FilledTonalButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Text(" إيقاف")
                            }
                        } else if (state.srtFile == null) {
                            Button(
                                onClick = viewModel::start,
                                enabled = state.videoUri != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null)
                                Text(" ترجمة الآن")
                            }
                        }
                    }
                }

                if (state.srtFile != null && state.videoUri != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("جاهز للمشاهدة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "${state.cues.size} مقطع ترجمة • ${formatDuration(state.processingMs)}" +
                                    if (state.uploadedBytes > 0L) " • رفع ${formatBytes(state.uploadedBytes)}" else ""
                            )
                            if (state.cloudMetrics.isNotBlank()) {
                                Text(state.cloudMetrics, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { showPlayer = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(" مشاهدة الآن")
                            }
                            OutlinedButton(
                                onClick = { saveSrt.launch("${state.videoName.substringBeforeLast('.', state.videoName)}_ar.srt") },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Text(" حفظ ملف SRT")
                            }
                        }
                    }

                    Text("لمحة من الترجمة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    state.cues.take(5).forEach { cue ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                cue.translatedText,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                cue.sourceText,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }

                Text(
                    "نرفع الصوت المضغوط فقط. الفيلم لا يغادر جهازك، ولا تتم إعادة ترميزه بعد الترجمة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("حسنًا") } },
            title = { Text("تعذر إكمال العملية") },
            text = { Text(state.error ?: "") },
        )
    }

    if (showPlayer && state.videoUri != null && state.srtFile != null) {
        CinemaPlayerDialog(
            videoUri = state.videoUri!!,
            subtitleFile = state.srtFile!!,
            movieName = state.videoName,
            onDismiss = { showPlayer = false },
        )
    }
}

@Composable
private fun FeaturePill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CinemaPlayerDialog(
    videoUri: Uri,
    subtitleFile: File,
    movieName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var subtitleSize by remember { mutableStateOf(22f) }
    val player = remember(videoUri, subtitleFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            setMediaItem(
                MediaItem.Builder()
                    .setUri(videoUri)
                    .setSubtitleConfigurations(listOf(subtitle))
                    .build()
            )
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                            controllerAutoShow = true
                            controllerShowTimeoutMs = 3000
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                            subtitleView?.setBottomPaddingFraction(0.10f)
                            subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                        }
                    },
                    update = { view ->
                        view.player = player
                        view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.52f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                    Text(
                        movieName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("ترجمة عربية", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.66f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("حجم الترجمة", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { subtitleSize = (subtitleSize - 2f).coerceAtLeast(16f) }) {
                            Text("أصغر", color = Color.White)
                        }
                        Text("${subtitleSize.toInt()}", color = Color.White, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { subtitleSize = (subtitleSize + 2f).coerceAtMost(34f) }) {
                            Text("أكبر", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun android.content.Context.displayName(uri: Uri): String {
    val fromProvider = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    return fromProvider ?: uri.lastPathSegment ?: "movie"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    return if (bytes < 1024L * 1024L) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMinutes = ms / 60_000L
    return if (totalMinutes < 60) "$totalMinutes د" else "${totalMinutes / 60} س ${totalMinutes % 60} د"
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
    return if (seconds < 60L) "$seconds ث" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} د"
}
