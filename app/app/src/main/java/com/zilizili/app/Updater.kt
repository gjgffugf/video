package com.zilizili.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 更新信息（对应 videoupdate.json） */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
    val changelog: String,
    val force: Boolean,
    val source: String   // 从哪个源拉到的，便于排查
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val percent: Int get() =
        if (totalBytes <= 0) 0 else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/**
 * 应用内更新
 *
 * raw.githubusercontent.com 国内经常连不上，所以按顺序尝试多个代理镜像，
 * 第一个能返回合法 JSON 的就用它。全程 OkHttp，不依赖浏览器。
 */
object Updater {

    private const val TAG = "zilizili.Updater"

    /** 更新源列表：直连 + 若干公共代理镜像 */
    fun jsonUrls(): List<String> {
        val o = BuildConfig.UPDATE_OWNER
        val r = BuildConfig.UPDATE_REPO
        val b = BuildConfig.UPDATE_BRANCH
        val f = BuildConfig.UPDATE_FILE
        val raw = "https://raw.githubusercontent.com/$o/$r/$b/$f"
        return listOf(
            raw,                                                  // 直连
            "https://cdn.jsdelivr.net/gh/$o/$r@$b/$f",            // jsDelivr（最稳，官方 CDN）
            "https://gh-proxy.com/$raw",                          // gh-proxy
            "https://ghproxy.net/$raw",                           // ghproxy.net
            "https://raw.fastgit.org/$o/$r/$b/$f",                // FastGit
            "https://gh.llkk.cc/$raw"                             // 备用
        ).distinct()
    }

    /** 给下载链接套代理前缀（直连失败时依次尝试） */
    fun downloadUrls(apkUrl: String): List<String> {
        val list = ArrayList<String>()
        list.add(apkUrl)
        if (apkUrl.contains("github.com") || apkUrl.contains("githubusercontent.com")) {
            list.add("https://gh-proxy.com/$apkUrl")
            list.add("https://ghproxy.net/$apkUrl")
            list.add("https://gh.llkk.cc/$apkUrl")
        }
        return list.distinct()
    }

    /** 依次尝试各源拉取更新信息，全部失败返回 null */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (url in jsonUrls()) {
            val info = runCatching { fetchJson(url) }.getOrNull()
            if (info != null) {
                Log.d(TAG, "更新信息来自: $url")
                return@withContext info
            }
            Log.d(TAG, "拉取失败: $url")
        }
        null
    }

    private fun fetchJson(url: String): UpdateInfo? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Browser.UA)
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        Api.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank() || body.startsWith("<")) return null

            val j = JSONObject(body)
            val code = j.optInt("versionCode", 0)
            val apk = j.optString("apkUrl")
            if (code <= 0 || apk.isBlank()) return null

            return UpdateInfo(
                versionCode = code,
                versionName = j.optString("versionName", code.toString()),
                apkUrl = apk,
                size = j.optLong("size", 0L),
                changelog = j.optString("changelog", ""),
                force = j.optBoolean("force", false),
                source = url
            )
        }
    }

    /** 是否需要更新 */
    fun hasUpdate(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.APP_VERSION_CODE

    /**
     * 下载 APK 到内部缓存，带进度回调。
     * 直连失败会自动切代理源重试。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "zilizili-v${info.versionName}.apk")
        if (out.exists()) out.delete()

        for (url in downloadUrls(info.apkUrl)) {
            val ok = runCatching {
                downloadOne(url, out, info.size) { p ->
                    withContext(Dispatchers.Main) { onProgress(p) }
                }
            }.getOrDefault(false)
            if (ok && out.exists() && out.length() > 0) return@withContext out
            Log.d(TAG, "下载失败: $url")
            if (out.exists()) out.delete()
        }
        null
    }

    private fun downloadOne(
        url: String,
        out: File,
        declaredSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Browser.UA)
            .get()
            .build()

        Api.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = if (declaredSize > 0) declaredSize else body.contentLength()

            FileOutputStream(out).use { fos ->
                val buf = ByteArray(1 shl 16)
                var sum = 0L
                while (true) {
                    val n = body.byteStream().read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    sum += n
                    onProgress(DownloadProgress(sum, total))
                }
                fos.flush()
            }
            return out.length() > 0
        }
    }

    /** 调起系统安装界面 */
    fun install(context: Context, apk: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", apk
            )
        } else {
            Uri.fromFile(apk)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
