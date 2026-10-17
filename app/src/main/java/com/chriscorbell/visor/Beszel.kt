package com.chriscorbell.visor

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the Beszel hub on minicore, which holds every machine's stats.
 *
 * The hub is PocketBase underneath. Machines are records in its `systems`
 * collection, refreshed once a minute. Subscribing to a machine's
 * `rt_metrics` topic over PocketBase's realtime stream makes the hub poll that
 * machine's agent every second for as long as the subscription lasts, which
 * is what keeps visor live. Everything runs on one background thread;
 * [Listener] callbacks arrive on it.
 */
class Beszel(
    private val baseUrl: String,
    private val credentials: () -> Credentials?,
    private val listener: Listener,
) {
    data class Credentials(val email: String, val password: String)

    enum class Link { NO_CREDENTIALS, CONNECTING, LIVE, OFFLINE }

    interface Listener {
        fun onLink(link: Link)
        /** The full list of machines, replacing whatever came before. */
        fun onSystems(systems: List<SystemRecord>)
        /** One machine's record changed: its status, name or once-a-minute info. */
        fun onSystem(system: SystemRecord)
        fun onSystemDeleted(id: String)
        /** A machine's live sample: the agent's `stats` and `info`, once a second. */
        fun onMetrics(id: String, stats: JSONObject, info: JSONObject)
    }

    @Volatile private var running = false
    @Volatile private var stream: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "beszel").apply { start() }
    }

    fun stop() {
        running = false
        stream?.disconnect()
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        var failures = 0
        while (running) {
            val creds = credentials()
            if (creds == null) {
                listener.onLink(Link.NO_CREDENTIALS)
                if (!pause(5_000)) return
                continue
            }
            listener.onLink(Link.CONNECTING)
            try {
                val token = signIn(creds)
                val systems = listSystems(token)
                listener.onSystems(systems)
                follow(token, systems.map { it.id }.toMutableSet()) { failures = 0 }
            } catch (e: IOException) {
                if (running) Log.i(TAG, "Hub link dropped: ${e.message}")
            } catch (e: RuntimeException) {
                if (running) Log.w(TAG, "Hub link failed", e)
            } finally {
                stream?.disconnect()
                stream = null
            }
            if (!running) return
            listener.onLink(Link.OFFLINE)
            if (!pause(RETRY_MS[minOf(failures++, RETRY_MS.lastIndex)])) return
        }
    }

    private fun signIn(creds: Credentials): String {
        val body = JSONObject().put("identity", creds.email).put("password", creds.password)
        return JSONObject(request("POST", "/api/collections/users/auth-with-password", null, body))
            .getString("token")
    }

    private fun listSystems(token: String): List<SystemRecord> {
        val page = JSONObject(request("GET", "/api/collections/systems/records?perPage=200&sort=name", token))
        val items = page.getJSONArray("items")
        return (0 until items.length()).map { SystemRecord.parse(items.getJSONObject(it)) }
    }

    /**
     * Opens the realtime stream and dispatches its events until it drops, or
     * until it is old enough that the sign-in should be renewed.
     */
    private fun follow(token: String, systemIds: MutableSet<String>, onLive: () -> Unit) {
        val conn = open("GET", "/api/realtime", null).apply {
            setRequestProperty("Accept", "text/event-stream")
            // Machines report every second; a quiet minute means the stream is dead.
            readTimeout = 60_000
        }
        stream = conn
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val openedAt = System.currentTimeMillis()
        var clientId: String? = null
        val topics = mutableMapOf<String, String>()

        fun subscribe() {
            val id = clientId ?: return
            topics.clear()
            for (system in systemIds) topics[metricsTopic(system)] = system
            val subscriptions = JSONArray().put(SYSTEMS_TOPIC)
            for (topic in topics.keys) subscriptions.put(topic)
            request("POST", "/api/realtime", token, JSONObject().put("clientId", id).put("subscriptions", subscriptions))
        }

        var event = ""
        val data = StringBuilder()
        while (running) {
            val line = reader.readLine() ?: throw IOException("stream closed")
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        val payload = data.toString()
                        when (event) {
                            "PB_CONNECT" -> {
                                clientId = JSONObject(payload).getString("clientId")
                                subscribe()
                                listener.onLink(Link.LIVE)
                                onLive()
                            }
                            SYSTEMS_TOPIC -> {
                                val message = JSONObject(payload)
                                val record = SystemRecord.parse(message.getJSONObject("record"))
                                when (message.optString("action")) {
                                    "delete" -> {
                                        systemIds.remove(record.id)
                                        listener.onSystemDeleted(record.id)
                                        subscribe()
                                    }
                                    else -> {
                                        listener.onSystem(record)
                                        if (systemIds.add(record.id)) subscribe()
                                    }
                                }
                            }
                            else -> topics[event]?.let { id ->
                                val sample = JSONObject(payload)
                                val stats = sample.optJSONObject("stats")
                                val info = sample.optJSONObject("info")
                                if (stats != null && info != null) listener.onMetrics(id, stats, info)
                            }
                        }
                    }
                    event = ""
                    data.setLength(0)
                }
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            // Sign in afresh now and then, long before the hub's tokens expire.
            if (System.currentTimeMillis() - openedAt > RENEW_MS) throw IOException("renewing sign-in")
        }
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject? = null): String {
        val conn = open(method, path, token)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                throw IOException("$method $path: HTTP $code $detail")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String, token: String?) =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("User-Agent", "visor/${BuildConfig.VERSION_CODE}")
            if (token != null) setRequestProperty("Authorization", token)
        }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        running
    } catch (_: InterruptedException) {
        false
    }

    private companion object {
        const val TAG = "visor.hub"
        const val SYSTEMS_TOPIC = "systems/*"
        const val RENEW_MS = 6 * 60 * 60_000L
        val RETRY_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

        /**
         * Serialized exactly as PocketBase's JS SDK does it, which Beszel's
         * web interface uses: the hub keeps one subscription name per machine,
         * so a different spelling of the same topic would miss its updates.
         */
        fun metricsTopic(systemId: String): String {
            val options = JSONObject().put("query", JSONObject().put("system", systemId)).toString()
            return "rt_metrics?options=" + URLEncoder.encode(options, "UTF-8").replace("+", "%20")
        }
    }
}

/** A machine as the hub's `systems` collection describes it. */
data class SystemRecord(
    val id: String,
    val name: String,
    val status: String,
    val info: JSONObject,
) {
    companion object {
        fun parse(json: JSONObject) = SystemRecord(
            id = json.getString("id"),
            name = json.optString("name"),
            status = json.optString("status"),
            info = json.optJSONObject("info") ?: JSONObject(),
        )
    }
}
