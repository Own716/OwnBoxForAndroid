package io.nekohasekai.sagernet.bg.autoselector

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-safe store for per-profile URL test timestamps (`latencyAt` in epoch seconds)
 * and latest errors without requiring a Room database schema migration.
 * Backed by `DataStore.configurationStore` (`PublicDatabase.kvPairDao`, accessible from `:ui` and `:bg`).
 */
object AutoSelectorHealthStore {

    data class Entry(
        val latency: Int = 0,
        val at: Long = 0L,
        val error: String? = null,
    )

    data class ResolvedHealth(
        val latency: Int,
        val at: Long,
        val error: String?,
    )

    private const val KEY_CACHE = "autoSelectorHealthCache"
    private const val MAX_ENTRIES = 5000

    private val lock = Any()

    @Volatile
    private var lastRawLoaded: String? = null

    private var cachedMap: MutableMap<Long, Entry> = HashMap()

    // Test hook for pure JVM tests where Room/DataStore is unavailable.
    @Volatile
    internal var memoryOnlyForTest: Boolean = false
    private val testMemoryMap = ConcurrentHashMap<Long, Entry>()

    private fun readPersistedMapLocked(): MutableMap<Long, Entry> {
        if (memoryOnlyForTest) {
            return HashMap(testMemoryMap)
        }
        val raw = runCatching { DataStore.configurationStore.getString(KEY_CACHE, "") }.getOrNull().orEmpty()
        if (raw == lastRawLoaded) {
            return cachedMap
        }
        val parsed = parseJsonMap(raw)
        lastRawLoaded = raw
        cachedMap = parsed
        return parsed
    }

    private fun writePersistedMapLocked(map: MutableMap<Long, Entry>) {
        if (memoryOnlyForTest) {
            testMemoryMap.clear()
            testMemoryMap.putAll(map)
            cachedMap = map
            return
        }
        if (map.size > MAX_ENTRIES) {
            val trimmed = map.entries
                .sortedByDescending { it.value.at }
                .take(MAX_ENTRIES)
                .associateTo(HashMap()) { it.key to it.value }
            map.clear()
            map.putAll(trimmed)
        }
        val encoded = encodeJsonMap(map)
        runCatching {
            DataStore.configurationStore.putString(KEY_CACHE, encoded)
        }
        lastRawLoaded = encoded
        cachedMap = map
    }

    fun get(profileId: Long): Entry? {
        if (profileId <= 0L) return null
        if (memoryOnlyForTest) return testMemoryMap[profileId]
        synchronized(lock) {
            return readPersistedMapLocked()[profileId]
        }
    }

    fun snapshot(): Map<Long, Entry> {
        if (memoryOnlyForTest) return HashMap(testMemoryMap)
        synchronized(lock) {
            return HashMap(readPersistedMapLocked())
        }
    }

    fun record(
        profileId: Long,
        latencyMs: Int,
        atSec: Long = System.currentTimeMillis() / 1000L,
        error: String? = null,
    ) {
        if (profileId <= 0L) return
        val normalizedLatency = latencyMs.coerceAtLeast(0)
        val normalizedError = error?.trim()?.takeIf { it.isNotEmpty() }
        recordBatch(mapOf(profileId to Entry(normalizedLatency, atSec, normalizedError)))
    }

    fun recordBatch(updates: Map<Long, Entry>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            val current = HashMap(readPersistedMapLocked())
            for ((id, entry) in updates) {
                if (id <= 0L) continue
                val existing = current[id]
                if (existing == null || entry.at >= existing.at) {
                    current[id] = entry
                }
            }
            writePersistedMapLocked(current)
        }
    }

    fun remove(profileId: Long) {
        if (profileId <= 0L) return
        synchronized(lock) {
            val current = HashMap(readPersistedMapLocked())
            if (current.remove(profileId) != null) {
                writePersistedMapLocked(current)
            }
        }
    }

    fun clearForTest() {
        synchronized(lock) {
            testMemoryMap.clear()
            cachedMap.clear()
            lastRawLoaded = null
        }
    }

    fun resolveMemberHealth(
        profile: ProxyEntity,
        storeSnapshot: Map<Long, Entry> = snapshot(),
    ): ResolvedHealth {
        val cached = storeSnapshot[profile.id]
        return when (profile.status) {
            1 -> {
                val ping = profile.ping.coerceAtLeast(0)
                if (ping > 0) {
                    val at = if (cached != null && cached.latency > 0 && cached.at > 0L) cached.at else 0L
                    ResolvedHealth(latency = ping, at = at, error = null)
                } else {
                    ResolvedHealth(latency = 0, at = 0L, error = null)
                }
            }
            2, 3 -> {
                val err = profile.error?.takeIf { it.isNotBlank() } ?: cached?.error
                val at = if (cached != null && cached.latency <= 0 && cached.at > 0L) cached.at else 0L
                ResolvedHealth(latency = 0, at = at, error = err)
            }
            else -> ResolvedHealth(latency = 0, at = 0L, error = null)
        }
    }

    internal fun parseJsonMap(raw: String): MutableMap<Long, Entry> {
        val result = HashMap<Long, Entry>()
        if (raw.isBlank()) return result
        runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            for ((k, v) in root.entrySet()) {
                val id = k.toLongOrNull() ?: continue
                if (!v.isJsonObject) continue
                val obj = v.asJsonObject
                val latency = obj.get("l")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val at = obj.get("t")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val err = obj.get("e")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                result[id] = Entry(latency = latency.coerceAtLeast(0), at = at, error = err)
            }
        }
        return result
    }

    internal fun encodeJsonMap(map: Map<Long, Entry>): String {
        val root = JsonObject()
        for ((id, entry) in map) {
            val obj = JsonObject()
            if (entry.latency > 0) obj.addProperty("l", entry.latency)
            if (entry.at > 0L) obj.addProperty("t", entry.at)
            if (!entry.error.isNullOrBlank()) obj.addProperty("e", entry.error)
            root.add(id.toString(), obj)
        }
        return root.toString()
    }
}
