package com.manzl.movietranslator

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Pixel-calibrated home rebuilt from a clean layout tree.
 *
 * Source of truth: the approved Honor 200 screenshot, 922 x 2048 physical pixels.
 * No responsive card compression is used on the reference aspect ratio. Instead, every major
 * surface is positioned on the reference grid and uniformly scaled. Other screens keep the
 * existing business logic and premium secondary UI.
 */
class ExactHonor200MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        requestNotificationsIfNeeded()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = ExactBlue,
                    onPrimary = Color.White,
                    background = ExactBg,
                    surface = Color.White,
                    onSurface = ExactInk,
                    onBackground = ExactInk,
                    surfaceVariant = ExactBlueSoft,
                    onSurfaceVariant = ExactMuted,
                    error = PremiumRed,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                ExactHonor200App(vm)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }
}

@Composable
private fun ExactHonor200App(viewModel: MovieTranslatorViewModel) {
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
        Box(modifier = Modifier.fillMaxSize().background(ExactBg)) {
            when (tab) {
                PremiumTab.HOME -> ExactHonor200Home(
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
                    tab = tab,
                    onTab = { tab = it },
                )

                PremiumTab.PROJECTS -> {
                    PremiumProjectsScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 104.dp),
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
                    ExactBottomBarOverlay(tab = tab, onTab = { tab = it })
                }

                PremiumTab.LIBRARY -> {
                    PremiumLibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 104.dp),
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
                    ExactBottomBarOverlay(tab = tab, onTab = { tab = it })
                }

                PremiumTab.SETTINGS -> {
                    PremiumSettingsScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 104.dp),
                        platforms = platforms,
                        onRefresh = viewModel::refreshPlatforms,
                    )
                    ExactBottomBarOverlay(tab = tab, onTab = { tab = it })
                }
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError(); viewModel.clearCloudUiError() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError(); viewModel.clearCloudUiError() }) {
                    Text("إغلاق", color = ExactBlue)
                }
            },
            title = { Text("تعذر إكمال العملية", color = ExactInk) },
            text = { Text(visibleError, color = ExactMuted) },
            containerColor = Color.White,
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
private fun ExactHonor200Home(
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
    tab: PremiumTab,
    onTab: (PremiumTab) -> Unit,
) {
    val context = LocalContext.current
    val online by produceState(initialValue = CloudConnectivity.isOnline(context), context) {
        while (true) {
            value = CloudConnectivity.isOnline(context)
            delay(2_000L)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(ExactBg)) {
        val scale = minOf(maxWidth.value / EXACT_W, maxHeight.value / EXACT_H)
        val frameWidth = (EXACT_W * scale).dp
        val frameHeight = (EXACT_H * scale).dp
        val density = LocalDensity.current
        fun p(px: Float): Dp = (px * scale).dp
        fun f(px: Float): TextUnit = with(density) { p(px).toSp() }

        val scroll = rememberScrollState()

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(frameWidth)
                    .height(frameHeight)
                    .background(ExactBg)
            ) {
                Box(
                    modifier = Modifier
                        .offset(y = p(0f))
                        .fillMaxWidth()
                        .height(p(775f))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF112E59),
                                    Color(0xFF1D4A86),
                                    Color(0xFF2E68AA),
                                ),
                                start = Offset.Zero,
                                end = Offset(p(922f).value * density.density, p(720f).value * density.density),
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .offset(x = p(30f), y = p(145f))
                        .size(p(103f))
                        .background(Color.White.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(p(72f)),
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier.offset(x = p(142f), y = p(145f)).width(p(230f)),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            "مرحبًا بك",
                            color = Color.White,
                            fontSize = f(35f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            "فلننجز شيئًا رائعًا اليوم 👋",
                            color = Color(0xFFE0ECFF),
                            fontSize = f(26f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = p(8f)),
                        )
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(
                        modifier = Modifier.offset(x = p(374f), y = p(117f)).width(p(500f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF64B6FF),
                            modifier = Modifier.size(p(54f)),
                        )
                        Spacer(Modifier.width(p(13f)))
                        Text(
                            "مترجم الأفلام",
                            color = Color.White,
                            fontSize = f(55f),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        "ذكاء اصطناعي لمحتوى بلا حدود",
                        modifier = Modifier.offset(x = p(376f), y = p(204f)).width(p(458f)),
                        color = Color(0xFFE0ECFF),
                        fontSize = f(31f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                if (state.videoUri == null) {
                    Card(
                        modifier = Modifier.offset(x = p(30f), y = p(303f)).width(p(862f)).height(p(469f)),
                        shape = RoundedCornerShape(p(68f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F)),
                        border = BorderStroke(p(2f), Color(0xFF69B5FF)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().clickable(onClick = onPickMovie),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.Movie, null, tint = Color(0xFF88C9FF), modifier = Modifier.size(p(78f)))
                            Text(
                                "اختر فيلمًا للترجمة",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = f(38f),
                                modifier = Modifier.padding(top = p(22f)),
                            )
                            Text("اضغط هنا لاختيار الفيديو", color = Color(0xFFD8E8FF), fontSize = f(24f))
                        }
                    }
                } else {
                    ExactMovieCard(
                        state = state,
                        onPickMovie = onPickMovie,
                        p = ::p,
                        f = ::f,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .offset(y = p(725f))
                        .fillMaxWidth()
                        .height(p(80f))
                ) {
                    val path = Path().apply {
                        moveTo(0f, size.height * 0.18f)
                        quadraticBezierTo(size.width * 0.50f, size.height * 1.04f, size.width, size.height * 0.12f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, ExactBg)
                }

                if (state.videoUri != null) {
                    ExactWorkflowCard(state = state, p = ::p, f = ::f)
                    ExactMetricsRow(state = state, p = ::p, f = ::f)
                    ExactUtilityPeek(p = ::p)
                }
            }

            if (state.videoUri != null) {
                Column(
                    modifier = Modifier.width(frameWidth).padding(horizontal = p(30f)),
                    verticalArrangement = Arrangement.spacedBy(p(18f)),
                ) {
                    PremiumStatusRow(online = online, onBackground = onBackground)
                    when {
                        state.isRunning -> PremiumStopButton(onCancel)
                        state.srtFile != null -> PremiumCompletedActions(onWatch = onWatch, onExport = onExport)
                        else -> PremiumStartButton(onStart)
                    }
                    Spacer(Modifier.height(p(260f)))
                }
            }
        }

        ExactBottomBar(
            tab = tab,
            onTab = onTab,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = p(1748f))
                .width(frameWidth),
            p = ::p,
            f = ::f,
        )
    }
}

@Composable
private fun ExactMovieCard(
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    val context = LocalContext.current
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val fileSize by produceState(initialValue = "—", state.videoUri) {
        value = state.videoUri?.let { context.premiumFileSize(it) } ?: "—"
    }

    Card(
        modifier = Modifier.offset(x = p(29f), y = p(303f)).width(p(864f)).height(p(469f)),
        shape = RoundedCornerShape(p(68f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12375F)),
        border = BorderStroke(p(2f), Color(0xFF69B5FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            PremiumVideoThumb(
                uri = state.videoUri,
                duration = premiumClock(state.videoDurationMs),
                modifier = Modifier
                    .offset(x = p(29f), y = p(31f))
                    .width(p(231f))
                    .height(p(300f)),
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = state.videoName.ifBlank { "الفيلم المحدد" },
                    modifier = Modifier.offset(x = p(294f), y = p(31f)).width(p(405f)),
                    color = Color.White,
                    fontSize = f(42f),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }

            IconButton(
                onClick = onPickMovie,
                enabled = !state.isRunning,
                modifier = Modifier
                    .offset(x = p(735f), y = p(25f))
                    .size(p(102f))
                    .background(Color.White.copy(alpha = 0.08f), CircleShape),
            ) {
                Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = Color.White, modifier = Modifier.size(p(48f)))
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.offset(x = p(315f), y = p(112f)).width(p(510f)).height(p(52f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(p(12f)),
                ) {
                    Icon(Icons.Default.Movie, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(p(31f)))
                    Text("MP4", color = Color(0xFFE1ECFF), fontSize = f(31f))
                    Text("|", color = Color(0xFFBDD0EA), fontSize = f(30f))
                    Icon(Icons.Default.Schedule, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(p(31f)))
                    Text(premiumClock(state.videoDurationMs), color = Color(0xFFE1ECFF), fontSize = f(31f))
                    Text("|", color = Color(0xFFBDD0EA), fontSize = f(30f))
                    Icon(Icons.Default.Description, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(p(29f)))
                    Text(fileSize.removeSuffix(" MB").removeSuffix(" GB"), color = Color(0xFFE1ECFF), fontSize = f(31f))
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    when {
                        state.srtFile != null -> "اكتملت الترجمة بنجاح"
                        state.isRunning -> state.stage.ifBlank { "جاري تجهيز الصوت بالذكاء الاصطناعي" }
                        else -> "الفيلم جاهز لبدء الترجمة"
                    },
                    modifier = Modifier.offset(x = p(302f), y = p(205f)).width(p(390f)),
                    color = Color.White,
                    fontSize = f(31f),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                "$percent%",
                modifier = Modifier.offset(x = p(680f), y = p(185f)).width(p(150f)),
                color = Color(0xFF83C7FF),
                fontSize = f(72f),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                textAlign = TextAlign.End,
            )

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .offset(x = p(315f), y = p(277f))
                    .width(p(511f))
                    .height(p(21f))
                    .clip(RoundedCornerShape(p(999f))),
                color = Color(0xFF70BFFF),
                trackColor = Color(0xFF668CB9),
            )

            Row(
                modifier = Modifier.offset(x = p(315f), y = p(326f)).width(p(330f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Schedule, null, tint = Color(0xFFD9E7FA), modifier = Modifier.size(p(31f)))
                Spacer(Modifier.width(p(11f)))
                Text(
                    "الوقت المتبقي: ${if (state.isRunning) premiumClock(remaining) else "—"}",
                    color = Color(0xFFD9E7FA),
                    fontSize = f(28f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ExactWorkflowCard(
    state: TranslatorUiState,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val activeIndex = when {
        state.srtFile != null || percent >= 98 -> 4
        percent >= 90 -> 3
        percent >= 70 -> 1
        else -> 2
    }

    Card(
        modifier = Modifier.offset(x = p(30f), y = p(849f)).width(p(862f)).height(p(552f)),
        shape = RoundedCornerShape(p(64f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(p(2f), Color(0xFFE2EAF4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.offset(x = p(30f), y = p(52f)).width(p(307f)).height(p(94f)),
                color = ExactBlueSoft,
                shape = RoundedCornerShape(p(32f)),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = p(20f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("مراقبة مباشرة", color = ExactBlue, fontSize = f(31f), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(p(12f)))
                        Icon(Icons.Default.GraphicEq, null, tint = ExactBlue, modifier = Modifier.size(p(32f)))
                    }
                }
            }

            Text(
                "خطوات العمل",
                modifier = Modifier.offset(x = p(342f), y = p(40f)).width(p(420f)),
                color = ExactInk,
                fontSize = f(50f),
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Text(
                "من الملف إلى الترجمة النهائية",
                modifier = Modifier.offset(x = p(342f), y = p(106f)).width(p(420f)),
                color = ExactMuted,
                fontSize = f(27f),
                textAlign = TextAlign.End,
                maxLines = 1,
            )

            val centers = listOf(100f, 267f, 432f, 598f, 765f)
            val specs = listOf(
                Triple("إنهاء", "حفظ الملف", Icons.Default.Flag),
                Triple("دمج وتركيب", "الصوت والترجمة", Icons.Default.Movie),
                Triple("معالجة الصوت", "جاري التنفيذ", Icons.Default.GraphicEq),
                Triple("ترجمة", "في الانتظار", Icons.Default.Description),
                Triple("تحليل الملف", "تم", Icons.Default.CloudUpload),
            )

            listOf(157f, 323f, 489f, 655f).forEachIndexed { index, x ->
                Box(
                    modifier = Modifier
                        .offset(x = p(x), y = p(251f))
                        .width(p(22f))
                        .height(p(4f))
                        .background(if (index == 2) ExactBlue else Color(0xFFC8D5E5), RoundedCornerShape(p(99f)))
                )
            }

            specs.forEachIndexed { visualIndex, spec ->
                val logicalIndex = when (visualIndex) {
                    0 -> 4
                    1 -> 3
                    2 -> 2
                    3 -> 1
                    else -> 0
                }
                val active = logicalIndex == activeIndex
                val done = when (logicalIndex) {
                    0 -> true
                    1 -> percent >= 90
                    2 -> percent >= 70
                    3 -> percent >= 98
                    else -> state.srtFile != null
                }
                val centerX = centers[visualIndex]
                ExactWorkflowStep(
                    centerX = centerX,
                    title = spec.first,
                    subtitle = if (logicalIndex == 2 && !active) "تم" else spec.second,
                    icon = spec.third,
                    active = active,
                    done = done,
                    p = p,
                    f = f,
                )
            }
        }
    }
}

@Composable
private fun ExactWorkflowStep(
    centerX: Float,
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    done: Boolean,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    val size = if (active) 118f else 108f
    val left = centerX - size / 2f
    val top = if (active) 193f else 198f

    Box(
        modifier = Modifier
            .offset(x = p(left), y = p(top))
            .size(p(size))
            .background(if (active) Color(0xFFF6FAFF) else Color(0xFFF0F4F9), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(
                    color = ExactBlue,
                    startAngle = -88f,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = p(7f).toPx()),
                )
            }
        }
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) ExactBlue else Color(0xFF67768C),
            modifier = Modifier.size(p(if (active) 50f else 42f)),
        )
        if (done && !active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(p(36f))
                    .background(ExactGreen, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(p(23f)))
            }
        }
    }

    if (title == "معالجة الصوت") {
        Text(
            "معالجة",
            modifier = Modifier.offset(x = p(centerX - 80f), y = p(330f)).width(p(160f)),
            color = ExactInk,
            fontSize = f(28f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "الصوت",
            modifier = Modifier.offset(x = p(centerX - 80f), y = p(380f)).width(p(160f)),
            color = ExactInk,
            fontSize = f(28f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            modifier = Modifier.offset(x = p(centerX - 90f), y = p(454f)).width(p(180f)),
            color = if (active) ExactBlue else ExactMuted,
            fontSize = f(23f),
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            title,
            modifier = Modifier.offset(x = p(centerX - 92f), y = p(330f)).width(p(184f)),
            color = ExactInk,
            fontSize = f(27f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            subtitle,
            modifier = Modifier.offset(x = p(centerX - 92f), y = p(398f)).width(p(184f)),
            color = ExactMuted,
            fontSize = f(21f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExactMetricsRow(
    state: TranslatorUiState,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val model = when {
        percent < 15 -> "تجهيز"
        percent < 70 -> "Whisper"
        percent < 88 -> "Azure"
        percent < 97 -> "Groq"
        else -> "إنهاء"
    }

    ExactMetricCard(32f, Icons.Default.Folder, Color(0xFFE8F8F0), ExactGreen, if (state.srtFile != null) "1/1" else "0/1", "ملفات مكتملة", p, f)
    ExactMetricCard(251f, Icons.Default.Schedule, Color(0xFFFFF1D8), Color(0xFFB97614), if (state.isRunning) premiumClock(remaining) else "—", "الوقت المتبقي", p, f)
    ExactMetricCard(472f, Icons.Default.Description, Color(0xFFF0EAFF), Color(0xFF7658D8), premiumClock(state.videoDurationMs), "مدة الفيديو", p, f)
    ExactMetricCard(691f, Icons.Default.Cloud, Color(0xFFEAF3FF), ExactBlue, model, "النموذج المستخدم", p, f)
}

@Composable
private fun ExactMetricCard(
    x: Float,
    icon: ImageVector,
    soft: Color,
    tint: Color,
    value: String,
    label: String,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    Card(
        modifier = Modifier.offset(x = p(x), y = p(1430f)).width(p(200f)).height(p(274f)),
        shape = RoundedCornerShape(p(48f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(p(2f), Color(0xFFE2EAF4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.offset(x = p(55f), y = p(25f)).size(p(90f)).background(soft, RoundedCornerShape(p(30f))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(p(48f)))
            }
            Text(
                value,
                modifier = Modifier.offset(x = p(12f), y = p(137f)).width(p(176f)),
                color = ExactInk,
                fontSize = f(if (value.length > 7) 36f else 41f),
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                modifier = Modifier.offset(x = p(10f), y = p(219f)).width(p(180f)),
                color = ExactMuted,
                fontSize = f(22f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ExactUtilityPeek(p: (Float) -> Dp) {
    listOf(52f to 377f, 492f to 380f).forEach { (x, w) ->
        Card(
            modifier = Modifier.offset(x = p(x), y = p(1728f)).width(p(w)).height(p(150f)),
            shape = RoundedCornerShape(p(44f)),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(p(2f), Color(0xFFE6EDF6)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {}
    }
}

@Composable
private fun ExactBottomBar(
    tab: PremiumTab,
    onTab: (PremiumTab) -> Unit,
    modifier: Modifier,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    Surface(
        modifier = modifier
            .height(p(216f))
            .padding(horizontal = p(31f))
            .shadow(p(20f), RoundedCornerShape(p(54f)), ambientColor = PremiumShadow, spotColor = PremiumShadow),
        color = Color.White,
        shape = RoundedCornerShape(p(54f)),
        border = BorderStroke(p(2f), Color(0xFFF0F4F9)),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = p(34f)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ExactNavItem(PremiumTab.SETTINGS, tab, onTab, "الإعدادات", Icons.Default.Settings, p, f)
                ExactNavItem(PremiumTab.LIBRARY, tab, onTab, "المكتبة", Icons.Default.VideoLibrary, p, f)
                ExactNavItem(PremiumTab.PROJECTS, tab, onTab, "مشاريعي", Icons.Default.Folder, p, f)
                ExactNavItem(PremiumTab.HOME, tab, onTab, "الرئيسية", Icons.Default.Home, p, f)
            }
        }
    }
}

@Composable
private fun ExactNavItem(
    item: PremiumTab,
    selectedTab: PremiumTab,
    onTab: (PremiumTab) -> Unit,
    label: String,
    icon: ImageVector,
    p: (Float) -> Dp,
    f: (Float) -> TextUnit,
) {
    val selected = item == selectedTab
    Column(
        modifier = Modifier
            .width(p(150f))
            .height(p(190f))
            .clickable { onTab(item) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = p(20f))
                .width(p(126f))
                .height(p(74f))
                .background(if (selected) ExactBlueSoft else Color.Transparent, RoundedCornerShape(p(40f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) ExactBlue else Color(0xFF708198),
                modifier = Modifier.size(p(50f)),
            )
        }
        Text(
            label,
            color = if (selected) ExactBlue else Color(0xFF708198),
            fontSize = f(26f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = p(13f)),
            maxLines = 1,
        )
    }
}

@Composable
private fun ExactBottomBarOverlay(tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / EXACT_W, maxHeight.value / EXACT_H)
        val density = LocalDensity.current
        fun p(px: Float): Dp = (px * scale).dp
        fun f(px: Float): TextUnit = with(density) { p(px).toSp() }
        ExactBottomBar(
            tab = tab,
            onTab = onTab,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = p(1748f)).width((EXACT_W * scale).dp),
            p = ::p,
            f = ::f,
        )
    }
}

private const val EXACT_W = 922f
private const val EXACT_H = 2048f

private val ExactBg = Color(0xFFF5FAFE)
private val ExactInk = Color(0xFF142C50)
private val ExactMuted = Color(0xFF8290A7)
private val ExactBlue = Color(0xFF2F77E9)
private val ExactBlueSoft = Color(0xFFEAF3FF)
private val ExactGreen = Color(0xFF2E9B68)
