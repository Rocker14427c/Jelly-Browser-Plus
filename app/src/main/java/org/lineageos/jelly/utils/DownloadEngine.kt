/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * In-app download engine. Unlike the system DownloadManager (which Chrome
 * and most WebView browsers delegate to, with its single-connection
 * bottlenecks), this engine downloads files itself:
 *
 *  - SEGMENTED downloads: files larger than a threshold are fetched with
 *    several parallel HTTP Range requests written into one file, which is
 *    dramatically faster on fast connections and on servers that throttle
 *    single streams.
 *  - Automatic fallback to a single connection when the server doesn't
 *    support ranges.
 *  - Pause / resume / retry with per-segment byte progress persisted in
 *    Room, so downloads survive the app being killed (they recover as
 *    "paused" and can be resumed).
 *  - Downloads land in the public Downloads folder via MediaStore on
 *    Android 10+ (no permission prompts), app-private storage elsewhere.
 *  - Live progress + speed exposed as a StateFlow for the in-app
 *    Downloads screen; a foreground service keeps the process alive while
 *    anything is running.
 */
package org.lineageos.jelly.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.downloads.DownloadService
import org.lineageos.jelly.database.DownloadDatabase
import org.lineageos.jelly.model.DownloadEntry
import org.lineageos.jelly.model.DownloadSegment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object DownloadEngine {
    private const val TAG = "DownloadEngine"

    const val STATUS_QUEUED = "queued"
    const val STATUS_RUNNING = "running"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"

    private const val MAX_SEGMENTS = 4
    private const val SEGMENT_MIN_SIZE = 512L * 1024L       // 512 KiB
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val RETRY_ATTEMPTS = 3
    private const val NOTIFY_INTERVAL_MS = 400L
    private const val PERSIST_INTERVAL_MS = 3_000L
    private const val UA_FALLBACK =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /** Live view of all downloads, for the in-app Downloads screen. */
    data class Info(
        val id: Long,
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val totalBytes: Long,
        val bytesDone: Long,
        val status: String,
        val speedBps: Long,
        val savedUri: String?
    ) {
        val progress: Int get() = if (totalBytes > 0) (bytesDone * 100 / totalBytes).toInt() else -1
    }

    private val infos = ConcurrentHashMap<Long, Info>()
    private val _state = MutableStateFlow<List<Info>>(emptyList())
    val state: StateFlow<List<Info>> = _state

    private val active = ConcurrentHashMap<Long, ActiveDownload>()

    // ------------------------------------------------------------------ UI

    fun refreshInfos(context: Context) {
        val app = context.applicationContext
        io {
            val dao = dao(app)
            infos.clear()
            dao.getEntriesSnapshot().forEach { e ->
                val done = if (e.status == STATUS_COMPLETED) e.totalBytes
                else dao.getSegments(e.id).sumOf { it.done }
                infos[e.id] = e.toInfo(done, 0)
            }
            publish()
        }
    }

    fun pause(context: Context, id: Long) {
        val app = context.applicationContext
        io { doPause(app, id) }
    }

    fun resume(context: Context, id: Long) {
        val app = context.applicationContext
        io { startActive(app, id) }
    }

    fun cancel(context: Context, id: Long) {
        val app = context.applicationContext
        io { doCancel(app, id) }
    }

    /** Removes a download entirely: entry, segments and the saved file. */
    fun delete(context: Context, id: Long) = cancel(context, id)

    /** Renames a download: MediaStore display name / file, plus the DB row. */
    fun rename(context: Context, id: Long, newName: String) {
        val app = context.applicationContext
        io {
            val dao = dao(app)
            val entry = dao.getEntry(id) ?: return@io
            val trimmed = newName.trim()
            if (trimmed.isEmpty() || trimmed == entry.fileName) return@io
            var finalName = trimmed
            val saved = entry.savedUri
            if (saved != null) {
                runCatching {
                    val uri = Uri.parse(saved)
                    if (uri.scheme == "content") {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, trimmed)
                        }
                        app.contentResolver.update(uri, values, null, null)
                    } else {
                        val f = File(saved)
                        val parent = f.parentFile ?: return@runCatching
                        val target = File(parent, trimmed)
                        if (f.renameTo(target)) finalName = target.name
                    }
                }
            }
            dao.updateFileName(id, finalName)
            infos[id]?.let { infos[id] = it.copy(fileName = finalName) }
            publish()
        }
    }

    fun hasActive(): Boolean =
        active.values.any { it.status.get() }

    // ------------------------------------------------------------- engine

    enum class EnqueueResult {
        /** A new download was created and is starting. */
        STARTED,

        /** The same URL is already queued/running/paused — not started again. */
        DUPLICATE,
    }

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?,
        onResult: (EnqueueResult) -> Unit = {}
    ) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        io {
            val dao = dao(app)

            // Duplicate guard: repeated taps on the same download link used
            // to create several parallel downloads of the same file.
            if (dao.getActiveIdByUrl(url) != null) {
                main.post { onResult(EnqueueResult.DUPLICATE) }
                return@io
            }

            // Insert the entry FIRST (before the possibly-slow size probe),
            // so the Downloads screen shows the row immediately and the user
            // sees the download registered instead of an empty list.
            val entry = DownloadEntry(
                id = 0, url = url, fileName = fileName, mimeType = mimeType,
                totalBytes = -1L, savedUri = null,
                status = STATUS_QUEUED, createdAt = System.currentTimeMillis(),
                completedAt = null
            )
            val id = dao.insertEntry(entry)
            infos[id] = entry.copy(id = id).toInfo(0, 0)
            publish()
            main.post { onResult(EnqueueResult.STARTED) }

            // Now resolve the size and lay out the segments.
            val total = probeSize(url, userAgent, app)
            dao.updateEntry(id, total ?: -1L, null, STATUS_QUEUED, null)
            dao.insertSegments(
                buildSegments(total).mapIndexed { i, s ->
                    DownloadSegment(id, i, s.first, s.second, 0)
                }
            )
            infos[id]?.let { infos[id] = it.copy(totalBytes = total ?: -1L) }
            publish()
            startActive(app, id, userAgent)
        }
    }

    /** Marks downloads left "running" by a dead process as paused. */
    fun recoverStale(context: Context) {
        val app = context.applicationContext
        io {
            dao(app).markRunningAsPaused()
            refreshInfos(app)
        }
    }

    // ------------------------------------------------------------ active

    private class SegState(
        val index: Int,
        val start: Long,
        val end: Long,          // inclusive, -1 = to EOF
        @Volatile var done: Long = 0
    ) {
        val absolutePos: Long get() = start + done
        @Volatile var conn: HttpURLConnection? = null
    }

    private class ActiveDownload(
        val id: Long,
        val url: String,
        val userAgent: String?,
        val mimeType: String?,
        val fileName: String,
        val channel: FileChannel,
        val target: Target,
        val cookie: String?,
        @Volatile var totalBytes: Long,
        val segments: List<SegState>
    ) {
        val status = AtomicBoolean(true)          // true = running
        val paused = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val singleThread = AtomicBoolean(false)
        val finishedThreads = AtomicInteger(0)
        val completedSegments = AtomicInteger(0)
        @Volatile var bytesDone = 0L
        @Volatile var speed = 0L
        @Volatile var lastNotify = 0L
        @Volatile var lastBytes = 0L
        @Volatile var lastTime = 0L
        @Volatile var persistAt = 0L
        @Volatile var targetTotal: Long = 0
    }

    private class Target(
        val channel: FileChannel,
        val uri: Uri?,        // MediaStore content uri (API 29+)
        val file: File?,      // fallback plain file
        val close: () -> Unit
    )

    private fun startActive(context: Context, id: Long, userAgent: String? = null) {
        val dao = dao(context)
        val entry = dao.getEntry(id) ?: return
        if (entry.status == STATUS_COMPLETED) return
        if (active.containsKey(id)) return

        val target = try {
            openTarget(context, entry)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open target for ${entry.fileName}", e)
            dao.updateStatus(id, STATUS_FAILED)
            infos[id]?.let { infos[id] = it.copy(status = STATUS_FAILED) }
            publish()
            return
        }

        val segments = dao.getSegments(id).map {
            SegState(it.segmentIndex, it.start, it.end, it.done)
        }
        if (segments.isEmpty()) return

        val cookie = CookieManager.getInstance().getCookie(entry.url)
        val ad = ActiveDownload(
            id, entry.url, userAgent, entry.mimeType, entry.fileName,
            target.channel, target, cookie,
            entry.totalBytes, segments
        )
        active[id] = ad
        if (ad.totalBytes > 0) {
            runCatching { target.channel.truncate(ad.totalBytes) }
        }
        setStatus(dao, id, STATUS_RUNNING)
        infos[id]?.let { infos[id] = it.copy(status = STATUS_RUNNING, speedBps = 0) }
        publish()

        // Persist where the bytes live so resumes can reopen it.
        val savedUri = target.uri?.toString() ?: target.file?.absolutePath
        dao.updateEntry(id, ad.totalBytes, savedUri, STATUS_RUNNING, null)

        segments.forEach { seg ->
            Thread({ segmentLoop(context, ad, seg) }, "jelly-dl-$id-seg${seg.index}").start()
        }
        DownloadService.sync(context)
    }

    private fun segmentLoop(context: Context, ad: ActiveDownload, seg: SegState) {
        try {
            var attempt = 0
            while (ad.status.get() && !ad.paused.get()) {
                try {
                    downloadSegment(context, ad, seg)
                    return
                } catch (e: InterruptedIOException) {
                    return // paused/cancelled: stream closed on purpose
                } catch (e: IOException) {
                    if (!ad.status.get() || ad.paused.get()) return
                    attempt++
                    if (attempt >= RETRY_ATTEMPTS) throw e
                    Log.w(TAG, "Segment ${seg.index} retry $attempt (${e.message})")
                    Thread.sleep(1000L * attempt)
                }
            }
        } catch (e: Exception) {
            if (ad.status.get() && !ad.paused.get()) {
                Log.e(TAG, "Segment ${seg.index} failed", e)
                ad.failed.set(true)
            }
        } finally {
            val finished = ad.finishedThreads.incrementAndGet()
            if (finished >= ad.segments.size) {
                onAllSegmentsDone(context, ad)
            }
        }
    }

    private fun downloadSegment(context: Context, ad: ActiveDownload, seg: SegState) {
        val conn = (URL(ad.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ad.userAgent ?: UA_FALLBACK)
            setRequestProperty("Accept-Encoding", "identity")
            ad.cookie?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Cookie", it) }
            if (seg.end >= 0) {
                setRequestProperty("Range", "bytes=${seg.absolutePos}-${seg.end}")
            }
        }
        seg.conn = conn
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val total = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                        ?: ad.totalBytes
                    if (total > 0 && ad.totalBytes <= 0) ad.totalBytes = total
                    val len = conn.contentLength.toLong()
                    copyStream(context, ad, seg, conn.inputStream, seg.absolutePos, len)
                }

                HttpURLConnection.HTTP_OK -> {
                    if (seg.index != 0) {
                        // This server answers ranges inconsistently; only a
                        // single sequential stream can produce a valid file.
                        throw IOException("Server does not support ranges")
                    }
                    if (ad.segments.size > 1) ad.singleThread.set(true)
                    val len = conn.contentLength.toLong()
                    if (len > 0 && ad.totalBytes <= 0) ad.totalBytes = len
                    copyStream(context, ad, seg, conn.inputStream, seg.absolutePos, len)
                }

                else -> throw IOException("HTTP $code")
            }
            ad.completedSegments.incrementAndGet()
        } finally {
            seg.conn = null
            runCatching { conn.disconnect() }
        }
    }

    private fun copyStream(
        context: Context, ad: ActiveDownload, seg: SegState,
        input: InputStream, startPos: Long, expectedLen: Long
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        var pos = startPos
        var remaining = if (expectedLen > 0) expectedLen else Long.MAX_VALUE
        while (remaining > 0 && ad.status.get() && !ad.paused.get()) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            synchronized(ad.channel) {
                ad.channel.position(pos)
                ad.channel.write(java.nio.ByteBuffer.wrap(buf, 0, n))
            }
            pos += n.toLong()
            seg.done += n.toLong()
            ad.bytesDone += n.toLong()
            remaining -= n.toLong()
            throttledNotify(context, ad, seg)
        }
        if (ad.paused.get() || !ad.status.get()) throw InterruptedIOException("stopped")
    }

    private fun throttledNotify(context: Context, ad: ActiveDownload, seg: SegState) {
        val now = System.currentTimeMillis()
        if (now - ad.lastNotify >= NOTIFY_INTERVAL_MS) {
            val dt = (now - ad.lastTime).coerceAtLeast(1)
            val db = (ad.bytesDone - ad.lastBytes).coerceAtLeast(0)
            ad.speed = db * 1000 / dt
            ad.lastTime = now
            ad.lastBytes = ad.bytesDone
            ad.lastNotify = now
            infos[ad.id]?.let {
                infos[ad.id] = it.copy(
                    bytesDone = ad.bytesDone, totalBytes = ad.totalBytes,
                    speedBps = ad.speed
                )
            }
            publish()
            DownloadService.updateNotification(context)
        }
        if (now - ad.persistAt >= PERSIST_INTERVAL_MS) {
            ad.persistAt = now
            dao(context).updateSegmentDone(ad.id, seg.index, seg.done)
        }
    }

    private fun onAllSegmentsDone(context: Context, ad: ActiveDownload) {
        val dao = dao(context)
        val segs = ad.segments.size
        val done = ad.completedSegments.get()
        if (done >= segs && !ad.failed.get() && !ad.paused.get()) {
            // Success: publish the file, record completion.
            val uri = ad.target.uri
            if (uri != null) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                        if (ad.totalBytes > 0) put(MediaStore.Downloads.SIZE, ad.totalBytes)
                        put(
                            MediaStore.Downloads.MIME_TYPE,
                            guessMime(ad.fileName, ad.url, ad.mimeType)
                        )
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            dao.updateStatus(ad.id, STATUS_COMPLETED)
            dao.updateEntry(
                ad.id, ad.totalBytes, uri?.toString() ?: ad.target.file?.absolutePath,
                STATUS_COMPLETED, System.currentTimeMillis()
            )
            dao.deleteSegments(ad.id)
            infos[ad.id]?.let {
                infos[ad.id] = it.copy(
                    status = STATUS_COMPLETED,
                    bytesDone = ad.totalBytes.coerceAtLeast(ad.bytesDone),
                    speedBps = 0
                )
            }
        } else if (ad.paused.get()) {
            // pause already persisted by doPause()
        } else {
            dao.updateStatus(ad.id, STATUS_FAILED)
            infos[ad.id]?.let { infos[ad.id] = it.copy(status = STATUS_FAILED, speedBps = 0) }
        }
        active.remove(ad.id)
        runCatching { ad.target.close() }
        publish()
        DownloadService.sync(context)
    }

    private fun doPause(context: Context, id: Long) {
        val ad = active.remove(id) ?: return
        ad.paused.set(true)
        // Unblock the reader threads by disconnecting their sockets.
        ad.segments.forEach { seg ->
            runCatching { seg.conn?.disconnect() }
            seg.conn = null
        }
        val dao = dao(context)
        ad.segments.forEach { seg ->
            dao.updateSegmentDone(id, seg.index, seg.done)
        }
        dao.updateStatus(id, STATUS_PAUSED)
        runCatching { ad.target.close() }
        infos[id]?.let { infos[id] = it.copy(status = STATUS_PAUSED, speedBps = 0) }
        publish()
        DownloadService.sync(context)
    }

    private fun doCancel(context: Context, id: Long) {
        val ad = active.remove(id)
        if (ad != null) {
            ad.status.set(false)
            ad.paused.set(true)
            ad.segments.forEach { runCatching { it.conn?.disconnect() } }
            runCatching { ad.target.close() }
        }
        val dao = dao(context)
        val entry = dao.getEntry(id)
        entry?.savedUri?.let { deleteSaved(context, it) }
        dao.deleteSegments(id)
        dao.deleteEntry(id)
        infos.remove(id)
        publish()
        DownloadService.sync(context)
    }

    private fun deleteSaved(context: Context, savedUri: String) {
        runCatching {
            val uri = Uri.parse(savedUri)
            if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
            else File(savedUri).delete()
        }
    }

    // ------------------------------------------------------------ storage

    private fun openTarget(context: Context, entry: DownloadEntry): Target {
        // Reopen an existing partial file on resume.
        entry.savedUri?.let { saved ->
            val reopen = openExisting(context, saved)
            if (reopen != null) return reopen
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return openMediaStore(context, entry) ?: openPrivateFile(context, entry)
        }
        return openPrivateFile(context, entry)
    }

    private fun openExisting(context: Context, savedUri: String): Target? {
        return try {
            val uri = Uri.parse(savedUri)
            if (uri.scheme == "content") {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
                val channel = FileOutputStream(pfd.fileDescriptor).channel
                Target(channel, uri, null) { runCatching { channel.close() } }
            } else {
                val f = File(savedUri)
                if (f.exists()) {
                    val channel = FileOutputStream(f, true).channel
                    Target(channel, null, f) { runCatching { channel.close() } }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the best MIME type for a downloaded file: the file name's
     * extension first (so .apk always maps to the package-archive type and
     * .pdf to application/pdf), then the URL, then the stored value if it is
     * specific. Never returns null — callers get a catch-all type when
     * unknown.
     */
    fun guessMime(fileName: String?, url: String?, stored: String?): String {
        fun fromName(name: String): String? {
            val ext = MimeTypeMap.getFileExtensionFromUrl(name)?.lowercase(Locale.US)
            return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        }
        if (fileName?.endsWith(".apk", ignoreCase = true) == true) {
            return "application/vnd.android.package-archive"
        }
        return fromName(fileName.orEmpty())
            ?: url?.let { fromName(it) }
            ?: stored?.takeUnless { it == "application/octet-stream" }
            ?: "*/*"
    }

    private fun openMediaStore(context: Context, entry: DownloadEntry): Target? {
        return try {
            val mime = guessMime(entry.fileName, entry.url, entry.mimeType)
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = context.contentResolver
            // Avoid silent "name (1).apk" surprises: pick a unique name.
            val finalName = uniqueMediaStoreName(resolver, collection, entry.fileName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            if (finalName != entry.fileName) {
                dao(context).updateFileName(entry.id, finalName)
                infos[entry.id]?.let { infos[entry.id] = it.copy(fileName = finalName) }
            }
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: run { resolver.delete(uri, null, null); return null }
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            Target(channel, uri, null) {
                runCatching { channel.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore target failed", e)
            null
        }
    }

    private fun uniqueMediaStoreName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        name: String
    ): String {
        val existing = HashSet<String>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) existing.add(c.getString(col))
            }
        }
        if (!existing.contains(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (existing.contains("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    private fun openPrivateFile(context: Context, entry: DownloadEntry): Target {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val f = File(dir, uniqueName(dir, entry.fileName))
        val channel = FileOutputStream(f, true).channel
        return Target(channel, null, f) { runCatching { channel.close() } }
    }

    private fun uniqueName(dir: File, name: String): String {
        val candidate = File(dir, name)
        if (!candidate.exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            val next = File(dir, "$base ($i)$ext")
            if (!next.exists()) return next.name
            i++
        }
        return name
    }

    // ------------------------------------------------------------- helpers

    private fun probeSize(url: String, userAgent: String?, context: Context): Long? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent ?: UA_FALLBACK)
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { setRequestProperty("Cookie", it) }
            }
            try {
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.contentLength.toLong()
                } else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSegments(total: Long?): List<Pair<Long, Long>> {
        if (total == null || total <= SEGMENT_MIN_SIZE) return listOf(0L to -1L)
        val count = minOf(MAX_SEGMENTS, maxOf(1, (total / SEGMENT_MIN_SIZE).toInt()))
        if (count <= 1) return listOf(0L to -1L)
        val per = total / count
        return (0 until count).map { i ->
            val start = i * per
            val end = if (i == count - 1) total - 1 else (i + 1) * per - 1
            start to end
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header == null) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    private fun setStatus(dao: DownloadDao, id: Long, status: String) {
        dao.updateStatus(id, status)
    }

    // DB access: engine always runs on its own threads; the context passed
    // in is always an application context (never an activity, so long-lived
    // download threads cannot leak it).
    private fun dao(context: Context): DownloadDao =
        DownloadDatabase.getDatabase(context.applicationContext).downloadDao()

    private fun io(task: () -> Unit) {
        Thread(task, "jelly-dl-engine").apply { isDaemon = true }.start()
    }

    private fun publish() {
        _state.value = infos.values.sortedByDescending { it.id }
    }

    private fun DownloadEntry.toInfo(bytesDone: Long, speed: Long) = Info(
        id, url, fileName, mimeType, totalBytes, bytesDone, status, speed, savedUri
    )

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
