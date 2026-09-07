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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateBottomPadding
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Reference-calibrated launcher for the approved premium design.
 *
 * The previous responsive launcher applied Scaffold system-bar padding and then the home header
 * applied statusBarsPadding again. On tall phones such as Honor 200 that double inset also reduced
 * BoxWithConstraints height, causing the whole premium UI to choose its compact scale and leaving a
 * large unused area above the bottom navigation.
 *
 * This launcher owns system insets exactly once: the blue home surface draws edge-to-edge behind the
 * status bar, while the header itself receives a single statusBarsPadding. Home geometry uses the
 * approved fixed premium components, which are already normalized app-wide by
 * MovieTranslatorApplication. Compact Android phones can scroll rather than crushing the design.
 */
class HonorReferenceMainActivity : ComponentActivity() {
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
                HonorReferenceApp(vm)
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
private fun HonorReferenceApp(viewModel: MovieTranslatorViewModel) {
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { PremiumBottomBar(tab = tab, onTab = { tab = it }) },
        ) { padding ->
            // Bottom-bar space is applied explicitly. Top system bars are handled by each screen
            // exactly once, preventing the double inset that shifted the approved design downward.
            val bottomOnly = Modifier.padding(bottom = padding.calculateBottomPadding())
            when (tab) {
                PremiumTab.HOME -> HonorReferenceHome(
                    modifier = bottomOnly,
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
                    modifier = bottomOnly,
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
                    modifier = bottomOnly,
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
                    modifier = bottomOnly,
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
private fun HonorReferenceHome(
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
        val pageWidth = if (maxWidth > 560.dp) 540.dp else maxWidth

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PremiumNavy, PremiumNavy2, Color(0xFF2D65A8)),
                            start = Offset.Zero,
                            end = Offset(1200f, 950f),
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = pageWidth)
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HonorReferenceHeader()
                    if (state.videoUri == null) {
                        PremiumEmptyHero(onPickMovie)
                    } else {
                        HonorReferenceMovieHero(state = state, onPickMovie = onPickMovie)
                    }
                }
            }

            PremiumWaveDivider()

            Column(
                modifier = Modifier.widthIn(max = pageWidth).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.videoUri != null) {
                    PremiumWorkflowCard(state)
                    PremiumMetricsRow(state)
                    PremiumStatusRow(online = online, onBackground = onBackground)

                    when {
                        state.isRunning -> PremiumStopButton(onCancel)
                        state.srtFile != null -> PremiumCompletedActions(onWatch = onWatch, onExport = onExport)
                        else -> PremiumStartButton(onStart)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HonorReferenceHeader() {
    // Physical placement follows the approved mockup regardless of RTL: greeting/avatar left,
    // product title right. Arabic text itself remains RTL inside each physical region.
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
                    modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AccountCircle, null, tint = PremiumWhite, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(10.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("مرحبًا بك", color = PremiumWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                        Text(
                            "فلننجز شيئًا رائعًا اليوم 👋",
                            color = Color(0xFFD9E8FF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier.weight(0.57f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF6BB7FF), modifier = Modifier.size(28.dp))
                        Text(
                            "مترجم الأفلام",
                            color = PremiumWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        "ذكاء اصطناعي لمحتوى بلا حدود",
                        color = Color(0xFFD9E8FF),
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HonorReferenceMovieHero(state: TranslatorUiState, onPickMovie: () -> Unit) {
    val context = LocalContext.current
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val fileSize by produceState(initialValue = "—", state.videoUri) {
        value = state.videoUri?.let { context.premiumFileSize(it) } ?: "—"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F).copy(alpha = 0.97f)),
        border = BorderStroke(1.dp, Color(0xFF69B5FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 350.dp
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                ) {
                    // Poster is physically locked to the left exactly like the approved design.
                    PremiumVideoThumb(
                        uri = state.videoUri,
                        duration = premiumClock(state.videoDurationMs),
                        modifier = Modifier
                            .width(if (compact) 96.dp else 118.dp)
                            .height(if (compact) 142.dp else 156.dp),
                    )

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Latin filename is explicitly laid out LTR so '.mp4' never jumps to the
                            // beginning when the surrounding screen is Arabic RTL.
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        state.videoName.ifBlank { "الفيلم المحدد" },
                                        modifier = Modifier.weight(1f),
                                        color = PremiumWhite,
                                        fontSize = if (compact) 18.sp else 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = onPickMovie,
                                        enabled = !state.isRunning,
                                        modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape),
                                    ) {
                                        Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = PremiumWhite)
                                    }
                                }
                            }

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(Icons.Default.Movie, null, tint = Color(0xFFD7E7FF), modifier = Modifier.size(16.dp))
                                    Text("MP4", color = Color(0xFFD7E7FF), fontSize = 12.sp)
                                    Text("|", color = Color(0xFF8DA9CA), fontSize = 12.sp)
                                    Icon(Icons.Default.Schedule, null, tint = Color(0xFFD7E7FF), modifier = Modifier.size(16.dp))
                                    Text(premiumClock(state.videoDurationMs), color = Color(0xFFD7E7FF), fontSize = 12.sp)
                                    Text("|", color = Color(0xFF8DA9CA), fontSize = 12.sp)
                                    Icon(Icons.Default.Description, null, tint = Color(0xFFD7E7FF), modifier = Modifier.size(16.dp))
                                    Text(fileSize, color = Color(0xFFD7E7FF), fontSize = 12.sp, maxLines = 1)
                                }
                            }

                            // The approved mockup puts status text on the left and the large percent
                            // on the right. Force physical LTR for this row while keeping Arabic text RTL.
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Text(
                                            when {
                                                state.srtFile != null -> "اكتملت الترجمة بنجاح"
                                                state.isRunning -> state.stage.ifBlank { "جاري تجهيز الصوت بالذكاء الاصطناعي" }
                                                else -> "الفيلم جاهز لبدء الترجمة"
                                            },
                                            modifier = Modifier.weight(1f),
                                            color = PremiumWhite,
                                            fontSize = if (compact) 13.sp else 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Start,
                                        )
                                    }
                                    Text(
                                        "$percent%",
                                        color = Color(0xFF83C7FF),
                                        fontSize = if (compact) 34.sp else 38.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                    )
                                }
                            }

                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = Color(0xFF66B6FF),
                                trackColor = Color(0xFF5D7FA8),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Icon(Icons.Default.Schedule, null, tint = Color(0xFFCFE2FC), modifier = Modifier.size(17.dp))
                                Text(
                                    "الوقت المتبقي: ${if (state.isRunning) premiumClock(remaining) else "—"}",
                                    color = Color(0xFFD7E7FF),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
