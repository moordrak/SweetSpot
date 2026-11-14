package io.github.moordrak.sweetspot

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class MeterException(message: String) : Exception(message)

/** Aparat pronađen u blizini. */
data class FoundMeter(val device: BluetoothDevice, val name: String, val rssi: Int) {
    val address: String get() = device.address
}

object Bluetooth {
    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasPermissions(context: Context) = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isEnabled(context: Context) = adapter(context)?.isEnabled == true

    private fun requireReady(context: Context): BluetoothAdapter {
        if (!hasPermissions(context)) throw MeterException("Aplikacija nema dozvolu za Bluetooth.")
        val adapter = adapter(context) ?: throw MeterException("Telefon nema Bluetooth.")
        if (!adapter.isEnabled) throw MeterException("Bluetooth je isključen.")
        return adapter
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(result: ScanResult): String =
        result.scanRecord?.deviceName ?: result.device.name ?: result.device.address

    /** Je li ovo Contour aparat ili bilo koji uređaj sa standardnim Glucose servisom. */
    private fun isMeter(result: ScanResult): Boolean {
        val name = nameOf(result).lowercase()
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
        return "contour" in name || ParcelUuid(GlucoseProfile.SERVICE) in uuids
    }

    /** Traži aparate u blizini [millis] milisekundi; najjači signal prvi. */
    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context, millis: Long = 8000): List<FoundMeter> {
        val scanner = requireReady(context).bluetoothLeScanner ?: throw MeterException("Bluetooth je isključen.")
        val found = linkedMapOf<String, FoundMeter>()
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isMeter(result)) found[result.device.address] = FoundMeter(result.device, nameOf(result), result.rssi)
            }
            override fun onScanFailed(errorCode: Int) { failed.complete(errorCode) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            val error = withTimeoutOrNull(millis) { failed.await() }
            if (error != null) throw MeterException("Traženje nije uspjelo (kod $error).")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return found.values.sortedByDescending { it.rssi }
    }

    /** Čeka da se određeni aparat pojavi (ili bilo koji Contour ako adresa nije poznata). */
    @SuppressLint("MissingPermission")
    suspend fun find(context: Context, address: String, millis: Long): BluetoothDevice? {
        val scanner = requireReady(context).bluetoothLeScanner ?: return null
        val result = CompletableDeferred<BluetoothDevice?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (address.isBlank() && isMeter(r) || r.device.address.equals(address, ignoreCase = true)) result.complete(r.device)
            }
            override fun onScanFailed(errorCode: Int) { result.complete(null) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        return try {
            withTimeoutOrNull(millis) { result.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** Uparuje telefon s aparatom; telefon može tražiti PIN s ekrana aparata. */
    @SuppressLint("MissingPermission")
    suspend fun bond(context: Context, device: BluetoothDevice, millis: Long = 120_000) {
        requireReady(context)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val state = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address == device.address) state.trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond())
                throw MeterException("Telefon nije mogao započeti uparivanje.")
            withTimeout(millis) {
                var started = device.bondState == BluetoothDevice.BOND_BONDING
                while (true) {
                    when (state.receive()) {
                        BluetoothDevice.BOND_BONDED -> return@withTimeout
                        BluetoothDevice.BOND_BONDING -> started = true
                        BluetoothDevice.BOND_NONE -> if (started) throw MeterException("Uparivanje je odbijeno ili nije uspjelo.")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw MeterException("Uparivanje nije završeno na vrijeme.")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

/**
 * Preuzimanje očitanja s aparata preko standardnog Glucose profila:
 * spajanje, uključivanje obavijesti, zahtjev RACP-u i prikupljanje zapisa do odgovora.
 */
class ContourClient(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun download(device: BluetoothDevice, fromSequence: Int?, progress: (String) -> Unit = {}): List<Reading> {
        if (!Bluetooth.hasPermissions(context)) throw MeterException("Aplikacija nema dozvolu za Bluetooth.")
        val session = Session()
        progress("Spajam se na aparat…")
        val gatt = device.connectGatt(context, false, session, BluetoothDevice.TRANSPORT_LE)
            ?: throw MeterException("Spajanje nije moguće.")
        try {
            withTimeout(30_000) { session.connected.await() }
            delay(600)          // neki aparati trebaju trenutak nakon spajanja
            if (!gatt.discoverServices()) throw MeterException("Ne mogu pročitati servise aparata.")
            withTimeout(20_000) { session.discovered.await() }
            val service = gatt.getService(GlucoseProfile.SERVICE)
                ?: throw MeterException("Aparat nema standardni Bluetooth Glucose servis.")
            val measurement = service.getCharacteristic(GlucoseProfile.MEASUREMENT)
                ?: throw MeterException("Aparat ne nudi očitanja.")
            val racp = service.getCharacteristic(GlucoseProfile.RACP)
                ?: throw MeterException("Aparat ne nudi preuzimanje zapisa.")

            if (device.bondState != BluetoothDevice.BOND_BONDED) progress("Uparujem (potvrdite uparivanje ako telefon pita)…")
            enable(gatt, session, measurement, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, device)
            service.getCharacteristic(GlucoseProfile.MEASUREMENT_CONTEXT)?.let {
                runCatching { enable(gatt, session, it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, device) }
            }
            enable(gatt, session, racp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, device)

            progress("Preuzimam očitanja…")
            val command = if (fromSequence != null && fromSequence >= 0) GlucoseProfile.requestFrom(fromSequence)
                          else GlucoseProfile.requestAll()
            write(gatt, session, racp, command)
            val code = withTimeout(120_000) { session.racpResponse.await() }
            if (code != GlucoseProfile.RESPONSE_SUCCESS && code != GlucoseProfile.RESPONSE_NO_RECORDS)
                throw MeterException("Aparat je odbio zahtjev (kod $code).")
            return session.readings.toList()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw MeterException(if (session.connected.isCompleted) "Aparat nije odgovorio na vrijeme." else "Spajanje s aparatom nije uspjelo.")
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** Uključuje obavijesti; ako aparat traži šifriranu vezu, čeka uparivanje i pokušava ponovno. */
    @SuppressLint("MissingPermission")
    private suspend fun enable(gatt: BluetoothGatt, session: Session, characteristic: BluetoothGattCharacteristic,
                               value: ByteArray, device: BluetoothDevice) {
        val descriptor = characteristic.getDescriptor(GlucoseProfile.CCCD)
            ?: throw MeterException("Aparat ne podržava obavijesti.")
        gatt.setCharacteristicNotification(characteristic, true)
        repeat(3) { attempt ->
            val status = writeDescriptor(gatt, session, descriptor, value)
            if (status == BluetoothGatt.GATT_SUCCESS) return
            val needsBond = status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION || status == 137
            if (!needsBond || attempt == 2) throw MeterException("Aparat je odbio vezu (kod $status).")
            // Android sam pokreće uparivanje; pričekaj da se završi
            withTimeoutOrNull(90_000) { while (device.bondState != BluetoothDevice.BOND_BONDED) delay(500) }
                ?: throw MeterException("Uparivanje nije završeno.")
            delay(1000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptor(gatt: BluetoothGatt, session: Session, descriptor: BluetoothGattDescriptor,
                                        value: ByteArray): Int {
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) throw MeterException("Ne mogu uključiti obavijesti aparata.")
        return withTimeout(30_000) { session.descriptorWrites.receive() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(gatt: BluetoothGatt, session: Session, characteristic: BluetoothGattCharacteristic,
                              value: ByteArray) {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) throw MeterException("Ne mogu poslati zahtjev aparatu.")
        val status = withTimeout(30_000) { session.characteristicWrites.receive() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw MeterException("Aparat nije prihvatio zahtjev (kod $status).")
    }

    /** Povratni pozivi jedne veze pretvoreni u korutine. */
    private class Session : BluetoothGattCallback() {
        val connected = CompletableDeferred<Unit>()
        val discovered = CompletableDeferred<Unit>()
        val descriptorWrites = Channel<Int>(Channel.UNLIMITED)
        val characteristicWrites = Channel<Int>(Channel.UNLIMITED)
        val racpResponse = CompletableDeferred<Int>()
        val readings = mutableListOf<Reading>()

        private fun fail(message: String) {
            val error = MeterException(message)
            connected.completeExceptionally(error)
            discovered.completeExceptionally(error)
            racpResponse.completeExceptionally(error)
            descriptorWrites.close(error)
            characteristicWrites.close(error)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> connected.complete(Unit)
                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    if (!racpResponse.isCompleted) fail(if (connected.isCompleted) "Aparat je prekinuo vezu." else "Spajanje s aparatom nije uspjelo (kod $status).")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(MeterException("Ne mogu pročitati servise aparata (kod $status)."))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWrites.trySend(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            characteristicWrites.trySend(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handle(characteristic.uuid, value)
        }

        @Deprecated("Koristi se samo na Androidu 12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) handle(characteristic.uuid, characteristic.value ?: return)
        }

        private fun handle(uuid: UUID, value: ByteArray) {
            when (uuid) {
                GlucoseProfile.MEASUREMENT -> runCatching { GlucoseProfile.parseMeasurement(value) }.getOrNull()?.let {
                    synchronized(readings) { readings += it }
                }
                GlucoseProfile.RACP -> GlucoseProfile.responseCode(value)?.let { racpResponse.complete(it) }
            }
        }
    }
}

/** Pomoćna: suspendira dok se Bluetooth ne uključi (ili istekne vrijeme). */
suspend fun awaitBluetoothOn(context: Context, millis: Long): Boolean =
    withTimeoutOrNull(millis) {
        suspendCancellableCoroutine { cont ->
            if (Bluetooth.isEnabled(context)) { cont.resume(true); return@suspendCancellableCoroutine }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON && cont.isActive) {
                        runCatching { context.unregisterReceiver(this) }
                        cont.resume(true)
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    } ?: false
