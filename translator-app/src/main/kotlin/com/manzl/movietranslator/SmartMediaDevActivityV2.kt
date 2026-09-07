package com.manzl.movietranslator

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartMediaDevActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 713)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = PremiumBlue,
                    onPrimary = PremiumWhite,
                    background = PremiumBg,
                    surface = PremiumWhite,
                    onSurface = PremiumInk,
                    error = PremiumRed,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                SmartMediaV2(vm)
            }
        }
    }
}

@Composable
private fun SmartMediaV2(viewModel: MovieTranslatorViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gateway = remember { SmartMediaGatewayClient(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var retention by remember { mutableStateOf(SmartRetention.SEVEN_DAYS) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var remoteRunning by remember { mutableStateOf(false) }
    var remoteProgress by remember { mutableFloatStateOf(0f) }
    var remoteStage by remember { mutableStateOf("") }
    var remoteResult by remember { mutableStateOf<SmartMediaRemoteResult?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteSubtitle by remember { mutableStateOf<File?>(null) }
    var playerUri by remember { mutableStateOf<Uri?>(null) }
    var playerSrt by remember { mutableStateOf<File?>(null) }
    var playerName by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.premiumDisplayName(uri))
    }

    fun handleRemoteResult(result: Result<SmartMediaRemoteResult?>) {
        result.onSuccess { value ->
            if (value != null) {
                remoteResult = value
                remoteSubtitle = runCatching { value.writeSubtitle(context) }.getOrNull()
                remoteProgress = 1f
                remoteStage = "جاهز للمشاهدة"
            }
        }.onFailure { error ->
            remoteError = error.message ?: "تعذر إكمال المهمة."
            remoteStage = "تعذر إكمال المهمة"
        }
        remoteRunning = false
    }

    fun runRemote(sourceUrl: String) {
        remoteRunning = true
        remoteProgress = 0f
        remoteStage = "إرسال المهمة للسحابة"
        remoteResult = null
        remoteError = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    gateway.translateUrl(sourceUrl, retention) { progress, stage ->
                        scope.launch {
                            remoteProgress = progress
                            remoteStage = stage
                        }
                    }
                }
            }
            handleRemoteResult(result.map { it })
        }
    }

    LaunchedEffect(Unit) {
        if (gateway.lastPendingJobId() != null && remoteResult == null && !remoteRunning) {
            remoteRunning = true
            remoteStage = "استعادة المهمة السحابية"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    gateway.resumePending { progress, stage ->
                        scope.launch {
                            remoteProgress = progress
                            remoteStage = stage
                        }
                    }
                }
            }
            handleRemoteResult(result)
        }
    }

    val empty = state.videoUri == null && remoteResult == null && !remoteRunning

    Scaffold(containerColor = PremiumBg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF102B53), Color(0xFF1D4F8C), PremiumBg, PremiumBg),
                        endY = if (empty) 1500f else 850f,
                    )
                )
                .padding(padding)
        ) {
            if (empty) {
                EntryPanel(
                    modifier = Modifier.align(Alignment.Center),
                    retention = retention,
                    onRetention = { retention = it },
                    onFile = { picker.launch(arrayOf("video/*")) },
                    onUrl = { showUrlDialog = true },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("مترجم H AI الرقمي", color = PremiumWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    SourcePanel(retention, { retention = it }, { picker.launch(arrayOf("video/*")) }, { showUrlDialog = true })

                    if (state.videoUri != null) {
                        LocalPanel(
                            state = state,
                            onStart = viewModel::start,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = viewModel::cancel,
                            onWatch = {
                                val uri = state.videoUri
                                val srt = state.srtFile
                                if (uri != null && srt != null) {
                                    playerUri = uri; playerSrt = srt; playerName = state.videoName
                                }
                            },
                        )
                    }

                    if (remoteRunning || remoteResult != null) {
                        RemotePanel(
                            running = remoteRunning,
                            progress = remoteProgress,
                            stage = remoteStage,
                            result = remoteResult,
                            onWatch = {
                                val r = remoteResult
                                val s = remoteSubtitle
                                if (r != null && s != null) {
                                    playerUri = Uri.parse(r.playbackUrl)
                                    playerSrt = s
                                    playerName = r.title
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showUrlDialog) {
        UrlDialog(
            retention = retention,
            onRetention = { retention = it },
            onDismiss = { showUrlDialog = false },
            onStart = { url -> showUrlDialog = false; runRemote(url) },
        )
    }

    remoteError?.let { message ->
        AlertDialog(
            onDismissRequest = { remoteError = null },
            confirmButton = { TextButton(onClick = { remoteError = null }) { Text("إغلاق") } },
            title = { Text("تعذر إكمال الرابط") },
            text = { Text(message) },
        )
    }

    val uri = playerUri
    val srt = playerSrt
    if (uri != null && srt != null) {
        PremiumCinemaPlayerDialog(
            videoUri = uri,
            subtitleFile = srt,
            movieName = playerName,
            onDismiss = { playerUri = null; playerSrt = null; playerName = "" },
        )
    }
}

@Composable
private fun EntryPanel(
    modifier: Modifier,
    retention: SmartRetention,
    onRetention: (SmartRetention) -> Unit,
    onFile: () -> Unit,
    onUrl: () -> Unit,
) {
    Column(
        modifier.widthIn(max = 520.dp).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("مترجم H AI الرقمي", color = PremiumWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF123A68)),
            border = BorderStroke(1.dp, Color(0xFF70BCFF)),
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF8FCBFF), modifier = Modifier.size(48.dp))
                Text("اختر الفيلم", color = PremiumWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onFile, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("من الجهاز")
                    }
                    OutlinedButton(onClick = onUrl, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Default.InsertLink, null); Spacer(Modifier.width(6.dp)); Text("رابط مباشر")
                    }
                }
                RetentionChips(retention, onRetention, dark = true)
                Text(
                    "الرابط المباشر يعمل بالكامل في السحابة؛ بعد بدء المهمة يمكن إغلاق التطبيق وتستمر المعالجة.",
                    color = Color(0xFFD7E8FF), fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SourcePanel(retention: SmartRetention, onRetention: (SmartRetention) -> Unit, onFile: () -> Unit, onUrl: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFile, modifier = Modifier.weight(1f)) { Text("من الجهاز") }
                OutlinedButton(onClick = onUrl, modifier = Modifier.weight(1f)) { Text("رابط مباشر") }
            }
            RetentionChips(retention, onRetention, dark = false)
        }
    }
}

@Composable
private fun RetentionChips(retention: SmartRetention, onRetention: (SmartRetention) -> Unit, dark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("الاحتفاظ بالفيلم", color = if (dark) Color(0xFFD7E8FF) else PremiumInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(SmartRetention.NONE, SmartRetention.ONE_DAY, SmartRetention.SEVEN_DAYS, SmartRetention.THIRTY_DAYS).forEach { option ->
                FilterChip(
                    selected = retention == option,
                    onClick = { onRetention(option) },
                    label = { Text(option.arabicLabel, fontSize = 10.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UrlDialog(retention: SmartRetention, onRetention: (SmartRetention) -> Unit, onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("رابط الفيلم المباشر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://…/movie.mp4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RetentionChips(retention, onRetention, dark = false)
                Text("H AI سيحدد اللغة تلقائيًا، ثم يترجم إلى العربية.", color = PremiumMuted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(url.trim()) },
                enabled = url.startsWith("https://") || url.startsWith("http://"),
            ) { Text("ابدأ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun LocalPanel(
    state: TranslatorUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onWatch: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.videoName, color = PremiumInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.stage, color = PremiumMuted, fontSize = 12.sp)
            if (state.isRunning) {
                CircularProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.CenterHorizontally).size(72.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStop, modifier = Modifier.weight(.4f), colors = ButtonDefaults.buttonColors(containerColor = PremiumRed)) {
                        Icon(Icons.Default.Stop, null); Text("إيقاف")
                    }
                    Button(onClick = if (state.isPaused) onResume else onPause, modifier = Modifier.weight(.6f)) {
                        Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Text(if (state.isPaused) "استمرار" else "مؤقت")
                    }
                }
            } else if (state.srtFile != null) {
                Button(onClick = onWatch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("مشاهدة مترجمة")
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ابدأ الترجمة") }
            }
        }
    }
}

@Composable
private fun RemotePanel(
    running: Boolean,
    progress: Float,
    stage: String,
    result: SmartMediaRemoteResult?,
    onWatch: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(result?.title ?: "فيلم من رابط مباشر", color = PremiumInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (running) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(82.dp))
                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", color = PremiumBlue, fontWeight = FontWeight.Bold)
                }
                Text(stage.ifBlank { "المعالجة السحابية" }, color = PremiumBlue, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("يمكنك إغلاق التطبيق؛ المهمة محفوظة في السحابة وسيستعيدها H AI عند العودة.", color = PremiumMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (result != null) {
                Text("اللغة: ${result.detectedLanguage}", color = PremiumMuted, fontSize = 11.sp)
                Text(result.providers, color = PremiumMuted, fontSize = 10.sp)
                if (result.summary.isNotBlank()) {
                    Text("الملخص", color = PremiumInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(result.summary, color = PremiumInk, fontSize = 12.sp)
                }
                if (result.characters.isNotEmpty()) {
                    Text("الشخصيات: ${result.characters.take(6).joinToString("، ")}", color = PremiumMuted, fontSize = 11.sp)
                }
                Button(onClick = onWatch, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("مشاهدة الآن بالعربية")
                }
            }
        }
    }
}
