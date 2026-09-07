package com.manzl.movietranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val DigitalV3Font = FontFamily(
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-medium"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-black"), weight = FontWeight.Bold),
)

class DigitalAiMainActivityV3 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 711)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = PremiumBlue,
                    onPrimary = PremiumWhite,
                    background = PremiumBg,
                    surface = PremiumWhite,
                    onSurface = PremiumInk,
                    onBackground = PremiumInk,
                    surfaceVariant = PremiumBlueSoft,
                    onSurfaceVariant = PremiumMuted,
                    error = PremiumRed,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                DigitalV3App(vm)
            }
        }
    }
}

@Composable
private fun DigitalV3App(viewModel: MovieTranslatorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val cloudError by viewModel.cloudUiError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(PremiumTab.HOME) }
    var playerUri by remember { mutableStateOf<Uri?>(null) }
    var playerSrt by remember { mutableStateOf<File?>(null) }
    var playerName by remember { mutableStateOf("") }
    var relinkTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    var downloadTarget by remember { mutableStateOf<CloudMovieItem?>(null) }

    val homePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.selectVideo(uri, context.premiumDisplayName(uri))
        }
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val movie = relinkTarget
        if (uri != null && movie != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.relinkMovie(movie, uri)
        }
        relinkTarget = null
    }
    val cloudSrtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val movie = downloadTarget
        if (uri != null && movie != null) viewModel.exportCloudSrt(movie, uri)
        downloadTarget = null
    }
    val homeSrtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) viewModel.exportSrt(uri)
    }

    LaunchedEffect(tab) {
        when (tab) {
            PremiumTab.PROJECTS, PremiumTab.LIBRARY -> viewModel.refreshLibrary()
            PremiumTab.SETTINGS -> viewModel.refreshPlatforms()
            PremiumTab.HOME -> Unit
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = DigitalV3Font),
    ) {
        Scaffold(
            containerColor = PremiumBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { DigitalV3BottomBar(tab) { tab = it } },
        ) { padding ->
            val body = Modifier.padding(bottom = padding.calculateBottomPadding())
            when (tab) {
                PremiumTab.HOME -> DigitalV3Home(
                    modifier = body,
                    state = state,
                    onPickMovie = { homePicker.launch(arrayOf("video/*")) },
                    onStart = viewModel::start,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onHardStop = {
                        val uri = state.videoUri
                        val name = state.videoName
                        viewModel.cancel()
                        if (uri != null && name.isNotBlank()) viewModel.selectVideo(uri, name)
                    },
                    onWatch = {
                        if (state.videoUri != null && state.srtFile != null) {
                            playerUri = state.videoUri
                            playerSrt = state.srtFile
                            playerName = state.videoName
                        }
                    },
                    onExport = {
                        val base = state.videoName.substringBeforeLast('.', state.videoName).ifBlank { "movie" }
                        homeSrtSaver.launch("${base}_ar.srt")
                    },
                )
                PremiumTab.PROJECTS -> PremiumProjectsScreen(
                    modifier = body,
                    state = state,
                    movies = library,
                    onRefresh = viewModel::refreshLibrary,
                    onWatch = { movie ->
                        val uri = movie.videoUri?.let(Uri::parse)
                        val srt = viewModel.prepareLibrarySubtitle(movie)
                        if (uri != null && srt != null && movie.localAvailable) {
                            playerUri = uri; playerSrt = srt; playerName = movie.movieName
                        }
                    },
                )
                PremiumTab.LIBRARY -> PremiumLibraryScreen(
                    modifier = body,
                    movies = library,
                    onRefresh = viewModel::refreshLibrary,
                    onRelink = { movie -> relinkTarget = movie; relinkPicker.launch(arrayOf("video/*")) },
                    onDownload = { movie ->
                        downloadTarget = movie
                        val base = movie.movieName.substringBeforeLast('.', movie.movieName).ifBlank { "movie" }
                        cloudSrtSaver.launch("${base}_ar.srt")
                    },
                    onDelete = viewModel::deleteTranslation,
                    onWatch = { movie ->
                        val uri = movie.videoUri?.let(Uri::parse)
                        val srt = viewModel.prepareLibrarySubtitle(movie)
                        if (uri != null && srt != null && movie.localAvailable) {
                            playerUri = uri; playerSrt = srt; playerName = movie.movieName
                        }
                    },
                )
                PremiumTab.SETTINGS -> PremiumSettingsScreen(body, platforms, viewModel::refreshPlatforms)
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError(); viewModel.clearCloudUiError() },
            confirmButton = { TextButton(onClick = { viewModel.clearError(); viewModel.clearCloudUiError() }) { Text("إغلاق") } },
            title = { Text("تعذر إكمال العملية") },
            text = { Text(visibleError) },
            containerColor = PremiumWhite,
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
private fun DigitalV3Home(
    modifier: Modifier,
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onHardStop: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val online by produceState(initialValue = CloudConnectivity.isOnline(context), context) {
        while (true) { value = CloudConnectivity.isOnline(context); delay(2_000L) }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(PremiumBg)) {
        val compact = maxWidth < 370.dp
        val roomy = maxWidth >= 480.dp
        val pageWidth = if (roomy) 560.dp else maxWidth
        val side = if (compact) 12.dp else 16.dp
        val gap = if (compact) 10.dp else 12.dp
        val hasMovie = state.videoUri != null

        if (!hasMovie) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF12325E), Color(0xFF1C5594), PremiumBg, PremiumBg),
                        startY = 0f,
                        endY = 1250f,
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = pageWidth)
                        .align(Alignment.Center)
                        .padding(horizontal = side),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                ) {
                    DigitalV3Header(compact)
                    DigitalV3Hero(state, onPickMovie, compact)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.horizontalGradient(listOf(Color(0xFF102B53), Color(0xFF1D4F8C), Color(0xFF2F6DB4)))
                    )
                ) {
                    Column(
                        Modifier.widthIn(max = pageWidth).align(Alignment.TopCenter).statusBarsPadding()
                            .padding(horizontal = side, vertical = if (compact) 8.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp),
                    ) {
                        DigitalV3Header(compact)
                        DigitalV3Hero(state, onPickMovie, compact)
                        Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
                    }
                }

                AnimatedVisibility(visible = true) {
                    Column(
                        Modifier.widthIn(max = pageWidth).padding(horizontal = side, vertical = gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        DigitalV3Workflow(state, compact)
                        DigitalV3Metrics(state, compact)
                        DigitalV3Status(online, compact)
                        DigitalV3Actions(state, onStart, onPause, onResume, onHardStop, onWatch, onExport, compact)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitalV3Header(compact: Boolean) {
    Box(Modifier.fillMaxWidth().height(if (compact) 46.dp else 52.dp), contentAlignment = Alignment.Center) {
        Text(
            "مترجم \u200EH AI\u200E الرقمي",
            color = PremiumWhite,
            fontFamily = DigitalV3Font,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 19.sp else 22.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DigitalV3Hero(state: TranslatorUiState, onPickMovie: () -> Unit, compact: Boolean) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val status = when {
        state.srtFile != null -> "مكتمل"
        state.isPaused -> "متوقف مؤقتًا"
        !state.isRunning -> "جاهز"
        percent < 15 -> "تجهيز"
        percent < 70 -> "فهم الصوت"
        percent < 90 -> "ترجمة"
        percent < 98 -> "مراجعة"
        else -> "إنهاء"
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(if (compact) 178.dp else 194.dp),
        shape = RoundedCornerShape(if (compact) 28.dp else 32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123A68)),
        border = BorderStroke(1.dp, Color(0xFF70BCFF)),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 9.dp else 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
            ) {
                DigitalV3Poster(state, compact, Modifier.weight(0.35f))
                DigitalV3Progress(percent, status, compact, Modifier.weight(0.39f))
                DigitalV3Upload(onPickMovie, !state.isRunning, compact, Modifier.weight(0.26f))
            }
        }
    }
}

@Composable
private fun DigitalV3Poster(state: TranslatorUiState, compact: Boolean, modifier: Modifier) {
    val name = state.videoName.ifBlank { "اختر فيلمًا" }
    val size = when {
        name.length <= 12 -> if (compact) 15.sp else 17.sp
        name.length <= 20 -> if (compact) 13.sp else 15.sp
        name.length <= 30 -> if (compact) 11.sp else 13.sp
        name.length <= 42 -> if (compact) 10.sp else 11.sp
        else -> if (compact) 9.sp else 10.sp
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                name,
                color = PremiumWhite,
                fontFamily = DigitalV3Font,
                fontSize = size,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = size * 1.05f,
                modifier = Modifier.fillMaxWidth().height(if (compact) 30.dp else 34.dp),
            )
            if (state.videoUri != null) {
                PremiumVideoThumb(
                    uri = state.videoUri,
                    duration = premiumClock(state.videoDurationMs),
                    modifier = Modifier.width(if (compact) 92.dp else 104.dp).height(if (compact) 117.dp else 130.dp),
                )
            } else {
                Box(
                    Modifier.width(if (compact) 92.dp else 104.dp).height(if (compact) 117.dp else 130.dp)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Movie, null, tint = Color(0xFF8FCBFF), modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun DigitalV3Progress(percent: Int, status: String, compact: Boolean, modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(if (compact) 96.dp else 110.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF72C2FF),
                    trackColor = Color(0xFF42658C),
                    strokeWidth = if (compact) 8.dp else 9.dp,
                )
                Text("$percent%", color = PremiumWhite, fontFamily = DigitalV3Font, fontSize = if (compact) 26.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(status, color = Color(0xFFD7E8FF), fontFamily = DigitalV3Font, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 7.dp), maxLines = 1)
        }
    }
}

@Composable
private fun DigitalV3Upload(onPickMovie: () -> Unit, enabled: Boolean, compact: Boolean, modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Button(
                onClick = onPickMovie,
                enabled = enabled,
                modifier = Modifier.size(if (compact) 55.dp else 62.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F7DE9), disabledContainerColor = Color(0xFF31567E), contentColor = PremiumWhite),
            ) { Icon(Icons.Default.CloudUpload, "رفع فيلم", modifier = Modifier.size(if (compact) 25.dp else 29.dp)) }
            Text("رفع فيلم", color = Color(0xFFDCEBFF), fontFamily = DigitalV3Font, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun DigitalV3Workflow(state: TranslatorUiState, compact: Boolean) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val active = when {
        state.srtFile != null || percent >= 98 -> 4
        percent >= 90 -> 3
        percent >= 70 -> 1
        else -> 2
    }
    val specs = listOf(
        Triple("تحليل", Icons.Default.CloudUpload, 0), Triple("ترجمة", Icons.Default.Description, 1),
        Triple("صوت", Icons.Default.GraphicEq, 2), Triple("دمج", Icons.Default.Movie, 3), Triple("إنهاء", Icons.Default.Flag, 4),
    )
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 25.dp else 29.dp), colors = CardDefaults.cardColors(containerColor = PremiumWhite), border = BorderStroke(1.dp, PremiumHairline)) {
        Column(Modifier.padding(horizontal = if (compact) 9.dp else 13.dp, vertical = if (compact) 12.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("خطوات العمل", color = PremiumInk, fontFamily = DigitalV3Font, fontSize = if (compact) 18.sp else 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(if (compact) 14.dp else 17.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    specs.reversed().forEachIndexed { index, spec ->
                        DigitalV3Step(spec.first, spec.second, spec.third == active, digitalV3StepDone(spec.third, percent, state.srtFile != null), compact, Modifier.weight(1f))
                        if (index < specs.lastIndex) Box(Modifier.padding(top = if (compact) 20.dp else 23.dp).width(if (compact) 6.dp else 10.dp).height(2.dp).background(Color(0xFFC7D4E3), RoundedCornerShape(99.dp)))
                    }
                }
            }
        }
    }
}

private fun digitalV3StepDone(index: Int, percent: Int, completed: Boolean): Boolean = when (index) {
    0 -> true; 2 -> percent >= 70; 1 -> percent >= 90; 3 -> percent >= 98; 4 -> completed; else -> false
}

@Composable
private fun DigitalV3Step(title: String, icon: ImageVector, active: Boolean, done: Boolean, compact: Boolean, modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (active) (if (compact) 48.dp else 54.dp) else (if (compact) 42.dp else 47.dp)).background(if (active) PremiumBlueSoft else Color(0xFFF0F4F9), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (active) PremiumBlue else Color(0xFF69788D), modifier = Modifier.size(if (compact) 20.dp else 23.dp))
                if (done && !active) Box(Modifier.align(Alignment.TopEnd).size(16.dp).background(PremiumGreen, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = PremiumWhite, modifier = Modifier.size(10.dp)) }
            }
            Text(title, color = if (active) PremiumBlue else PremiumInk, fontFamily = DigitalV3Font, fontSize = if (compact) 11.sp else 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private data class DigitalV3Metric(val icon: ImageVector, val soft: Color, val tint: Color, val value: String, val label: String)

@Composable
private fun DigitalV3Metrics(state: TranslatorUiState, compact: Boolean) {
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val model = when { percent < 15 -> "تجهيز"; percent < 70 -> "Whisper"; percent < 88 -> "Azure"; percent < 97 -> "Groq"; else -> "إنهاء" }
    val items = listOf(
        DigitalV3Metric(Icons.Default.CloudUpload, Color(0xFFEAF3FF), PremiumBlue, model, "الموديل"),
        DigitalV3Metric(Icons.Default.Schedule, Color(0xFFF1ECFF), Color(0xFF7657D8), premiumClock(state.videoDurationMs), "المدة"),
        DigitalV3Metric(Icons.Default.Schedule, Color(0xFFFFF2DB), Color(0xFFB97514), if (state.isRunning) premiumClock(remaining) else "—", "المتبقي"),
        DigitalV3Metric(Icons.Default.Folder, Color(0xFFE8F8F0), PremiumGreen, if (state.srtFile != null) "1/1" else "0/1", "النتيجة"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items.forEach { item -> DigitalV3MetricCard(item, compact, Modifier.weight(1f)) } }
}

@Composable
private fun DigitalV3MetricCard(item: DigitalV3Metric, compact: Boolean, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(if (compact) 20.dp else 22.dp), colors = CardDefaults.cardColors(containerColor = PremiumWhite), border = BorderStroke(1.dp, PremiumHairline)) {
        Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 9.dp else 11.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (compact) 34.dp else 38.dp).background(item.soft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = item.tint, modifier = Modifier.size(if (compact) 19.dp else 21.dp)) }
            Text(item.value, color = PremiumInk, fontFamily = DigitalV3Font, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.label, color = PremiumMuted, fontFamily = DigitalV3Font, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DigitalV3Status(online: Boolean, compact: Boolean) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(if (compact) 20.dp else 23.dp), colors = CardDefaults.cardColors(containerColor = PremiumWhite), border = BorderStroke(1.dp, PremiumHairline)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = if (compact) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { Text("الخلفية مفعّلة", color = PremiumInk, fontFamily = DigitalV3Font, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
            Card(Modifier.weight(1f), shape = RoundedCornerShape(if (compact) 20.dp else 23.dp), colors = CardDefaults.cardColors(containerColor = PremiumWhite), border = BorderStroke(1.dp, PremiumHairline)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = if (compact) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(if (online) Icons.Default.CloudDone else Icons.Default.CloudOff, null, tint = if (online) PremiumGreen else PremiumMuted, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text(if (online) "متصل" else "غير متصل", color = PremiumInk, fontFamily = DigitalV3Font, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun DigitalV3Actions(state: TranslatorUiState, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onHardStop: () -> Unit, onWatch: () -> Unit, onExport: () -> Unit, compact: Boolean) {
    when {
        state.isRunning -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onHardStop, modifier = Modifier.weight(0.42f).height(if (compact) 56.dp else 62.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE24B55))) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("إيقاف", fontFamily = DigitalV3Font, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Button(onClick = if (state.isPaused) onResume else onPause, modifier = Modifier.weight(0.58f).height(if (compact) 56.dp else 62.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.isPaused) Color(0xFF2E8A64) else PremiumBlue)) {
                    Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.isPaused) "استمرار" else "إيقاف مؤقت", fontFamily = DigitalV3Font, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        state.srtFile != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onWatch, modifier = Modifier.weight(1f).height(if (compact) 56.dp else 62.dp), shape = RoundedCornerShape(23.dp), colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("مشاهدة", fontFamily = DigitalV3Font, fontWeight = FontWeight.Bold) }
            Button(onClick = onExport, modifier = Modifier.weight(1f).height(if (compact) 56.dp else 62.dp), shape = RoundedCornerShape(23.dp), colors = ButtonDefaults.buttonColors(containerColor = PremiumWhite, contentColor = PremiumBlue), border = BorderStroke(1.dp, PremiumHairline)) { Text("حفظ SRT", fontFamily = DigitalV3Font, fontWeight = FontWeight.Bold) }
        }
        else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(if (compact) 58.dp else 64.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue)) { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(23.dp)); Spacer(Modifier.width(8.dp)); Text("ابدأ الترجمة", fontFamily = DigitalV3Font, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun DigitalV3BottomBar(tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    Surface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = PremiumShadow, spotColor = PremiumShadow), color = PremiumWhite, shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, Color(0xFFF0F4F9))) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding().height(72.dp)) {
            PremiumTab.entries.forEach { item ->
                val selected = item == tab
                val icon = when (item) { PremiumTab.HOME -> Icons.Default.Home; PremiumTab.PROJECTS -> Icons.Default.Folder; PremiumTab.LIBRARY -> Icons.Default.VideoLibrary; PremiumTab.SETTINGS -> Icons.Default.Settings }
                val label = when (item) { PremiumTab.HOME -> "الرئيسية"; PremiumTab.PROJECTS -> "مشاريعي"; PremiumTab.LIBRARY -> "المكتبة"; PremiumTab.SETTINGS -> "الإعدادات" }
                NavigationBarItem(selected = selected, onClick = { onTab(item) }, icon = { Icon(icon, null, modifier = Modifier.size(24.dp)) }, label = { Text(label, fontFamily = DigitalV3Font, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = PremiumBlue, selectedTextColor = PremiumBlue, indicatorColor = PremiumBlueSoft, unselectedIconColor = Color(0xFF75849A), unselectedTextColor = Color(0xFF75849A)))
            }
        }
    }
}
