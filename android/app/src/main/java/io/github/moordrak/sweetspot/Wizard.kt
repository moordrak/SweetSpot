package io.github.moordrak.sweetspot

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class WizardStep(val title: String) {
    PREPARE("Priprema"), PERMISSIONS("Dozvole"), SCAN("Traženje aparata"), CONNECT("Spajanje"), DONE("Gotovo")
}

data class WizardState(
    val step: WizardStep = WizardStep.PREPARE,
    val busy: Boolean = false,
    val status: String = "",
    val error: String = "",
    val meters: List<MeterItem> = emptyList(),
    val selected: String = "",
    val chosen: MeterItem? = null,
    val downloaded: Int = 0,
    val latest: String = "",
    val sent: String = "",
)

/** Aparat za prikaz (adresa, naziv, signal); uređaj se dohvaća po adresi kad zatreba. */
data class MeterItem(val address: String, val name: String, val rssi: Int)

/** Vodi kroz prvo spajanje: priprema -> dozvole -> traženje -> spajanje -> gotovo. */
class WizardViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val settings = Settings(app)
    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state
    private val found = mutableMapOf<String, FoundMeter>()
    private var job: Job? = null

    /** Poziva se pri svakom otvaranju čarobnjaka; dok on radi, pozadinska sinkronizacija miruje. */
    fun start() {
        job?.cancel()
        settings.wizardActive = true
        SyncWorker.cancelNow(context)
        BackgroundScan.stop(context)
        _state.value = WizardState()
    }

    fun go(step: WizardStep) {
        job?.cancel()
        _state.update { it.copy(step = step, busy = false, status = "", error = "") }
        if (step == WizardStep.SCAN) scan()
    }

    fun next() {
        when (_state.value.step) {
            WizardStep.PREPARE -> go(if (permissionsReady()) WizardStep.SCAN else WizardStep.PERMISSIONS)
            WizardStep.PERMISSIONS -> go(WizardStep.SCAN)
            WizardStep.SCAN -> _state.value.meters.firstOrNull { it.address == _state.value.selected }?.let { connect(it) }
            WizardStep.CONNECT -> _state.value.chosen?.let { connect(it) }      // "Pokušaj ponovno"
            WizardStep.DONE -> Unit
        }
    }

    fun back() = go(if (_state.value.step == WizardStep.CONNECT) WizardStep.SCAN else WizardStep.PREPARE)

    fun select(address: String) = _state.update { it.copy(selected = address) }

    fun permissionsReady() = Bluetooth.hasPermissions(context) && Bluetooth.isEnabled(context)

    fun scan() {
        job?.cancel()
        _state.update { it.copy(step = WizardStep.SCAN, busy = true, meters = emptyList(), selected = "", error = "",
            status = "Tražim aparate u blizini (oko 8 sekundi)…") }
        job = viewModelScope.launch {
            try {
                val meters = Bluetooth.scan(context, 8000)
                found.clear(); meters.forEach { found[it.address] = it }
                val items = meters.map { MeterItem(it.address, it.name, it.rssi) }
                _state.update {
                    it.copy(busy = false, meters = items, selected = items.firstOrNull()?.address.orEmpty(), status = when (items.size) {
                        0 -> "Nijedan aparat nije pronađen. Provjerite je li aparat u načinu uparivanja (plavo svjetlo) i blizu telefona pa tražite ponovno."
                        1 -> "Pronađen je jedan aparat. Dodirnite Dalje za spajanje."
                        else -> "Odaberite svoj aparat i dodirnite Dalje."
                    })
                }
            } catch (e: MeterException) {
                _state.update { it.copy(busy = false, status = "", error = e.message.orEmpty()) }
            }
        }
    }

    private fun connect(meter: MeterItem) {
        val device = found[meter.address]?.device ?: run { scan(); return }
        job?.cancel()
        _state.update { it.copy(step = WizardStep.CONNECT, chosen = meter, busy = true, error = "", status = "Uparujem s aparatom…") }
        job = viewModelScope.launch {
            try {
                Bluetooth.bond(context, device)
                // novi aparat: preuzmi sve zapise
                if (!settings.meterAddress.equals(meter.address, ignoreCase = true)) settings.lastSequence = -1
                val outcome = SyncEngine.syncDevice(context, device) { text -> _state.update { it.copy(status = text) } }
                settings.meterAddress = meter.address
                settings.meterName = meter.name
                settings.wizardDone = true
                AgentLog.add(context, "Aparat ${meter.name} (${meter.address}) je povezan; preuzeto ${outcome.downloaded} očitanja.")
                val sent = when {
                    !settings.isConfigured -> "Poslužitelj još nije podešen. Otvorite Postavke da bi se očitanja počela slati."
                    outcome.result == null -> "Poslužitelj trenutno nije dostupan – očitanja su spremljena i poslat će se kasnije."
                    else -> "Poslano na poslužitelj, novih očitanja: ${outcome.result.new}"
                }
                _state.update {
                    it.copy(step = WizardStep.DONE, busy = false, status = "", downloaded = outcome.downloaded,
                        latest = outcome.latest?.let(SyncEngine::describe).orEmpty(), sent = sent)
                }
            } catch (e: MeterException) {
                AgentLog.add(context, "Povezivanje aparata nije uspjelo: ${e.message}")
                _state.update { it.copy(busy = false, status = "", error = e.message.orEmpty()) }
            } catch (e: SecurityException) {
                _state.update { it.copy(busy = false, status = "", error = "Aplikacija nema dozvolu za Bluetooth.") }
            }
        }
    }

    fun finish() {
        job?.cancel()
        settings.wizardDone = true
        settings.wizardActive = false
        BackgroundScan.update(context)
    }

    override fun onCleared() {
        settings.wizardActive = false
        BackgroundScan.update(context)
    }

    /** Samo za snimke zaslona u razvojnoj izgradnji. */
    fun demo(step: String) {
        val meter = MeterItem("C0:26:DA:00:12:34", "Contour7830H1234567", -58)
        _state.value = when (step.lowercase()) {
            "scan" -> WizardState(WizardStep.SCAN, meters = listOf(meter), selected = meter.address,
                status = "Pronađen je jedan aparat. Dodirnite Dalje za spajanje.")
            "connect" -> WizardState(WizardStep.CONNECT, busy = true, chosen = meter, status = "Preuzimam očitanja…")
            "error" -> WizardState(WizardStep.CONNECT, chosen = meter, error = "Aparat nije odgovorio na vrijeme.")
            "done" -> WizardState(WizardStep.DONE, chosen = meter, downloaded = 143,
                latest = SyncEngine.describe(Reading(143, LocalDateTime.of(2026, 11, 14, 20, 10), 9.1)),
                sent = "Poslano na poslužitelj, novih očitanja: 143")
            "permissions" -> WizardState(WizardStep.PERMISSIONS)
            else -> WizardState()
        }
    }
}

private val HINTS = listOf(
    "Je li aparat još u načinu uparivanja (plavo svjetlo)?",
    "Ako telefon traži PIN, upišite broj koji piše na ekranu aparata.",
    "Je li aparat povezan s drugim telefonom ili aplikacijom Contour Diabetes? Aparat se spaja samo na jedan uređaj.",
    "Ako je aparat već bio uparen s ovim telefonom, uklonite ga u postavkama Bluetootha pa pokušajte ponovno.",
)

@Composable
fun WizardScreen(onClose: () -> Unit, demoStep: String? = null) {
    val vm: WizardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }      // npr. povratak iz postavki Bluetootha
    LaunchedEffect(Unit) { vm.start(); if (demoStep != null) vm.demo(demoStep) }

    fun close() { vm.finish(); onClose() }

    ScreenColumn {
        BackRow("Povezivanje aparata", ::close)
        StepIndicator(state.step)
        SectionCard {
            Text("Korak ${state.step.ordinal + 1} od ${WizardStep.entries.size}: ${state.step.title}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (state.step) {
                WizardStep.PREPARE -> PrepareStep()
                WizardStep.PERMISSIONS -> PermissionsStep(resumed, onReady = { vm.go(WizardStep.SCAN) })
                WizardStep.SCAN -> ScanStep(state, vm::select, vm::scan)
                WizardStep.CONNECT -> ConnectStep(state)
                WizardStep.DONE -> DoneStep(state)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (state.step) {
                WizardStep.PREPARE -> {
                    TextButton(onClick = ::close) { Text("Odustani") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::next) { Text("Dalje") }
                }
                WizardStep.PERMISSIONS -> {
                    OutlinedButton(onClick = vm::back) { Text("Natrag") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::next, enabled = remember(resumed, state) { vm.permissionsReady() }) { Text("Dalje") }
                }
                WizardStep.SCAN -> {
                    OutlinedButton(onClick = vm::back) { Text("Natrag") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::next, enabled = !state.busy && state.selected.isNotBlank()) { Text("Dalje") }
                }
                WizardStep.CONNECT -> {
                    OutlinedButton(onClick = vm::back, enabled = !state.busy) { Text("Natrag") }
                    Spacer(Modifier.weight(1f))
                    if (!state.busy) Button(onClick = vm::next) { Text("Pokušaj ponovno") }
                }
                WizardStep.DONE -> {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = ::close) { Text("Završi") }
                }
            }
        }
        if (state.step == WizardStep.PREPARE) {
            Text("Bluetooth na računalu ili drugom telefonu ne smije biti spojen s aparatom dok se povezujete.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepIndicator(current: WizardStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        WizardStep.entries.forEach { step ->
            Surface(
                color = if (step.ordinal <= current.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            ) { Spacer(Modifier.size(width = 1.dp, height = 4.dp)) }
        }
    }
}

@Composable
private fun PrepareStep() {
    Text("Čarobnjak povezuje vaš Contour Next aparat s ovim telefonom.")
    Text("Prije nastavka:", fontWeight = FontWeight.Medium)
    Numbered(1, "Uključite Bluetooth na telefonu.")
    Numbered(2, "Ako koristite aplikaciju Contour Diabetes ili agenta na računalu, privremeno isključite njihov Bluetooth – aparat se spaja samo na jedan uređaj.")
    Numbered(3, "Stavite aparat u način uparivanja:")
    Bullet("Contour Next One: dok je aparat isključen, držite tipku OK dok ne počne treptati plavo svjetlo.")
    Bullet("Contour Next Gen: uključite aparat pa na početnom zaslonu držite tipku ▲ oko 3 sekunde, dok se ne pojavi simbol Bluetootha.")
    Text("Kad je aparat u načinu uparivanja, dodirnite Dalje.")
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row { Text("$n.", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp)); Text(text) }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(start = 22.dp)) { Text("•", modifier = Modifier.width(16.dp)); Text(text) }
}

@Composable
private fun PermissionsStep(resumed: Int, onReady: () -> Unit) {
    val context = LocalContext.current
    remember(resumed) { resumed }
    val permissions = buildList {
        addAll(Bluetooth.PERMISSIONS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    var denied by remember { mutableStateOf(false) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Bluetooth.hasPermissions(context) && Bluetooth.isEnabled(context)) onReady()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Bluetooth.hasPermissions(context)) {
            if (Bluetooth.isEnabled(context)) onReady()
            else enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else denied = true
    }
    val hasPermission = Bluetooth.hasPermissions(context)
    Text("Agentu treba dozvola za pronalaženje uređaja u blizini i spajanje s njima (Bluetooth). " +
        "Lokacija se ne koristi. Obavijesti javljaju kad su nova očitanja poslana.")
    StatusRow(hasPermission, if (hasPermission) "Dozvola za Bluetooth je dana." else "Dozvola za Bluetooth još nije dana.")
    StatusRow(Bluetooth.isEnabled(context), if (Bluetooth.isEnabled(context)) "Bluetooth je uključen." else "Bluetooth je isključen.")
    if (!hasPermission) {
        Button(onClick = { launcher.launch(permissions) }) { Text("Dopusti") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Otvori postavke aplikacije") }
        if (denied) Text("Ako se prozor za dozvolu više ne pojavljuje, dozvolu dajte u postavkama aplikacije (Dozvole → Uređaji u blizini).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (!Bluetooth.isEnabled(context)) {
        Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Uključi Bluetooth") }
    }
}

@Composable
private fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else Warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp)); Text(text)
    }
}

@Composable
private fun ScanStep(state: WizardState, onSelect: (String) -> Unit, onRescan: () -> Unit) {
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.status.isNotBlank()) Text(state.status)
    if (state.error.isNotBlank()) ErrorText(state.error)
    state.meters.forEach { meter ->
        val selected = meter.address == state.selected
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().clickable { onSelect(meter.address) },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(meter.name, fontWeight = FontWeight.Medium)
                    Label("signal ${meter.rssi} dBm")
                }
            }
        }
    }
    if (!state.busy) OutlinedButton(onClick = onRescan) { Text("Traži ponovno") }
}

@Composable
private fun ConnectStep(state: WizardState) {
    if (state.busy) {
        Text(state.status.ifBlank { "Spajam se na ${state.chosen?.name}…" })
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Ako telefon pita za uparivanje ili PIN, potvrdite ga (PIN piše na ekranu aparata).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (state.error.isNotBlank()) {
        ErrorText("Spajanje nije uspjelo: ${state.error}")
        Text("Što provjeriti:", fontWeight = FontWeight.Medium)
        HINTS.forEach { Bullet(it) }
    }
}

@Composable
private fun DoneStep(state: WizardState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("Aparat ${state.chosen?.name.orEmpty()} je povezan.", fontWeight = FontWeight.SemiBold)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Preuzeto očitanja: ${state.downloaded}")
        if (state.latest.isNotBlank()) Text("Zadnje očitanje: ${state.latest}")
    }
    Text(state.sent)
    Text("Od sada telefon sam preuzima nova očitanja kad je aparat uključen i u blizini, npr. odmah nakon mjerenja.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp)); Text(text)
    }
}
