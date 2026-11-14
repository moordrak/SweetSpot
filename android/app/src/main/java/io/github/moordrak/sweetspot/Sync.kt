package io.github.moordrak.sweetspot

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SweetSpotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        SyncWorker.schedulePeriodic(this)
        BackgroundScan.update(this)
    }
}

/** Ishod jedne sinkronizacije za prikaz u sučelju. */
data class SyncOutcome(val downloaded: Int, val result: PushResult?, val latest: Reading?)

object SyncEngine {
    private val shortFormat = DateTimeFormatter.ofPattern("d.M.yyyy. HH:mm")

    fun describe(reading: Reading): String =
        "${"%.1f".format(reading.mmol).replace('.', ',')} mmol/L (${reading.time.format(shortFormat)})"

    /**
     * Preuzima nova očitanja s već pronađenog aparata, stavlja ih u red čekanja i šalje poslužitelju.
     * Red čekanja osigurava da se očitanja ne izgube ako poslužitelj nije dostupan.
     */
    suspend fun syncDevice(context: Context, device: android.bluetooth.BluetoothDevice, progress: (String) -> Unit): SyncOutcome {
        val settings = Settings(context)
        val from = settings.lastSequence.takeIf { it >= 0 }?.plus(1)
        val readings = ContourClient(context).download(device, from, progress)
        AgentLog.add(context, "S aparata preuzeto ${readings.size} očitanja.")
        Uploader.enqueue(context, readings)
        readings.maxOfOrNull { it.sequence }?.let { if (it > settings.lastSequence) settings.lastSequence = it }
        val latest = readings.maxByOrNull { it.time }
        if (latest != null) settings.lastReading = describe(latest)
        progress("Šaljem na poslužitelj…")
        val result = Uploader.flush(context)
        record(context, result)
        return SyncOutcome(readings.size, result, latest)
    }

    fun record(context: Context, result: PushResult?) {
        val settings = Settings(context)
        if (result != null) {
            settings.lastSyncMillis = System.currentTimeMillis()
            settings.lastNew = result.new
            settings.lastError = ""
        }
    }
}

/**
 * Pozadinski posao: pronađi aparat, preuzmi nova očitanja i pošalji ih.
 * Pokreće ga pozadinsko traženje (kad se aparat pojavi), gumb "Sinkroniziraj sada" i periodični raspored.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = Settings(context)
        val mode = inputData.getString(KEY_MODE) ?: MODE_METER
        if (settings.wizardActive) return Result.success()      // čarobnjak koristi Bluetooth
        if (!settings.isConfigured) {
            settings.lastError = "Poslužitelj nije podešen."
            return Result.success()
        }

        if (mode == MODE_UPLOAD) {
            if (settings.pendingCount > 0) SyncEngine.record(context, Uploader.flush(context))
            BackgroundScan.update(context)
            return Result.success()
        }

        settings.lastAttemptMillis = System.currentTimeMillis()
        fun status(text: String) = setProgressAsync(workDataOf(KEY_STATUS to text))
        return try {
            if (!Bluetooth.hasPermissions(context)) throw MeterException("Aplikacija nema dozvolu za Bluetooth.")
            if (!Bluetooth.isEnabled(context)) throw MeterException("Bluetooth je isključen.")
            status("Tražim aparat…")
            val device = Bluetooth.find(context, settings.meterAddress, if (mode == MODE_MANUAL) 30_000 else 20_000)
            if (device == null) {
                if (mode == MODE_MANUAL) settings.lastError = "Aparat nije pronađen. Uključite ga ili napravite mjerenje pa pokušajte ponovno."
                if (settings.pendingCount > 0) SyncEngine.record(context, Uploader.flush(context))
                return Result.success()
            }
            val outcome = SyncEngine.syncDevice(context, device) { status(it) }
            val result = outcome.result
            if (result != null && result.new > 0 && mode != MODE_MANUAL) Notifications.newReadings(context, result.new, outcome.latest)
            if (result == null && settings.lastError.isBlank()) settings.lastError = "Poslužitelj nije dostupan; očitanja čekaju slanje."
            Result.success(workDataOf(KEY_NEW to (result?.new ?: -1), KEY_DOWNLOADED to outcome.downloaded))
        } catch (e: MeterException) {
            AgentLog.add(context, "Sinkronizacija nije uspjela: ${e.message}")
            settings.lastError = e.message.orEmpty()
            Result.success(workDataOf(KEY_ERROR to e.message))
        } catch (e: SecurityException) {
            settings.lastError = "Aplikacija nema dozvolu za Bluetooth."
            Result.success(workDataOf(KEY_ERROR to settings.lastError))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SweetSpot")
            .setContentText("Preuzimam očitanja s aparata…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(Notifications.ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_STATUS = "status"
        const val KEY_NEW = "new"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_ERROR = "error"
        const val MODE_METER = "meter"
        const val MODE_MANUAL = "manual"
        const val MODE_UPLOAD = "upload"
        const val WORK_NOW = "sweetspot-sync-now"
        private const val WORK_PERIODIC = "sweetspot-periodic"

        /** Odmah pokreće sinkronizaciju (ručno ili kad se aparat pojavi u blizini). */
        fun runNow(context: Context, manual: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_MODE to if (manual) MODE_MANUAL else MODE_METER))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NOW, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        /** Svakih 15 minuta: pošalji očitanja koja čekaju i obnovi pozadinsko traženje. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MODE to MODE_UPLOAD))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelNow(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
    }
}

/**
 * Pozadinsko traženje: sustav sam javlja aplikaciji kad se povezani aparat pojavi u blizini
 * (npr. nakon mjerenja), i to bez stalno upaljene aplikacije.
 */
object BackgroundScan {
    private const val COOLDOWN_MS = 3 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, ScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @SuppressLint("MissingPermission")
    fun update(context: Context) {
        val settings = Settings(context)
        val scanner = Bluetooth.adapter(context)?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !Bluetooth.hasPermissions(context)) return
        runCatching { scanner.stopScan(pendingIntent(context)) }
        val active = settings.backgroundSync && settings.meterAddress.isNotBlank() && settings.isConfigured && !settings.wizardActive
        if (!active) return
        val filter = ScanFilter.Builder().setDeviceAddress(settings.meterAddress).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val code = runCatching { scanner.startScan(listOf(filter), scanSettings, pendingIntent(context)) }.getOrDefault(-1)
        if (code != 0) AgentLog.add(context, "Pozadinsko traženje nije uključeno (kod $code).")
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        val scanner = Bluetooth.adapter(context)?.bluetoothLeScanner ?: return
        if (Bluetooth.hasPermissions(context)) runCatching { scanner.stopScan(pendingIntent(context)) }
    }

    fun onMeterSeen(context: Context) {
        val settings = Settings(context)
        if (settings.wizardActive) return
        val now = System.currentTimeMillis()
        if (now - settings.lastAttemptMillis < COOLDOWN_MS) return
        settings.lastAttemptMillis = now
        AgentLog.add(context, "Aparat je u blizini, pokrećem sinkronizaciju.")
        SyncWorker.runNow(context, manual = false)
    }
}

class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            AgentLog.add(context, "Pozadinsko traženje javlja grešku $error.")
            return
        }
        BackgroundScan.onMeterSeen(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SyncWorker.schedulePeriodic(context)
            BackgroundScan.update(context)
        }
    }
}

object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_READINGS = "readings"
    const val ID_SYNC = 1
    private const val ID_READINGS = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Preuzimanje s aparata",
            NotificationManager.IMPORTANCE_LOW).apply { description = "Prikazuje se dok se preuzimaju očitanja." })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_READINGS, "Nova očitanja",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Obavijest kad su nova očitanja poslana na SweetSpot." })
    }

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun newReadings(context: Context, count: Int, latest: Reading?) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = latest?.let { "Zadnje: ${SyncEngine.describe(it)}" } ?: "Očitanja su poslana na poslužitelj."
        val notification = NotificationCompat.Builder(context, CHANNEL_READINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2A78D6.toInt())
            .setContentTitle(if (count == 1) "Poslano 1 novo očitanje" else "Poslano novih očitanja: $count")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_READINGS, notification)
    }
}
