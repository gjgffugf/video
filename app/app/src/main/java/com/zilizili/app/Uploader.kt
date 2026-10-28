package com.zilizili.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
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
/** 封面上限 10MB */
const val MAX_COVER_BYTES = 10L * 1024 * 1024
/** 头像上限 8MB */
const val MAX_AVATAR_BYTES = 8L * 1024 * 1024

private val OCTET_STREAM = "application/octet-stream".toMediaType()

/** 统计真实写入网络的字节数（进度条不是假的） */
private class CountingRequestBody(
    private val delegate: RequestBody,
    private val onBytes: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val counting = object : ForwardingSink(sink) {
            var sent = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onBytes(sent, total)
            }
        }
        val b = counting.buffer()
        delegate.writeTo(b)
        b.flush()
    }
}

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
     * 整文件上传（含封面/简介/标题），带真实进度与速度。
     * onProgress 已在主线程回调。
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

            // 上传前先确保浏览器会话 Cookie 就绪，
            // 否则大文件会被主机的人机验证页拦下来（拿到的是 HTML 不是 JSON）
            Api.ensureSession()

            emit("hashing", 0, size)
            val sha1 = sha1Of(ctx, uri)
            emit("preparing", 0, size)

            val fileBody = object : RequestBody() {
                override fun contentType(): MediaType = OCTET_STREAM
                override fun contentLength(): Long = size
                override fun writeTo(sink: BufferedSink) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        sink.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) { val n = input.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
                        }
                    }
                }
            }

            // 速度 = 每 500ms 采样一次的增量
            var lastSent = 0L
            var lastTs = System.currentTimeMillis()
            val counting = CountingRequestBody(fileBody) { sent, total ->
                val now = System.currentTimeMillis()
                val dt = now - lastTs
                val speed = if (dt >= 500) {
                    val v = ((sent - lastSent) * 1000 / dt).coerceAtLeast(0)
                    lastSent = sent; lastTs = now; v
                } else -1L
                emit("uploading", sent, total, if (speed < 0) 0 else speed)
            }

            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("session", Session.token)
                .addFormDataPart("title", title)
                .addFormDataPart("intro", intro)
                .addFormDataPart("sha1", sha1)
                .addFormDataPart("file", title, counting)

            if (coverUri != null) {
                val cs = sizeOf(ctx, coverUri)
                if (cs > 0 && cs <= MAX_COVER_BYTES) {
                    val bytes = Api.readBytes(ctx, coverUri)
                    if (bytes != null) {
                        val n = nameOf(ctx, coverUri)
                        b.addFormDataPart("cover", n, bytes.toRequestBody(Api.guessImageType(n)))
                    }
                } else {
                    return@withContext UploadResult(false, error = "封面超过 ${MAX_COVER_BYTES / 1048576} MB")
                }
            }
            val multipart = b.build()

            val raw = Api.httpClient.newCall(
                Request.Builder().url(BuildConfig.API_BASE + "?action=upload").post(multipart).build()
            ).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext UploadResult(false,
                        error = "HTTP ${r.code}（多半是 PHP 的 post_max_size / upload_max_filesize 太小）")
                }
                s
            }

            emit("finishing", size, size)
            if (raw.isBlank()) return@withContext UploadResult(false, error = "服务端无响应")
            if (Api.looksHtml(raw)) {
                return@withContext UploadResult(false,
                    error = "服务端返回网页不是 JSON（被主机人机验证拦截）")
            }

            val j = JSONObject(raw)
            if (!j.optBoolean("ok", false)) {
                val tip = j.optString("tip", "")
                return@withContext UploadResult(false,
                    error = j.optString("error", "上传失败") + if (tip.isNotBlank()) " · $tip" else "")
            }

            emit("done", size, size)
            val item = j.optJSONObject("item")
            val v = if (item != null) VideoItem(
                sha1 = item.optString("sha1"),
                title = item.optString("title"),
                intro = item.optString("intro"),
                url = item.optString("url"),
                cover = item.optString("cover"),
                size = item.optLong("size", 0L),
                mime = item.optString("mime", "video/mp4"),
                authorId = item.optString("author_id"),
                authorName = item.optString("author_name"),
                authorAvatar = item.optString("author_avatar"),
                createdAt = item.optString("created_at"),
                likes = item.optInt("likes", 0),
                commentCount = item.optInt("comment_count", 0)
            ) else null
            UploadResult(true, item = v, duplicate = j.optBoolean("duplicate", false))
        } catch (e: Exception) {
            UploadResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
