package com.zilizili.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一条推送视频 */
data class VideoItem(
    val sha1: String,
    val title: String,
    val url: String,
    val cover: String,
    val size: Long,
    val mime: String
)

/** 拉取结果：成功带列表，失败带可读原因 */
data class FeedResult(
    val ok: Boolean,
    val items: List<VideoItem> = emptyList(),
    val error: String = "",
    val rawHead: String = ""
)

/**
 * 浏览器伪装
 * 你的服务端前有 WAF / 反爬页，OkHttp 默认头（UA=okhttp/4.12.0）会被拦。
 * 这里补上和真手机浏览器一致的请求头 + Cookie 会话。
 */
object Browser {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val LANG = "zh-CN,zh;q=0.9,en;q=0.8"

    fun headers(url: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = UA
        m["Accept-Language"] = LANG
        m["Accept"] = "application/json, text/plain, */*"
        m["Sec-Fetch-Dest"] = "empty"
        m["Sec-Fetch-Mode"] = "cors"
        m["Sec-Fetch-Site"] = "same-origin"
        m["sec-ch-ua"] = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
        m["sec-ch-ua-mobile"] = "?1"
        m["sec-ch-ua-platform"] = "\"Android\""
        // 注意：不要手动设 Accept-Encoding，OkHttp 会自己处理 gzip/br
        return m
    }

    /** 反爬页特征 */
    private val BLOCK_MARKERS = listOf(
        "__cf_chl", "cf-browser-verification", "challenge-platform", "cf_chl_opt",
        "Just a moment", "Checking your browser", "Attention Required",
        "_incapsula_", "Incapsula", "Sucuri", "ModSecurity", "yunsuo", "safe3waf",
        "policy_default_denied", "Request denied",
        "enable JavaScript", "javascript required",
        "正在验证", "安全验证", "人机验证", "防火墙", "请输入验证码", "滑动验证"
    )

    fun looksBlocked(body: String): Boolean {
        if (body.isBlank()) return false
        val head = body.take(4096).lowercase()
        return BLOCK_MARKERS.any { head.contains(it.lowercase()) }
    }
}

/** 简易 Cookie 会话：按域名存，跨请求复用 */
object SessionCookieJar : CookieJar {

    private val store = HashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { c ->
            bucket.removeAll { it.name == c.name }
            if (c.expiresAt > System.currentTimeMillis()) bucket.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val bucket = store[url.host] ?: return emptyList()
        bucket.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return bucket.toList()
    }

    @Synchronized
    fun clear() = store.clear()
}

/** 给每个请求套上浏览器头 */
class BrowserHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val built = req.newBuilder().apply {
            Browser.headers(req.url.toString()).forEach { (k, v) ->
                // 已存在的头不覆盖
                if (req.header(k) == null) header(k, v)
            }
        }.build()
        return chain.proceed(built)
    }
}

/** 与 PHP 服务端通信 */
object Api {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar)
        .addInterceptor(BrowserHeadersInterceptor())
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取随机推送列表
     * @param endpoint api.php 的完整地址，例如 https://xfan.ccccocccc.cc/api.php
     */
    fun feed(endpoint: String, count: Int): FeedResult {
        val url = endpoint.trimEnd('/') + "?action=feed&count=$count"

        return try {
            // 暖机：先访问站点首页建立会话，很多 WAF 只拦"无会话直连深层路径"
            warmUp(endpoint)

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    return FeedResult(
                        ok = false,
                        error = "HTTP ${resp.code}",
                        rawHead = body.take(300)
                    )
                }
                if (body.isBlank()) {
                    return FeedResult(ok = false, error = "响应内容为空")
                }
                if (Browser.looksBlocked(body)) {
                    return FeedResult(
                        ok = false,
                        error = "疑似被 WAF / 反爬页拦截",
                        rawHead = body.take(300)
                    )
                }

                parse(body)
            }
        } catch (e: IOException) {
            FeedResult(ok = false, error = "网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            FeedResult(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 诊断用：返回服务端原始响应的前 500 字符 */
    fun diag(endpoint: String): String {
        val url = endpoint.trimEnd('/') + "?action=feed&count=1"
        return try {
            warmUp(endpoint)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                "HTTP ${resp.code}\n" + (resp.body?.string().orEmpty().take(500))
            }
        } catch (e: Exception) {
            "请求异常：${e.message}"
        }
    }

    /** 访问站点根目录拿 Cookie，失败不影响主请求 */
    private fun warmUp(endpoint: String) {
        val origin = originOf(endpoint) ?: return
        try {
            val request = Request.Builder().url("$origin/").get().build()
            client.newCall(request).execute().use { /* 只建立会话，忽略内容 */ }
        } catch (e: Exception) {
            // 暖机失败无所谓，继续走主请求
        }
    }

    private fun originOf(endpoint: String): String? {
        return try {
            val u = HttpUrl.parse(endpoint) ?: return null
            val port = if (u.port() == 80 || u.port() == 443) "" else ":${u.port()}"
            "${u.scheme()}://${u.host()}$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(body: String): FeedResult {
        return try {
            val root = JSONObject(body)
            if (!root.optBoolean("ok", true)) {
                return FeedResult(
                    ok = false,
                    error = root.optString("error", "服务端返回 ok=false"),
                    rawHead = body.take(300)
                )
            }
            val arr = root.optJSONArray("items") ?: return FeedResult(ok = true, items = emptyList())

            val out = ArrayList<VideoItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    VideoItem(
                        sha1 = o.optString("sha1"),
                        title = o.optString("title").ifBlank { "未命名" },
                        url = o.optString("url"),
                        cover = o.optString("cover"),
                        size = o.optLong("size", 0L),
                        mime = o.optString("mime", "video/mp4")
                    )
                )
            }
            FeedResult(ok = true, items = out)
        } catch (e: Exception) {
            FeedResult(
                ok = false,
                error = "JSON 解析失败（可能返回的是拦截页而非 JSON）",
                rawHead = body.take(300)
            )
        }
    }
}
