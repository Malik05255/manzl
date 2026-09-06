package com.manzl.movietranslator

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val AppBg = Color(0xFF08110D)
private val AppSurface = Color(0xFF101A15)
private val AppSurface2 = Color(0xFF17231C)
private val Emerald = Color(0xFF34D36B)
private val EmeraldSoft = Color(0xFF9EF0B8)
private val Muted = Color(0xFF94A39A)
private val Danger = Color(0xFFFF6B6B)

private enum class AppTab { HOME, LIBRARY, PLATFORMS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Emerald,
                    onPrimary = Color(0xFF041108),
                    background = AppBg,
                    surface = AppSurface,
                    surfaceVariant = AppSurface2,
                    onSurface = Color.White,
                    onBackground = Color.White,
                    onSurfaceVariant = Muted,
                    error = Danger,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                MovieTranslatorApp(vm)
            }
        }
    }
}

@Composable
private fun MovieTranslatorApp(viewModel: MovieTranslatorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val cloudError by viewModel.cloudUiError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var activePlayerUri by remember { mutableStateOf<Uri?>(null) }
    var activePlayerSrt by remember { mutableStateOf<File?>(null) }
    var activePlayerName by remember { mutableStateOf("") }
    var relinkTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    var downloadTarget by remember { mutableStateOf<CloudMovieItem?>(null) }

    val homePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectVideo(uri, context.displayName(uri))
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val movie = relinkTarget
        if (uri != null && movie != null) viewModel.relinkMovie(movie, uri)
        relinkTarget = null
    }
    val cloudSrtSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        val movie = downloadTarget
        if (uri != null && movie != null) viewModel.exportCloudSrt(movie, uri)
        downloadTarget = null
    }

    LaunchedEffect(tab) {
        when (tab) {
            AppTab.LIBRARY -> viewModel.refreshLibrary()
            AppTab.PLATFORMS -> viewModel.refreshPlatforms()
            else -> Unit
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = AppBg,
            bottomBar = {
                AppBottomBar(tab) { tab = it }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .statusBarsPadding()
                    .fillMaxSize(),
            ) {
                AppHeader(tab)
                when (tab) {
                    AppTab.HOME -> HomeScreen(
                        state = state,
                        onPickMovie = { homePicker.launch(arrayOf("video/*")) },
                        onExecute = viewModel::start,
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
                    )
                    AppTab.LIBRARY -> LibraryScreen(
                        movies = library,
                        onRefresh = viewModel::refreshLibrary,
                        onRelink = { movie ->
                            relinkTarget = movie
                            relinkPicker.launch(arrayOf("video/*"))
                        },
                        onDownload = { movie ->
                            downloadTarget = movie
                            cloudSrtSaver.launch("${movie.movieName.substringBeforeLast('.', movie.movieName)}_ar.srt")
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
                    AppTab.PLATFORMS -> PlatformsScreen(platforms = platforms, onRefresh = viewModel::refreshPlatforms)
                }
            }
        }
    }

    val visibleError = state.error ?: cloudError
    if (visibleError != null) {
        AlertDialog(
            onDismissRequest = {
                viewModel.clearError()
                viewModel.clearCloudUiError()
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearError()
                    viewModel.clearCloudUiError()
                }) { Text("حسنًا") }
            },
            title = { Text("تعذر إكمال العملية") },
            text = { Text(visibleError) },
        )
    }

    val playerUri = activePlayerUri
    val playerSrt = activePlayerSrt
    if (playerUri != null && playerSrt != null) {
        CinemaPlayerDialog(
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
private fun AppHeader(tab: AppTab) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = Emerald.copy(alpha = 0.14f)) {
            Icon(
                imageVector = when (tab) {
                    AppTab.HOME -> Icons.Default.AutoAwesome
                    AppTab.LIBRARY -> Icons.Default.VideoLibrary
                    AppTab.PLATFORMS -> Icons.Default.Cloud
                },
                contentDescription = null,
                tint = Emerald,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                when (tab) {
                    AppTab.HOME -> "مترجم الأفلام"
                    AppTab.LIBRARY -> "مكتبة الأفلام"
                    AppTab.PLATFORMS -> "منصاتي"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (tab == AppTab.HOME) {
                Text("تركي ← عربي", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AppBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(containerColor = AppSurface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selected == AppTab.HOME,
            onClick = { onSelect(AppTab.HOME) },
            icon = { Icon(Icons.Default.Home, null) },
            label = { Text("الرئيسية") },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = selected == AppTab.LIBRARY,
            onClick = { onSelect(AppTab.LIBRARY) },
            icon = { Icon(Icons.Default.VideoLibrary, null) },
            label = { Text("المكتبة") },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = selected == AppTab.PLATFORMS,
            onClick = { onSelect(AppTab.PLATFORMS) },
            icon = { Icon(Icons.Default.Cloud, null) },
            label = { Text("منصاتي") },
            colors = navColors(),
        )
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color(0xFF041108),
    selectedTextColor = EmeraldSoft,
    indicatorColor = Emerald,
    unselectedIconColor = Muted,
    unselectedTextColor = Muted,
)

@Composable
private fun HomeScreen(
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(16.dp), color = AppSurface2, modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Movie, null, tint = Emerald, modifier = Modifier.size(28.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            state.videoName.ifBlank { "اختر فيلمًا" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (state.videoUri == null) "جاهز لرفع الفيلم" else formatClock(state.videoDurationMs),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onPickMovie,
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Text(if (state.videoUri == null) " رفع الفيلم" else " تغيير")
                    }
                    Button(
                        onClick = onExecute,
                        enabled = state.videoUri != null && !state.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color(0xFF031108)),
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Text(" تنفيذ", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        ProgressConsole(state = state, onBackground = onBackground, onCancel = onCancel)

        if (!state.isRunning && state.srtFile != null && state.videoUri != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Emerald.copy(alpha = 0.10f)),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = Emerald) {
                        Icon(Icons.Default.CloudDone, null, tint = Color(0xFF031108), modifier = Modifier.padding(9.dp))
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("الترجمة جاهزة", fontWeight = FontWeight.Bold)
                        Text("موجودة أيضًا في مكتبة الأفلام", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onWatch) {
                        Icon(Icons.Default.PlayArrow, "مشاهدة", tint = Emerald, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ProgressConsole(
    state: TranslatorUiState,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = state.progress.coerceIn(0f, 1f)
    val percent = (progress * 100f).roundToInt()
    val next = nextMilestone(percent)
    val remaining = (next.first - percent).coerceAtLeast(0)

    Card(
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$percent%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Emerald)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        percent >= 100 -> "مكتمل"
                        state.isRunning -> "باقي $remaining٪ للخطوة التالية"
                        else -> "جاهز"
                    },
                    color = if (state.isRunning) EmeraldSoft else Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Emerald,
                    trackColor = AppSurface2,
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(15, 45, 62, 82, 95).forEach { mark ->
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(8.dp)
                                .background(if (percent >= mark) Color(0xFF031108) else Muted.copy(alpha = 0.45f), CircleShape)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(if (state.isRunning) Emerald else Muted, CircleShape))
                Text(
                    state.stage,
                    modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.isRunning && percent < 100) {
                Text(
                    "التالي: ${next.second}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onBackground,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppSurface2, contentColor = EmeraldSoft),
                ) {
                    Icon(Icons.Default.Cloud, null)
                    Text(if (percent >= 45) " المنصة تكمل • إخفاء التطبيق" else " متابعة في الخلفية")
                }
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("إيقاف المهمة", color = Danger)
                }
            }
        }
    }
}

private fun nextMilestone(percent: Int): Pair<Int, String> = when {
    percent < 15 -> 15 to "اكتمال تجهيز الصوت"
    percent < 45 -> 45 to "وصول الصوت للمنصة"
    percent < 62 -> 62 to "فهم الحوار التركي"
    percent < 82 -> 82 to "اكتمال الترجمة الأولية"
    percent < 95 -> 95 to "اكتمال مراجعة الدقة"
    percent < 100 -> 100 to "الحفظ في مكتبة الأفلام"
    else -> 100 to "تمت العملية"
}

@Composable
private fun LibraryScreen(
    movies: List<CloudMovieItem>,
    onRefresh: () -> Unit,
    onRelink: (CloudMovieItem) -> Unit,
    onDownload: (CloudMovieItem) -> Unit,
    onDelete: (CloudMovieItem) -> Unit,
    onWatch: (CloudMovieItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${movies.size} فيلم", color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("تحديث", color = Emerald) }
        }

        if (movies.isEmpty()) {
            EmptyLibrary()
        } else {
            movies.forEachIndexed { index, movie ->
                MovieLibraryCard(
                    index = index + 1,
                    movie = movie,
                    onRelink = { onRelink(movie) },
                    onDownload = { onDownload(movie) },
                    onDelete = { onDelete(movie) },
                    onWatch = { onWatch(movie) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun EmptyLibrary() {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.VideoLibrary, null, tint = Emerald, modifier = Modifier.size(42.dp))
            Text("مكتبتك فارغة", fontWeight = FontWeight.Bold)
            Text("أول ترجمة مكتملة ستظهر هنا", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MovieLibraryCard(
    index: Int,
    movie: CloudMovieItem,
    onRelink: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onWatch: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = AppSurface2) {
                    Text("$index", modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = Emerald, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text(movie.movieName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatClock(movie.durationMs), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRelink) { Icon(Icons.Default.Edit, "تعديل المسار", tint = Muted) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    text = if (movie.localAvailable) "الفيلم على الجوال" else "حدد مسار الفيلم",
                    active = movie.localAvailable,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = if (!movie.srtText.isNullOrBlank()) "الترجمة جاهزة" else "بدون ترجمة",
                    active = !movie.srtText.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onWatch,
                    enabled = movie.localAvailable && !movie.srtText.isNullOrBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color(0xFF031108)),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(" مشاهدة")
                }
                IconButton(onClick = onDownload, enabled = !movie.srtText.isNullOrBlank()) {
                    Icon(Icons.Default.Download, "تنزيل الترجمة", tint = if (!movie.srtText.isNullOrBlank()) EmeraldSoft else Muted)
                }
                IconButton(onClick = onDelete, enabled = !movie.srtText.isNullOrBlank()) {
                    Icon(Icons.Default.DeleteOutline, "حذف الترجمة", tint = if (!movie.srtText.isNullOrBlank()) Danger else Muted)
                }
                IconButton(onClick = {}, enabled = false) {
                    Icon(Icons.Default.SaveAlt, "حفظ الفيلم مع الترجمة", tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (active) Emerald.copy(alpha = 0.11f) else AppSurface2,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = if (active) EmeraldSoft else Muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PlatformsScreen(platforms: List<PlatformQuota>, onRefresh: () -> Unit) {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الحصص المجانية", color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("تحديث", color = Emerald) }
        }
        if (platforms.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(22.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Emerald)
                    Text("قراءة حالة المنصات…", modifier = Modifier.padding(horizontal = 12.dp), color = Muted)
                }
            }
        } else {
            platforms.forEach { platform -> PlatformCard(platform, now) }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PlatformCard(platform: PlatformQuota, now: Long) {
    val remaining = platform.remainingPercent.coerceIn(0, 100)
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(22.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp)) {
                CircularProgressIndicator(
                    progress = { remaining / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = Emerald,
                    trackColor = AppSurface2,
                    strokeWidth = 7.dp,
                )
                Text("$remaining%", fontWeight = FontWeight.Bold, color = EmeraldSoft)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(platform.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (platform.detail.isNotBlank()) {
                    Text(platform.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Text(
                    "استعادة الحصة خلال ${formatCountdown(platform.resetAtEpochMs - now)}",
                    color = EmeraldSoft,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun CinemaPlayerDialog(
    videoUri: Uri,
    subtitleFile: File,
    movieName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var subtitleSize by remember { mutableStateOf(22f) }
    val player = remember(videoUri, subtitleFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            setMediaItem(
                MediaItem.Builder()
                    .setUri(videoUri)
                    .setSubtitleConfigurations(listOf(subtitle))
                    .build()
            )
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                    update = { view ->
                        view.player = player
                        view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.58f)).statusBarsPadding().padding(8.dp).align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "إغلاق", tint = Color.White) }
                    Text(movieName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(999.dp), color = Emerald.copy(alpha = 0.18f)) {
                        Text("AR", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = EmeraldSoft, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.70f),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { subtitleSize = (subtitleSize - 2f).coerceAtLeast(16f) }) { Text("A−", color = Color.White) }
                        Text("ترجمة", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { subtitleSize = (subtitleSize + 2f).coerceAtMost(34f) }) { Text("A+", color = Color.White) }
                    }
                }
            }
        }
    }
}

private fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index) ?: "فيلم"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "فيلم"
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "—"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatCountdown(ms: Long): String {
    if (ms <= 0L) return "قريبًا"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
