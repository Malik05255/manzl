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
internal fun PremiumHomeScreen(
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(PremiumBg),
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
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PremiumHomeHeader()
                if (state.videoUri == null) {
                    PremiumEmptyHero(onPickMovie)
                } else {
                    PremiumMovieHero(state = state, onPickMovie = onPickMovie)
                }
            }
        }

        PremiumWaveDivider()

        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
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

@Composable
internal fun PremiumHomeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF6BB7FF), modifier = Modifier.size(28.dp))
                Text("مترجم الأفلام", color = PremiumWhite, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
            }
            Text(
                "ذكاء اصطناعي لمحتوى بلا حدود",
                color = Color(0xFFD9E8FF),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Spacer(Modifier.width(18.dp))

        Column(horizontalAlignment = Alignment.Start) {
            Text("مرحبًا بك", color = PremiumWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("فلننجز شيئًا رائعًا اليوم 👋", color = Color(0xFFD9E8FF), fontSize = 13.sp)
        }
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.AccountCircle, null, tint = PremiumWhite, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
internal fun PremiumEmptyHero(onPickMovie: () -> Unit) {
    Card(
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF163B69).copy(alpha = 0.95f)),
        border = BorderStroke(1.dp, Color(0xFF65AFFF)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(78.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF8FCBFF), modifier = Modifier.size(38.dp))
            }
            Text("اختر فيلمًا للترجمة", color = PremiumWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("يدعم أفلامًا حتى 3 ساعات مع معالجة ذكية تلقائية", color = Color(0xFFD8E9FF), fontSize = 14.sp, textAlign = TextAlign.Center)
            Button(
                onClick = onPickMovie,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue2),
            ) {
                Text("اختيار فيديو", color = PremiumWhite, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun PremiumMovieHero(state: TranslatorUiState, onPickMovie: () -> Unit) {
    val context = LocalContext.current
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val fileSize by produceState(initialValue = "—", state.videoUri) {
        value = state.videoUri?.let { context.premiumFileSize(it) } ?: "—"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12365F).copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, Color(0xFF69B5FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 340.dp
            Row(
                modifier = Modifier.padding(if (compact) 11.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 14.dp),
            ) {
                PremiumVideoThumb(
                    uri = state.videoUri,
                    duration = premiumClock(state.videoDurationMs),
                    modifier = Modifier
                        .width(if (compact) 88.dp else 118.dp)
                        .height(if (compact) 130.dp else 156.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.videoName.ifBlank { "الفيلم المحدد" },
                            modifier = Modifier.weight(1f),
                            color = PremiumWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = onPickMovie,
                            enabled = !state.isRunning,
                            modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape),
                        ) {
                            Icon(Icons.Default.MoreVert, "تغيير الفيلم", tint = PremiumWhite)
                        }
                    }

                    Text(
                        "MP4  |  ${premiumClock(state.videoDurationMs)}  |  $fileSize",
                        color = Color(0xFFD7E7FF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )

                    Row(
                        modifier = Modifier.padding(top = 14.dp),
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
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$percent%",
                            color = Color(0xFF83C7FF),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
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

@Composable
internal fun PremiumVideoThumb(uri: Uri?, duration: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frame by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF2C5A86)),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF678DB1), Color(0xFF264866)))
                )
            )
        }
        Box(
            modifier = Modifier.size(50.dp).background(Color.White.copy(alpha = 0.78f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF5B6876), modifier = Modifier.size(32.dp))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = Color.Black.copy(alpha = 0.60f),
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(duration, color = PremiumWhite, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@Composable
internal fun PremiumWaveDivider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(PremiumNavy2),
    ) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.25f)
            quadraticBezierTo(size.width * 0.48f, size.height * 1.15f, size.width, size.height * 0.15f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, PremiumBg)
    }
}
