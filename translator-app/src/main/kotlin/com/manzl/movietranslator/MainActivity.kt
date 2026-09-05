package com.manzl.movietranslator

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.media3.ui.PlayerView
import java.io.File

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
        if (uri != null) {
            viewModel.selectVideo(uri, context.displayName(uri))
        }
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
                Text(
                    text = "تركي ← عربي، على جهازك",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "يستخرج التطبيق صوت الفيلم فقط، يحوّل الحوار التركي إلى نص محليًا، ثم يترجمه للعربية ويعرضه مع الفيلم بدون إعادة ترميز الفيديو.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(34.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.videoName.ifBlank { "لم يتم اختيار فيلم" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (state.modelInstalled) "نموذج الاستماع التركي مثبت" else "يُنزل نموذج تركي صغير في أول استخدام فقط",
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.stage, style = MaterialTheme.typography.titleMedium)
                        if (state.isRunning || state.progress > 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                            )
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
                                Text(" بدء الترجمة")
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
                            Text("جاهز", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("تم إنشاء ${state.cues.size} مقطع ترجمة عربي. الفيديو الأصلي لا يُنسخ ولا يُضغط.")
                            Button(
                                onClick = { showPlayer = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(" مشاهدة الفيلم بالترجمة")
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

                    Text("معاينة الترجمة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    state.cues.take(6).forEach { cue ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = cue.translatedText,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = cue.sourceText,
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
                    text = "بعد تنزيل النماذج أول مرة، التعرف على الكلام والترجمة يعملان بدون إرسال الفيلم إلى خادم.",
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
        VideoPlayerDialog(
            videoUri = state.videoUri!!,
            subtitleFile = state.srtFile!!,
            onDismiss = { showPlayer = false },
        )
    }
}

@Composable
private fun VideoPlayerDialog(
    videoUri: Uri,
    subtitleFile: File,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(videoUri, subtitleFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val item = MediaItem.Builder()
                .setUri(videoUri)
                .setSubtitleConfigurations(listOf(subtitle))
                .build()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
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
