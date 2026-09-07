package com.manzl.movietranslator

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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

class ResponsivePremiumMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsIfNeeded()
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
                ResponsivePremiumApp(vm)
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
private fun ResponsivePremiumApp(viewModel: MovieTranslatorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val cloudError by viewModel.cloudUiError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(PremiumTab.HOME) }
    var activePlayerUri by remember { mutableStateOf<Uri?>(null) }
    var activePlayerSrt by remember { mutableStateOf<File?>(null) }
    var activePlayerName by remember { mutableStateOf("") }
    var relinkTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    var downloadTarget by remember { mutableStateOf<CloudMovieItem?>(null) }

    val homePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.premiumDisplayName(uri))
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
            PremiumTab.PROJECTS, PremiumTab.LIBRARY -> viewModel.refreshLibrary()
            PremiumTab.SETTINGS -> viewModel.refreshPlatforms()
            PremiumTab.HOME -> Unit
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = PremiumBg,
            bottomBar = { ResponsivePremiumBottomBar(tab = tab, onTab = { tab = it }) },
        ) { padding ->
            when (tab) {
                PremiumTab.HOME -> ResponsivePremiumHome(
                    modifier = Modifier.padding(padding),
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
                PremiumTab.PROJECTS -> PremiumProjectsScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    movies = library,
                    onRefresh = viewModel::refreshLibrary,
                    onWatch = { movie ->
                        val uri = movie.videoUri?.let(Uri::parse)
                        val srt = viewModel.prepareLibrarySubtitle(movie)
                        if (uri != null && srt != null && movie.localAvailable) {
                            activePlayerUri = uri
                            activePlayerSrt = srt
                            activePlayerName = movie.movieName
                        }
                    },
                )
                PremiumTab.LIBRARY -> PremiumLibraryScreen(
                    modifier = Modifier.padding(padding),
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
                    },
                )
                PremiumTab.SETTINGS -> PremiumSettingsScreen(
                    modifier = Modifier.padding(padding),
                    platforms = platforms,
                    onRefresh = viewModel::refreshPlatforms,
                )
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError(); viewModel.clearCloudUiError() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError(); viewModel.clearCloudUiError() }) {
                    Text("إغلاق", color = PremiumBlue)
                }
            },
            title = { Text("تعذر إكمال العملية", color = PremiumInk) },
            text = { Text(visibleError, color = PremiumMuted) },
            containerColor = PremiumWhite,
        )
    }

    val playerUri = activePlayerUri
    val playerSrt = activePlayerSrt
    if (playerUri != null && playerSrt != null) {
        PremiumCinemaPlayerDialog(
            videoUri = playerUri,
            subtitleFile = playerSrt,
            movieName = activePlayerName,
            onDismiss = {
                activePlayerUri = null
                activePlayerSrt = null
                activePlayerName = ""
            },
        )
    }
}

@Composable
private fun ResponsivePremiumHome(
    modifier: Modifier,
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val online by produceState(initialValue = CloudConnectivity.isOnline(context), context) {
        while (true) {
            value = CloudConnectivity.isOnline(context)
            delay(2_000L)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(PremiumBg)) {
        val density = LocalDensity.current
        val spec = remember(maxWidth, maxHeight, density.fontScale) {
            calculatePremiumResponsiveSpec(maxWidth.value, maxHeight.value, density.fontScale)
        }
        val pageWidth = if (maxWidth > 560.dp) 540.dp else maxWidth

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PremiumNavy, PremiumNavy2, Color(0xFF2F69AD)),
                            start = Offset.Zero,
                            end = Offset(1200f, 900f),
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = pageWidth)
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = spec.outerPadding.dp, vertical = (8f * spec.scale).dp),
                    verticalArrangement = Arrangement.spacedBy(spec.verticalGap.dp),
                ) {
                    ResponsivePremiumHeader(spec)
                    if (state.videoUri == null) {
                        ResponsiveEmptyHero(spec, onPickMovie)
                    } else {
                        ResponsiveMovieHero(spec, state, onPickMovie)
                    }
                }
            }

            ResponsiveWaveDivider()

            Column(
                modifier = Modifier
                    .widthIn(max = pageWidth)
                    .padding(horizontal = spec.outerPadding.dp),
                verticalArrangement = Arrangement.spacedBy(spec.verticalGap.dp),
            ) {
                if (state.videoUri != null) {
                    ResponsiveWorkflowCard(spec, state)
                    ResponsiveMetrics(spec, state)
                    ResponsiveStatus(spec, online, onBackground)
                    when {
                        state.isRunning -> ResponsiveStopButton(spec, onCancel)
                        state.srtFile != null -> ResponsiveCompletedActions(spec, onWatch, onExport)
                        else -> ResponsiveStartButton(spec, onStart)
                    }
                }
                Spacer(Modifier.height((6f * spec.scale).dp))
            }
        }
    }
}

@Composable
private fun ResponsivePremiumHeader(spec: PremiumResponsiveSpec) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(0.43f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(spec.avatarDp.dp)
                        .background(Color.White.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AccountCircle, null, tint = PremiumWhite, modifier = Modifier.size((spec.avatarDp * 0.72f).dp))
                }
                Spacer(Modifier.width((9f * spec.scale).dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            "مرحبًا بك",
                            color = PremiumWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = spec.greetingTitleSp.sp,
                            maxLines = 1,
                        )
                        Text(
                            "فلننجز شيئًا رائعًا اليوم 👋",
                            color = Color(0xFFD9E8FF),
                            fontSize = spec.greetingSubtitleSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.width((10f * spec.scale).dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier.weight(0.57f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((6f * spec.scale).dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF70BDFF), modifier = Modifier.size((24f * spec.scale).dp))
                        Text(
                            "مترجم الأفلام",
                            color = PremiumWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = spec.headerTitleSp.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        "ذكاء اصطناعي لمحتوى بلا حدود",
                        color = Color(0xFFD9E8FF),
                        fontSize = spec.headerSubtitleSp.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = (2f * spec.scale).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsiveEmptyHero(spec: PremiumResponsiveSpec, onPickMovie: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(spec.heroHeightDp.dp),
        shape = RoundedCornerShape((30f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF153D6D).copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, Color(0xFF69B7FF)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding((18f * spec.scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size((58f * spec.scale).dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape((18f * spec.scale).dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF92CEFF), modifier = Modifier.size((30f * spec.scale).dp))
            }
            Text("اختر فيلمًا للترجمة", color = PremiumWhite, fontSize = (20f * spec.scale).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = (10f * spec.scale).dp))
            Text("معالجة ذكية تلقائية للأفلام الطويلة", color = Color(0xFFD8E9FF), fontSize = (12f * spec.scale).sp, textAlign = TextAlign.Center, maxLines = 1)
            Button(
                onClick = onPickMovie,
                modifier = Modifier.padding(top = (10f * spec.scale).dp),
                shape = RoundedCornerShape((16f * spec.scale).dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue2),
            ) { Text("اختيار فيديو", color = PremiumWhite, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ResponsiveMovieHero(spec: PremiumResponsiveSpec, state: TranslatorUiState, onPickMovie: () -> Unit) {
    val context = LocalContext.current
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val fileSize by produceState(initialValue = "—", state.videoUri) {
        value = state.videoUri?.let { context.premiumFileSize(it) } ?: "—"
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(spec.heroHeightDp.dp),
        shape = RoundedCornerShape((30f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F).copy(alpha = 0.97f)),
        border = BorderStroke(1.dp, Color(0xFF69B5FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxSize().padding((13f * spec.scale).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((13f * spec.scale).dp),
            ) {
                PremiumVideoThumb(
                    uri = state.videoUri,
                    duration = premiumClock(state.videoDurationMs),
                    modifier = Modifier
                        .width(spec.posterWidthDp.dp)
                        .height(spec.posterHeightDp.dp),
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onPickMovie,
                                enabled = !state.isRunning,
                                modifier = Modifier
                                    .size((38f * spec.scale).dp)
                                    .background(Color.White.copy(alpha = 0.08f), CircleShape),
                            ) {
                                Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = PremiumWhite, modifier = Modifier.size((22f * spec.scale).dp))
                            }
                            Spacer(Modifier.width((8f * spec.scale).dp))
                            Text(
                                state.videoName.ifBlank { "الفيلم المحدد" },
                                modifier = Modifier.weight(1f),
                                color = PremiumWhite,
                                fontSize = (18f * spec.scale).sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Text(
                            "MP4  |  ${premiumClock(state.videoDurationMs)}  |  $fileSize",
                            color = Color(0xFFD7E7FF),
                            fontSize = (12f * spec.scale).sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(top = (5f * spec.scale).dp),
                        )

                        Row(
                            modifier = Modifier.padding(top = (11f * spec.scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    state.srtFile != null -> "اكتملت الترجمة بنجاح"
                                    state.isRunning -> state.stage.ifBlank { "جاري تجهيز الصوت بالذكاء الاصطناعي" }
                                    else -> "الفيلم جاهز لبدء الترجمة"
                                },
                                modifier = Modifier.weight(1f),
                                color = PremiumWhite,
                                fontSize = (13f * spec.scale).sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$percent%",
                                color = Color(0xFF84C8FF),
                                fontSize = (34f * spec.scale).sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                            )
                        }

                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = (5f * spec.scale).dp)
                                .height((8f * spec.scale).dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = Color(0xFF66B6FF),
                            trackColor = Color(0xFF5D7FA8),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = (7f * spec.scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Schedule, null, tint = Color(0xFFCFE2FC), modifier = Modifier.size((16f * spec.scale).dp))
                            Text(
                                "الوقت المتبقي: ${if (state.isRunning) premiumClock(remaining) else "—"}",
                                color = Color(0xFFD7E7FF),
                                fontSize = (11f * spec.scale).sp,
                                maxLines = 1,
                                modifier = Modifier.padding(start = (5f * spec.scale).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponsiveWaveDivider() {
    Canvas(modifier = Modifier.fillMaxWidth().height(15.dp).background(PremiumBg)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(size.width * 0.5f, size.height * 1.55f, size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, color = PremiumBg)
    }
}

@Composable
private fun ResponsiveWorkflowCard(spec: PremiumResponsiveSpec, state: TranslatorUiState) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val active = when {
        state.srtFile != null || percent >= 98 -> 4
        percent >= 90 -> 3
        percent >= 70 -> 1
        else -> 2
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(spec.workflowHeightDp.dp),
        shape = RoundedCornerShape((28f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = (12f * spec.scale).dp, vertical = (10f * spec.scale).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("خطوات العمل", color = PremiumInk, fontSize = (20f * spec.scale).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("من الملف إلى الترجمة النهائية", color = PremiumMuted, fontSize = (10f * spec.scale).sp, maxLines = 1)
                }
                Surface(color = PremiumBlueSoft, shape = RoundedCornerShape((16f * spec.scale).dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = (10f * spec.scale).dp, vertical = (6f * spec.scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((5f * spec.scale).dp),
                    ) {
                        Icon(Icons.Default.GraphicEq, null, tint = PremiumBlue, modifier = Modifier.size((16f * spec.scale).dp))
                        Text("مراقبة مباشرة", color = PremiumBlue, fontSize = (11f * spec.scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = (10f * spec.scale).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val steps = listOf(
                    Triple("تحليل الملف", Icons.Default.CloudUpload, 0),
                    Triple("ترجمة", Icons.Default.Description, 1),
                    Triple("معالجة الصوت", Icons.Default.GraphicEq, 2),
                    Triple("دمج وتركيب", Icons.Default.Movie, 3),
                    Triple("إنهاء", Icons.Default.Flag, 4),
                )
                steps.forEachIndexed { index, (title, icon, stepIndex) ->
                    ResponsiveWorkflowStep(spec, title, icon, stepIndex, active, state.srtFile != null || percent >= stepDoneThreshold(stepIndex), Modifier.weight(1f))
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width((10f * spec.scale).dp)
                                .height(2.dp)
                                .background(if (active == stepIndex || active == stepIndex + 1) PremiumBlue else Color(0xFFC8D3E2), RoundedCornerShape(999.dp))
                        )
                    }
                }
            }
        }
    }
}

private fun stepDoneThreshold(index: Int): Int = when (index) {
    0 -> 1
    2 -> 70
    1 -> 90
    3 -> 98
    4 -> 100
    else -> 100
}

@Composable
private fun ResponsiveWorkflowStep(
    spec: PremiumResponsiveSpec,
    title: String,
    icon: ImageVector,
    index: Int,
    activeIndex: Int,
    done: Boolean,
    modifier: Modifier,
) {
    val isActive = index == activeIndex
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size((if (isActive) 50f else 43f).times(spec.scale).dp)
                .background(if (isActive) PremiumBlueSoft else Color(0xFFF0F4F9), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                CircularProgressIndicator(
                    progress = { 0.76f },
                    modifier = Modifier.fillMaxSize(),
                    color = PremiumBlue,
                    trackColor = Color(0xFFDCE7F5),
                    strokeWidth = 3.dp,
                )
            }
            Icon(icon, null, tint = if (isActive) PremiumBlue else Color(0xFF657389), modifier = Modifier.size((20f * spec.scale).dp))
            if (done && !isActive) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size((16f * spec.scale).dp).background(PremiumGreen, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = PremiumWhite, modifier = Modifier.size((10f * spec.scale).dp))
                }
            }
        }
        Text(
            title,
            color = PremiumInk,
            fontSize = (9.5f * spec.scale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = (11f * spec.scale).sp,
            modifier = Modifier.padding(top = (5f * spec.scale).dp),
        )
        Text(
            if (isActive) "جاري التنفيذ" else if (done) "تم" else "في الانتظار",
            color = if (isActive) PremiumBlue else PremiumMuted,
            fontSize = (7.5f * spec.scale).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ResponsiveMetrics(spec: PremiumResponsiveSpec, state: TranslatorUiState) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val model = when {
        percent < 15 -> "تجهيز"
        percent < 70 -> "Whisper"
        percent < 88 -> "Azure"
        percent < 97 -> "Groq"
        else -> "إنهاء"
    }

    val cards = listOf(
        ResponsiveMetric(Icons.Default.Cloud, Color(0xFFEAF3FF), PremiumBlue, model, "النموذج المستخدم"),
        ResponsiveMetric(Icons.Default.Description, Color(0xFFF1ECFF), Color(0xFF7657D8), premiumClock(state.videoDurationMs), "مدة الفيديو"),
        ResponsiveMetric(Icons.Default.Schedule, Color(0xFFFFF2DB), Color(0xFFB97514), if (state.isRunning) premiumClock(remaining) else "—", "الوقت المتبقي"),
        ResponsiveMetric(Icons.Default.Folder, Color(0xFFE8F8F0), PremiumGreen, if (state.srtFile != null) "1/1" else "0/1", "ملفات مكتملة"),
    )

    if (spec.compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(spec.verticalGap.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spec.verticalGap.dp)) {
                ResponsiveMetricCard(spec, cards[0], Modifier.weight(1f))
                ResponsiveMetricCard(spec, cards[1], Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spec.verticalGap.dp)) {
                ResponsiveMetricCard(spec, cards[2], Modifier.weight(1f))
                ResponsiveMetricCard(spec, cards[3], Modifier.weight(1f))
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((7f * spec.scale).dp)) {
            cards.forEach { ResponsiveMetricCard(spec, it, Modifier.weight(1f)) }
        }
    }
}

private data class ResponsiveMetric(
    val icon: ImageVector,
    val soft: Color,
    val tint: Color,
    val value: String,
    val label: String,
)

@Composable
private fun ResponsiveMetricCard(spec: PremiumResponsiveSpec, metric: ResponsiveMetric, modifier: Modifier) {
    Card(
        modifier = modifier.height(spec.metricHeightDp.dp),
        shape = RoundedCornerShape((22f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = (5f * spec.scale).dp, vertical = (8f * spec.scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size((34f * spec.scale).dp).background(metric.soft, RoundedCornerShape((12f * spec.scale).dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(metric.icon, null, tint = metric.tint, modifier = Modifier.size((19f * spec.scale).dp))
            }
            Text(metric.value, color = PremiumInk, fontSize = (14f * spec.scale).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = (5f * spec.scale).dp), maxLines = 1)
            Text(metric.label, color = PremiumMuted, fontSize = (8.2f * spec.scale).sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = (9.5f * spec.scale).sp)
        }
    }
}

@Composable
private fun ResponsiveStatus(spec: PremiumResponsiveSpec, online: Boolean, onBackground: () -> Unit) {
    if (spec.compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(spec.verticalGap.dp)) {
            ResponsiveCloudStatus(spec, online, Modifier.fillMaxWidth())
            ResponsiveBackgroundStatus(spec, onBackground, Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((8f * spec.scale).dp)) {
            ResponsiveCloudStatus(spec, online, Modifier.weight(1f))
            ResponsiveBackgroundStatus(spec, onBackground, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResponsiveCloudStatus(spec: PremiumResponsiveSpec, online: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier.height(spec.statusHeightDp.dp),
        shape = RoundedCornerShape((24f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = (11f * spec.scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((38f * spec.scale).dp).background(PremiumGreenSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (online) Icons.Default.CloudDone else Icons.Default.SignalWifiOff, null, tint = if (online) PremiumGreen else PremiumMuted, modifier = Modifier.size((21f * spec.scale).dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = (8f * spec.scale).dp)) {
                Text("الاتصال بالسحابة", color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = (11.5f * spec.scale).sp, maxLines = 1)
                Text(if (online) "متصل وجاهز" else "غير متصل", color = PremiumMuted, fontSize = (8.5f * spec.scale).sp, maxLines = 1)
            }
            if (online) {
                Box(Modifier.size((20f * spec.scale).dp).background(PremiumGreenSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = PremiumGreen, modifier = Modifier.size((13f * spec.scale).dp))
                }
            }
        }
    }
}

@Composable
private fun ResponsiveBackgroundStatus(spec: PremiumResponsiveSpec, onBackground: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.height(spec.statusHeightDp.dp),
        shape = RoundedCornerShape((24f * spec.scale).dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = (10f * spec.scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((38f * spec.scale).dp).background(PremiumBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Schedule, null, tint = PremiumInk, modifier = Modifier.size((20f * spec.scale).dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = (8f * spec.scale).dp)) {
                Text("المتابعة في الخلفية", color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = (11.2f * spec.scale).sp, maxLines = 1)
                Text("استمر حتى مع إغلاق التطبيق", color = PremiumMuted, fontSize = (7.7f * spec.scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = true,
                onCheckedChange = { onBackground() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PremiumWhite,
                    checkedTrackColor = PremiumBlue,
                    uncheckedThumbColor = PremiumWhite,
                    uncheckedTrackColor = Color(0xFFCBD6E4),
                ),
            )
        }
    }
}

@Composable
private fun ResponsiveStopButton(spec: PremiumResponsiveSpec, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(spec.stopHeightDp.dp),
        shape = RoundedCornerShape((25f * spec.scale).dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumRed),
    ) {
        Box(Modifier.size((36f * spec.scale).dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Stop, null, tint = PremiumWhite, modifier = Modifier.size((21f * spec.scale).dp))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("إيقاف المهمة", color = PremiumWhite, fontSize = (19f * spec.scale).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("سيتم حفظ التقدم الحالي", color = Color(0xFFFFD8DC), fontSize = (9f * spec.scale).sp, maxLines = 1)
        }
        Spacer(Modifier.width((36f * spec.scale).dp))
    }
}

@Composable
private fun ResponsiveStartButton(spec: PremiumResponsiveSpec, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(spec.stopHeightDp.dp),
        shape = RoundedCornerShape((25f * spec.scale).dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = PremiumWhite)
        Text("ابدأ الترجمة", color = PremiumWhite, fontSize = (18f * spec.scale).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1)
    }
}

@Composable
private fun ResponsiveCompletedActions(spec: PremiumResponsiveSpec, onWatch: () -> Unit, onExport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((8f * spec.scale).dp)) {
        Button(
            onClick = onWatch,
            modifier = Modifier.weight(1f).height(spec.stopHeightDp.dp),
            shape = RoundedCornerShape((23f * spec.scale).dp),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Text("مشاهدة الفيلم", fontWeight = FontWeight.Bold, maxLines = 1)
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f).height(spec.stopHeightDp.dp),
            shape = RoundedCornerShape((23f * spec.scale).dp),
            border = BorderStroke(1.dp, PremiumHairline),
        ) {
            Icon(Icons.Default.Download, null, tint = PremiumBlue)
            Text("حفظ SRT", color = PremiumBlue, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ResponsivePremiumBottomBar(tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    BoxWithConstraints {
        val scale = (maxWidth.value / 430f).coerceIn(0.86f, 1.08f)
        Surface(
            modifier = Modifier
                .padding(horizontal = (12f * scale).dp, vertical = (5f * scale).dp)
                .shadow(12.dp, RoundedCornerShape((27f * scale).dp), ambientColor = PremiumShadow, spotColor = PremiumShadow),
            color = PremiumWhite,
            shape = RoundedCornerShape((27f * scale).dp),
            border = BorderStroke(1.dp, Color(0xFFF0F4F9)),
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding().height((74f * scale).dp),
            ) {
                PremiumTab.entries.forEach { item ->
                    val selected = item == tab
                    val icon = when (item) {
                        PremiumTab.HOME -> Icons.Default.Home
                        PremiumTab.PROJECTS -> Icons.Default.Folder
                        PremiumTab.LIBRARY -> Icons.Default.VideoLibrary
                        PremiumTab.SETTINGS -> Icons.Default.Settings
                    }
                    val label = when (item) {
                        PremiumTab.HOME -> "الرئيسية"
                        PremiumTab.PROJECTS -> "مشاريعي"
                        PremiumTab.LIBRARY -> "المكتبة"
                        PremiumTab.SETTINGS -> "الإعدادات"
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onTab(item) },
                        icon = { Icon(icon, null, modifier = Modifier.size((24f * scale).dp)) },
                        label = { Text(label, fontSize = (9.5f * scale).sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PremiumBlue,
                            selectedTextColor = PremiumBlue,
                            indicatorColor = PremiumBlueSoft,
                            unselectedIconColor = Color(0xFF6F7E95),
                            unselectedTextColor = Color(0xFF6F7E95),
                        ),
                    )
                }
            }
        }
    }
}
