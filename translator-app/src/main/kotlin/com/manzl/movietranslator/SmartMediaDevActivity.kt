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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
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

class SmartMediaDevActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 712)
        }
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
                SmartMediaDevScreen(vm)
            }
        }
    }
}

@Composable
private fun SmartMediaDevScreen(viewModel: MovieTranslatorViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val gateway = remember { SmartMediaGatewayClient(context.applicationContext) }

    var retention by remember { mutableStateOf(SmartRetention.SEVEN_DAYS) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var remoteRunning by remember { mutableStateOf(false) }
    var remoteStage by remember { mutableStateOf("") }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteResult by remember { mutableStateOf<SmartMediaRemoteResult?>(null) }
    var remoteSubtitle by remember { mutableStateOf<File?>(null) }
    var playerUri by remember { mutableStateOf<Uri?>(null) }
    var playerSrt by remember { mutableStateOf<File?>(null) }
    var playerName by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.premiumDisplayName(uri))
    }

    fun startRemote(url: String) {
        remoteRunning = true
        remoteError = null
        remoteResult = null
        remoteStage = "H AI يحدد اللغة وأفضل مسار..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    gateway.translateUrl(url, retention)
                }
            }.onSuccess { result ->
                remoteResult = result
                remoteSubtitle = runCatching { result.writeSubtitle(context) }.getOrNull()
                remoteStage = "جاهز للمشاهدة"
            }.onFailure { error ->
                remoteError = error.message ?: "تعذر ترجمة الرابط."
                remoteStage = "تعذر إكمال المهمة"
            }
            remoteRunning = false
        }
    }

    val hasLocal = state.videoUri != null
    val hasRemote = remoteResult != null || remoteRunning
    val isEmpty = !hasLocal && !hasRemote

    Scaffold(containerColor = PremiumBg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF102B53), Color(0xFF1D4F8C), PremiumBg, PremiumBg),
                        endY = if (isEmpty) 1400f else 850f,
                    )
                )
                .padding(padding)
        ) {
            if (isEmpty) {
                SmartEntryCard(
                    modifier = Modifier.align(Alignment.Center),
                    retention = retention,
                    onRetention = { retention = it },
                    onPickFile = { picker.launch(arrayOf("video/*")) },
                    onPickUrl = { showUrlDialog = true },
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
                    Text(
                        "مترجم H AI الرقمي",
                        color = PremiumWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    SmartSourceChooser(
                        retention = retention,
                        onRetention = { retention = it },
                        onPickFile = { picker.launch(arrayOf("video/*")) },
                        onPickUrl = { showUrlDialog = true },
                    )

                    if (hasLocal) {
                        LocalMovieCard(
                            state = state,
                            retention = retention,
                            onStart = viewModel::start,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = viewModel::cancel,
                            onWatch = {
                                val uri = state.videoUri
                                val srt = state.srtFile
                                if (uri != null && srt != null) {
                                    playerUri = uri
                                    playerSrt = srt
                                    playerName = state.videoName
                                }
                            },
                        )
                    }

                    if (hasRemote) {
                        RemoteMovieCard(
                            running = remoteRunning,
                            stage = remoteStage,
                            result = remoteResult,
                            onWatch = {
                                val result = remoteResult
                                val srt = remoteSubtitle
                                if (result != null && srt != null) {
                                    playerUri = Uri.parse(result.playbackUrl)
                                    playerSrt = srt
                                    playerName = result.title
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
        SmartUrlDialog(
            retention = retention,
            onRetention = { retention = it },
            onDismiss = { showUrlDialog = false },
            onStart = { url ->
                showUrlDialog = false
                startRemote(url)
            },
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
private fun SmartEntryCard(
    modifier: Modifier,
    retention: SmartRetention,
    onRetention: (SmartRetention) -> Unit,
    onPickFile: () -> Unit,
    onPickUrl: () -> Unit,
) {
    Column(
        modifier = modifier.widthIn(max = 520.dp).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("مترجم H AI الرقمي", color = PremiumWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF123A68)),
            border = BorderStroke(1.dp, Color(0xFF70BCFF)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF8FCBFF), modifier = Modifier.size(48.dp))
                Text("اختر طريقة الفيلم", color = PremiumWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPickFile, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("من الجهاز")
                    }
                    OutlinedButton(onClick = onPickUrl, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Default.InsertLink, null); Spacer(Modifier.width(6.dp)); Text("رابط مباشر")
                    }
                }
                RetentionSelector(retention, onRetention, dark = true)
                Text(
                    "الرابط المباشر هو الأخف على الجوال: المعالجة تتم سحابيًا بدون نسخ الفيلم للجهاز.",
                    color = Color(0xFFD4E6FF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SmartSourceChooser(
    retention: SmartRetention,
    onRetention: (SmartRetention) -> Unit,
    onPickFile: () -> Unit,
    onPickUrl: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onPickFile, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("فيلم من الجهاز") }
                OutlinedButton(onClick = onPickUrl, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("رابط مباشر") }
            }
            RetentionSelector(retention, onRetention, dark = false)
        }
    }
}

@Composable
private fun RetentionSelector(retention: SmartRetention, onRetention: (SmartRetention) -> Unit, dark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("الاحتفاظ بالفيلم", color = if (dark) Color(0xFFDCEBFF) else PremiumInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun SmartUrlDialog(
    retention: SmartRetention,
    onRetention: (SmartRetention) -> Unit,
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("رابط الفيلم المباشر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://…/movie.mp4") },
                    singleLine = true,
                )
                RetentionSelector(retention, onRetention, dark = false)
                Text("سيتم اكتشاف لغة الفيلم تلقائيًا وترجمته إلى العربية.", color = PremiumMuted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onStart(url.trim()) }, enabled = url.startsWith("http://") || url.startsWith("https://")) { Text("ابدأ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun LocalMovieCard(
    state: TranslatorUiState,
    retention: SmartRetention,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onWatch: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.videoName, color = PremiumInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.stage, color = PremiumMuted, fontSize = 12.sp)
            if (state.isRunning) {
                CircularProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.CenterHorizontally).size(72.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStop, modifier = Modifier.weight(0.4f), colors = ButtonDefaults.buttonColors(containerColor = PremiumRed)) { Icon(Icons.Default.Stop, null); Text("إيقاف") }
                    Button(onClick = if (state.isPaused) onResume else onPause, modifier = Modifier.weight(0.6f)) { Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Text(if (state.isPaused) "استمرار" else "مؤقت") }
                }
            } else if (state.srtFile != null) {
                Button(onClick = onWatch, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("مشاهدة مترجمة") }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("ابدأ الترجمة") }
            }
            if (retention != SmartRetention.NONE) {
                Text("ملاحظة التطوير: رفع نسخة الفيديو المحلي إلى R2 منفصل عن الترجمة الحالية وسيُفعّل بعد ربط Worker التخزين.", color = PremiumMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RemoteMovieCard(
    running: Boolean,
    stage: String,
    result: SmartMediaRemoteResult?,
    onWatch: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(result?.title ?: "فيلم من رابط مباشر", color = PremiumInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stage, color = if (running) PremiumBlue else PremiumGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (running) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(68.dp))
                Text("الفيلم يُسحب ويُفهم ويُترجم في السحابة. يمكنك إبقاء الجوال بدون معالجة محلية.", color = PremiumMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (result != null) {
                Text("اللغة: ${result.detectedLanguage} • ${result.providers}", color = PremiumMuted, fontSize = 11.sp)
                if (result.summary.isNotBlank()) {
                    Text("الملخص", color = PremiumInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(result.summary, color = PremiumInk, fontSize = 12.sp)
                }
                if (result.characters.isNotEmpty()) Text("الشخصيات: ${result.characters.take(6).joinToString("، ")}", color = PremiumMuted, fontSize = 11.sp)
                Button(onClick = onWatch, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("مشاهدة الآن بالعربية")
                }
            }
        }
    }
}
