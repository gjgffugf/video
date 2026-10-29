package com.zilizili.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** 上传进度（含实时速度） */
data class UploadProgress(
    val stage: String,          // hashing / preparing / uploading / finishing / done
    val uploadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L
) {
    val percent: Int get() =
        if (totalBytes <= 0) 0 else ((uploadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    fun speedText(): String {
        val s = speedBytesPerSec
        return when {
            s >= 1048576 -> "%.1f MB/s".format(s / 1048576.0)
            s >= 1024 -> "%.0f KB/s".format(s / 1024.0)
            s > 0 -> "$s B/s"
            else -> ""
        }
    }
}

data class UploadResult(
    val ok: Boolean,
    val item: VideoItem? = null,
    val duplicate: Boolean = false,
    val error: String = ""
)

/** 视频上限 250MB */
const val MAX_UPLOAD_BYTES = 250L * 1024 * 1024
/**
 * 单片大小 2MB。
 *
 * 虚拟主机 PHP 的 post_max_size / upload_max_filesize 通常只有 2~20MB，
 * 整文件一次 POST 传大文件会被直接掐断（报 unexpected end of stream）。
 * 分片上传每片都远小于该限制，是这类主机上唯一稳的做法。
 */
const val CHUNK_SIZE = 2 * 1024 * 1024
/** 封面上限 10MB */
const val MAX_COVER_BYTES = 10L * 1024 * 1024
/** 头像上限 8MB */
const val MAX_AVATAR_BYTES = 8L * 1024 * 1024

private val OCTET_STREAM = "application/octet-stream".toMediaType()


object Uploader {

    fun sizeOf(ctx: Context, uri: Uri): Long {
        val r = ctx.contentResolver
        r.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) { val v = c.getLong(i); if (v > 0) return v }
        }
        return runCatching { r.openInputStream(uri)?.use { countBytes(it) } ?: 0L }.getOrDefault(0L)
    }

    fun nameOf(ctx: Context, uri: Uri): String {
        val r = ctx.contentResolver
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) { val v = c.getString(i); if (!v.isNullOrBlank()) return v }
        }
        return uri.lastPathSegment ?: "upload.mp4"
    }

    private fun countBytes(input: InputStream): Long {
        val buf = ByteArray(1 shl 16)
        var t = 0L
        while (true) { val n = input.read(buf); if (n <= 0) break; t += n }
        return t
    }

    /** 流式算 SHA-1：250MB 不进内存 */
    suspend fun sha1Of(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 20)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    /**
     * 分片上传（含封面/简介/标题），带真实进度与速度。
     *
     * 走 init -> chunk(多次) -> finish 三段式：
     * 单片 2MB，绕开虚拟主机 PHP 的 post_max_size 限制，
     * 避免整文件 POST 被掐断报 unexpected end of stream。
     * 支持秒传（服务端已有同 sha1）与断点续传（跳过已收到的片）。
     *
     * onProgress 已在主线程回调，可直接更新 UI。
     */
    suspend fun upload(
        ctx: Context,
        uri: Uri,
        title: String,
        intro: String,
        coverUri: Uri?,
        onProgress: (UploadProgress) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {

        val main = Handler(Looper.getMainLooper())
        fun emit(stage: String, sent: Long, total: Long, speed: Long = 0L) {
            main.post { onProgress(UploadProgress(stage, sent, total, speed)) }
        }

        try {
            val size = sizeOf(ctx, uri)
            if (size <= 0) return@withContext UploadResult(false, error = "读不到文件大小")
            if (size > MAX_UPLOAD_BYTES) {
                return@withContext UploadResult(false,
                    error = "文件 %.1f MB 超过上限 %d MB".format(size / 1048576.0, MAX_UPLOAD_BYTES / 1048576))
            }

            // 上传前先确保浏览器会话 Cookie 就绪。
            // 这一步可能要开 WebView 跑验证，耗时几秒，必须先 emit 一个状态，
            // 否则界面看起来像"点了没反应"。
            emit("warming", 0, size)
            Api.ensureSession()

            emit("hashing", 0, size)
            val sha1 = sha1Of(ctx, uri)
            emit("preparing", 0, size)

            val ep = BuildConfig.API_BASE

            // ---------- 1) init ----------
            val initRaw = Api.postFormRaw(ep + "?action=upload_init", linkedMapOf(
                "sha1" to sha1,
                "size" to size.toString(),
                "name" to title,
                "title" to title,
                "intro" to intro,
                "chunk_size" to CHUNK_SIZE.toString()
            ))
            if (initRaw.isBlank() || Api.looksHtml(initRaw)) {
                return@withContext UploadResult(false, error = "连接服务端失败（或被主机验证拦截）")
            }
            val initJson = JSONObject(initRaw)
            if (!initJson.optBoolean("ok", false)) {
                return@withContext UploadResult(false,
                    error = "初始化失败：" + initJson.optString("error", "") +
                        " " + initJson.optString("tip", ""))
            }

            // 秒传：服务端已有同 sha1 且文件还在
            if (initJson.optBoolean("instant", false)) {
                emit("done", size, size)
                val it = JSONObject()
                it.put("sha1", sha1)
                it.put("title", title)
                it.put("url", initJson.optString("url"))
                return@withContext UploadResult(true,
                    item = parseItem(it), duplicate = true)
            }

            val uploadId = initJson.optString("upload_id")
            val total = initJson.optInt("total", 0)
            if (uploadId.isBlank() || total <= 0) {
                return@withContext UploadResult(false, error = "初始化返回异常")
            }

            // 断点续传：跳过已收到的分片
            val received = HashSet<Int>()
            val arr = initJson.optJSONArray("received")
            if (arr != null) for (i in 0 until arr.length()) received.add(arr.optInt(i, -1))

            var uploaded = 0L
            for (i in received) uploaded += minOf(CHUNK_SIZE.toLong(), size - i * CHUNK_SIZE.toLong())
            emit("uploading", uploaded, size)

            // ---------- 2) 逐片上传 ----------
            var lastSent = uploaded
            var lastTs = System.currentTimeMillis()

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(CHUNK_SIZE)
                var idx = 0
                while (idx < total) {
                    var off = 0
                    while (off < CHUNK_SIZE) {
                        val n = input.read(buf, off, CHUNK_SIZE - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off <= 0) break

                    // 服务端已有此片则跳过（断点续传）
                    if (!received.contains(idx)) {
                        val chunk = if (off == CHUNK_SIZE) buf else buf.copyOf(off)
                        val r = Api.postBytes(
                            ep + "?action=upload_chunk&upload_id=" +
                                java.net.URLEncoder.encode(uploadId, "UTF-8") +
                                "&index=" + idx,
                            chunk, OCTET_STREAM
                        )
                        if (r.isBlank() || Api.looksHtml(r)) {
                            return@withContext UploadResult(false,
                                error = "第 $idx 片上传失败（连接被中断）")
                        }
                        val cj = JSONObject(r)
                        if (!cj.optBoolean("ok", false)) {
                            return@withContext UploadResult(false,
                                error = "第 $idx 片失败：" + cj.optString("error", ""))
                        }
                    }

                    uploaded += off
                    val now = System.currentTimeMillis()
                    val dt = now - lastTs
                    val speed = if (dt >= 500) {
                        val v = ((uploaded - lastSent) * 1000 / dt).coerceAtLeast(0)
                        lastSent = uploaded; lastTs = now; v
                    } else 0L
                    emit("uploading", minOf(uploaded, size), size, speed)
                    idx++
                }
            }

            // ---------- 3) finish ----------
            emit("finishing", size, size)
            val finRaw = Api.postFormRaw(ep + "?action=upload_finish", linkedMapOf(
                "upload_id" to uploadId, "sha1" to sha1
            ))
            if (finRaw.isBlank() || Api.looksHtml(finRaw)) {
                return@withContext UploadResult(false, error = "合并分片失败（连接被中断）")
            }
            val finJson = JSONObject(finRaw)
            if (!finJson.optBoolean("ok", false)) {
                return@withContext UploadResult(false,
                    error = "合并失败：" + finJson.optString("error", "") +
                        " " + finJson.optString("tip", ""))
            }

            // 封面单独上传（分片流程不支持带封面）
            if (coverUri != null) {
                val cs = sizeOf(ctx, coverUri)
                if (cs > 0 && cs <= MAX_COVER_BYTES) {
                    Api.updateWork(sha1, null, coverUri, ctx)
                }
            }

            emit("done", size, size)
            val itemObj = finJson.optJSONObject("item")
            UploadResult(true, item = if (itemObj != null) parseItem(itemObj) else null)
        } catch (e: Exception) {
            UploadResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 把服务端返回的 item JSON 解析成 VideoItem */
    private fun parseItem(j: JSONObject): VideoItem = VideoItem(
        sha1 = j.optString("sha1"),
        title = j.optString("title"),
        intro = j.optString("intro"),
        url = j.optString("url"),
        cover = j.optString("cover"),
        size = j.optLong("size", 0L),
        mime = j.optString("mime", "video/mp4"),
        authorId = j.optString("author_id"),
        authorName = j.optString("author_name"),
        authorAvatar = j.optString("author_avatar"),
        createdAt = j.optString("created_at"),
        likes = j.optInt("likes", 0),
        commentCount = j.optInt("comment_count", 0)
    )
}
