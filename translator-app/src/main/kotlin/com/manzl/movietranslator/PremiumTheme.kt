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

internal val PremiumBg = Color(0xFFF5F9FF)
internal val PremiumWhite = Color(0xFFFFFFFF)
internal val PremiumInk = Color(0xFF132B50)
internal val PremiumMuted = Color(0xFF8190A8)
internal val PremiumBlue = Color(0xFF2E74E8)
internal val PremiumBlue2 = Color(0xFF5AA8FF)
internal val PremiumBlueSoft = Color(0xFFEAF3FF)
internal val PremiumHairline = Color(0xFFE3EBF6)
internal val PremiumGreen = Color(0xFF2E9B68)
internal val PremiumGreenSoft = Color(0xFFE9F7EF)
internal val PremiumRed = Color(0xFFD9434A)
internal val PremiumRed2 = Color(0xFFED5964)
internal val PremiumShadow = Color(0x1F6389B5)
internal val PremiumNavy = Color(0xFF102B53)
internal val PremiumNavy2 = Color(0xFF1D4D89)

internal enum class PremiumTab { HOME, PROJECTS, LIBRARY, SETTINGS }

@Composable
internal fun PremiumBottomBar(tab: PremiumTab, onTab: (PremiumTab) -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .shadow(16.dp, RoundedCornerShape(30.dp), ambientColor = PremiumShadow, spotColor = PremiumShadow),
        color = PremiumWhite,
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, Color(0xFFF0F4F9)),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding().height(82.dp),
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
                    icon = { Icon(icon, null, modifier = Modifier.size(27.dp)) },
                    label = { Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
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

@Composable
internal fun PremiumCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(13.dp, RoundedCornerShape(28.dp), ambientColor = PremiumShadow, spotColor = PremiumShadow),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

internal fun Context.premiumDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index) ?: "فيلم"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "فيلم"
}

internal fun Context.premiumFileSize(uri: Uri): String {
    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        } else null
    }
    return premiumBytes(size ?: 0L)
}

internal fun premiumBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}

internal fun premiumClock(ms: Long): String {
    if (ms <= 0L) return "—"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
