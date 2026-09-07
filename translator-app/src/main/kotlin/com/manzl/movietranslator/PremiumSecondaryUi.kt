package com.manzl.movietranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun PremiumProjectsScreen(
    modifier: Modifier,
    state: TranslatorUiState,
    movies: List<CloudMovieItem>,
    onRefresh: () -> Unit,
    onWatch: (CloudMovieItem) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumSectionHeader("مشاريعي", "كل مهام الترجمة في مكان واحد", onRefresh)
        if (state.videoUri != null) {
            PremiumCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(PremiumBlueSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Movie, null, tint = PremiumBlue)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("المشروع الحالي", color = PremiumMuted, fontSize = 11.sp)
                        Text(state.videoName, color = PremiumInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (state.isRunning) state.stage else if (state.srtFile != null) "مكتمل" else "جاهز", color = PremiumBlue, fontSize = 12.sp)
                    }
                }
            }
        }
        Text("المشاريع المحفوظة", color = PremiumInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        if (movies.isEmpty()) {
            PremiumEmptyList("لا توجد مشاريع محفوظة بعد")
        } else {
            movies.forEach { movie -> PremiumProjectCard(movie, onWatch) }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
internal fun PremiumLibraryScreen(
    modifier: Modifier,
    movies: List<CloudMovieItem>,
    onRefresh: () -> Unit,
    onRelink: (CloudMovieItem) -> Unit,
    onDownload: (CloudMovieItem) -> Unit,
    onDelete: (CloudMovieItem) -> Unit,
    onWatch: (CloudMovieItem) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<CloudMovieItem?>(null) }
    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumSectionHeader("المكتبة", "${movies.size} فيلم محفوظ", onRefresh)
        if (movies.isEmpty()) {
            PremiumEmptyList("لا توجد ترجمات بعد")
        } else {
            movies.forEach { movie ->
                PremiumCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).background(PremiumBlueSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Movie, null, tint = PremiumBlue)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(movie.movieName, color = PremiumInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(premiumClock(movie.durationMs), color = PremiumMuted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { onRelink(movie) }) { Icon(Icons.Default.Edit, "تغيير", tint = PremiumMuted) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { onWatch(movie) },
                                enabled = movie.localAvailable && !movie.srtText.isNullOrBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text("مشاهدة")
                            }
                            OutlinedButton(
                                onClick = { onDownload(movie) },
                                enabled = !movie.srtText.isNullOrBlank(),
                                modifier = Modifier.weight(0.65f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, PremiumHairline),
                            ) { Icon(Icons.Default.Download, null, tint = PremiumBlue) }
                            OutlinedButton(
                                onClick = { deleteTarget = movie },
                                modifier = Modifier.weight(0.55f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, PremiumHairline),
                            ) { Icon(Icons.Default.DeleteOutline, null, tint = PremiumRed) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text("حذف", color = PremiumRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء", color = PremiumMuted) }
            },
            title = { Text("حذف الترجمة؟", color = PremiumInk) },
            text = { Text("سيتم حذف ملف الترجمة المحفوظ فقط.", color = PremiumMuted) },
            containerColor = PremiumWhite,
        )
    }
}

@Composable
private fun PremiumProjectCard(movie: CloudMovieItem, onWatch: (CloudMovieItem) -> Unit) {
    PremiumCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(54.dp).background(PremiumBlueSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Folder, null, tint = PremiumBlue)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(movie.movieName, color = PremiumInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(premiumClock(movie.durationMs), color = PremiumMuted, fontSize = 12.sp)
            }
            Button(
                onClick = { onWatch(movie) },
                enabled = movie.localAvailable && !movie.srtText.isNullOrBlank(),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
            ) {
                Icon(Icons.Default.PlayArrow, null)
            }
        }
    }
}

@Composable
internal fun PremiumSettingsScreen(
    modifier: Modifier,
    platforms: List<PlatformQuota>,
    onRefresh: () -> Unit,
) {
    val byId = platforms.associateBy { it.id.lowercase() }
    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumSectionHeader("الإعدادات", "الوضع الذكي والخدمات السحابية", onRefresh)
        PremiumCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).background(PremiumBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = PremiumBlue)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("الوضع الذكي", color = PremiumInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("يختار أفضل مسار متاح تلقائيًا بدون تكلفة مدفوعة", color = PremiumMuted, fontSize = 12.sp)
                }
                Surface(shape = RoundedCornerShape(999.dp), color = PremiumGreenSoft) {
                    Text("مفعّل", color = PremiumGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        Text("حالة الخدمات", color = PremiumInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        PremiumServiceCard("Groq Whisper", "فهم الحوار التركي", byId["groq"], true)
        PremiumServiceCard("Azure Translator", "الترجمة الأساسية السريعة", byId["azure"], false)
        PremiumServiceCard("Groq Review", "مراجعة الأسطر التي تحتاج تحسينًا", byId["groq_review"], true)
        PremiumServiceCard("Gemini", "احتياطي عند تعذر المسار الأساسي", byId["gemini"], true)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PremiumServiceCard(title: String, subtitle: String, quota: PlatformQuota?, expected: Boolean) {
    PremiumCard {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).background(PremiumBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Cloud, null, tint = PremiumBlue)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(quota?.detail?.takeIf { it.isNotBlank() } ?: subtitle, color = PremiumMuted, fontSize = 12.sp, maxLines = 2)
            }
            if (quota != null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                    CircularProgressIndicator(
                        progress = { quota.remainingPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxSize(),
                        color = PremiumBlue,
                        trackColor = Color(0xFFE6EEF8),
                        strokeWidth = 5.dp,
                    )
                    Text("${quota.remainingPercent}%", color = PremiumInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(if (expected) "جاهز" else "بانتظار المفتاح", color = if (expected) PremiumGreen else PremiumMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PremiumSectionHeader(title: String, subtitle: String, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = PremiumInk, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = PremiumMuted, fontSize = 13.sp)
        }
        IconButton(onClick = onRefresh, modifier = Modifier.background(PremiumBlueSoft, RoundedCornerShape(17.dp))) {
            Icon(Icons.Default.Refresh, "تحديث", tint = PremiumBlue)
        }
    }
}

@Composable
private fun PremiumEmptyList(message: String) {
    PremiumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.VideoLibrary, null, tint = PremiumBlue, modifier = Modifier.size(42.dp))
            Text(message, color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("ستظهر الملفات هنا بعد اكتمال الترجمة.", color = PremiumMuted, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
    }
}
