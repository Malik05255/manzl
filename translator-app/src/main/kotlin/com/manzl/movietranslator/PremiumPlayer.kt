package com.manzl.movietranslator

import android.net.Uri
import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PremiumCinemaPlayerDialog(
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
            setMediaItem(MediaItem.Builder().setUri(videoUri).setSubtitleConfigurations(listOf(subtitle)).build())
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
            Box(Modifier.fillMaxSize()) {
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
                        .background(Color.Black.copy(alpha = 0.56f))
                        .padding(8.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "إغلاق", tint = PremiumWhite)
                    }
                    Text(
                        movieName,
                        modifier = Modifier.weight(1f),
                        color = PremiumWhite,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 14.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { subtitleSize = (subtitleSize - 2f).coerceAtLeast(16f) }) {
                            Text("A−", color = PremiumWhite)
                        }
                        Text("حجم الترجمة", color = PremiumWhite.copy(alpha = 0.78f), fontSize = 12.sp)
                        TextButton(onClick = { subtitleSize = (subtitleSize + 2f).coerceAtMost(34f) }) {
                            Text("A+", color = PremiumWhite)
                        }
                    }
                }
            }
        }
    }
}
