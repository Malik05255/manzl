package com.manzl.movietranslator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class ApiKeySetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val forceSettings = intent?.action == Intent.ACTION_APPLICATION_PREFERENCES

        setContent {
            MaterialTheme {
                CloudKeyGate(
                    forceSettings = forceSettings,
                    onReady = ::openTranslator,
                )
            }
        }
    }

    private fun openTranslator() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private enum class GatePhase { CHECKING, NEEDS_KEYS, SAVING }

@Composable
private fun ApiKeySetupActivity.CloudKeyGate(
    forceSettings: Boolean,
    onReady: () -> Unit,
) {
    val store = remember { SecureApiKeyStore(applicationContext) }
    val gateway = remember { CloudRegistrationClient() }
    val deviceHash = remember { CloudIdentity.deviceHash(applicationContext) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(if (forceSettings) GatePhase.NEEDS_KEYS else GatePhase.CHECKING) }
    var gateError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(forceSettings) {
        if (forceSettings) return@LaunchedEffect
        runCatching {
            val status = gateway.status(deviceHash)
            if (status.authorized) {
                store.clear()
                onReady()
                return@runCatching
            }

            // Migration path from the previous app version: if keys are still in Android Keystore,
            // upload them once to the private gateway and remove the local copies.
            if (store.isConfigured()) {
                gateway.register(deviceHash, store.requireGroqKey(), store.requireGeminiKey())
                store.clear()
                onReady()
                return@runCatching
            }
            phase = GatePhase.NEEDS_KEYS
        }.onFailure { error ->
            phase = GatePhase.NEEDS_KEYS
            gateError = error.message ?: "تعذر التحقق من السحابة الآن."
        }
    }

    when (phase) {
        GatePhase.CHECKING -> CloudCheckingScreen()
        GatePhase.NEEDS_KEYS, GatePhase.SAVING -> ApiKeySetupScreen(
            isSaving = phase == GatePhase.SAVING,
            initialError = gateError,
            onSave = { groq, gemini, reportError ->
                phase = GatePhase.SAVING
                gateError = null
                scope.launch {
                    runCatching {
                        gateway.register(deviceHash, groq, gemini)
                        store.clear()
                    }.onSuccess {
                        onReady()
                    }.onFailure { error ->
                        phase = GatePhase.NEEDS_KEYS
                        val message = error.message ?: "تعذر حفظ المفاتيح في السحابة."
                        gateError = message
                        reportError(message)
                    }
                }
            },
        )
    }
}

@Composable
private fun CloudCheckingScreen() {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    "تجهيز مترجم الأفلام…",
                    modifier = Modifier.padding(top = 18.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "نتحقق من ربط جهازك بالسحابة الخاصة بك.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ApiKeySetupScreen(
    isSaving: Boolean,
    initialError: String?,
    onSave: (String, String, (String) -> Unit) -> Unit,
) {
    var groq by remember { mutableStateOf("") }
    var gemini by remember { mutableStateOf("") }
    var error by remember(initialError) { mutableStateOf(initialError) }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 18.dp),
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.padding(15.dp),
                    )
                }
                Text(
                    "إعداد نهائي لمرة واحدة",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "أدخل مفتاح Groq ومفتاح Gemini هذه المرة فقط. سيُحفظان مشفّرين في السحابة الخاصة بالتطبيق، وبعدها لن يطلبهما التطبيق حتى لو حذفته وثبّتّه من جديد على نفس الجهاز.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OutlinedTextField(
                            value = groq,
                            onValueChange = { groq = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Groq API Key") },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            singleLine = true,
                            enabled = !isSaving,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = gemini,
                            onValueChange = { gemini = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Gemini API Key") },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            singleLine = true,
                            enabled = !isSaving,
                            visualTransformation = PasswordVisualTransformation(),
                        )

                        if (error != null) {
                            Text(
                                error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Button(
                            onClick = {
                                onSave(groq.trim(), gemini.trim()) { message -> error = message }
                            },
                            enabled = !isSaving && groq.isNotBlank() && gemini.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                                Text(" ربط السحابة…")
                            } else {
                                Icon(Icons.Default.Lock, contentDescription = null)
                                Text(" حفظ نهائي وفتح التطبيق")
                            }
                        }
                    }
                }

                Spacer(Modifier.padding(top = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        "بعد الربط: اختيار الفيلم ← ترجمة ← مشاهدة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
