package io.github.moordrak.sweetspot

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/** Jedno očitanje s aparata. Vrijeme je lokalno vrijeme aparata. */
data class Reading(val sequence: Int, val time: LocalDateTime, val mmol: Double) {
    val isoTime: String get() = time.format(ISO)

    companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}

/** Standardni Bluetooth "Glucose Profile" (servis 0x1808) koji koriste Contour Next One i Next Gen. */
object GlucoseProfile {
    val SERVICE: UUID = uuid16(0x1808)
    val MEASUREMENT: UUID = uuid16(0x2A18)
    val MEASUREMENT_CONTEXT: UUID = uuid16(0x2A34)
    val RACP: UUID = uuid16(0x2A52)
    val CCCD: UUID = uuid16(0x2902)

    private const val OP_REPORT_RECORDS = 0x01
    private const val OP_RESPONSE = 0x06
    private const val OPERATOR_ALL = 0x01
    private const val OPERATOR_GREATER_OR_EQUAL = 0x03
    private const val FILTER_SEQUENCE = 0x01

    const val RESPONSE_SUCCESS = 0x01
    const val RESPONSE_NO_RECORDS = 0x06

    private const val MGDL_PER_MMOL = 18.016

    fun uuid16(value: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", value))

    /** Zahtjev za sva spremljena očitanja. */
    fun requestAll(): ByteArray = byteArrayOf(OP_REPORT_RECORDS.toByte(), OPERATOR_ALL.toByte())

    /** Zahtjev za očitanja čiji je redni broj >= [sequence] (samo nova očitanja). */
    fun requestFrom(sequence: Int): ByteArray = byteArrayOf(
        OP_REPORT_RECORDS.toByte(), OPERATOR_GREATER_OR_EQUAL.toByte(), FILTER_SEQUENCE.toByte(),
        (sequence and 0xFF).toByte(), ((sequence shr 8) and 0xFF).toByte(),
    )

    /** Kod odgovora na zahtjev (1 = uspjeh, 6 = nema zapisa) ili null ako poruka nije odgovor. */
    fun responseCode(data: ByteArray): Int? =
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == OP_RESPONSE) data[3].toInt() and 0xFF else null

    /** IEEE 11073 16-bitni SFLOAT. */
    fun sfloat(raw: Int): Double {
        var mantissa = raw and 0x0FFF
        var exponent = (raw shr 12) and 0x0F
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 8) exponent -= 16
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    /** Glucose Measurement (0x2A18) -> očitanje; null ako zapis nema vrijednost. */
    fun parseMeasurement(data: ByteArray): Reading? {
        if (data.size < 10) return null
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

        val flags = u8(0)
        val sequence = u16(1)
        var time = LocalDateTime.of(u16(3), u8(5), u8(6), u8(7), u8(8), u8(9))
        var pos = 10
        if (flags and 0x01 != 0) {                       // vremenski pomak u minutama
            if (data.size < pos + 2) return null
            time = time.plusMinutes(u16(pos).toShort().toLong())
            pos += 2
        }
        if (flags and 0x02 == 0 || data.size < pos + 2) return null
        val value = sfloat(u16(pos))
        val mmol = if (flags and 0x04 != 0) value * 1000 else value * 100000 / MGDL_PER_MMOL
        return Reading(sequence, time, (mmol * 10).roundToInt() / 10.0)
    }
}
