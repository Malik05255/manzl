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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Pixel-measured rebuild of the user's approved 941 x 1672 reference image.
 *
 * Important: fixed physical positions use absoluteOffset, never RTL-aware offset. Horizontal
 * coordinates are measured directly from the reference. Vertical coordinates are mapped across the
 * usable Honor 200 height so the full composition occupies the display instead of being compressed
 * into a short centered frame. Text/icon scale follows width, while vertical placement follows the
 * screen height. This keeps circles circular and the visual hierarchy faithful on tall phones.
 */
class MeasuredReferenceMainActivity : ComponentActivity() {
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
                    primary = RefBlue,
                    onPrimary = Color.White,
                    background = RefBg,
                    surface = Color.White,
                    onSurface = RefInk,
                    onBackground = RefInk,
                    surfaceVariant = RefBlueSoft,
                    onSurfaceVariant = RefMuted,
                    error = PremiumRed,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                MeasuredReferenceApp(vm)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 704)
        }
    }
}

@Composable
private fun MeasuredReferenceApp(viewModel: MovieTranslatorViewModel) {
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

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(RefBg)) {
            when (tab) {
                PremiumTab.HOME -> MeasuredHome(
                    state = state,
                    tab = tab,
                    onTab = { tab = it },
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

                PremiumTab.PROJECTS -> {
                    PremiumProjectsScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
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
                    PremiumBottomBar(tab = tab, onTab = { tab = it })
                }

                PremiumTab.LIBRARY -> {
                    PremiumLibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
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
                    PremiumBottomBar(tab = tab, onTab = { tab = it })
                }

                PremiumTab.SETTINGS -> {
                    PremiumSettingsScreen(
                        modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
                        platforms = platforms,
                        onRefresh = viewModel::refreshPlatforms,
                    )
                    PremiumBottomBar(tab = tab, onTab = { tab = it })
                }
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError(); viewModel.clearCloudUiError() },
            confirmButton = { TextButton(onClick = { viewModel.clearError(); viewModel.clearCloudUiError() }) { Text("إغلاق", color = RefBlue) } },
            title = { Text("تعذر إكمال العملية", color = RefInk) },
            text = { Text(visibleError, color = RefMuted) },
            containerColor = Color.White,
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

private class RefScale(
    val sx: Float,
    val sy: Float,
    val density: androidx.compose.ui.unit.Density,
) {
    fun x(px: Float): Dp = with(density) { (px * sx).toDp() }
    fun y(px: Float): Dp = with(density) { (px * sy).toDp() }
    fun s(px: Float): Dp = x(px)
    fun fs(px: Float): TextUnit = with(density) { x(px).toSp() }
}

@Composable
private fun MeasuredHome(
    state: TranslatorUiState,
    tab: PremiumTab,
    onTab: (PremiumTab) -> Unit,
    onPickMovie: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val online by produceState(initialValue = CloudConnectivity.isOnline(context), context) {
        while (true) { value = CloudConnectivity.isOnline(context); delay(2_000L) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(RefBg)) {
        val density = LocalDensity.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val physicalW = with(density) { maxWidth.toPx() }
        val physicalH = with(density) { (maxHeight - navBottom).toPx() }
        val r = RefScale(
            sx = physicalW / REF_W,
            sy = physicalH / REF_H,
            density = density,
        )

        Box(
            Modifier
                .width(maxWidth)
                .height(maxHeight - navBottom)
                .background(RefBg)
        ) {
            RefTopBackground(r)
            RefHeader(r)

            if (state.videoUri == null) {
                RefEmptyHero(r, onPickMovie)
            } else {
                RefMovieHero(r, state, onPickMovie)
                RefWorkflow(r, state)
                RefMetrics(r, state)
                RefStatus(r, online, onBackground)
                RefMainAction(r, state, onStart, onCancel, onWatch, onExport)
            }
            RefBottomNav(r, tab, onTab)
        }
    }
}

@Composable
private fun RefTopBackground(r: RefScale) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(r.y(410f))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF102B53), Color(0xFF1B4B87), Color(0xFF2E68AA)),
                    start = Offset.Zero,
                    end = Offset(1400f, 800f),
                )
            )
    )
    Canvas(Modifier.absoluteOffset(y = r.y(365f)).fillMaxWidth().height(r.y(70f))) {
        val p = Path().apply {
            moveTo(0f, size.height * 0.15f)
            quadraticBezierTo(size.width * 0.50f, size.height * 1.02f, size.width, size.height * 0.14f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, RefBg)
    }
}

@Composable
private fun RefHeader(r: RefScale) {
    // Physical placement from the approved image: avatar/greeting LEFT, title RIGHT.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.absoluteOffset(r.x(37f), r.y(78f)).size(r.s(86f)).background(Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AccountCircle, null, tint = Color.White, modifier = Modifier.size(r.s(60f)))
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    Modifier.absoluteOffset(r.x(148f), r.y(91f)).width(r.x(225f)),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text("مرحبًا بك", color = Color.White, fontSize = r.fs(24f), fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("فلننجز شيئًا رائعًا اليوم 👋", color = Color(0xFFDDEAFF), fontSize = r.fs(18f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(
                    Modifier.absoluteOffset(r.x(598f), r.y(84f)).width(r.x(305f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF65B7FF), modifier = Modifier.size(r.s(40f)))
                    Spacer(Modifier.width(r.x(8f)))
                    Text("مترجم الأفلام", color = Color.White, fontSize = r.fs(39f), fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                }
                Text(
                    "ذكاء اصطناعي لمحتوى بلا حدود",
                    modifier = Modifier.absoluteOffset(r.x(615f), r.y(145f)).width(r.x(282f)),
                    color = Color(0xFFDDEAFF),
                    fontSize = r.fs(20f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun RefEmptyHero(r: RefScale, onPickMovie: () -> Unit) {
    Card(
        modifier = Modifier.absoluteOffset(r.x(25f), r.y(203f)).width(r.x(882f)).height(r.y(353f)),
        shape = RoundedCornerShape(r.s(34f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F)),
        border = BorderStroke(r.s(1.3f), Color(0xFF69B5FF)),
    ) {
        Column(
            Modifier.fillMaxSize().clickable(onClick = onPickMovie),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Movie, null, tint = Color(0xFF88C9FF), modifier = Modifier.size(r.s(56f)))
            Text("اختر فيلمًا للترجمة", color = Color.White, fontSize = r.fs(28f), fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = r.y(10f)))
            Text("اضغط هنا لاختيار الفيديو", color = Color(0xFFD8E8FF), fontSize = r.fs(18f))
        }
    }
}

@Composable
private fun RefMovieHero(r: RefScale, state: TranslatorUiState, onPickMovie: () -> Unit) {
    val context = LocalContext.current
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val fileSize by produceState(initialValue = "—", state.videoUri) {
        value = state.videoUri?.let { context.premiumFileSize(it) } ?: "—"
    }

    Card(
        modifier = Modifier.absoluteOffset(r.x(25f), r.y(203f)).width(r.x(882f)).height(r.y(353f)),
        shape = RoundedCornerShape(r.s(34f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F).copy(alpha = 0.985f)),
        border = BorderStroke(r.s(1.3f), Color(0xFF69B5FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Poster is physically LEFT in the approved reference.
            PremiumVideoThumb(
                uri = state.videoUri,
                duration = premiumClock(state.videoDurationMs),
                modifier = Modifier.absoluteOffset(r.x(36f), r.y(42f)).width(r.x(205f)).height(r.y(270f)),
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    state.videoName.ifBlank { "الفيلم المحدد" },
                    modifier = Modifier.absoluteOffset(r.x(282f), r.y(52f)).width(r.x(428f)),
                    color = Color.White,
                    fontSize = r.fs(28f),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Row(
                    modifier = Modifier.absoluteOffset(r.x(282f), r.y(111f)).width(r.x(440f)).height(r.y(37f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(r.x(9f)),
                ) {
                    Icon(Icons.Default.Movie, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(r.s(22f)))
                    Text("MP4", color = Color(0xFFE1ECFF), fontSize = r.fs(21f))
                    Text("|", color = Color(0xFFBDD0EA), fontSize = r.fs(20f))
                    Icon(Icons.Default.Schedule, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(r.s(22f)))
                    Text(premiumClock(state.videoDurationMs), color = Color(0xFFE1ECFF), fontSize = r.fs(21f))
                    Text("|", color = Color(0xFFBDD0EA), fontSize = r.fs(20f))
                    Icon(Icons.Default.Description, null, tint = Color(0xFFE1ECFF), modifier = Modifier.size(r.s(21f)))
                    Text(fileSize, color = Color(0xFFE1ECFF), fontSize = r.fs(20f), maxLines = 1)
                }
            }

            IconButton(
                onClick = onPickMovie,
                enabled = !state.isRunning,
                modifier = Modifier.absoluteOffset(r.x(784f), r.y(43f)).size(r.s(58f)).background(Color.White.copy(alpha = 0.08f), CircleShape),
            ) {
                Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = Color.White, modifier = Modifier.size(r.s(31f)))
            }

            Text(
                when {
                    state.srtFile != null -> "اكتملت الترجمة بنجاح"
                    state.isRunning -> state.stage.ifBlank { "جاري تجهيز الصوت بالذكاء الاصطناعي" }
                    else -> "الفيلم جاهز لبدء الترجمة"
                },
                modifier = Modifier.absoluteOffset(r.x(282f), r.y(184f)).width(r.x(425f)),
                color = Color.White,
                fontSize = r.fs(23f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                "$percent%",
                modifier = Modifier.absoluteOffset(r.x(728f), r.y(166f)).width(r.x(115f)),
                color = Color(0xFF83C7FF),
                fontSize = r.fs(53f),
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.End,
                maxLines = 1,
            )

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.absoluteOffset(r.x(282f), r.y(247f)).width(r.x(560f)).height(r.y(18f)).clip(RoundedCornerShape(r.s(999f))),
                color = Color(0xFF70BFFF),
                trackColor = Color(0xFF668CB9),
            )

            Row(
                modifier = Modifier.absoluteOffset(r.x(606f), r.y(289f)).width(r.x(238f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text("الوقت المتبقي: ${if (state.isRunning) premiumClock(remaining) else "—"}", color = Color(0xFFD9E7FA), fontSize = r.fs(19f), maxLines = 1)
                Spacer(Modifier.width(r.x(8f)))
                Icon(Icons.Default.Schedule, null, tint = Color(0xFFD9E7FA), modifier = Modifier.size(r.s(22f)))
            }
        }
    }
}

@Composable
private fun RefWorkflow(r: RefScale, state: TranslatorUiState) {
    val pct = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val active = when {
        state.srtFile != null || pct >= 98 -> 0
        pct >= 90 -> 1
        pct >= 70 -> 3
        else -> 2
    }

    Card(
        modifier = Modifier.absoluteOffset(r.x(25f), r.y(585f)).width(r.x(882f)).height(r.y(350f)),
        shape = RoundedCornerShape(r.s(34f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(r.s(1.2f), Color(0xFFE2EAF4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.absoluteOffset(r.x(29f), r.y(28f)).width(r.x(239f)).height(r.y(68f)),
                color = RefBlueSoft,
                shape = RoundedCornerShape(r.s(24f)),
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = RefBlue, modifier = Modifier.size(r.s(28f)))
                    Spacer(Modifier.width(r.x(9f)))
                    Text("مراقبة مباشرة", color = RefBlue, fontSize = r.fs(21f), fontWeight = FontWeight.Bold)
                }
            }

            Text("خطوات العمل", modifier = Modifier.absoluteOffset(r.x(664f), r.y(26f)).width(r.x(195f)), color = RefInk, fontSize = r.fs(30f), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End, maxLines = 1)
            Text("من الملف إلى الترجمة النهائية", modifier = Modifier.absoluteOffset(r.x(622f), r.y(71f)).width(r.x(238f)), color = RefMuted, fontSize = r.fs(18f), textAlign = TextAlign.End, maxLines = 1)

            val centers = listOf(96f, 291f, 465f, 635f, 808f)
            val specs = listOf(
                Triple("إنهاء", "حفظ الملف", Icons.Default.Flag),
                Triple("دمج وتركيب", "الصوت والترجمة", Icons.Default.Movie),
                Triple("معالجة الصوت", "جاري التنفيذ", Icons.Default.GraphicEq),
                Triple("ترجمة", "في الانتظار", Icons.Default.Description),
                Triple("تحليل الملف", "تم", Icons.Default.CloudUpload),
            )

            // Physical left-to-right order is fixed by the approved image.
            for (i in centers.indices) {
                val isActive = i == active
                val isDone = (i == 4) || (state.srtFile != null && i != 0)
                RefWorkflowStep(r, centers[i], specs[i].first, specs[i].second, specs[i].third, isActive, isDone)
                if (i < centers.lastIndex) {
                    val x1 = centers[i] + 55f
                    val x2 = centers[i + 1] - 55f
                    Box(Modifier.absoluteOffset(r.x(x1), r.y(181f)).width(r.x(x2 - x1)).height(r.s(2f)).background(if (i == 1 || i == 2) RefBlue else Color(0xFFCBD5E3)))
                }
            }
        }
    }
}

@Composable
private fun RefWorkflowStep(r: RefScale, centerX: Float, title: String, subtitle: String, icon: ImageVector, active: Boolean, done: Boolean) {
    val diameter = if (active) 105f else 94f
    val left = centerX - diameter / 2f
    Box(
        modifier = Modifier.absoluteOffset(r.x(left), r.y(if (active) 128f else 135f)).size(r.s(diameter)).background(Color(0xFFF1F5FA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(RefBlue, -90f, 300f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r.s(5f).toPx()))
            }
        }
        Icon(icon, null, tint = if (active) RefBlue else Color(0xFF68778D), modifier = Modifier.size(r.s(if (active) 42f else 35f)))
        if (done && !active) {
            Box(Modifier.align(Alignment.TopEnd).size(r.s(27f)).background(RefGreen, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(r.s(18f)))
            }
        }
    }

    if (title == "معالجة الصوت") {
        Text("معالجة الصوت", modifier = Modifier.absoluteOffset(r.x(centerX - 75f), r.y(242f)).width(r.x(150f)), color = RefInk, fontSize = r.fs(19f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        Text(subtitle, modifier = Modifier.absoluteOffset(r.x(centerX - 75f), r.y(286f)).width(r.x(150f)), color = if (active) RefBlue else RefMuted, fontSize = r.fs(17f), textAlign = TextAlign.Center, maxLines = 1)
    } else {
        Text(title, modifier = Modifier.absoluteOffset(r.x(centerX - 78f), r.y(242f)).width(r.x(156f)), color = RefInk, fontSize = r.fs(18f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, modifier = Modifier.absoluteOffset(r.x(centerX - 78f), r.y(286f)).width(r.x(156f)), color = RefMuted, fontSize = r.fs(16f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RefMetrics(r: RefScale, state: TranslatorUiState) {
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val pct = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val model = when { pct < 15 -> "تجهيز"; pct < 70 -> "Whisper"; pct < 88 -> "Azure"; pct < 97 -> "Groq"; else -> "إنهاء" }
    RefMetricCard(r, 28f, Icons.Default.Folder, Color(0xFFE8F8F0), RefGreen, if (state.srtFile != null) "1/1" else "0/1", "ملفات مكتملة")
    RefMetricCard(r, 249f, Icons.Default.Schedule, Color(0xFFFFF1D8), Color(0xFFB97614), if (state.isRunning) premiumClock(remaining) else "—", "الوقت المتبقي")
    RefMetricCard(r, 470f, Icons.Default.Description, Color(0xFFF0EAFF), Color(0xFF7658D8), premiumClock(state.videoDurationMs), "مدة الفيديو")
    RefMetricCard(r, 691f, Icons.Default.Cloud, Color(0xFFEAF3FF), RefBlue, model, "النموذج المستخدم")
}

@Composable
private fun RefMetricCard(r: RefScale, x: Float, icon: ImageVector, soft: Color, tint: Color, value: String, label: String) {
    Card(
        modifier = Modifier.absoluteOffset(r.x(x), r.y(954f)).width(r.x(208f)).height(r.y(208f)),
        shape = RoundedCornerShape(r.s(28f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(r.s(1f), Color(0xFFE2EAF4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.absoluteOffset(r.x(58f), r.y(20f)).size(r.s(72f)).background(soft, RoundedCornerShape(r.s(23f))), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(r.s(38f)))
            }
            Text(value, modifier = Modifier.absoluteOffset(r.x(12f), r.y(103f)).width(r.x(184f)), color = RefInk, fontSize = r.fs(if (value.length > 7) 27f else 31f), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, modifier = Modifier.absoluteOffset(r.x(9f), r.y(157f)).width(r.x(190f)), color = RefMuted, fontSize = r.fs(17f), textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun RefStatus(r: RefScale, online: Boolean, onBackground: () -> Unit) {
    // Left card: background continuation.
    Card(
        modifier = Modifier.absoluteOffset(r.x(28f), r.y(1181f)).width(r.x(437f)).height(r.y(128f)),
        shape = RoundedCornerShape(r.s(29f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(r.s(1f), Color(0xFFE5ECF5)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.absoluteOffset(r.x(24f), r.y(24f)).size(r.s(76f)).background(Color(0xFFF2F6FB), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Schedule, null, tint = RefInk, modifier = Modifier.size(r.s(39f)))
            }
            Text("المتابعة في الخلفية", modifier = Modifier.absoluteOffset(r.x(120f), r.y(19f)).width(r.x(200f)), color = RefInk, fontSize = r.fs(20f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
            Text("استمر في العمل حتى مع إغلاق التطبيق", modifier = Modifier.absoluteOffset(r.x(112f), r.y(53f)).width(r.x(215f)), color = RefMuted, fontSize = r.fs(15f), textAlign = TextAlign.End, maxLines = 2)
            Switch(
                checked = true,
                onCheckedChange = { if (it) onBackground() },
                modifier = Modifier.absoluteOffset(r.x(333f), r.y(35f)).width(r.x(78f)),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = RefBlue),
            )
        }
    }

    // Right card: cloud connectivity.
    Card(
        modifier = Modifier.absoluteOffset(r.x(477f), r.y(1181f)).width(r.x(437f)).height(r.y(128f)),
        shape = RoundedCornerShape(r.s(29f)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(r.s(1f), Color(0xFFE5ECF5)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.absoluteOffset(r.x(24f), r.y(24f)).size(r.s(76f)).background(Color(0xFFEAF8F1), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (online) Icons.Default.CloudDone else Icons.Default.Cloud, null, tint = RefGreen, modifier = Modifier.size(r.s(42f)))
            }
            Text("الاتصال بالسحابة", modifier = Modifier.absoluteOffset(r.x(116f), r.y(23f)).width(r.x(215f)), color = RefInk, fontSize = r.fs(20f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
            Text(if (online) "متصل وجاهز" else "غير متصل", modifier = Modifier.absoluteOffset(r.x(116f), r.y(60f)).width(r.x(215f)), color = RefMuted, fontSize = r.fs(16f), textAlign = TextAlign.End, maxLines = 1)
            Box(Modifier.absoluteOffset(r.x(348f), r.y(40f)).size(r.s(38f)).background(Color(0xFFE9F7EF), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = RefGreen, modifier = Modifier.size(r.s(25f)))
            }
        }
    }
}

@Composable
private fun RefMainAction(r: RefScale, state: TranslatorUiState, onStart: () -> Unit, onCancel: () -> Unit, onWatch: () -> Unit, onExport: () -> Unit) {
    val running = state.isRunning
    val complete = state.srtFile != null
    val c1 = when { running -> Color(0xFFBF2F35); complete -> Color(0xFF2E9B68); else -> RefBlue }
    val c2 = when { running -> Color(0xFFE44855); complete -> Color(0xFF43B987); else -> Color(0xFF4C96F1) }
    val title = when { running -> "إيقاف المهمة"; complete -> "الفيلم جاهز"; else -> "ابدأ الترجمة" }
    val subtitle = when { running -> "سيتم حفظ التقدم الحالي"; complete -> "المشاهدة أو تنزيل الترجمة"; else -> "" }

    Surface(
        modifier = Modifier.absoluteOffset(r.x(29f), r.y(1334f)).width(r.x(884f)).height(r.y(131f)).clickable {
            when { running -> onCancel(); complete -> onWatch(); else -> onStart() }
        },
        color = Color.Transparent,
        shape = RoundedCornerShape(r.s(34f)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(c1, c2)), RoundedCornerShape(r.s(34f)))) {
            Box(Modifier.absoluteOffset(r.x(34f), r.y(20f)).size(r.s(88f)).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(r.s(46f)))
            }
            Text(title, modifier = Modifier.absoluteOffset(r.x(310f), r.y(if (subtitle.isBlank()) 42f else 31f)).width(r.x(360f)), color = Color.White, fontSize = r.fs(29f), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Text(subtitle, modifier = Modifier.absoluteOffset(r.x(305f), r.y(76f)).width(r.x(370f)), color = Color.White.copy(alpha = 0.82f), fontSize = r.fs(18f), textAlign = TextAlign.Center, maxLines = 1)
            }
            if (complete) {
                Text("تنزيل", modifier = Modifier.absoluteOffset(r.x(726f), r.y(53f)).width(r.x(90f)).clickable { onExport() }, color = Color.White, fontSize = r.fs(18f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RefBottomNav(r: RefScale, tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    Surface(
        modifier = Modifier.absoluteOffset(r.x(26f), r.y(1490f)).width(r.x(889f)).height(r.y(139f)).shadow(r.s(10f), RoundedCornerShape(r.s(34f))),
        color = Color.White,
        shape = RoundedCornerShape(r.s(34f)),
        border = BorderStroke(r.s(1f), Color(0xFFF0F4F9)),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Measured physical positions from reference: settings, library, projects, home.
            RefNavItem(r, PremiumTab.SETTINGS, tab, onTab, 105f, "الإعدادات", Icons.Default.Settings)
            RefNavItem(r, PremiumTab.LIBRARY, tab, onTab, 345f, "المكتبة", Icons.Default.VideoLibrary)
            RefNavItem(r, PremiumTab.PROJECTS, tab, onTab, 570f, "مشاريعي", Icons.Default.Folder)
            RefNavItem(r, PremiumTab.HOME, tab, onTab, 806f, "الرئيسية", Icons.Default.Home)
        }
    }
}

@Composable
private fun RefNavItem(r: RefScale, item: PremiumTab, selectedTab: PremiumTab, onTab: (PremiumTab) -> Unit, centerX: Float, label: String, icon: ImageVector) {
    val selected = item == selectedTab
    Box(
        Modifier.absoluteOffset(r.x(centerX - 77f), r.y(11f)).width(r.x(154f)).height(r.y(116f)).clickable { onTab(item) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.absoluteOffset(y = r.y(4f)).width(r.x(132f)).height(r.y(65f)).background(if (selected) RefBlueSoft else Color.Transparent, RoundedCornerShape(r.s(32f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) RefBlue else Color(0xFF708198), modifier = Modifier.size(r.s(38f)))
        }
        Text(label, modifier = Modifier.absoluteOffset(y = r.y(82f)).fillMaxWidth(), color = if (selected) RefBlue else Color(0xFF708198), fontSize = r.fs(18f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

private const val REF_W = 941f
private const val REF_H = 1672f
private val RefBg = Color(0xFFF5FAFE)
private val RefInk = Color(0xFF142C50)
private val RefMuted = Color(0xFF8290A7)
private val RefBlue = Color(0xFF2F77E9)
private val RefBlueSoft = Color(0xFFEAF3FF)
private val RefGreen = Color(0xFF2E9B68)
