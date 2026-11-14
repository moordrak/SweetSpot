package io.github.moordrak.sweetspot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ServerException(val code: Int, message: String) : IOException(message)

data class PushResult(val received: Int, val new: Int)

/** Komunikacija sa SweetSpot poslužiteljem (isti API kao Windows agent). */
class ServerApi(private val url: String, private val token: String) {

    /** Broj očitanja na poslužitelju (provjera veze i tokena). */
    fun count(): Int = JSONArray(request("GET", "/api/readings", null)).length()

    fun push(readings: JSONArray): PushResult {
        val body = JSONObject().put("readings", readings).toString()
        val answer = JSONObject(request("POST", "/api/readings", body))
        return PushResult(answer.optInt("received"), answer.optInt("new"))
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw ServerException(code, message.ifBlank { "HTTP $code" })
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun from(settings: Settings) = ServerApi(settings.url, settings.token)

        fun describe(error: Throwable): String = when (error) {
            is ServerException -> if (error.code == 401) "Pogrešan token." else "Poslužitelj je odgovorio greškom: ${error.message}"
            is UnknownHostException -> "Adresa poslužitelja nije pronađena."
            is ConnectException -> "Poslužitelj nije dostupan (je li telefon u kućnoj mreži?)."
            is SocketTimeoutException -> "Poslužitelj ne odgovara."
            is SSLException -> "Greška u HTTPS vezi: ${error.message}"
            is IOException -> "Greška u vezi: ${error.message}"
            else -> "Greška: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}

/** Slanje očitanja uz red čekanja: ako poslužitelj nije dostupan, očitanja se čuvaju i šalju kasnije. */
object Uploader {
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, "neposlano.json")

    fun pending(context: Context): JSONArray = synchronized(lock) {
        runCatching { JSONArray(file(context).readText()) }.getOrDefault(JSONArray())
    }

    private fun savePending(context: Context, queue: JSONArray) {
        if (queue.length() == 0) file(context).delete() else file(context).writeText(queue.toString())
        Settings(context).pendingCount = queue.length()
    }

    /** Dodaje očitanja u red čekanja (prije slanja, da se ništa ne izgubi). */
    fun enqueue(context: Context, readings: List<Reading>) = synchronized(lock) {
        if (readings.isEmpty()) return@synchronized
        val queue = pending(context)
        readings.forEach {
            queue.put(JSONObject().put("time", it.isoTime).put("mmol", it.mmol).put("source", "aparat"))
        }
        savePending(context, queue)
    }

    /** Šalje sve iz reda čekanja. Vraća rezultat ili null ako slanje nije uspjelo (red ostaje). */
    fun flush(context: Context): PushResult? {
        val settings = Settings(context)
        if (!settings.isConfigured) return null
        val queue = pending(context)
        if (queue.length() == 0) return PushResult(0, 0)
        return try {
            val result = ServerApi.from(settings).push(queue)
            synchronized(lock) {
                // u međuvremenu su mogla stići nova očitanja; uklanjaju se samo poslana
                val now = pending(context)
                val rest = JSONArray()
                for (i in queue.length() until now.length()) rest.put(now.get(i))
                savePending(context, rest)
            }
            AgentLog.add(context, "Poslano ${result.received} očitanja, novih: ${result.new}.")
            result
        } catch (e: Exception) {
            val text = ServerApi.describe(e)
            AgentLog.add(context, "Slanje nije uspjelo: $text Čeka ${queue.length()} očitanja.")
            settings.lastError = text
            null
        }
    }
}

/** Kratki zapisnik rada agenta (zadnjih ~300 redaka) za zaslon Zapisnik. */
object AgentLog {
    private const val MAX_LINES = 300
    private val format = java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss")

    @Synchronized
    fun add(context: Context, message: String) {
        val file = File(context.filesDir, "agent.log")
        val lines = (if (file.exists()) file.readLines() else emptyList()) +
            "${java.time.LocalDateTime.now().format(format)}  $message"
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        android.util.Log.i("SweetSpot", message)
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.filesDir, "agent.log")
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, "agent.log").delete()
    }
}
