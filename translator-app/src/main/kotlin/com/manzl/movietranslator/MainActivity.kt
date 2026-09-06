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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
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

private val Night = Color(0xFF080B12)
private val NightSoft = Color(0xFF0D1320)
private val Panel = Color(0xFF111827)
private val PanelRaised = Color(0xFF182235)
private val Stroke = Color(0xFF273349)
private val Accent = Color(0xFF88A7FF)
private val AccentSoft = Color(0xFFB8C8FF)
private val TextPrimary = Color(0xFFF7F8FC)
private val TextMuted = Color(0xFF9DA8BA)
private val Success = Color(0xFF6EDAB3)
private val Warning = Color(0xFFFFC66A)
private val Danger = Color(0xFFFF8D9B)

private enum class AppTab { HOME, LIBRARY, STATUS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestTranslationNotificationsIfNeeded()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Night,
                    background = Night,
                    surface = Panel,
                    surfaceVariant = PanelRaised,
                    onSurface = TextPrimary,
                    onBackground = TextPrimary,
                    onSurfaceVariant = TextMuted,
                    error = Danger,
                )
            ) {
                val vm: MovieTranslatorViewModel = viewModel()
                MovieTranslatorApp(vm)
            }
        }
    }

    private fun requestTranslationNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
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
    val homeSrtSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) viewModel.exportSrt(uri)
    }

    LaunchedEffect(tab) {
        when (tab) {
            AppTab.LIBRARY -> viewModel.refreshLibrary()
            AppTab.STATUS -> viewModel.refreshPlatforms()
            AppTab.HOME -> Unit
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Night,
                            NightSoft,
                            Night,
                        )
                    )
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { PremiumBottomBar(selected = tab, onSelect = { tab = it }) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .statusBarsPadding()
                        .fillMaxSize(),
                ) {
                    PremiumHeader(tab)
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
                            onExport = {
                                val base = state.videoName.substringBeforeLast('.', state.videoName).ifBlank { "movie" }
                                homeSrtSaver.launch("${base}_ar.srt")
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

                        AppTab.STATUS -> ServicesScreen(platforms = platforms, onRefresh = viewModel::refreshPlatforms)
                    }
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
                TextButton(
                    onClick = {
                        viewModel.clearError()
                        viewModel.clearCloudUiError()
                    }
                ) { Text("إغلاق") }
            },
            title = { Text("تعذر إكمال العملية") },
            text = { Text(visibleError) },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            textContentColor = TextMuted,
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
private fun PremiumHeader(tab: AppTab) {
    val title = when (tab) {
        AppTab.HOME -> "مترجم الأفلام"
        AppTab.LIBRARY -> "المكتبة"
        AppTab.STATUS -> "الخدمات"
    }
    val subtitle = when (tab) {
        AppTab.HOME -> "ترجمة تركية إلى عربية، تلقائيًا"
        AppTab.LIBRARY -> "كل ترجماتك في مكان واحد"
        AppTab.STATUS -> "حالة المعالجة السحابية"
    }
    val icon = when (tab) {
        AppTab.HOME -> Icons.Default.AutoAwesome
        AppTab.LIBRARY -> Icons.Default.VideoLibrary
        AppTab.STATUS -> Icons.Default.Cloud
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .border(1.dp, Accent.copy(alpha = 0.20f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (tab == AppTab.HOME) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Success.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Success.copy(alpha = 0.18f)),
            ) {
                Text(
                    "جاهز",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    color = Success,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PremiumBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke.copy(alpha = 0.7f)),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            NavigationBarItem(
                selected = selected == AppTab.HOME,
                onClick = { onSelect(AppTab.HOME) },
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("الرئيسية") },
                colors = premiumNavColors(),
            )
            NavigationBarItem(
                selected = selected == AppTab.LIBRARY,
                onClick = { onSelect(AppTab.LIBRARY) },
                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                label = { Text("المكتبة") },
                colors = premiumNavColors(),
            )
            NavigationBarItem(
                selected = selected == AppTab.STATUS,
                onClick = { onSelect(AppTab.STATUS) },
                icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                label = { Text("الخدمات") },
                colors = premiumNavColors(),
            )
        }
    }
}

@Composable
private fun premiumNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = AccentSoft,
    indicatorColor = Accent.copy(alpha = 0.13f),
    unselectedIconColor = TextMuted,
    unselectedTextColor = TextMuted,
)

@Composable
private fun HomeScreen(
    state: TranslatorUiState,
    onPickMovie: () -> Unit,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.videoUri == null) {
            HeroPickerCard(onPickMovie = onPickMovie)
        } else {
            SelectedMovieCard(state = state, onPickMovie = onPickMovie)
        }

        when {
            state.isRunning -> TranslationProgressCard(
                state = state,
                onBackground = onBackground,
                onCancel = onCancel,
            )

            state.srtFile != null && state.videoUri != null -> CompletedTranslationCard(
                state = state,
                onWatch = onWatch,
                onExport = onExport,
            )

            state.videoUri != null -> ReadyToTranslateCard(onExecute = onExecute)
        }

        PrivacyCard()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HeroPickerCard(onPickMovie: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.18f), RoundedCornerShape(28.dp)),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "اختر فيلمك وابدأ مباشرة",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "التطبيق يتولى استخراج الصوت، فهم الحوار، الترجمة والمراجعة دون إعدادات تقنية.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onPickMovie,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Night),
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Text(" اختيار فيلم", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SelectedMovieCard(state: TranslatorUiState, onPickMovie: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(PanelRaised, RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    state.videoName.ifBlank { "الفيلم المحدد" },
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                    Text(formatClock(state.videoDurationMs), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = onPickMovie,
                enabled = !state.isRunning,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("تغيير")
            }
        }
    }
}

@Composable
private fun ReadyToTranslateCard(onExecute: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Success.copy(alpha = 0.12f)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("الفيلم جاهز", fontWeight = FontWeight.Bold)
                    Text("ابدأ الترجمة وسيكمل التطبيق الخطوات تلقائيًا.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = onExecute,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Night),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(" ابدأ الترجمة", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TranslationProgressCard(
    state: TranslatorUiState,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = state.progress.coerceIn(0f, 1f)
    val percent = (progress * 100f).roundToInt()
    val retrying = state.stage.contains("إعادة") ||
        state.stage.contains("غير متاح") ||
        state.stage.contains("سنستكمل") ||
        state.stage.contains("تأخرت") ||
        state.stage.contains("مشغولة")
    val statusColor = if (retrying) Warning else Accent

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("جارٍ ترجمة الفيلم", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("يمكنك ترك التطبيق وسيستمر العمل تلقائيًا.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = Accent.copy(alpha = 0.11f)) {
                    Text(
                        "$percent%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = AccentSoft,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                color = Accent,
                trackColor = PanelRaised,
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )

            TranslationSteps(percent)

            Surface(
                color = statusColor.copy(alpha = 0.09f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.16f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                    Text(
                        state.stage,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        color = if (retrying) Warning else TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            OutlinedButton(
                onClick = onBackground,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Text(" متابعة في الخلفية")
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("إيقاف المهمة", color = Danger)
            }
        }
    }
}

@Composable
private fun TranslationSteps(percent: Int) {
    val steps = listOf(
        1 to "تجهيز",
        15 to "رفع",
        45 to "فهم",
        82 to "ترجمة",
        95 to "مراجعة",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        steps.forEach { (threshold, label) ->
            val reached = percent >= threshold
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (reached) 10.dp else 8.dp)
                        .background(if (reached) Accent else Stroke, CircleShape)
                )
                Text(
                    label,
                    modifier = Modifier.padding(top = 5.dp),
                    color = if (reached) AccentSoft else TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CompletedTranslationCard(
    state: TranslatorUiState,
    onWatch: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Success.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Success.copy(alpha = 0.18f), RoundedCornerShape(26.dp)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Success.copy(alpha = 0.15f)) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("الترجمة جاهزة", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    val detail = if (state.processingMs > 0L) {
                        "اكتملت خلال ${formatClock(state.processingMs)}"
                    } else {
                        "أصبحت جاهزة للمشاهدة والحفظ"
                    }
                    Text(detail, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onWatch,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Success, contentColor = Night),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" مشاهدة", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(" حفظ SRT")
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        color = Panel.copy(alpha = 0.72f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null, tint = TextMuted, modifier = Modifier.size(19.dp))
            Text(
                "الفيلم يبقى على جهازك؛ تُرسل نسخة صوتية مؤقتة فقط لإتمام الترجمة.",
                modifier = Modifier.padding(horizontal = 10.dp),
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
    var deleteTarget by remember { mutableStateOf<CloudMovieItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ترجماتك المحفوظة", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("${movies.size} فيلم", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = Accent)
            }
        }

        if (movies.isEmpty()) {
            EmptyLibrary()
        } else {
            movies.forEach { movie ->
                MovieLibraryCard(
                    movie = movie,
                    onRelink = { onRelink(movie) },
                    onDownload = { onDownload(movie) },
                    onDelete = { deleteTarget = movie },
                    onWatch = { onWatch(movie) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        deleteTarget = null
                    }
                ) { Text("حذف", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
            },
            title = { Text("حذف الترجمة؟") },
            text = { Text("سيتم حذف ملف الترجمة المحفوظ لهذا الفيلم فقط.") },
            containerColor = Panel,
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(62.dp).background(PanelRaised, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
            }
            Text("لا توجد ترجمات بعد", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("عند اكتمال أول فيلم سيظهر هنا تلقائيًا.", color = TextMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MovieLibraryCard(
    movie: CloudMovieItem,
    onRelink: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onWatch: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(52.dp).background(PanelRaised, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(25.dp))
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text(movie.movieName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatClock(movie.durationMs), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRelink) {
                    Icon(Icons.Default.Edit, contentDescription = "تغيير ملف الفيلم", tint = TextMuted)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusTag(
                    text = if (movie.localAvailable) "الفيلم متصل" else "الفيلم غير محدد",
                    active = movie.localAvailable,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(
                    text = if (!movie.srtText.isNullOrBlank()) "الترجمة جاهزة" else "لا توجد ترجمة",
                    active = !movie.srtText.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (movie.localAvailable && !movie.srtText.isNullOrBlank()) {
                    Button(
                        onClick = onWatch,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Night),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" مشاهدة", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onRelink,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = AccentSoft),
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Text(" تحديد الفيلم")
                    }
                }

                OutlinedButton(
                    onClick = onDownload,
                    enabled = !movie.srtText.isNullOrBlank(),
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(" SRT")
                }
                IconButton(onClick = onDelete, enabled = !movie.srtText.isNullOrBlank()) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "حذف الترجمة",
                        tint = if (!movie.srtText.isNullOrBlank()) Danger else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (active) Success.copy(alpha = 0.09f) else PanelRaised,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = if (active) Success else TextMuted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ServicesScreen(platforms: List<PlatformQuota>, onRefresh: () -> Unit) {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = Accent.copy(alpha = 0.08f),
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(46.dp).background(Accent.copy(alpha = 0.12f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Accent)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("يعمل تلقائيًا", fontWeight = FontWeight.Bold)
                    Text("لا يحتاج التطبيق إلى أي إعداد تقني منك.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = Accent)
                }
            }
        }

        Text("السعة المتاحة", color = TextMuted, style = MaterialTheme.typography.labelLarge)

        if (platforms.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Accent, strokeWidth = 3.dp)
                    Text("جارٍ قراءة حالة الخدمات…", modifier = Modifier.padding(horizontal = 12.dp), color = TextMuted)
                }
            }
        } else {
            platforms.forEach { platform -> ServiceQuotaCard(platform = platform, now = now) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ServiceQuotaCard(platform: PlatformQuota, now: Long) {
    val remaining = platform.remainingPercent.coerceIn(0, 100)
    val color = when {
        remaining >= 50 -> Success
        remaining >= 20 -> Warning
        else -> Danger
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
                CircularProgressIndicator(
                    progress = { remaining / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = color,
                    trackColor = PanelRaised,
                    strokeWidth = 6.dp,
                )
                Text("$remaining%", fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(platform.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (platform.detail.isNotBlank()) {
                    Text(platform.detail, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (platform.resetAtEpochMs > 0L) {
                    Text(
                        "تتجدد خلال ${formatCountdown(platform.resetAtEpochMs - now)}",
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.58f))
                        .statusBarsPadding()
                        .padding(8.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                    Text(
                        movieName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(shape = RoundedCornerShape(999.dp), color = Accent.copy(alpha = 0.18f)) {
                        Text(
                            "AR",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = AccentSoft,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.70f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { subtitleSize = (subtitleSize - 2f).coerceAtLeast(16f) }) {
                            Text("A−", color = Color.White)
                        }
                        Text("حجم الترجمة", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { subtitleSize = (subtitleSize + 2f).coerceAtMost(34f) }) {
                            Text("A+", color = Color.White)
                        }
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
