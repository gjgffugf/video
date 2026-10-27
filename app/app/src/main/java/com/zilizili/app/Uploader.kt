package com.zilizili.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/** 上传进度 */
data class UploadProgress(
    val stage: String,          // hashing / uploading / finishing / done
    val uploadedBytes: Long,
    val totalBytes: Long
) {
    val percent: Int get() =
        if (totalBytes <= 0) 0 else ((uploadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** 上传结果 */
data class UploadResult(
    val ok: Boolean,
    val sha1: String = "",
    val url: String = "",
    val instant: Boolean = false,
    val error: String = ""
)

/** 单文件上限 250MB */
const val MAX_UPLOAD_BYTES = 250L * 1024 * 1024

/** 单分片大小 2MB —— 虚拟主机 post_max_size 通常 2~20M，2MB 最稳 */
const val CHUNK_SIZE = 2 * 1024 * 1024

private val OCTET_STREAM = "application/octet-stream".toMediaType()

object Uploader {

    /** 读取 Uri 对应的字节数 */
    fun sizeOf(context: Context, uri: Uri): Long {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) {
                val v = c.getLong(idx)
                if (v > 0) return v
            }
        }
        // 兜底：直接量一遍流长度
        return runCatching {
            resolver.openInputStream(uri)?.use { countBytes(it) } ?: 0L
        }.getOrDefault(0L)
    }

    /** 友好文件名 */
    fun nameOf(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val v = c.getString(idx)
                if (!v.isNullOrBlank()) return v
            }
        }
        return uri.lastPathSegment ?: "upload.mp4"
    }

    private fun countBytes(input: InputStream): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
        }
        return total
    }

    /**
     * 流式算 SHA-1 —— 250MB 文件不能一次读进内存，
     * 所以分块喂给 MessageDigest。
     */
    suspend fun sha1Of(
        context: Context,
        uri: Uri,
        onProgress: suspend (Long) -> Unit = {}
    ): String =
        withContext(Dispatchers.IO) {
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(1 shl 20) // 1MB
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    total += n
                    if (total % (8 * 1024 * 1024) < buf.size) onProgress(total)
                }
            }
            onProgress(total)
            // 用 and 0xFF 避免 Byte 有符号导致出现 ffffff80 这类结果
            md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        }

    /**
     * 分片上传：init -> chunk(多次) -> finish
     *
     * @param onProgress 在主线程回调，直接更新进度条即可
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        title: String = "",
        onProgress: (UploadProgress) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {

        val endpoint = BuildConfig.API_BASE
        val token = BuildConfig.UPLOAD_TOKEN

        fun post(url: String, body: RequestBody): String? =
            Api.httpClient.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { it.body?.string() }

        fun form(vararg kv: Pair<String, String>): RequestBody =
            FormBody.Builder().apply { kv.forEach { add(it.first, it.second) } }.build()

        try {
            // ---- 1. 算指纹与大小 ----
            val size = sizeOf(context, uri)
            if (size <= 0) return@withContext UploadResult(false, error = "读不到文件大小")
            if (size > MAX_UPLOAD_BYTES) {
                return@withContext UploadResult(
                    false,
                    error = "文件 %.1f MB 超过上限 %d MB".format(size / 1048576.0, MAX_UPLOAD_BYTES / 1048576)
                )
            }

            val name = nameOf(context, uri)
            val sha1 = sha1Of(context, uri) { done ->
                if (size > 0) {
                    val p = UploadProgress("hashing", done, size)
                    withContext(Dispatchers.Main) { onProgress(p) }
                }
            }

            suspend fun emit(stage: String, uploaded: Long) {
                withContext(Dispatchers.Main) {
                    onProgress(UploadProgress(stage, uploaded, size))
                }
            }

            // ---- 2. init ----
            emit("preparing", 0)
            val initRaw = post(
                endpoint + "?action=upload_init",
                form(
                    "token" to token,
                    "sha1" to sha1,
                    "size" to size.toString(),
                    "name" to name,
                    "title" to title.ifBlank { name },
                    "chunk_size" to CHUNK_SIZE.toString()
                )
            ) ?: return@withContext UploadResult(false, error = "init 无响应")

            val initJson = JSONObject(initRaw)
            if (!initJson.optBoolean("ok", false)) {
                return@withContext UploadResult(
                    false,
                    error = initJson.optString("error", "init 失败") +
                        " " + initJson.optString("tip", "")
                )
            }
            // 秒传命中
            if (initJson.optBoolean("instant", false)) {
                emit("done", size)
                return@withContext UploadResult(
                    ok = true, sha1 = sha1,
                    url = initJson.optString("url"),
                    instant = true
                )
            }

            val uploadId = initJson.optString("upload_id")
            val total = initJson.optInt("total", 0)
            if (uploadId.isBlank() || total <= 0) {
                return@withContext UploadResult(false, error = "init 返回异常")
            }

            // 已收到的分片（断点续传）
            val received = HashSet<Int>()
            val arr = initJson.optJSONArray("received")
            if (arr != null) {
                for (i in 0 until arr.length()) received.add(arr.optInt(i, -1))
            }

            var uploaded = received.size.toLong() * CHUNK_SIZE.toLong()
            if (uploaded > size) uploaded = size
            emit("uploading", uploaded)

            // ---- 3. 逐片上传 ----
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext UploadResult(false, error = "打不开文件流")

            stream.use { input ->
                val buf = ByteArray(CHUNK_SIZE)
                for (index in 0 until total) {
                    if (received.contains(index)) {
                        // 已传过的片要跳过去，但流位置还得前进
                        input.skip(CHUNK_SIZE.toLong())
                        continue
                    }

                    // 读满一个分片
                    var off = 0
                    while (off < CHUNK_SIZE) {
                        val n = input.read(buf, off, CHUNK_SIZE - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off <= 0) break

                    val chunk = if (off == CHUNK_SIZE) buf else buf.copyOf(off)
                    // OkHttp 4.x：MediaType.parse() / RequestBody.create() 都是
                    // ERROR 级废弃，已改为 Kotlin 扩展函数，必须用下面这种写法
                    val body = chunk.toRequestBody(OCTET_STREAM)
                    val r = post(
                        endpoint + "?action=upload_chunk&upload_id=" + uploadId + "&index=" + index,
                        body
                    )
                    if (r.isNullOrBlank()) {
                        return@withContext UploadResult(false, error = "第 $index 片上传失败")
                    }
                    val cj = JSONObject(r)
                    if (!cj.optBoolean("ok", false)) {
                        return@withContext UploadResult(
                            false,
                            error = "第 $index 片失败：" + cj.optString("error", "")
                        )
                    }
                    uploaded += off
                    emit("uploading", minOf(uploaded, size))
                }
            }

            // ---- 4. finish ----
            emit("finishing", size)
            val finRaw = post(
                endpoint + "?action=upload_finish",
                form("token" to token, "upload_id" to uploadId, "sha1" to sha1)
            ) ?: return@withContext UploadResult(false, error = "finish 无响应")

            val finJson = JSONObject(finRaw)
            if (!finJson.optBoolean("ok", false)) {
                return@withContext UploadResult(
                    false,
                    error = finJson.optString("error", "finish 失败") +
                        " " + finJson.optString("tip", "")
                )
            }

            emit("done", size)
            UploadResult(
                ok = true, sha1 = sha1,
                url = finJson.optString("url"),
                instant = false
            )
        } catch (e: Exception) {
            UploadResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
