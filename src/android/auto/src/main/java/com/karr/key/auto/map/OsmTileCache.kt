package com.karr.key.auto.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches and caches OpenStreetMap raster tiles (the same MAPNIK tiles the phone map draws) for the
 * car surface renderer. [tile] returns a cached bitmap immediately or null while it loads one in the
 * background, invoking [onTileLoaded] when a fetch completes so the renderer can repaint.
 *
 * Memory-cache only for now (no disk cache) — enough for an in-session map; a cold start re-fetches.
 * OSM's tile-usage policy requires an identifying User-Agent and discourages bulk downloading, both
 * satisfied here for this research/sideloaded build.
 */
internal class OsmTileCache(
    private val onTileLoaded: () -> Unit,
) {
    private val memory = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(TILE_THREADS)

    fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        if (inFlight.add(key)) {
            executor.execute {
                val bitmap = runCatching { download(z, x, y) }.getOrNull()
                if (bitmap != null) memory.put(key, bitmap)
                inFlight.remove(key)
                if (bitmap != null) onTileLoaded()
            }
        }
        return null
    }

    fun shutdown() {
        executor.shutdownNow()
        memory.evictAll()
    }

    private fun download(z: Int, x: Int, y: Int): Bitmap? {
        val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            .openConnection() as HttpURLConnection
        return connection.runCatching {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            inputStream.use(BitmapFactory::decodeStream)
        }.also { connection.disconnect() }.getOrNull()
    }

    private companion object {
        const val TILE_THREADS = 4
        const val TIMEOUT_MS = 8_000
        const val USER_AGENT =
            "KARR-Key/1.0 (Android Auto research build; +https://github.com/neednotapply/KARR-Key)"

        fun maxCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024)
                .toInt()
    }
}
