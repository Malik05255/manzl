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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

private val MinimalArabicFont = FontFamily(
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-medium"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-black"), weight = FontWeight.Bold),
)

/**
 * Clean adaptive launcher requested after the Honor 200 visual pass.
 *
 * It deliberately removes decorative copy and duplicated status text. Geometry is driven by the
 * actual Compose window constraints, not hard-coded physical pixels. Narrow phones stack utility
 * sections, regular phones keep the compact dashboard, and wider windows expand up to a controlled
 * content width instead of stretching cards indefinitely.
 */
class MinimalPremiumMainActivity : ComponentActivity() {
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
                MinimalPremiumApp(vm)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 706)
        }
    }
}

@Composable
private fun MinimalPremiumApp(viewModel: MovieTranslatorViewModel) {
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

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = MinimalArabicFont),
    ) {
        Scaffold(
            containerColor = PremiumBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { MinimalBottomBar(tab = tab, onTab = { tab = it }) },
        ) { padding ->
            val bottomOnly = Modifier.padding(bottom = padding.calculateBottomPadding())
            when (tab) {
                PremiumTab.HOME -> MinimalHome(
                    modifier = bottomOnly,
                    state = state,
                    onPickMovie = { homePicker.launch(arrayOf("video/*")) },
                    onStart = viewModel::start,
                    onCancel = viewModel::cancel,
                    onBackground = { (context as? Activity)?.moveTaskToBack(true) },
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
                    modifier = bottomOnly,
                    state = state,
                    movies = library,
                    onRefresh = viewModel::refreshLibrary,
                    onWatch = { movie ->
                        val uri = movie.videoUri?.let(Uri::parse)
                        val srt = viewModel.prepareLibrarySubtitle(movie)
                        if (uri != null && srt != null && movie.localAvailable) {
                            playerUri = uri
                            playerSrt = srt
                            playerName = movie.movieName
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
                            playerUri = uri
                            playerSrt = srt
                            playerName = movie.movieName
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
                    Text("إغلاق", color = PremiumBlue, fontFamily = MinimalArabicFont)
                }
            },
            title = { Text("تعذر إكمال العملية", color = PremiumInk, fontFamily = MinimalArabicFont) },
            text = { Text(visibleError, color = PremiumMuted, fontFamily = MinimalArabicFont) },
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
private fun MinimalHome(
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
        val compact = maxWidth < 370.dp
        val roomy = maxWidth >= 480.dp
        val pageWidth = if (roomy) 560.dp else maxWidth
        val side = if (compact) 12.dp else 16.dp
        val gap = if (compact) 10.dp else 12.dp

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF102B53), Color(0xFF1C4D89), Color(0xFF2F6AAF)),
                            start = Offset.Zero,
                            end = Offset(1200f, 760f),
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = pageWidth)
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = side, vertical = if (compact) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                ) {
                    MinimalHeader(compact)
                    if (state.videoUri == null) {
                        MinimalEmptyHero(onPickMovie, compact)
                    } else {
                        MinimalMovieHero(state, onPickMovie, compact)
                    }
                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                }
            }

            Column(
                modifier = Modifier.widthIn(max = pageWidth).padding(horizontal = side, vertical = gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                if (state.videoUri != null) {
                    MinimalWorkflow(state, compact)
                    MinimalMetrics(state, compact)
                    MinimalStatus(online, onBackground, compact)
                    MinimalMainAction(state, onStart, onCancel, onWatch, onExport, compact)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun MinimalHeader(compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(if (compact) 54.dp else 62.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            "مترجم الأفلام",
            color = PremiumWhite,
            fontFamily = MinimalArabicFont,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 29.sp else 34.sp,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(9.dp))
        Text("✦", color = Color(0xFF70C1FF), fontSize = if (compact) 26.sp else 30.sp, maxLines = 1)
    }
}

@Composable
private fun MinimalEmptyHero(onPickMovie: () -> Unit, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().height(if (compact) 150.dp else 170.dp).clickable(onClick = onPickMovie),
        shape = RoundedCornerShape(if (compact) 26.dp else 32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123A68)),
        border = BorderStroke(1.dp, Color(0xFF69B5FF)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Movie, null, tint = Color(0xFF8BCCFF), modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Text("اختر فيلمًا", color = PremiumWhite, fontFamily = MinimalArabicFont, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MinimalMovieHero(state: TranslatorUiState, onPickMovie: () -> Unit, compact: Boolean) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val status = when {
        state.srtFile != null -> "مكتمل"
        !state.isRunning -> "جاهز للترجمة"
        percent < 15 -> "تجهيز الصوت"
        percent < 70 -> "فهم الصوت"
        percent < 90 -> "ترجمة"
        percent < 98 -> "مراجعة"
        else -> "إنهاء"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 28.dp else 34.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123A68).copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, Color(0xFF69B5FF)),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 15.dp),
            ) {
                PremiumVideoThumb(
                    uri = state.videoUri,
                    duration = premiumClock(state.videoDurationMs),
                    modifier = Modifier
                        .width(if (compact) 92.dp else 112.dp)
                        .height(if (compact) 134.dp else 154.dp),
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.videoName.ifBlank { "الفيلم المحدد" },
                                    modifier = Modifier.weight(1f),
                                    color = PremiumWhite,
                                    fontFamily = MinimalArabicFont,
                                    fontSize = if (compact) 17.sp else 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start,
                                )
                                IconButton(
                                    onClick = onPickMovie,
                                    enabled = !state.isRunning,
                                    modifier = Modifier.size(if (compact) 36.dp else 40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape),
                                ) {
                                    Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = PremiumWhite)
                                }
                            }
                        }

                        Spacer(Modifier.height(if (compact) 18.dp else 22.dp))

                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text(
                                        status,
                                        modifier = Modifier.weight(1f),
                                        color = PremiumWhite,
                                        fontFamily = MinimalArabicFont,
                                        fontSize = if (compact) 13.sp else 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    "$percent%",
                                    color = Color(0xFF83C7FF),
                                    fontSize = if (compact) 33.sp else 40.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(8.dp).clip(RoundedCornerShape(99.dp)),
                            color = Color(0xFF69BCFF),
                            trackColor = Color(0xFF6488B3),
                        )

                        if (state.isRunning) {
                            Text(
                                premiumClock(remaining),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                color = Color(0xFFCFE2FA),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalWorkflow(state: TranslatorUiState, compact: Boolean) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val active = when {
        state.srtFile != null || percent >= 98 -> 4
        percent >= 90 -> 3
        percent >= 70 -> 1
        else -> 2
    }
    val specs = listOf(
        Triple("تحليل", Icons.Default.CloudUpload, 0),
        Triple("ترجمة", Icons.Default.Description, 1),
        Triple("صوت", Icons.Default.GraphicEq, 2),
        Triple("دمج", Icons.Default.Movie, 3),
        Triple("إنهاء", Icons.Default.Flag, 4),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 26.dp else 30.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 13.dp else 16.dp)) {
            Text(
                "خطوات العمل",
                color = PremiumInk,
                fontFamily = MinimalArabicFont,
                fontSize = if (compact) 20.sp else 23.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    specs.forEachIndexed { i, spec ->
                        MinimalStep(
                            title = spec.first,
                            icon = spec.second,
                            active = spec.third == active,
                            done = isStepDone(spec.third, percent, state.srtFile != null),
                            compact = compact,
                            modifier = Modifier.weight(1f),
                        )
                        if (i < specs.lastIndex) {
                            Box(
                                Modifier
                                    .padding(top = if (compact) 20.dp else 23.dp)
                                    .width(if (compact) 7.dp else 11.dp)
                                    .height(2.dp)
                                    .background(Color(0xFFC9D5E4), RoundedCornerShape(99.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isStepDone(index: Int, percent: Int, completed: Boolean): Boolean = when (index) {
    0 -> true
    2 -> percent >= 70
    1 -> percent >= 90
    3 -> percent >= 98
    4 -> completed
    else -> false
}

@Composable
private fun MinimalStep(
    title: String,
    icon: ImageVector,
    active: Boolean,
    done: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(if (active) (if (compact) 48.dp else 54.dp) else (if (compact) 42.dp else 47.dp))
                    .background(if (active) PremiumBlueSoft else Color(0xFFF0F4F9), CircleShape)
                    .then(if (active) Modifier.shadow(0.dp) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (active) PremiumBlue else Color(0xFF69788D), modifier = Modifier.size(if (compact) 20.dp else 23.dp))
                if (done && !active) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).size(16.dp).background(PremiumGreen, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, null, tint = PremiumWhite, modifier = Modifier.size(10.dp))
                    }
                }
            }
            Text(
                title,
                color = if (active) PremiumBlue else PremiumInk,
                fontFamily = MinimalArabicFont,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (active) {
                Text("الآن", color = PremiumBlue, fontFamily = MinimalArabicFont, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MinimalMetrics(state: TranslatorUiState, compact: Boolean) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val model = when {
        percent < 15 -> "تجهيز"
        percent < 70 -> "Whisper"
        percent < 88 -> "Azure"
        percent < 97 -> "Groq"
        else -> "إنهاء"
    }
    val items = buildList {
        add(MinimalMetric(Icons.Default.CloudUpload, Color(0xFFEAF3FF), PremiumBlue, model, "الموديل"))
        add(MinimalMetric(Icons.Default.Schedule, Color(0xFFF1ECFF), Color(0xFF7657D8), premiumClock(state.videoDurationMs), "المدة"))
        if (state.isRunning) add(MinimalMetric(Icons.Default.Schedule, Color(0xFFFFF2DB), Color(0xFFB97514), premiumClock(remaining), "المتبقي"))
        add(MinimalMetric(Icons.Default.Folder, Color(0xFFE8F8F0), PremiumGreen, if (state.srtFile != null) "1/1" else "0/1", "النتيجة"))
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 365.dp
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { item -> MinimalMetricCard(item, compact, Modifier.weight(1f)) }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item -> MinimalMetricCard(item, compact, Modifier.weight(1f)) }
            }
        }
    }
}

private data class MinimalMetric(
    val icon: ImageVector,
    val soft: Color,
    val tint: Color,
    val value: String,
    val label: String,
)

@Composable
private fun MinimalMetricCard(item: MinimalMetric, compact: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 20.dp else 23.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 10.dp else 12.dp, horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(if (compact) 34.dp else 39.dp).background(item.soft, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, null, tint = item.tint, modifier = Modifier.size(if (compact) 19.dp else 21.dp))
            }
            Text(
                item.value,
                color = PremiumInk,
                fontFamily = MinimalArabicFont,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 7.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.label, color = PremiumMuted, fontFamily = MinimalArabicFont, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MinimalStatus(online: Boolean, onBackground: () -> Unit, compact: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 350.dp
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MinimalCloudCard(online, compact, Modifier.fillMaxWidth())
                MinimalBackgroundCard(onBackground, compact, Modifier.fillMaxWidth())
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MinimalBackgroundCard(onBackground, compact, Modifier.weight(1f))
                    MinimalCloudCard(online, compact, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MinimalCloudCard(online: Boolean, compact: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 21.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = if (compact) 11.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).background(if (online) PremiumGreenSoft else Color(0xFFF0F3F7), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (online) Icons.Default.CloudDone else Icons.Default.CloudOff, null, tint = if (online) PremiumGreen else PremiumMuted, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (online) "متصل" else "غير متصل",
                    color = PremiumInk,
                    fontFamily = MinimalArabicFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MinimalBackgroundCard(onBackground: () -> Unit, compact: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 21.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("الخلفية", color = PremiumInk, fontFamily = MinimalArabicFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
}

@Composable
private fun MinimalMainAction(
    state: TranslatorUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
    compact: Boolean,
) {
    when {
        state.isRunning -> Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(if (compact) 58.dp else 64.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumRed),
        ) {
            Icon(Icons.Default.Stop, null, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text("إيقاف", fontFamily = MinimalArabicFont, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        state.srtFile != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onWatch,
                modifier = Modifier.weight(1f).height(if (compact) 56.dp else 62.dp),
                shape = RoundedCornerShape(23.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("مشاهدة", fontFamily = MinimalArabicFont, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f).height(if (compact) 56.dp else 62.dp),
                shape = RoundedCornerShape(23.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumWhite, contentColor = PremiumBlue),
                border = BorderStroke(1.dp, PremiumHairline),
            ) {
                Text("حفظ SRT", fontFamily = MinimalArabicFont, fontWeight = FontWeight.Bold)
            }
        }

        else -> Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(if (compact) 58.dp else 64.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(8.dp))
            Text("ابدأ الترجمة", fontFamily = MinimalArabicFont, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MinimalBottomBar(tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = PremiumShadow, spotColor = PremiumShadow),
        color = PremiumWhite,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color(0xFFF0F4F9)),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding().height(72.dp),
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
                    icon = { Icon(icon, null, modifier = Modifier.size(24.dp)) },
                    label = { Text(label, fontFamily = MinimalArabicFont, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PremiumBlue,
                        selectedTextColor = PremiumBlue,
                        indicatorColor = PremiumBlueSoft,
                        unselectedIconColor = Color(0xFF75849A),
                        unselectedTextColor = Color(0xFF75849A),
                    ),
                )
            }
        }
    }
}
