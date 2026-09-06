package com.manzl.movietranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val AppBackground = Color(0xFFF6FAFE)
private val CardWhite = Color(0xFFFFFFFF)
private val Ink = Color(0xFF101D33)
private val Muted = Color(0xFF7C8A9E)
private val SoftBlue = Color(0xFFEAF3FC)
private val SoftBlue2 = Color(0xFFDDECFB)
private val AccentBlue = Color(0xFF4E83B7)
private val AccentBlueDark = Color(0xFF3D6F9F)
private val SuccessGreen = Color(0xFF3B956C)
private val SuccessSoft = Color(0xFFE5F3EC)
private val Hairline = Color(0xFFE7EDF4)
private val DangerRed = Color(0xFFB64A55)
private val ShadowTint = Color(0x1A7C9BB7)

private enum class LightTab { HOME, LIBRARY, SERVICES }

class LightMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsIfNeeded()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AccentBlue,
                    onPrimary = Color.White,
                    background = AppBackground,
                    surface = CardWhite,
                    onSurface = Ink,
                    onBackground = Ink,
                    surfaceVariant = SoftBlue,
                    onSurfaceVariant = Muted,
                    error = DangerRed,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                LightMovieTranslatorApp(vm)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }
}

@Composable
private fun LightMovieTranslatorApp(viewModel: MovieTranslatorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val cloudError by viewModel.cloudUiError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(LightTab.HOME) }
    var activePlayerUri by remember { mutableStateOf<Uri?>(null) }
    var activePlayerSrt by remember { mutableStateOf<File?>(null) }
    var activePlayerName by remember { mutableStateOf("") }
    var relinkTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    var downloadTarget by remember { mutableStateOf<CloudMovieItem?>(null) }

    val homePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.lightDisplayName(uri))
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val movie = relinkTarget
        if (uri != null && movie != null) viewModel.relinkMovie(movie, uri)
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
            LightTab.LIBRARY -> viewModel.refreshLibrary()
            LightTab.SERVICES -> viewModel.refreshPlatforms()
            LightTab.HOME -> Unit
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFBFDFF), AppBackground, Color(0xFFF2F7FC))
                    )
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { LightBottomBar(tab = tab, onTab = { tab = it }) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .statusBarsPadding()
                        .fillMaxSize()
                ) {
                    LightHeader(tab)
                    when (tab) {
                        LightTab.HOME -> LightHomeScreen(
                            state = state,
                            onPickMovie = { homePicker.launch(arrayOf("video/*")) },
                            onStart = viewModel::start,
                            onCancel = viewModel::cancel,
                            onBackground = { (context as? Activity)?.moveTaskToBack(true) },
                            onWatch = {
                                val uri = state.videoUri
                                val srt = state.srtFile
                                if (uri != null && srt != null) {
                                    activePlayerUri = uri
                                    activePlayerSrt = srt
                                    activePlayerName = state.videoName
                                }
                            },
                            onExport = {
                                val base = state.videoName.substringBeforeLast('.', state.videoName).ifBlank { "movie" }
                                homeSrtSaver.launch("${base}_ar.srt")
                            },
                        )
                        LightTab.LIBRARY -> LightLibraryScreen(
                            movies = library,
                            onRefresh = viewModel::refreshLibrary,
                            onRelink = { movie ->
                                relinkTarget = movie
                                relinkPicker.launch(arrayOf("video/*"))
                            },
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
                                    activePlayerUri = uri
                                    activePlayerSrt = srt
                                    activePlayerName = movie.movieName
                                }
                            }
                        )
                        LightTab.SERVICES -> LightServicesScreen(platforms, viewModel::refreshPlatforms)
                    }
                }
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError(); viewModel.clearCloudUiError() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError(); viewModel.clearCloudUiError() }) { Text("إغلاق") }
            },
            title = { Text("تعذر إكمال العملية", color = Ink) },
            text = { Text(visibleError, color = Muted) },
            containerColor = CardWhite,
        )
    }

    val playerUri = activePlayerUri
    val playerSrt = activePlayerSrt
    if (playerUri != null && playerSrt != null) {
        LightCinemaPlayerDialog(
            videoUri = playerUri,
            subtitleFile = playerSrt,
            movieName = activePlayerName,
            onDismiss = {
                activePlayerUri = null
                activePlayerSrt = null
                activePlayerName = ""
            }
        )
    }
}

@Composable
private fun LightHeader(tab: LightTab) {
    val title = when (tab) {
        LightTab.HOME -> "مترجم الأفلام"
        LightTab.LIBRARY -> "المكتبة"
        LightTab.SERVICES -> "الخدمات"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Ink,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.6).sp,
        )
        if (tab == LightTab.HOME) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = SuccessSoft,
                border = BorderStroke(1.dp, Color(0xFFDBECE4)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(12.dp).background(SuccessGreen, CircleShape))
                    Text("جاهز", color = Color(0xFF176B49), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun LightHomeScreen(
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.videoUri == null) LightEmptyMovieCard(onPickMovie) else LightMovieCard(state, onPickMovie)

        when {
            state.isRunning -> LightProgressCard(state)
            state.srtFile != null -> LightCompletedCard(state, onWatch, onExport)
            state.videoUri != null -> LightReadyCard()
        }

        LightStatusCards(onBackground)

        when {
            state.isRunning -> LightPrimaryButton("إيقاف المهمة", true, onCancel)
            state.srtFile != null -> LightPrimaryButton("مشاهدة الفيلم", false, onWatch)
            state.videoUri != null -> LightPrimaryButton("ابدأ الترجمة", false, onStart)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LightEmptyMovieCard(onPickMovie: () -> Unit) {
    SoftCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.size(72.dp).background(SoftBlue, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Movie, null, tint = AccentBlue, modifier = Modifier.size(34.dp)) }
            Text("اختر فيلمًا للترجمة", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            OutlinedButton(onClick = onPickMovie, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Hairline)) {
                Text("اختيار فيديو", color = AccentBlueDark)
            }
        }
    }
}

@Composable
private fun LightMovieCard(state: TranslatorUiState, onPickMovie: () -> Unit) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(78.dp).background(Color(0xFFF0F5FA), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PlayArrow, null, tint = Muted, modifier = Modifier.size(31.dp))
                    Text("فيديو", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 15.dp)) {
                Text(
                    state.videoName.ifBlank { "الفيلم المحدد" },
                    color = Ink,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Text(lightClock(state.videoDurationMs), color = Muted, fontSize = 17.sp)
                }
            }
            IconButton(
                onClick = onPickMovie,
                enabled = !state.isRunning,
                modifier = Modifier.background(Color(0xFFF0F5FA), RoundedCornerShape(18.dp)),
            ) { Icon(Icons.Default.MoreHoriz, "تغيير الفيلم", tint = AccentBlue) }
        }
    }
}

@Composable
private fun LightProgressCard(state: TranslatorUiState) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepColumn(
                modifier = Modifier.weight(0.78f),
                steps = listOf("ترجمة" to 76, "مراجعة" to 88, "تسليم" to 97),
                percent = percent,
            )
            Box(
                modifier = Modifier.weight(1.08f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(145.dp),
                    color = AccentBlue,
                    trackColor = Color(0xFFE8F0F8),
                    strokeWidth = 13.dp,
                )
                Text(
                    "$percent%",
                    color = AccentBlueDark,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 37.sp,
                )
            }
            StepColumn(
                modifier = Modifier.weight(0.78f),
                steps = listOf("تجهيز" to 10, "رفع" to 38, "فهم" to 62),
                percent = percent,
            )
        }
        Text(
            state.stage,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            color = Muted,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepColumn(
    modifier: Modifier,
    steps: List<Pair<String, Int>>,
    percent: Int,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        steps.forEach { (label, threshold) ->
            val complete = percent >= threshold
            val active = !complete && when (label) {
                "تجهيز" -> percent < 15
                "رفع" -> percent in 15..47
                "فهم" -> percent in 48..69
                "ترجمة" -> percent in 70..83
                "مراجعة" -> percent in 84..96
                else -> percent >= 97
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (active) Color(0xFFE7F2FE) else Color(0xFFF2F6FA),
                border = BorderStroke(1.dp, if (active) Color(0xFFD6E8FA) else Hairline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, modifier = Modifier.weight(1f), color = if (active) AccentBlueDark else Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                when {
                                    complete -> AccentBlue
                                    active -> Color(0xFFDCECFB)
                                    else -> Color.Transparent
                                },
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            complete -> Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            active -> Box(Modifier.size(12.dp).background(AccentBlue, CircleShape))
                            else -> CircularProgressIndicator(progress = { 0f }, modifier = Modifier.size(30.dp), trackColor = Color(0xFFBFCBD8), color = Color.Transparent, strokeWidth = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightReadyCard() {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).background(SuccessSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = SuccessGreen)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("الفيلم جاهز", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("سيعمل الوضع الذكي تلقائيًا بأعلى مسار متاح.", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LightCompletedCard(state: TranslatorUiState, onWatch: () -> Unit, onExport: () -> Unit) {
    SoftCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(SuccessSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudDone, null, tint = SuccessGreen)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("الترجمة جاهزة", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                    Text(if (state.processingMs > 0L) "اكتملت خلال ${lightClock(state.processingMs)}" else "جاهزة للمشاهدة والحفظ", color = Muted, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onWatch, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                    Icon(Icons.Default.PlayArrow, null); Text(" مشاهدة")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                    Icon(Icons.Default.Download, null, tint = AccentBlue); Text(" حفظ SRT", color = AccentBlueDark)
                }
            }
        }
    }
}

@Composable
private fun LightStatusCards(onBackground: () -> Unit) {
    val context = LocalContext.current
    val online by produceState(initialValue = CloudConnectivity.isOnline(context), context) {
        while (true) {
            value = CloudConnectivity.isOnline(context)
            delay(2_000L)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard(modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (online) Icons.Default.CloudDone else Icons.Default.SignalWifiOff, null, tint = if (online) SuccessGreen else Muted)
                }
                Text(if (online) "متصل" else "غير متصل", modifier = Modifier.weight(1f).padding(horizontal = 11.dp), color = Ink, fontWeight = FontWeight.SemiBold)
            }
        }
        SoftCard(modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("متابعة في الخلفية", modifier = Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Switch(
                    checked = true,
                    onCheckedChange = { if (it) onBackground() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFCBD6E2),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LightPrimaryButton(label: String, destructive: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(21.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (destructive) AccentBlue else AccentBlue),
    ) {
        if (destructive) Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun LightLibraryScreen(
    movies: List<CloudMovieItem>,
    onRefresh: () -> Unit,
    onRelink: (CloudMovieItem) -> Unit,
    onDownload: (CloudMovieItem) -> Unit,
    onDelete: (CloudMovieItem) -> Unit,
    onWatch: (CloudMovieItem) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${movies.size} فيلم", color = Muted, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.background(SoftBlue, RoundedCornerShape(16.dp))) {
                Icon(Icons.Default.Refresh, "تحديث", tint = AccentBlue)
            }
        }
        if (movies.isEmpty()) {
            SoftCard {
                Column(Modifier.fillMaxWidth().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.VideoLibrary, null, tint = AccentBlue, modifier = Modifier.size(42.dp))
                    Text("لا توجد ترجمات بعد", color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("ستظهر الأفلام هنا بعد اكتمال الترجمة.", color = Muted, textAlign = TextAlign.Center)
                }
            }
        } else {
            movies.forEach { movie ->
                SoftCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(58.dp).background(SoftBlue, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Movie, null, tint = AccentBlue)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(movie.movieName, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(lightClock(movie.durationMs), color = Muted, fontSize = 13.sp)
                            }
                            IconButton(onClick = { onRelink(movie) }) { Icon(Icons.Default.Edit, "تغيير", tint = Muted) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onWatch(movie) }, enabled = movie.localAvailable && !movie.srtText.isNullOrBlank(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                                Icon(Icons.Default.PlayArrow, null); Text(" مشاهدة")
                            }
                            OutlinedButton(onClick = { onDownload(movie) }, enabled = !movie.srtText.isNullOrBlank(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Hairline)) {
                                Icon(Icons.Default.Download, null, tint = AccentBlue)
                            }
                            OutlinedButton(onClick = { deleteTarget = movie }, modifier = Modifier.weight(0.7f), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Hairline)) {
                                Icon(Icons.Default.DeleteOutline, null, tint = DangerRed)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text("حذف", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("إلغاء", color = Muted) } },
            title = { Text("حذف الترجمة؟", color = Ink) },
            text = { Text("سيتم حذف ملف الترجمة المحفوظ فقط.", color = Muted) },
            containerColor = CardWhite,
        )
    }
}

@Composable
private fun LightServicesScreen(platforms: List<PlatformQuota>, onRefresh: () -> Unit) {
    val byId = platforms.associateBy { it.id.lowercase() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الوضع الذكي", modifier = Modifier.weight(1f), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh, modifier = Modifier.background(SoftBlue, RoundedCornerShape(16.dp))) {
                Icon(Icons.Default.Refresh, "تحديث", tint = AccentBlue)
            }
        }
        ServiceCard("Groq Whisper", "فهم الحوار التركي", byId["groq"], true)
        ServiceCard("Azure Translator", "الترجمة الأساسية السريعة", byId["azure"], false)
        ServiceCard("Groq Review", "مراجعة الأسطر التي تحتاج تحسينًا", byId["groq_review"], true)
        ServiceCard("Gemini", "احتياطي عند تعذر المسار الأساسي", byId["gemini"], true)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ServiceCard(title: String, subtitle: String, quota: PlatformQuota?, expected: Boolean) {
    SoftCard {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(if (quota != null || expected) SoftBlue else Color(0xFFF2F4F7), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Cloud, null, tint = if (quota != null || expected) AccentBlue else Muted)
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(quota?.detail?.takeIf { it.isNotBlank() } ?: subtitle, color = Muted, fontSize = 13.sp, maxLines = 2)
            }
            if (quota != null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
                    CircularProgressIndicator(progress = { quota.remainingPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxSize(), color = AccentBlue, trackColor = Color(0xFFE7EEF6), strokeWidth = 5.dp)
                    Text("${quota.remainingPercent}%", color = AccentBlueDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(if (expected) "جاهز" else "بانتظار المفتاح", color = if (expected) SuccessGreen else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LightBottomBar(tab: LightTab, onTab: (LightTab) -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = ShadowTint, spotColor = ShadowTint),
        color = CardWhite,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFF0F3F7)),
    ) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding().height(86.dp)) {
            LightTab.entries.forEach { item ->
                val selected = tab == item
                val icon = when (item) {
                    LightTab.HOME -> Icons.Default.Home
                    LightTab.LIBRARY -> Icons.Default.VideoLibrary
                    LightTab.SERVICES -> Icons.Default.Cloud
                }
                val label = when (item) {
                    LightTab.HOME -> "الرئيسية"
                    LightTab.LIBRARY -> "المكتبة"
                    LightTab.SERVICES -> "الخدمات"
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTab(item) },
                    icon = { Icon(icon, null, modifier = Modifier.size(29.dp)) },
                    label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentBlue,
                        selectedTextColor = AccentBlueDark,
                        indicatorColor = SoftBlue,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().shadow(16.dp, RoundedCornerShape(30.dp), ambientColor = ShadowTint, spotColor = ShadowTint),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(1.dp, Color(0xFFF2F5F8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LightCinemaPlayerDialog(videoUri: Uri, subtitleFile: File, movieName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var subtitleSize by remember { mutableStateOf(22f) }
    val player = remember(videoUri, subtitleFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            setMediaItem(MediaItem.Builder().setUri(videoUri).setSubtitleConfigurations(listOf(subtitle)).build())
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                            controllerAutoShow = false
                            controllerShowTimeoutMs = 2600
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                            subtitleView?.setBottomPaddingFraction(0.11f)
                            subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                        }
                    },
                    update = { view -> view.player = player; view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize) },
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.56f)).statusBarsPadding().padding(8.dp).align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "إغلاق", tint = Color.White) }
                    Text(movieName, modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp), shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.72f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { subtitleSize = (subtitleSize - 2f).coerceAtLeast(16f) }) { Text("A−", color = Color.White) }
                        Text("حجم الترجمة", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
                        TextButton(onClick = { subtitleSize = (subtitleSize + 2f).coerceAtMost(34f) }) { Text("A+", color = Color.White) }
                    }
                }
            }
        }
    }
}

private fun Context.lightDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index) ?: "فيلم"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "فيلم"
}

private fun lightClock(ms: Long): String {
    if (ms <= 0L) return "—"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
