package io.github.moordrak.sweetspot

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Postavke agenta i stanje zadnje sinkronizacije (SharedPreferences). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sweetspot", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(value) = prefs.edit().putString("url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    /** Adresa i naziv povezanog aparata (prazno = još nije povezan). */
    var meterAddress: String
        get() = prefs.getString("meter_address", "") ?: ""
        set(value) = prefs.edit().putString("meter_address", value).apply()

    var meterName: String
        get() = prefs.getString("meter_name", "") ?: ""
        set(value) = prefs.edit().putString("meter_name", value).apply()

    /** Najveći preuzeti redni broj zapisa; sljedeći put traže se samo noviji. -1 = još ništa. */
    var lastSequence: Int
        get() = prefs.getInt("last_sequence", -1)
        set(value) = prefs.edit().putInt("last_sequence", value).apply()

    var backgroundSync: Boolean
        get() = prefs.getBoolean("background", true)
        set(value) = prefs.edit().putBoolean("background", value).apply()

    var wizardDone: Boolean
        get() = prefs.getBoolean("wizard_done", false)
        set(value) = prefs.edit().putBoolean("wizard_done", value).apply()

    /**
     * Dok je čarobnjak otvoren, pozadinska sinkronizacija miruje. Pamti se vrijeme početka,
     * pa se zastavica sama poništi nakon 10 minuta ako aplikacija bude prekinuta.
     */
    var wizardActive: Boolean
        get() = System.currentTimeMillis() - prefs.getLong("wizard_since", 0L) < 10 * 60 * 1000L
        set(value) = prefs.edit().putLong("wizard_since", if (value) System.currentTimeMillis() else 0L).apply()

    var lastSyncMillis: Long
        get() = prefs.getLong("last_sync", 0L)
        set(value) = prefs.edit().putLong("last_sync", value).apply()

    var lastAttemptMillis: Long
        get() = prefs.getLong("last_attempt", 0L)
        set(value) = prefs.edit().putLong("last_attempt", value).apply()

    var lastNew: Int
        get() = prefs.getInt("last_new", 0)
        set(value) = prefs.edit().putInt("last_new", value).apply()

    /** Zadnje očitanje kao tekst, npr. "5,6 mmol/L (14.11.2026. 08:30)". */
    var lastReading: String
        get() = prefs.getString("last_reading", "") ?: ""
        set(value) = prefs.edit().putString("last_reading", value).apply()

    /** Zadnja poruka o stanju ili grešci; prazno kad je sve u redu. */
    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit().putString("last_error", value).apply()

    var pendingCount: Int
        get() = prefs.getInt("pending_count", 0)
        set(value) = prefs.edit().putInt("pending_count", value).apply()

    val isConfigured: Boolean
        get() = (url.startsWith("http://") || url.startsWith("https://")) && token.isNotBlank()

    /** Javlja se pri svakoj promjeni postavki (za osvježavanje sučelja). */
    fun changes(): Flow<Long> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(System.nanoTime()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(System.nanoTime())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun forgetMeter() {
        prefs.edit().remove("meter_address").remove("meter_name").putInt("last_sequence", -1).apply()
    }

    companion object {
        /** Provjera adrese i tokena; vraća poruku o grešci ili null. */
        fun validate(url: String, token: String): String? {
            val u = url.trim()
            if (!(u.startsWith("http://") || u.startsWith("https://"))) return "Adresa mora počinjati s http:// ili https://"
            if (u.length < 10) return "Upišite punu adresu, npr. http://192.168.1.60:8085"
            if (token.isBlank()) return "Upišite token (ispisuje ga instalacija poslužitelja)."
            return null
        }
    }
}
