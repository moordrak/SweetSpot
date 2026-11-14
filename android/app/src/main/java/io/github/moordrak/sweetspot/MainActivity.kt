package io.github.moordrak.sweetspot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, SETTINGS, WIZARD, LOG }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(this)
        // Samo u razvojnoj izgradnji: zaslon i probni podaci za snimke zaslona (adb ... --es screen ...)
        val debugScreen = if (BuildConfig.DEBUG) intent.getStringExtra("screen") else null
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) Demo.fill(this)
        val start = debugScreen?.let { runCatching { Screen.valueOf(it.uppercase()) }.getOrNull() } ?: when {
            !settings.isConfigured -> Screen.SETTINGS
            !settings.wizardDone -> Screen.WIZARD
            else -> Screen.HOME
        }
        val wizardStep = if (BuildConfig.DEBUG) intent.getStringExtra("wizard") else null
        setContent { SweetSpotTheme { AppRoot(start, wizardStep) } }
    }
}

@Composable
fun AppRoot(start: Screen, demoWizardStep: String?) {
    var screen by rememberSaveable { mutableStateOf(start) }
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            Screen.HOME -> HomeScreen(onNavigate = { screen = it })
            Screen.SETTINGS -> SettingsScreen(
                onBack = { screen = Screen.HOME },
                onSaved = { screen = if (settings.wizardDone) Screen.HOME else Screen.WIZARD },
                onWizard = { screen = Screen.WIZARD })
            Screen.WIZARD -> WizardScreen(onClose = { screen = Screen.HOME }, demoStep = demoWizardStep)
            Screen.LOG -> LogScreen(onBack = { screen = Screen.HOME })
        }
    }
}

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Natrag") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- početni zaslon

@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val tick by settings.changes().collectAsStateWithLifecycle(0L)
    val work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NOW)
        .collectAsStateWithLifecycle(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val progress = work.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.getString(SyncWorker.KEY_STATUS)
    var permissionsOk by remember { mutableStateOf(Bluetooth.hasPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsOk = Bluetooth.hasPermissions(context)
        BackgroundScan.update(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context))
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    remember(tick) { tick }       // ponovno iscrtavanje pri promjeni postavki
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Header("SweetSpot", "Agent za Contour Next") }
            IconButton(onClick = { onNavigate(Screen.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Postavke") }
        }

        if (!permissionsOk) {
            NoticeCard(Icons.Filled.Warning, "Potrebna je dozvola za Bluetooth",
                "Bez nje agent ne može pronaći aparat.", "Dopusti") {
                permissionLauncher.launch(Bluetooth.PERMISSIONS)
            }
        }

        val ok = settings.isConfigured && settings.meterAddress.isNotBlank() && settings.lastError.isBlank()
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info, contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(if (ok) "Sve je spremno" else "Stanje", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            StatusLine("Aparat", settings.meterName.ifBlank { "nije povezan" })
            StatusLine("Poslužitelj", settings.url.ifBlank { "nije podešen" })
            StatusLine("Zadnja sinkronizacija", if (settings.lastSyncMillis == 0L) "još nije bilo" else
                relativeTime(settings.lastSyncMillis) + " (novih: ${settings.lastNew})")
            if (settings.lastReading.isNotBlank()) StatusLine("Zadnje očitanje", settings.lastReading)
            if (settings.pendingCount > 0) StatusLine("Čeka slanje", "${settings.pendingCount} očitanja")
        }

        if (settings.lastError.isNotBlank() && !running) {
            NoticeCard(Icons.Filled.Warning, "Zadnji pokušaj nije uspio", settings.lastError)
        }

        SectionCard {
            Button(onClick = { settings.lastError = ""; SyncWorker.runNow(context, manual = true) },
                enabled = !running && settings.isConfigured && permissionsOk, modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(progress ?: "Pripremam…")
                } else {
                    Icon(Icons.Filled.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sinkroniziraj sada")
                }
            }
            Text("Uključite aparat ili napravite mjerenje pa dodirnite gumb.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sinkronizacija u pozadini", fontWeight = FontWeight.Medium)
                    Text("Telefon sam preuzme očitanja kad se aparat pojavi u blizini, npr. nakon mjerenja.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.backgroundSync, onCheckedChange = {
                    settings.backgroundSync = it
                    BackgroundScan.update(context)
                })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settings.url))) }
            }, enabled = settings.isConfigured, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Otvori SweetSpot")
            }
            OutlinedButton(onClick = { onNavigate(Screen.WIZARD) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Poveži aparat")
            }
        }
        TextButton(onClick = { onNavigate(Screen.LOG) }) {
            Icon(Icons.Outlined.Description, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("Zapisnik")
        }
    }
}

/** Vrijeme u prošlosti na hrvatskom, neovisno o jeziku telefona: "prije 12 min", "jučer u 20:10"… */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    val zone = java.time.ZoneId.systemDefault()
    val then = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> "upravo"
        minutes < 60 -> "prije $minutes min"
        then.toLocalDate() == today -> "danas u ${then.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        then.toLocalDate() == today.minusDays(1) -> "jučer u ${then.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        else -> then.format(java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy. HH:mm"))
    }
}

@Composable
fun StatusLine(label: String, value: String) {
    Column {
        Label(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun NoticeCard(icon: ImageVector, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = Warn)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

// ---------------------------------------------------------------- postavke

@Composable
fun SettingsScreen(onBack: () -> Unit, onSaved: () -> Unit, onWizard: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val tick by settings.changes().collectAsStateWithLifecycle(0L)
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(settings.url) }
    var token by rememberSaveable { mutableStateOf(settings.token) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    remember(tick) { tick }
    ScreenColumn {
        BackRow("Postavke", onBack)
        SectionCard {
            Text("SweetSpot poslužitelj", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = url, onValueChange = { url = it; message = "" }, label = { Text("Adresa") },
                placeholder = { Text("http://192.168.1.60:8085") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(value = token, onValueChange = { token = it; message = "" }, label = { Text("Token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showToken) "Sakrij token" else "Prikaži token")
                    }
                })
            Label("Token ispisuje instalacija poslužitelja; isti je kao lozinka za web sučelje.")
            if (message.isNotBlank()) Text(message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !testing, onClick = {
                    val error = Settings.validate(url, token)
                    if (error != null) { message = error; return@OutlinedButton }
                    testing = true; message = "Provjeravam vezu…"
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching { "Veza radi. Na poslužitelju je ${ServerApi(url.trim(), token.trim()).count()} očitanja." }
                                .getOrElse { ServerApi.describe(it) }
                        }
                        testing = false
                    }
                }) { Text("Provjeri vezu") }
                Button(onClick = {
                    val error = Settings.validate(url, token)
                    if (error != null) { message = error; return@Button }
                    settings.url = url; settings.token = token; settings.lastError = ""
                    AgentLog.add(context, "Postavke su spremljene (${settings.url}).")
                    BackgroundScan.update(context)
                    onSaved()
                }) { Text("Spremi") }
            }
        }

        SectionCard {
            Text("Aparat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(settings.meterName.ifBlank { "Nije povezan." })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onWizard) { Text("Poveži aparat…") }
                if (settings.meterAddress.isNotBlank()) OutlinedButton(onClick = {
                    settings.forgetMeter(); BackgroundScan.update(context)
                    AgentLog.add(context, "Aparat je zaboravljen.")
                }) { Text("Zaboravi") }
            }
            if (settings.meterAddress.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Label("Agent preuzima samo očitanja koja još nije poslao. Ako je nešto nedostaje na poslužitelju, preuzmite sve ponovno; duplikati se ne upisuju.")
                OutlinedButton(enabled = settings.lastSequence >= 0, onClick = {
                    settings.lastSequence = -1
                    AgentLog.add(context, "Sljedeća sinkronizacija preuzima sva očitanja.")
                }) { Text(if (settings.lastSequence >= 0) "Preuzmi sva očitanja ponovno" else "Sljedeći put preuzimaju se sva") }
            }
        }

        Text("SweetSpot Agent ${BuildConfig.VERSION_NAME}", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

// ---------------------------------------------------------------- zapisnik

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(AgentLog.read(context)) }
    ScreenColumn {
        BackRow("Zapisnik", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { text = AgentLog.read(context) }) { Text("Osvježi") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, "SweetSpot Agent – zapisnik")
                context.startActivity(Intent.createChooser(send, "Podijeli zapisnik"))
            }, enabled = text.isNotBlank()) { Text("Podijeli") }
            OutlinedButton(onClick = { AgentLog.clear(context); text = "" }, enabled = text.isNotBlank()) { Text("Obriši") }
        }
        SectionCard {
            SelectionContainer {
                Text(text.ifBlank { "Zapisnik je prazan." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
