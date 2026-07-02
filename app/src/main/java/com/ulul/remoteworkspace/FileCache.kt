package com.ulul.remoteworkspace

import com.rk.file.sandboxHomeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/**
 * Disk-backed LRU cache for remote file contents.
 *
 * Why disk-backed (not in-memory):
 * - Files can be large; keeping everything in RAM would pressure Android's memory killer.
 * - The cache survives GC and short app backgrounding (no re-download needed).
 * - Max 100MB cap keeps things predictable; LRU eviction removes least-recently-used entries
 *   when the cap is exceeded.
 *
 * Cache key: remote path + "|" + mtime (Unix seconds).
 * Invalidation: write-through - any write/delete/rename for a path removes its cache entry.
 * On cache-hit with a changed mtime, the old entry is evicted and a fresh download is done.
 *
 * Cache dir: [sandboxHomeDir]/.xed_remote_cache/ - inside Xed-Editor's Ubuntu home so it's
 * cleaned up when the terminal environment is reset, and never touches user's public storage.
 */
object FileCache {

    private const val MAX_BYTES = 100L * 1024 * 1024   // 100 MB
    private const val CACHE_DIR_NAME = ".xed_remote_cache"

    private val mutex = Mutex()

    // LinkedHashMap with access-order gives us LRU semantics for free.
    // key  = cacheKey(path, mtime)
    // value = (cacheFile, sizeBytes)
    private val index: LinkedHashMap<String, Pair<File, Long>> =
        object : LinkedHashMap<String, Pair<File, Long>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Pair<File, Long>>): Boolean = false
        }

    private val cacheDir: File get() = File(sandboxHomeDir(), CACHE_DIR_NAME).also { it.mkdirs() }

    /** Total bytes currently tracked in the index. */
    private var totalBytes: Long = 0L

    @Volatile private var indexed = false

    /**
     * Lazily scans the cache directory into the index on first access.
     * Called automatically from get()/put() - no explicit init() at startup needed.
     * This keeps extension load time near-zero.
     */
    private suspend fun ensureIndexed() {
        if (indexed) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (indexed) return@withLock
                cacheDir.listFiles()?.forEach { f ->
                    if (f.isFile && !index.containsKey(f.name)) {
                        index[f.name] = f to f.length()
                        totalBytes += f.length()
                    }
                }
                evictIfNeeded()
                indexed = true
            }
        }
    }

    /** Kept for compatibility; now a no-op since the cache is lazy. */
    suspend fun init() { ensureIndexed() }

    // ---- public API ----

    /**
     * Returns the cached content for [remotePath] with [mtime], or null on cache-miss.
     * Moves the entry to "most recently used" position on hit.
     */
    suspend fun get(remotePath: String, mtime: Long): ByteArray? = withContext(Dispatchers.IO) {
        ensureIndexed()
        mutex.withLock {
            val key = cacheKey(remotePath, mtime)
            val (file, _) = index[key] ?: return@withLock null
            if (!file.exists()) {
                index.remove(key)
                return@withLock null
            }
            file.readBytes()
        }
    }

    suspend fun put(remotePath: String, mtime: Long, content: ByteArray) = withContext(Dispatchers.IO) {
        ensureIndexed()
        mutex.withLock {
            // Remove any stale entry for this path (different mtime).
            evictPath(remotePath, exceptMtime = mtime)

            val key = cacheKey(remotePath, mtime)
            if (index.containsKey(key)) return@withLock   // Already cached.

            if (content.size > MAX_BYTES / 4) return@withLock  // Single file >25MB - skip caching.

            val file = File(cacheDir, key)
            file.writeBytes(content)
            index[key] = file to content.size.toLong()
            totalBytes += content.size.toLong()
            evictIfNeeded()
        }
    }

    /**
     * Removes all cache entries whose path matches [remotePath] exactly.
     * Call this after any write/delete/rename that invalidates the content.
     */
    suspend fun invalidate(remotePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock { evictPath(remotePath) }
    }

    /** Clears the entire cache - call on disconnect or manual "clear cache" command. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            index.values.forEach { (file, _) -> file.delete() }
            index.clear()
            totalBytes = 0L
        }
    }

    /** Current cache size in bytes. */
    val currentBytes: Long get() = totalBytes

    // ---- helpers ----

    /** Evicts entries for [remotePath] (optionally skipping one [exceptMtime]). */
    private fun evictPath(remotePath: String, exceptMtime: Long? = null) {
        val prefix = pathToKeyPrefix(remotePath)
        val toRemove = index.keys.filter { k ->
            k.startsWith(prefix) && (exceptMtime == null || k != cacheKey(remotePath, exceptMtime))
        }
        toRemove.forEach { k ->
            val (file, size) = index.remove(k) ?: return@forEach
            file.delete()
            totalBytes -= size
        }
    }

    /** Evicts least-recently-used entries until totalBytes ≤ MAX_BYTES. */
    private fun evictIfNeeded() {
        val iter = index.entries.iterator()
        while (totalBytes > MAX_BYTES && iter.hasNext()) {
            val (_, pair) = iter.next()
            val (file, size) = pair
            file.delete()
            totalBytes -= size
            iter.remove()
        }
    }

    private fun cacheKey(path: String, mtime: Long): String {
        // Replace characters that aren't safe as filenames.
        val safePath = path.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return "${safePath}__${mtime}"
    }

    private fun pathToKeyPrefix(path: String): String {
        val safePath = path.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return "${safePath}__"
    }
}

// ---- Extension helpers used by RemoteFileObject ----

suspend fun FileCache.getOrFetch(
    remotePath: String,
    mtime: Long,
    fetch: suspend () -> ByteArray?
): ByteArray? {
    val cached = get(remotePath, mtime)
    if (cached != null) return cached
    val fresh = fetch() ?: return null
    put(remotePath, mtime, fresh)
    return fresh
}

suspend fun ByteArray.toText(charset: Charset = Charsets.UTF_8): String = String(this, charset)
