package com.runvision.wear.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Open-Meteo elevation client (Copernicus GLO-90 DEM, returned in meters MSL).
 *
 * Endpoint: GET https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}
 * Response: {"elevation":[109.0]}
 *
 * Caching: in-memory grid (lat,lon rounded to DEM_GRID_DEG ≈ 90m at Korea latitude).
 * Dedupe: per-cell Mutex prevents duplicate concurrent fetches.
 * Failure: silent fallback — caller treats a missing entry as "no DEM available
 * this sample" and degrades to GPS reference or the dead-reckoned baro value.
 *
 * Deliberately uses HttpURLConnection (no OkHttp/ktor dep). One endpoint, one
 * field, ~50 lines of network code. Adding a client framework here would be
 * over-engineering.
 */
class ElevationLookup {

    companion object {
        private const val TAG = "ElevationLookup"
        private const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        /** Grid cell size ≈ 90m at Korea latitude (matches GLO-90 native resolution). */
        const val DEM_GRID_DEG = 0.001
        /** Cap in-memory cache to stay tiny (LRU-ish via insertion order on overflow). */
        private const val MAX_CACHE_ENTRIES = 200
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000
    }

    /** (cellLatIdx, cellLonIdx) -> elevation meters (MSL). */
    private val cache = ConcurrentHashMap<Long, Double>()

    /** Per-cell mutex to dedupe concurrent fetches for the same cell. */
    private val fetchLocks = ConcurrentHashMap<Long, Mutex>()

    /**
     * Synchronous cache lookup. Returns null if the cell isn't fetched yet.
     * Callers are expected to invoke [fetchAsync] separately so the next sample
     * sees the value populated. Never blocks on the network.
     *
     * @param fetchOnMiss true(default): caller may follow with [fetchAsync]. false:
     * cache-only mode — used after session anchor 확보 so network round-trips don't
     * leak power on a phone-free workout. The flag itself doesn't trigger fetches
     * (fetchAsync is suspend); it's a contract hint the caller uses to decide.
     */
    fun lookup(lat: Double, lon: Double, fetchOnMiss: Boolean = true): Double? {
        val key = cellKey(lat, lon)
        return cache[key]
    }

    /**
     * Fire-and-forget fetch. Idempotent: if a fetch for this cell is already
     * in flight, returns immediately. If the cell is already cached, no-op.
     */
    suspend fun fetchAsync(lat: Double, lon: Double) {
        val key = cellKey(lat, lon)
        if (cache.containsKey(key)) return
        val lock = fetchLocks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return  // already fetching
        try {
            // Re-check after acquiring the lock (another fetch may have completed).
            if (cache.containsKey(key)) return
            val elevation = withContext(Dispatchers.IO) { fetchOnce(lat, lon) }
            if (elevation != null) {
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    // Cheap eviction: drop ~10 entries. Caller doesn't depend on
                    // strict LRU — these are stable DEM values, any cell is fine.
                    val victims = cache.keys.take(10)
                    victims.forEach { cache.remove(it) }
                }
                cache[key] = elevation
                Log.d(TAG, "DEM cached lat=$lat lon=$lon -> $elevation m (cells=${cache.size})")
            }
        } finally {
            lock.unlock()
        }
    }

    private fun fetchOnce(lat: Double, lon: Double): Double? {
        val urlStr = "$ENDPOINT?latitude=$lat&longitude=$lon"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "DEM HTTP $code for $urlStr")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Tiny known schema: {"elevation":[109.0]}. A 50-line org.json/Moshi
            // is overkill — match the single numeric element with a regex.
            val match = Regex("""\"elevation\"\s*:\s*\[\s*(-?[0-9]+(?:\.[0-9]+)?)""")
                .find(body) ?: return null
            val v = match.groupValues[1].toDoubleOrNull() ?: return null
            if (v.isFinite() && v > -500.0 && v < 9000.0) v else null
        } catch (e: Exception) {
            Log.w(TAG, "DEM fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        // Pack two ~24-bit grid indices into one Long for ConcurrentHashMap.
        val latIdx = floor(lat / DEM_GRID_DEG).toLong()
        val lonIdx = floor(lon / DEM_GRID_DEG).toLong()
        return (latIdx shl 32) or (lonIdx and 0xFFFFFFFFL)
    }
}
