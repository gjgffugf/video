package com.zilizili.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.URI
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
    val rawHead: String = "",
    val waf: String = "",                 // 识别出的拦截方，如 "Cloudflare JS 挑战"
    val isJsChallenge: Boolean = false    // 是否是需要执行 JS 的人机验证
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

    /** 常见拦截方指纹，用来判断到底是谁在挡 */
    private val WAF_SIGNATURES = listOf(
        "iFastNet / InfinityFree 浏览器验证（免费版强制，无法关闭）" to listOf(
            "aes.js", "this site requires javascript", "requires javascript to work",
            "infinityfree", "ifastnet", "?i=1"
        ),
        "Cloudflare（JS 挑战 / 盾)" to listOf(
            "__cf_chl", "cf-browser-verification", "challenge-platform",
            "cf_chl_opt", "Just a moment", "Checking your browser", "cf-ray"
        ),
        "Pangolin 网关（策略拒绝）" to listOf(
            "policy_default_denied", "Request denied", "main-policy", "pangolin"
        ),
        "Incapsula / Imperva" to listOf("_incapsula_", "Incapsula", "visid_incap"),
        "Sucuri" to listOf("Sucuri", "sucuri_cloudproxy"),
        "ModSecurity / 云 WAF" to listOf("ModSecurity", "mod_security", "yunsuo", "safe3waf", "jiasule"),
        "国内云 WAF 人机验证" to listOf(
            "正在验证", "安全验证", "人机验证", "访问验证", "请输入验证码", "滑动验证"
        )
    )

    /** 返回识别出的拦截方名称，识别不出返回空串 */
    fun detectWaf(body: String): String {
        if (body.isBlank()) return ""
        val head = body.take(8192).lowercase()
        for ((name, sigs) in WAF_SIGNATURES) {
            if (sigs.any { head.contains(it.lowercase()) }) return name
        }
        return ""
    }

    /**
     * 是否是"必须执行 JS 才能通过"的人机验证页。
     * 这类页面浏览器能过、普通 HTTP 客户端过不了 —— 只能靠放行规则解决，
     * 不该也不能用脚本去破解。
     */
    fun isJsChallenge(body: String): Boolean {
        if (body.isBlank()) return false
        val head = body.take(8192).lowercase()
        val signs = listOf(
            "cf-browser-verification", "challenge-platform", "__cf_chl", "cf_chl_opt",
            "just a moment", "checking your browser",
            "_incapsula_", "sucuri_cloudproxy",
            "enable javascript", "javascript required", "请开启javascript",
            "this site requires javascript", "requires javascript to work", "aes.js",
            "正在验证", "安全验证", "人机验证", "滑动验证", "请输入验证码"
        )
        val hasScript = head.contains("<script")
        return hasScript && signs.any { head.contains(it) }
    }
}

/** 简易 Cookie 会话：按域名存，跨请求复用 */
object SessionCookieJar : CookieJar {

    private val store = HashMap<String, MutableList<Cookie>>()

    /** WebView 拿到的原始 Cookie 串，按 host 存 */
    private val rawByHost = HashMap<String, String>()

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

    /**
     * 把 WebView 拿到的原始 Cookie 串灌进来（形如 "a=1; b=2"），
     * 拦截器会在后续请求里直接带 Cookie 头。
     *
     * 这里刻意不用 okhttp3 的 String.toHttpUrlOrNull() / Cookie.parse() ——
     * 那些是 OkHttp 4.x Kotlin 扩展，部分环境下解析不到，容易编译失败。
     * 直接存原始字符串最稳。
     */
    @Synchronized
    fun putRaw(host: String, raw: String) {
        if (host.isBlank() || raw.isBlank()) return
        rawByHost[host] = raw.trim()
    }

    @Synchronized
    fun getRaw(host: String): String = rawByHost[host].orEmpty()

    @Synchronized
    fun clear() {
        store.clear()
        rawByHost.clear()
    }
}

/** 取 URL 的 host，解析失败返回空串（用 java.net.URI，不依赖 OkHttp 的 Kotlin 扩展） */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

/** 给每个请求套上浏览器头，并带上 WebView 取到的 Cookie */
class BrowserHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val built = req.newBuilder().apply {
            Browser.headers(req.url.toString()).forEach { (k, v) ->
                // 已存在的头不覆盖
                if (req.header(k) == null) header(k, v)
            }
            // WebView 过验证后拿到的 Cookie，直接作为请求头带上
            val host = hostOf(req.url.toString())
            if (host.isNotBlank() && req.header("Cookie") == null) {
                val raw = SessionCookieJar.getRaw(host)
                if (raw.isNotBlank()) header("Cookie", raw)
            }
        }.build()
        return chain.proceed(built)
    }
}

/** 与 PHP 服务端通信 */
object Api {

    /** 播放器复用同一个 client，这样视频请求自动带上 Cookie 与浏览器头 */
    val httpClient: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
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
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    return FeedResult(
                        ok = false,
                        error = "HTTP ${resp.code}",
                        rawHead = body.take(400),
                        waf = Browser.detectWaf(body),
                        isJsChallenge = Browser.isJsChallenge(body)
                    )
                }
                if (body.isBlank()) {
                    return FeedResult(ok = false, error = "响应内容为空")
                }
                if (Browser.looksBlocked(body)) {
                    val waf = Browser.detectWaf(body)
                    val js = Browser.isJsChallenge(body)
                    return FeedResult(
                        ok = false,
                        error = if (js) "被人机验证页拦截（需执行 JS）" else "疑似被 WAF / 反爬页拦截",
                        rawHead = body.take(400),
                        waf = waf,
                        isJsChallenge = js
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

    /**
     * 三级降级拉取：
     *   1) OkHttp 直连（快）
     *   2) 失败 -> 用 WebView 打开站点过验证，把 Cookie 灌回 OkHttp，再试一次
     *   3) 仍失败 -> 直接在 WebView 里用 JS fetch 拿响应文本
     *
     * @param context 用于创建 WebView（内部已转 applicationContext，不会泄漏 Activity）
     */
    suspend fun feedSmart(context: Context, endpoint: String, count: Int): FeedResult {
        val origin = WebSession.originOf(endpoint)
        val url = endpoint.trimEnd('/') + "?action=feed&count=$count"

        // ---- 1) OkHttp 直连 ----
        val first = feed(endpoint, count)
        if (first.ok) return first

        // ---- 2) WebView 过验证，拿 Cookie 后重试 ----
        val rawCookie = withTimeoutOrNull(20_000) {
            WebSession.warmUp(context, origin)
        }.orEmpty()

        if (rawCookie.isNotBlank()) {
            SessionCookieJar.putRaw(hostOf(origin), rawCookie)
            val second = feed(endpoint, count)
            if (second.ok) return second
            // ---- 3) WebView 内 JS fetch ----
            val text = withTimeoutOrNull(25_000) {
                WebSession.fetchText(context, origin, url)
            }
            if (!text.isNullOrBlank()) {
                val parsed = parse(text)
                if (parsed.ok && parsed.items.isNotEmpty()) return parsed
                if (parsed.ok) return second.copy(error = "服务端返回 0 条视频")
                return parsed
            }
            return second
        }

        // Cookie 都没拿到，直接走 WebView fetch 兜底
        val text = withTimeoutOrNull(25_000) {
            WebSession.fetchText(context, origin, url)
        }
        if (!text.isNullOrBlank()) return parse(text)

        return first.copy(
            error = first.error + "（WebView 兜底也未取到内容）"
        )
    }

    /** 诊断用：返回服务端原始响应的前 500 字符 */
    fun diag(endpoint: String): String {
        val url = endpoint.trimEnd('/') + "?action=feed&count=1"
        return try {
            warmUp(endpoint)
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
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
            httpClient.newCall(request).execute().use { /* 只建立会话，忽略内容 */ }
        } catch (e: Exception) {
            // 暖机失败无所谓，继续走主请求
        }
    }

    /**
     * 取站点根地址（scheme://host[:port]）
     * 用 java.net.URI 而不是 okhttp HttpUrl ——
     * OkHttp 4.x 的 Kotlin API 里 HttpUrl.parse() 已改为 String.toHttpUrl()，
     * 且 scheme()/host()/port() 变成了属性，直接调用会编译报错。
     */
    private fun originOf(endpoint: String): String? {
        return try {
            val u = URI(endpoint)
            val scheme = u.scheme
            val host = u.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) return null
            val p = u.port
            val port = if (p == -1 || p == 80 || p == 443) "" else ":$p"
            "$scheme://$host$port"
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
