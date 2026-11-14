package io.github.moordrak.sweetspot

import android.content.Context

/** Probni podaci za snimke zaslona; koristi se samo u razvojnoj izgradnji (BuildConfig.DEBUG). */
object Demo {
    fun fill(context: Context) {
        if (!BuildConfig.DEBUG) return
        val settings = Settings(context)
        settings.url = "http://192.168.1.60:8085"
        settings.token = "probni-token"
        settings.meterAddress = "C0:26:DA:00:12:34"
        settings.meterName = "Contour7830H1234567"
        settings.lastSequence = 143
        settings.lastSyncMillis = System.currentTimeMillis() - 12 * 60 * 1000L
        settings.lastNew = 1
        settings.lastReading = "9,1 mmol/L (14.11.2026. 20:10)"
        settings.lastError = ""
        settings.wizardDone = true
        AgentLog.clear(context)
        listOf(
            "Postavke su spremljene (http://192.168.1.60:8085).",
            "Aparat Contour7830H1234567 (C0:26:DA:00:12:34) je povezan; preuzeto 142 očitanja.",
            "Poslano 142 očitanja, novih: 142.",
            "Aparat je u blizini, pokrećem sinkronizaciju.",
            "S aparata preuzeto 1 očitanja.",
            "Poslano 1 očitanja, novih: 1.",
        ).forEach { AgentLog.add(context, it) }
    }
}
