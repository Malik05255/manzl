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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale

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
                    title = {
                        Column {
                            Text("مترجم الأفلام", fontWeight = FontWeight.Bold)
                            Text(
                                "تركي ← عربي • سحابي سريع",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.padding(16.dp))
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "فيلمك، لكن عربي",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "اختر الفيلم واضغط ترجمة. نرسل الصوت المضغوط فقط إلى السحابة، ويظل الفيديو الأصلي على جهازك.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeaturePill("Whisper Large V3")
                            FeaturePill("Gemini")
                        }
                        Text(
                            "حتى 3 ساعات • جزء واحد أو جزآن مخفيان تلقائيًا • ملف SRT واحد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.Movie,
                                    contentDescription = null,
                                    modifier = Modifier.padding(12.dp).size(30.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.videoName.ifBlank { "اختر فيلمًا للبدء" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val duration = state.videoDurationMs.takeIf { it > 0L }?.let(::formatMediaDuration)
                                Text(
                                    duration?.let { "المدة $it • الفيديو لا يُرفع" }
                                        ?: "يدعم MP4 وMKV ومعظم صيغ الفيديو الشائعة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (state.isRunning) "جاري العمل" else if (state.srtFile != null) "اكتملت الترجمة" else "جاهز",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(state.stage, style = MaterialTheme.typography.titleMedium)
                            }
                            if (state.srtFile != null) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (state.isRunning || state.progress > 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        if (state.partCount > 0 || state.uploadedBytes > 0L) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.partCount > 0) FeaturePill(if (state.partCount == 1) "صوت واحد" else "جزآن تلقائيًا")
                                if (state.uploadedBytes > 0L) FeaturePill(formatBytes(state.uploadedBytes) + " رفع")
                            }
                        }

                        if (state.isRunning) {
                            FilledTonalButton(
                                onClick = viewModel::cancel,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("جاهز للمشاهدة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("${state.cues.size} مقطع ترجمة عربي • ملف الفيديو الأصلي لم يتغير")
                            if (state.processingMs > 0L) {
                                Text(
                                    "الوقت الكلي ${formatMediaDuration(state.processingMs)}" +
                                        state.cloudMetrics.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = { showPlayer = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
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

                    Text("معاينة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            state.cues.take(5).forEachIndexed { index, cue ->
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {
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
                                }
                                if (index < state.cues.take(5).lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }

                Text(
                    "الخصوصية: لا نرفع الفيديو. يُنشئ التطبيق نسخة صوتية صغيرة مؤقتة، وترجع السحابة النص والتوقيت فقط.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 22.dp),
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("حجم الترجمة", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
                        listOf(18f to "ص", 22f to "م", 27f to "ك").forEach { (size, label) ->
                            TextButton(onClick = { subtitleSize = size }) {
                                Text(
                                    label,
                                    color = if (subtitleSize == size) MaterialTheme.colorScheme.primary else Color.White,
                                    fontWeight = if (subtitleSize == size) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
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
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else "${bytes / 1024} KB"
}

private fun formatMediaDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return when {
        hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        else -> String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}
