package io.github.moordrak.sweetspot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class GlucoseProfileTest {

    private fun measurement(flags: Int, seq: Int, offset: Int?, sfloatRaw: Int): ByteArray {
        val bytes = mutableListOf(
            flags, seq and 0xFF, seq shr 8,
            2026 and 0xFF, 2026 shr 8, 11, 14, 8, 30, 0,
        )
        if (offset != null) bytes += listOf(offset and 0xFF, (offset shr 8) and 0xFF)
        bytes += listOf(sfloatRaw and 0xFF, sfloatRaw shr 8, 0x11)
        return bytes.map { it.toByte() }.toByteArray()
    }

    @Test
    fun sfloat() {
        assertEquals(0.056, GlucoseProfile.sfloat((0xD shl 12) or 56), 1e-12)         // 56 x 10^-3
        assertEquals(-2.0, GlucoseProfile.sfloat(0x0FFE), 1e-12)                      // mantisa -2
        assertEquals(1010.0, GlucoseProfile.sfloat((0x1 shl 12) or 101), 1e-9)        // 101 x 10^1
    }

    @Test
    fun parsesMolPerLitre() {
        // 5,6 mmol/L = 0,0056 mol/L = 56 x 10^-4
        val r = GlucoseProfile.parseMeasurement(measurement(0x06, 7, null, (0xC shl 12) or 56))!!
        assertEquals(7, r.sequence)
        assertEquals(LocalDateTime.of(2026, 11, 14, 8, 30, 0), r.time)
        assertEquals(5.6, r.mmol, 1e-9)
        assertEquals("2026-11-14T08:30:00", r.isoTime)
    }

    @Test
    fun parsesMilligramsWithTimeOffset() {
        // 101 mg/dL = 101 x 10^-5 kg/L, pomak +60 min
        val r = GlucoseProfile.parseMeasurement(measurement(0x03, 8, 60, (0xB shl 12) or 101))!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 30, 0), r.time)
        assertEquals(5.6, r.mmol, 1e-9)
    }

    @Test
    fun negativeTimeOffset() {
        val r = GlucoseProfile.parseMeasurement(measurement(0x07, 9, -90, (0xC shl 12) or 56))!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 7, 0, 0), r.time)
    }

    @Test
    fun recordWithoutValueIsSkipped() {
        assertNull(GlucoseProfile.parseMeasurement(measurement(0x00, 1, null, 0)))
        assertNull(GlucoseProfile.parseMeasurement(byteArrayOf(0x02, 0x01)))
    }

    @Test
    fun racpRequests() {
        assertArrayEquals(byteArrayOf(0x01, 0x01), GlucoseProfile.requestAll())
        assertArrayEquals(byteArrayOf(0x01, 0x03, 0x01, 0x2C, 0x01), GlucoseProfile.requestFrom(300))
        assertEquals(1, GlucoseProfile.responseCode(byteArrayOf(0x06, 0x00, 0x01, 0x01)))
        assertEquals(6, GlucoseProfile.responseCode(byteArrayOf(0x06, 0x00, 0x01, 0x06)))
        assertNull(GlucoseProfile.responseCode(byteArrayOf(0x05, 0x00, 0x03)))
    }
}
