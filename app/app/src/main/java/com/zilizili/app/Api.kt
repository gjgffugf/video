package com.zilizili.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一条视频 */
data class VideoItem(
    val sha1: String,
    val title: String,
    var intro: String,
    var url: String,
    var cover: String,
    val size: Long,
    val mime: String,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String,
    val createdAt: String,
    var likes: Int = 0,
    var liked: Boolean = false,
    var commentCount: Int = 0
)

/** 用户资料 */
data class UserInfo(
    val id: String,
    val username: String,
    val bio: String,
    val avatar: String,
    val createdAt: String
)

/** 一条评论：原始形如 "@xxx:内容" */
data class CommentItem(
    val index: Int,
    val author: String,
    val text: String,
    val raw: String,
    val isAuthor: Boolean
)

data class FeedResult(
    val ok: Boolean,
    val items: List<VideoItem> = emptyList(),
    val error: String = "",
    val rawHead: String = "",
    val waf: String = "",
    val isJsChallenge: Boolean = false
)

/** 通用结果 */
data class SimpleResult(val ok: Boolean, val error: String = "", val extra: JSONObject? = null)

/** 浏览器伪装请求头 */
object Browser {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val BLOCK = listOf(
        "__cf_chl", "cf-browser-verification", "challenge-platform", "cf_chl_opt",
        "Just a moment", "Checking your browser", "_incapsula_", "Sucuri", "ModSecurity",
        "yunsuo", "policy_default_denied", "Request denied",
        "正在验证", "安全验证", "人机验证", "请输入验证码", "滑动验证",
        "aes.js", "This site requires Javascript", "infinityfree", "ifastnet"
    )

    fun headers(): Map<String, String> = linkedMapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    fun looksBlocked(body: String): Boolean {
        if (body.isBlank()) return false
        val h = body.take(4096).lowercase()
        return BLOCK.any { h.contains(it.lowercase()) }
    }

    private val WAF = listOf(
        "iFastNet / InfinityFree 浏览器验证（免费版强制，无法关闭）" to
            listOf("aes.js", "this site requires javascript", "infinityfree", "ifastnet", "?i=1"),
        "Cloudflare（JS 挑战）" to
            listOf("__cf_chl", "cf-browser-verification", "challenge-platform", "just a moment"),
        "Pangolin 网关（策略拒绝）" to listOf("policy_default_denied", "request denied", "main-policy")
    )

    fun detectWaf(body: String): String {
        if (body.isBlank()) return ""
        val h = body.take(8192).lowercase()
        for ((n, s) in WAF) if (s.any { h.contains(it.lowercase()) }) return n
        return ""
    }

    fun isJsChallenge(body: String): Boolean {
        if (body.isBlank()) return false
        val h = body.take(8192).lowercase()
        val signs = listOf("cf-browser-verification", "challenge-platform", "__cf_chl",
            "just a moment", "checking your browser", "this site requires javascript",
            "aes.js", "enable javascript")
        return h.contains("<script") && signs.any { h.contains(it) }
    }
}

/** 本地会话存储 */
object Session {
    private const val PREF = "zlz_session"
    private const val K_TOKEN = "token"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_BIO = "bio"
    private const val K_AVATAR = "avatar"

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        token = sp.getString(K_TOKEN, "").orEmpty()
        val id = sp.getString(K_ID, "").orEmpty()
        me = if (id.isBlank()) null else UserInfo(
            id = id,
            username = sp.getString(K_NAME, "").orEmpty(),
            bio = sp.getString(K_BIO, "").orEmpty(),
            avatar = sp.getString(K_AVATAR, "").orEmpty(),
            createdAt = ""
        )
    }

    fun save(ctx: Context, tok: String, u: UserInfo) {
        token = tok
        me = u
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(K_TOKEN, tok)
            .putString(K_ID, u.id)
            .putString(K_NAME, u.username)
            .putString(K_BIO, u.bio)
            .putString(K_AVATAR, u.avatar)
            .apply()
    }

    fun clear(ctx: Context) {
        token = ""
        me = null
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Volatile
    var token: String = ""

    @Volatile
    var me: UserInfo? = null

    fun isLogin(): Boolean = token.isNotBlank() && me != null
}

object Api {

    /** Application 上下文，供 WebView 预热使用 */
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
    }

    /** 站点根地址 */
    private fun origin(): String = WebSession.originOf(BuildConfig.API_BASE)

    /**
     * 把 WebView（CookieManager）里的 Cookie 带给 OkHttp。
     *
     * 这样只要 WebView 过一次验证，之后所有 OkHttp 请求（包括大文件上传）
     * 都能自动带上验证 Cookie，不必每次都走 WebView。
     */
    private class BrowserCookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val b = req.newBuilder()
            Browser.headers().forEach { (k, v) -> if (req.header(k) == null) b.header(k, v) }

            // 从系统 CookieManager 取（WebView 验证通过后写进去的）
            var raw = ""
            runCatching { raw = CookieManager.getInstance().getCookie(req.url.toString()) ?: "" }
            if (raw.isNotBlank() && req.header("Cookie") == null) b.header("Cookie", raw)

            if (Session.token.isNotBlank() && req.header("X-ZLZ-Session") == null) {
                b.header("X-ZLZ-Session", Session.token)
            }
            return chain.proceed(b.build())
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(BrowserCookieInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun ep(): String = BuildConfig.API_BASE

    // ---------- 底层传输 ----------

    /** 响应是否是验证页 / 拦截页（HTML），而不是我们要的 JSON */
    private fun blocked(s: String?): Boolean {
        if (s.isNullOrBlank()) return true
        val t = s.trimStart()
        if (t.startsWith("<")) return true
        return Browser.looksBlocked(s)
    }

    private fun rawGet(url: String): String? = try {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful && s.isBlank()) null else s
        }
    } catch (e: Exception) { null }

    private fun rawPost(url: String, body: RequestBody): String? = try {
        httpClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful && s.isBlank()) null else s
        }
    } catch (e: Exception) { null }

    /** ---- 对外暴露底层传输，供 Uploader 做分片上传 ---- */

    // 注意：rawGet / rawPost 返回的是 String?（失败时为 null），
    // 这里统一用 orEmpty() 转成非空 String，避免可空类型不匹配。
    suspend fun getRaw(url: String): String = withContext(Dispatchers.IO) {
        runCatching { rawGet(url) }.getOrNull() ?: ""
    }

    suspend fun postFormRaw(url: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            runCatching { rawPost(url, form(params)) }.getOrNull() ?: ""
        }

    /** 分片是裸字节 body，session 通过拦截器自动带 X-ZLZ-Session 头 */
    suspend fun postBytes(url: String, bytes: ByteArray, type: MediaType): String =
        withContext(Dispatchers.IO) {
            runCatching { rawPost(url, bytes.toRequestBody(type)) }.getOrNull() ?: ""
        }

    /**
     * 对外：上传等大请求前先确保浏览器会话 Cookie 就绪。
     * 已经预热过就直接返回，不会重复开 WebView。
     */
    suspend fun ensureSession() {
        if (WebSession.warmed) return
        runCatching { ensureWarm() }
    }

    /** 响应是否是 HTML（验证页）而非 JSON */
    fun looksHtml(s: String): Boolean = blocked(s)

    /** 用 WebView 过一次验证，把 Cookie 落到 CookieManager */
    private suspend fun ensureWarm(): Boolean {
        val ctx = appCtx ?: return false
        if (WebSession.warmed) return true
        val o = origin()
        if (o.isBlank()) return false
        WebSession.warmUp(ctx, o)
        return WebSession.warmed
    }

    /**
     * 统一 GET：OkHttp →（被拦）WebView 预热带 Cookie 重试 →（还不行）WebView 内 fetch
     */
    private suspend fun get(url: String): String {
        var s = rawGet(url)
        if (!blocked(s)) return s ?: ""

        if (ensureWarm()) {
            s = rawGet(url)
            if (!blocked(s)) return s ?: ""
        }

        val ctx = appCtx ?: return ""
        val o = origin()
        if (o.isBlank()) return ""
        return WebSession.fetchGet(ctx, o, url)
    }

    /**
     * 统一 POST 表单：同样的三级降级
     */
    private suspend fun postForm(url: String, params: Map<String, String>): String {
        var s = rawPost(url, form(params))
        if (!blocked(s)) return s ?: ""

        if (ensureWarm()) {
            s = rawPost(url, form(params))
            if (!blocked(s)) return s ?: ""
        }

        val ctx = appCtx ?: return ""
        val o = origin()
        if (o.isBlank()) return ""
        return WebSession.fetchPostForm(ctx, o, url, params)
    }

    /**
     * multipart（上传头像/封面/视频）：体积大，只走 OkHttp，
     * 但会先确保 Cookie 已就绪。
     */
    private suspend fun postMultipart(url: String, body: RequestBody): String {
        ensureWarm()
        return rawPost(url, body) ?: ""
    }

    /** 统一解析：拿不到 JSON 时给出可读的错误，而不是抛异常 */
    private fun json(s: String, url: String): JSONObject {
        if (s.isBlank()) throw IOException("服务端无响应")
        val t = s.trimStart()
        if (t.startsWith("<")) {
            throw IOException(
                "服务端返回的是网页不是 JSON（多半被主机的人机验证拦了）\n" +
                    (if (Browser.detectWaf(s).isNotBlank()) "拦截方：" + Browser.detectWaf(s) else "")
            )
        }
        return JSONObject(s)
    }

    private fun form(kv: Map<String, String>): RequestBody =
        FormBody.Builder().apply { kv.forEach { (k, v) -> add(k, v) } }.build()

    fun formOf(vararg kv: Pair<String, String>): Map<String, String> = linkedMapOf(*kv)

    private fun u(j: JSONObject): UserInfo = UserInfo(
        id = j.optString("id"),
        username = j.optString("username"),
        bio = j.optString("bio"),
        avatar = j.optString("avatar"),
        createdAt = j.optString("created_at")
    )

    private fun v(j: JSONObject): VideoItem = VideoItem(
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
        liked = false,
        commentCount = j.optInt("comment_count", 0)
    )

    private fun vlist(arr: org.json.JSONArray?): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        if (arr == null) return out
        for (i in 0 until arr.length()) out.add(v(arr.getJSONObject(i)))
        return out
    }

    // ---------- 视频 ----------

    suspend fun feed(count: Int): FeedResult = withContext(Dispatchers.IO) {
        try {
            val s = get(ep() + "?action=feed&count=$count")
            val j = json(s, "feed")
            if (!j.optBoolean("ok", false)) {
                return@withContext FeedResult(false, error = j.optString("error", "feed 失败"))
            }
            FeedResult(true, items = vlist(j.optJSONArray("items")))
        } catch (e: Exception) {
            FeedResult(false, error = e.message ?: "未知错误",
                waf = if (e is IOException) "已尝试 WebView 浏览器会话" else "")
        }
    }

    /** 滑动时刷新该视频的最新直链 */
    suspend fun videoInfo(sha1: String): VideoItem? = withContext(Dispatchers.IO) {
        try {
            val j = json(get(ep() + "?action=video_info&sha1=$sha1"), "video_info")
            if (!j.optBoolean("ok", false)) return@withContext null
            v(j.optJSONObject("item") ?: return@withContext null)
        } catch (e: Exception) { null }
    }

    // ---------- 账号 ----------

    @Volatile var lastToken: String = ""
    @Volatile var lastUser: UserInfo? = null

    suspend fun register(name: String, pw: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val j = json(
                postForm(ep() + "?action=register",
                    formOf("username" to name, "password" to pw, "password2" to pw)),
                "register"
            )
            if (!j.optBoolean("ok", false)) {
                return@withContext false to mapErr(j.optString("error", "注册失败"), j.optString("tip"))
            }
            lastToken = j.optString("session")
            val uo = j.optJSONObject("user")
            if (uo != null) lastUser = u(uo)
            true to ""
        } catch (e: IOException) {
            false to e.message.orEmpty()
        } catch (e: Exception) {
            false to (e.message ?: "注册异常")
        }
    }

    suspend fun login(account: String, pw: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val j = json(
                postForm(ep() + "?action=login", formOf("account" to account, "password" to pw)),
                "login"
            )
            if (!j.optBoolean("ok", false)) {
                return@withContext false to mapErr(j.optString("error", "登录失败"), j.optString("tip"))
            }
            lastToken = j.optString("session")
            val uo = j.optJSONObject("user")
            if (uo != null) lastUser = u(uo)
            true to ""
        } catch (e: IOException) {
            false to e.message.orEmpty()
        } catch (e: Exception) {
            false to (e.message ?: "登录异常")
        }
    }

    private fun mapErr(err: String, tip: String): String {
        val base = when (err) {
            "username_taken" -> "用户名已被占用"
            "password_mismatch" -> "两次密码不一致"
            "password_short" -> "密码至少 6 位"
            "username_length" -> "用户名需 2-20 字"
            "bad_username" -> "用户名不能包含 @ : , # / \\"
            "user_not_found" -> "账号不存在"
            "bad_password" -> "密码错误"
            "need_login" -> "请先登录"
            "empty_username" -> "请填用户名"
            "empty_password" -> "请填密码"
            "bio_too_long" -> "简介最多 200 字"
            "text_too_long" -> "评论最多 200 字"
            "forbidden" -> "没有权限"
            else -> err
        }
        return if (tip.isNotBlank()) "$base（$tip）" else base
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        try {
            postForm(ep() + "?action=logout", formOf("session" to Session.token))
            true
        } catch (e: Exception) { false }
    }

    suspend fun me(): UserInfo? = withContext(Dispatchers.IO) {
        try {
            val j = json(get(ep() + "?action=me&session=" + Uri.encode(Session.token)), "me")
            if (!j.optBoolean("ok", false)) return@withContext null
            u(j.optJSONObject("user") ?: return@withContext null)
        } catch (e: Exception) { null }
    }

    suspend fun updateProfile(username: String?, bio: String?): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val pm = LinkedHashMap<String, String>()
                pm["session"] = Session.token
                if (username != null) pm["username"] = username
                if (bio != null) pm["bio"] = bio
                val j = json(postForm(ep() + "?action=update_profile", pm), "update_profile")
                if (!j.optBoolean("ok", false)) {
                    return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
                }
                val uo = j.optJSONObject("user")
                if (uo != null) lastUser = u(uo)
                true to ""
            } catch (e: IOException) { false to e.message.orEmpty() }
            catch (e: Exception) { false to (e.message ?: "异常") }
        }

    suspend fun uploadAvatar(ctx: Context, uri: Uri): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            Api.init(ctx)
            val bytes = readBytes(ctx, uri) ?: return@withContext false to "读不到文件"
            val name = Uploader.nameOf(ctx, uri)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("session", Session.token)
                .addFormDataPart("file", name, bytes.toRequestBody(guessImageType(name)))
                .build()
            val j = json(postMultipart(ep() + "?action=upload_avatar", body), "upload_avatar")
            if (!j.optBoolean("ok", false)) {
                return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            }
            val uo = j.optJSONObject("user")
            if (uo != null) lastUser = u(uo)
            true to ""
        } catch (e: IOException) { false to e.message.orEmpty() }
        catch (e: Exception) { false to (e.message ?: "异常") }
    }

    // ---------- 互动 ----------

    suspend fun like(sha1: String, authorId: String): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        try {
            val j = json(postForm(ep() + "?action=like",
                formOf("session" to Session.token, "sha1" to sha1, "author_id" to authorId)), "like")
            if (!j.optBoolean("ok", false)) return@withContext false to 0
            true to j.optInt("likes", 0)
        } catch (e: Exception) { false to 0 }
    }

    suspend fun comments(sha1: String, authorId: String): Pair<List<CommentItem>, Int> =
        withContext(Dispatchers.IO) {
            try {
                val j = json(get(ep() + "?action=comments&sha1=$sha1&author_id=$authorId"), "comments")
                if (!j.optBoolean("ok", false)) return@withContext emptyList<CommentItem>() to 0
                val arr = j.optJSONArray("comments")
                val out = ArrayList<CommentItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(
                            CommentItem(
                                o.optInt("index", i), o.optString("author"),
                                o.optString("text"), o.optString("raw"),
                                o.optBoolean("is_author", false)
                            )
                        )
                    }
                }
                out to j.optInt("likes", 0)
            } catch (e: Exception) { emptyList<CommentItem>() to 0 }
        }

    suspend fun addComment(sha1: String, authorId: String, text: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val j = json(postForm(ep() + "?action=comment",
                    formOf("session" to Session.token, "sha1" to sha1,
                        "author_id" to authorId, "text" to text)), "comment")
                if (!j.optBoolean("ok", false)) {
                    return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
                }
                true to ""
            } catch (e: IOException) { false to e.message.orEmpty() }
            catch (e: Exception) { false to (e.message ?: "异常") }
        }

    // ---------- 我的作品 / 用户空间 ----------

    suspend fun myWorks(): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            val j = json(get(ep() + "?action=my_works&session=" + Uri.encode(Session.token)), "my_works")
            if (!j.optBoolean("ok", false)) return@withContext emptyList()
            vlist(j.optJSONArray("items"))
        } catch (e: Exception) { emptyList() }
    }

    suspend fun userSpace(userId: String): Pair<UserInfo?, List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val j = json(get(ep() + "?action=user_space&uid=" + Uri.encode(userId)), "user_space")
            if (!j.optBoolean("ok", false)) return@withContext (null to emptyList<VideoItem>())
            val uo = j.optJSONObject("user")
            (if (uo != null) u(uo) else null) to vlist(j.optJSONArray("items"))
        } catch (e: Exception) { null to emptyList() }
    }

    suspend fun deleteWork(sha1: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val j = json(postForm(ep() + "?action=delete_work",
                formOf("session" to Session.token, "sha1" to sha1)), "delete_work")
            if (!j.optBoolean("ok", false)) {
                return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            }
            true to ""
        } catch (e: IOException) { false to e.message.orEmpty() }
        catch (e: Exception) { false to (e.message ?: "异常") }
    }

    suspend fun updateWork(sha1: String, intro: String?, coverUri: Uri?, ctx: Context?): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                if (ctx != null) Api.init(ctx)
                val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("session", Session.token)
                    .addFormDataPart("sha1", sha1)
                if (intro != null) b.addFormDataPart("intro", intro)
                if (coverUri != null && ctx != null) {
                    val bytes = readBytes(ctx, coverUri)
                    if (bytes != null) {
                        val n = Uploader.nameOf(ctx, coverUri)
                        b.addFormDataPart("cover", n, bytes.toRequestBody(guessImageType(n)))
                    }
                }
                val j = json(postMultipart(ep() + "?action=update_work", b.build()), "update_work")
                if (!j.optBoolean("ok", false)) {
                    return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
                }
                true to ""
            } catch (e: IOException) { false to e.message.orEmpty() }
            catch (e: Exception) { false to (e.message ?: "异常") }
        }

    // ---------- 工具 ----------

    fun readBytes(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }

    fun guessImageType(name: String): MediaType {
        val e = name.substringAfterLast('.', "").lowercase()
        val t = when (e) {
            "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
        return t.toMediaType()
    }

    /**
     * 分享链接：指向服务端的 play.php
     *
     * 只用 ?sha1= 定位，不放用户名/视频名/外链。
     * 原因：链接被浏览器、微信、备忘录转发时会被反复 URL 编码，
     * %2F 会变成 %252F，名字类参数必然匹配失败。
     * 而 sha1 是纯十六进制，无论怎么编码都不会变形。
     */
    fun shareLink(item: VideoItem): String {
        val base = BuildConfig.API_BASE.substringBeforeLast('/')
        return "$base/play.php?sha1=" + item.sha1
    }
}
