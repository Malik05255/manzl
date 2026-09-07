package com.manzl.movietranslator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun PremiumWorkflowCard(state: TranslatorUiState) {
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val activeIndex = when {
        state.srtFile != null || percent >= 98 -> 4
        percent >= 90 -> 3
        percent >= 70 -> 1
        else -> 2
    }
    val done: Set<Int> = buildSet {
        add(0)
        if (percent >= 70) add(2)
        if (percent >= 90) add(1)
        if (percent >= 98) add(3)
    }

    PremiumCard {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("خطوات العمل", color = PremiumInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("من الملف إلى الترجمة النهائية", color = PremiumMuted, fontSize = 12.sp)
                }
                Surface(color = PremiumBlueSoft, shape = RoundedCornerShape(18.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.GraphicEq, null, tint = PremiumBlue, modifier = Modifier.size(18.dp))
                        Text("مراقبة مباشرة", color = PremiumBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                val compact = maxWidth < 330.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    PremiumWorkflowStep("تحليل الملف", "تم", Icons.Default.CloudUpload, 0, activeIndex, true, compact, Modifier.weight(1f))
                    PremiumConnector(false, compact)
                    PremiumWorkflowStep("ترجمة", if (activeIndex == 1) "جاري التنفيذ" else "في الانتظار", Icons.Default.Description, 1, activeIndex, 1 in done, compact, Modifier.weight(1f))
                    PremiumConnector(activeIndex == 2, compact)
                    PremiumWorkflowStep("معالجة الصوت", if (activeIndex == 2) "جاري التنفيذ" else "تم", Icons.Default.GraphicEq, 2, activeIndex, 2 in done, compact, Modifier.weight(1f))
                    PremiumConnector(false, compact)
                    PremiumWorkflowStep("دمج وتركيب", "الصوت والترجمة", Icons.Default.Movie, 3, activeIndex, 3 in done, compact, Modifier.weight(1f))
                    PremiumConnector(false, compact)
                    PremiumWorkflowStep("إنهاء", "حفظ الملف", Icons.Default.Flag, 4, activeIndex, state.srtFile != null, compact, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun PremiumWorkflowStep(
    title: String,
    subtitle: String,
    icon: ImageVector,
    index: Int,
    activeIndex: Int,
    done: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = index == activeIndex
    val baseSize = if (compact) 40.dp else 50.dp
    val activeSize = if (compact) 46.dp else 58.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(if (active) activeSize else baseSize)
                .background(if (active) PremiumBlueSoft else Color(0xFFF0F4F9), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                CircularProgressIndicator(
                    progress = { 0.76f },
                    modifier = Modifier.fillMaxSize(),
                    color = PremiumBlue,
                    trackColor = Color(0xFFDCE7F5),
                    strokeWidth = 3.dp,
                )
            }
            Icon(icon, null, tint = if (active) PremiumBlue else Color(0xFF657389), modifier = Modifier.size(if (compact) 19.dp else 24.dp))
            if (done && !active) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(17.dp).background(PremiumGreen, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = PremiumWhite, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(title, color = PremiumInk, fontSize = (if (compact) 8 else 10).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
        Text(subtitle, color = if (active) PremiumBlue else PremiumMuted, fontSize = (if (compact) 7 else 8).sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun PremiumConnector(active: Boolean = false, compact: Boolean = false) {
    Box(
        modifier = Modifier
            .padding(top = if (compact) 20.dp else 24.dp)
            .width(if (compact) 5.dp else 10.dp)
            .height(2.dp)
            .background(if (active) PremiumBlue else Color(0xFFC8D3E2), RoundedCornerShape(999.dp))
    )
}

@Composable
internal fun PremiumMetricsRow(state: TranslatorUiState) {
    val remaining = (state.videoDurationMs * (1f - state.progress.coerceIn(0f, 1f))).toLong()
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val model = when {
        percent < 15 -> "تجهيز"
        percent < 70 -> "Whisper"
        percent < 88 -> "Azure"
        percent < 97 -> "Groq"
        else -> "إنهاء"
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PremiumMetricCard(Icons.Default.Cloud, Color(0xFFEAF3FF), PremiumBlue, model, "النموذج المستخدم", Modifier.weight(1f))
        PremiumMetricCard(Icons.Default.Description, Color(0xFFF1ECFF), Color(0xFF7657D8), premiumClock(state.videoDurationMs), "مدة الفيديو", Modifier.weight(1f))
        PremiumMetricCard(Icons.Default.Schedule, Color(0xFFFFF2DB), Color(0xFFB97514), if (state.isRunning) premiumClock(remaining) else "—", "الوقت المتبقي", Modifier.weight(1f))
        PremiumMetricCard(Icons.Default.Folder, Color(0xFFE8F8F0), PremiumGreen, if (state.srtFile != null) "1/1" else "0/1", "ملفات مكتملة", Modifier.weight(1f))
    }
}

@Composable
internal fun PremiumMetricCard(icon: ImageVector, soft: Color, tint: Color, value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PremiumWhite),
        border = BorderStroke(1.dp, PremiumHairline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(42.dp).background(soft, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
            }
            Text(value, color = PremiumInk, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
            Text(label, color = PremiumMuted, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
internal fun PremiumStatusRow(online: Boolean, onBackground: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PremiumCard(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(PremiumGreenSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (online) Icons.Default.CloudDone else Icons.Default.SignalWifiOff, null, tint = if (online) PremiumGreen else PremiumMuted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("الاتصال بالسحابة", color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(if (online) "متصل وجاهز" else "غير متصل", color = PremiumMuted, fontSize = 10.sp)
                }
                if (online) {
                    Box(Modifier.size(24.dp).background(PremiumGreenSoft, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, null, tint = PremiumGreen, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        PremiumCard(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(PremiumBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Schedule, null, tint = PremiumInk)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("المتابعة في الخلفية", color = PremiumInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("استمر حتى مع إغلاق التطبيق", color = PremiumMuted, fontSize = 9.sp)
                }
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
internal fun PremiumStopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumRed),
    ) {
        Box(Modifier.size(42.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Stop, null, tint = PremiumWhite, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("إيقاف المهمة", color = PremiumWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("سيتم حفظ التقدم الحالي", color = Color(0xFFFFD8DC), fontSize = 11.sp)
        }
        Spacer(Modifier.width(42.dp))
    }
}

@Composable
internal fun PremiumStartButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = PremiumWhite)
        Text("ابدأ الترجمة", color = PremiumWhite, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
internal fun PremiumCompletedActions(onWatch: () -> Unit, onExport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onWatch,
            modifier = Modifier.weight(1f).height(62.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumBlue),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Text("مشاهدة الفيلم", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f).height(62.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, PremiumHairline),
        ) {
            Icon(Icons.Default.Download, null, tint = PremiumBlue)
            Text("حفظ SRT", color = PremiumBlue, fontWeight = FontWeight.Bold)
        }
    }
}
