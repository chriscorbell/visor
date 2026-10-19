package com.chriscorbell.visor

import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What visor knows about one machine. Lives on the UI thread. */
class Node(val id: String) {
    var name = ""
    var status = Status.PENDING
    var uptimeSec = 0L
    var cpu = 0f
    var mem = 0f
    var memUsedGb = 0f
    var memTotalGb = 0f
    var disk = 0f
    var diskUsedGb = 0f
    var diskTotalGb = 0f
    /** The busiest GPU's load, or null on a machine without one. */
    var gpu: Float? = null
    var gpuName: String? = null
    /**
     * The biggest ZFS pool, or else the biggest extra filesystem. On the NAS
     * this, not the root disk, is the storage that matters.
     */
    var pool: Volume? = null
    /** ZFS pools whose health isn't ONLINE, by name. */
    var unhealthyPools: Map<String, String> = emptyMap()
    var tempC: Float? = null
    var netUpBps = 0L
    var netDownBps = 0L
    var load1: Float? = null
    var lastSampleAt = 0L
    val cpuHistory = Ring(HISTORY)

    val live get() = status == Status.UP && SystemClock.elapsedRealtime() - lastSampleAt < STALE_MS

    data class Volume(val name: String, val usedGb: Float, val totalGb: Float, val zfs: Boolean = false) {
        val pct get() = if (totalGb > 0) usedGb / totalGb * 100f else 0f
    }

    /** The once-a-minute summary in the hub's systems record. */
    fun applyInfo(info: JSONObject) {
        uptimeSec = info.optLong("u", uptimeSec)
        if (SystemClock.elapsedRealtime() - lastSampleAt < STALE_MS) return
        cpu = info.optDouble("cpu", cpu.toDouble()).toFloat()
        mem = info.optDouble("mp", mem.toDouble()).toFloat()
        disk = info.optDouble("dp", disk.toDouble()).toFloat()
        if (info.has("g")) gpu = info.optDouble("g").toFloat()
        info.optDouble("dt").takeIf { !it.isNaN() && it > 0 }?.let { tempC = it.toFloat() }
        info.optJSONArray("la")?.let { load1 = it.optDouble(0).toFloat() }
    }

    /** A live sample from the agent, once a second. */
    fun applySample(stats: JSONObject, info: JSONObject) {
        lastSampleAt = SystemClock.elapsedRealtime()
        uptimeSec = info.optLong("u", uptimeSec)
        cpu = stats.optDouble("cpu").toFloat()
        mem = stats.optDouble("mp").toFloat()
        memUsedGb = stats.optDouble("mu").toFloat()
        memTotalGb = stats.optDouble("m").toFloat()
        disk = stats.optDouble("dp").toFloat()
        diskUsedGb = stats.optDouble("du").toFloat()
        diskTotalGb = stats.optDouble("d").toFloat()
        stats.optJSONArray("b")?.let {
            netUpBps = it.optLong(0)
            netDownBps = it.optLong(1)
        }
        stats.optJSONArray("la")?.let { load1 = it.optDouble(0).toFloat() }
        tempC = info.optDouble("dt").takeIf { !it.isNaN() && it > 0 }?.toFloat()

        val gpus = stats.optJSONObject("g")
        if (gpus != null && gpus.length() > 0) {
            var best: JSONObject? = null
            for (key in gpus.keys()) {
                val g = gpus.getJSONObject(key)
                if (best == null || g.optDouble("u") > best.optDouble("u")) best = g
            }
            gpu = best!!.optDouble("u").toFloat()
            gpuName = best.optString("n").ifBlank { null }
        } else {
            gpu = null
            gpuName = null
        }

        val zfs = stats.optJSONObject("z")
        val volumes = stats.optJSONObject("efs")
        val unhealthy = HashMap<String, String>()
        pool = if (zfs != null && zfs.length() > 0) {
            zfs.keys().asSequence().map { key ->
                val z = zfs.getJSONObject(key)
                val health = z.optString("h")
                if (health.isNotEmpty() && health != "ONLINE") unhealthy[key] = health
                Volume(key, z.optDouble("du").toFloat(), z.optDouble("d").toFloat(), zfs = true)
            }.filter { it.totalGb > 0 }.maxByOrNull { it.totalGb }
        } else {
            volumes?.keys()?.asSequence()?.map { key ->
                val v = volumes.getJSONObject(key)
                Volume(key, v.optDouble("du").toFloat(), v.optDouble("d").toFloat())
            }?.filter { it.totalGb > 0 }?.maxByOrNull { it.totalGb }
        }
        unhealthyPools = unhealthy

        cpuHistory.push(cpu)
    }

    companion object {
        const val HISTORY = 60
        /** A machine that sent nothing for this long is drawn as stale. */
        const val STALE_MS = 10_000L
    }
}

enum class Status {
    UP, DOWN, PAUSED, PENDING;

    companion object {
        fun parse(value: String) = when (value) {
            "up" -> UP
            "down" -> DOWN
            "paused" -> PAUSED
            else -> PENDING
        }
    }
}

/** A fixed-size history, oldest first. */
class Ring(val capacity: Int) {
    private val values = FloatArray(capacity)
    var size = 0
        private set
    private var head = 0

    fun push(value: Float) {
        values[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    operator fun get(i: Int): Float = values[(head - size + i + capacity) % capacity]
}

enum class Tone { INFO, GOOD, WARN, ALERT }

class Event(val at: Long, val source: String, val text: String, val tone: Tone) {
    val time: String = clock.format(Date(at))

    private companion object {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/**
 * Every machine, in display order, plus the event log: status changes, and
 * loads that stay over a threshold for a few seconds. Lives on the UI thread.
 */
class Fleet {
    val nodes = LinkedHashMap<String, Node>()
    val events = ArrayDeque<Event>()
    /** Goes up with every event, so the log panel knows when to redraw. */
    var revision = 0
        private set
    private val alarms = HashMap<String, Long>()

    /**
     * Machines by grid slot, in [ORDER]. Each keeps its own slot whether or
     * not it has reported yet, so the grid never shuffles.
     */
    fun slots(count: Int): List<Node?> = List(count) { i ->
        ORDER.getOrNull(i)?.let { name -> nodes.values.firstOrNull { it.name == name } }
    }

    val online get() = nodes.values.count { it.status == Status.UP }

    fun replace(systems: List<SystemRecord>) {
        val keep = systems.map { it.id }.toSet()
        nodes.keys.retainAll(keep)
        for (system in systems) update(system, announce = false)
    }

    fun update(system: SystemRecord, announce: Boolean = true) {
        val node = nodes.getOrPut(system.id) { Node(system.id) }
        node.name = system.name
        val next = Status.parse(system.status)
        if (announce && next != node.status) {
            when {
                next == Status.UP -> log(node.name, "ONLINE", Tone.GOOD)
                next == Status.DOWN -> log(node.name, "SIGNAL LOST", Tone.ALERT)
                next == Status.PAUSED -> log(node.name, "PAUSED", Tone.INFO)
            }
        }
        node.status = next
        node.applyInfo(system.info)
    }

    fun remove(id: String) {
        nodes.remove(id)
    }

    fun sample(id: String, stats: JSONObject, info: JSONObject): Node? {
        val node = nodes[id] ?: return null
        val wasUnhealthy = node.unhealthyPools
        node.applySample(stats, info)
        for ((name, health) in node.unhealthyPools) {
            if (wasUnhealthy[name] != health) log(node.name, "$name $health", Tone.ALERT)
        }
        for (name in wasUnhealthy.keys - node.unhealthyPools.keys) log(node.name, "$name ONLINE", Tone.GOOD)
        watch(node, "CPU", node.cpu, high = 90f, clear = 70f)
        watch(node, "MEM", node.mem, high = 90f, clear = 80f)
        watch(node, "DSK", node.disk, high = 90f, clear = 85f)
        node.tempC?.let { watch(node, "TEMP", it, high = 85f, clear = 75f, unit = "°C") }
        return node
    }

    fun log(source: String, text: String, tone: Tone) {
        events.addLast(Event(System.currentTimeMillis(), source, text, tone))
        revision++
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /**
     * Logs a reading that has stayed over [high] for a few seconds, and again
     * once it falls back under [clear], so a brief spike stays quiet.
     */
    private fun watch(node: Node, metric: String, value: Float, high: Float, clear: Float, unit: String = "%") {
        val key = node.id + metric
        val now = SystemClock.elapsedRealtime()
        val since = alarms[key]
        when {
            value >= high && since == null -> alarms[key] = -now
            value >= high && since != null && since < 0 && now + since >= SUSTAIN_MS -> {
                alarms[key] = now
                log(node.name, "$metric HIGH ${value.toInt()}$unit", Tone.WARN)
            }
            value < clear && since != null -> {
                alarms.remove(key)
                if (since > 0) log(node.name, "$metric NOMINAL ${value.toInt()}$unit", Tone.INFO)
            }
            value < high && since != null && since < 0 -> alarms.remove(key)
        }
    }

    companion object {
        const val MAX_EVENTS = 40
        const val SUSTAIN_MS = 5_000L

        /**
         * Fleet hostnames in the order the grid shows them. visor follows only
         * these, so a machine the hub keeps for its history, like nas-old,
         * stays off the screen and out of the counts.
         */
        val ORDER = listOf("minicore", "llm", "nas", "cloud", "haos", "rpi5", "agent-pc", "mbp", "gaming-pc")

        /** What each machine is for, under its name. */
        val ROLES = mapOf(
            "minicore" to "PRODUCTION DOCKER",
            "llm" to "LLM INFERENCE",
            "nas" to "PRIMARY STORAGE",
            "cloud" to "OCI // CHICAGO",
            "haos" to "HOME ASSISTANT",
            "rpi5" to "PXE BOOT",
            "agent-pc" to "MILO // AGENT",
            "mbp" to "WORKSTATION",
            "gaming-pc" to "GAMING RIG",
        )
    }
}
