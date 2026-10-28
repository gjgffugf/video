package com.zilizili.app

import android.content.Context
import android.net.Uri
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
import org.json.JSONObject
import java.io.IOException
import java.net.URI
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

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                Browser.headers().forEach { (k, v) -> if (req.header(k) == null) b.header(k, v) }
                if (Session.token.isNotBlank() && req.header("X-ZLZ-Session") == null) {
                    b.header("X-ZLZ-Session", Session.token)
                }
                chain.proceed(b.build())
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun ep(): String = BuildConfig.API_BASE

    private fun form(vararg kv: Pair<String, String>): okhttp3.RequestBody =
        FormBody.Builder().apply { kv.forEach { add(it.first, it.second) } }.build()

    private fun call(url: String, body: okhttp3.RequestBody? = null): String? =
        httpClient.newCall(
            Request.Builder().url(url).apply { if (body != null) post(body) else get() }.build()
        ).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            s
        }

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
            val s = call(ep() + "?action=feed&count=$count") ?: return@withContext FeedResult(false, error = "无响应")
            if (Browser.looksBlocked(s)) {
                return@withContext FeedResult(false, error = "疑似被 WAF 拦截",
                    rawHead = s.take(300), waf = Browser.detectWaf(s), isJsChallenge = Browser.isJsChallenge(s))
            }
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext FeedResult(false, error = j.optString("error", "feed 失败"))
            FeedResult(true, items = vlist(j.optJSONArray("items")))
        } catch (e: IOException) {
            FeedResult(false, error = "网络错误：${e.message ?: ""}")
        } catch (e: Exception) {
            FeedResult(false, error = e.message ?: "未知错误")
        }
    }

    /** 滑动到下一个时刷新该视频的最新直链 */
    suspend fun videoInfo(sha1: String): VideoItem? = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=video_info&sha1=$sha1") ?: return@withContext null
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext null
            val o = j.optJSONObject("item") ?: return@withContext null
            v(o)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 账号 ----------

    suspend fun register(name: String, pw: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=register",
                form("username" to name, "password" to pw, "password2" to pw)) ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to j.optString("error", "注册失败").let {
                mapErr(it, j.optString("tip"))
            }
            val tok = j.optString("session")
            val uo = j.optJSONObject("user")
            if (uo != null) lastUser = u(uo)
            lastToken = tok
            true to ""
        } catch (e: Exception) {
            false to (e.message ?: "注册异常")
        }
    }

    suspend fun login(account: String, pw: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=login", form("account" to account, "password" to pw))
                ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "登录失败"), j.optString("tip"))
            lastToken = j.optString("session")
            val uo = j.optJSONObject("user")
            if (uo != null) lastUser = u(uo)
            true to ""
        } catch (e: Exception) {
            false to (e.message ?: "登录异常")
        }
    }

    @Volatile var lastToken: String = ""
    @Volatile var lastUser: UserInfo? = null

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
        try { call(ep() + "?action=logout&session=" + Uri.encode(Session.token), form("x" to "1")); true }
        catch (e: Exception) { false }
    }

    suspend fun me(): UserInfo? = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=me&session=" + Uri.encode(Session.token)) ?: return@withContext null
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext null
            u(j.optJSONObject("user") ?: return@withContext null)
        } catch (e: Exception) { null }
    }

    suspend fun updateProfile(username: String?, bio: String?): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val b = FormBody.Builder().add("session", Session.token)
            if (username != null) b.add("username", username)
            if (bio != null) b.add("bio", bio)
            val s = call(ep() + "?action=update_profile", b.build()) ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            lastUser = u(j.optJSONObject("user") ?: return@withContext true to "")
            true to ""
        } catch (e: Exception) { false to (e.message ?: "异常") }
    }

    suspend fun uploadAvatar(ctx: Context, uri: Uri): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val bytes = readBytes(ctx, uri) ?: return@withContext false to "读不到文件"
            val name = Uploader.nameOf(ctx, uri)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("session", Session.token)
                .addFormDataPart("file", name, bytes.toRequestBody(guessImageType(name)))
                .build()
            val s = call(ep() + "?action=upload_avatar", body) ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            lastUser = u(j.optJSONObject("user") ?: return@withContext true to "")
            true to ""
        } catch (e: Exception) { false to (e.message ?: "异常") }
    }

    // ---------- 互动 ----------

    suspend fun like(sha1: String, authorId: String): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=like",
                form("session" to Session.token, "sha1" to sha1, "author_id" to authorId))
                ?: return@withContext false to 0
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to 0
            true to j.optInt("likes", 0)
        } catch (e: Exception) { false to 0 }
    }

    suspend fun comments(sha1: String, authorId: String): Pair<List<CommentItem>, Int> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=comments&sha1=$sha1&author_id=$authorId")
                ?: return@withContext emptyList<CommentItem>() to 0
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext emptyList<CommentItem>() to 0
            val arr = j.optJSONArray("comments")
            val out = ArrayList<CommentItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(CommentItem(o.optInt("index", i), o.optString("author"),
                        o.optString("text"), o.optString("raw"), o.optBoolean("is_author", false)))
                }
            }
            out to j.optInt("likes", 0)
        } catch (e: Exception) { emptyList<CommentItem>() to 0 }
    }

    suspend fun addComment(sha1: String, authorId: String, text: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=comment",
                form("session" to Session.token, "sha1" to sha1, "author_id" to authorId, "text" to text))
                ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            true to ""
        } catch (e: Exception) { false to (e.message ?: "异常") }
    }

    // ---------- 我的作品 / 用户空间 ----------

    suspend fun myWorks(): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=my_works&session=" + Uri.encode(Session.token))
                ?: return@withContext emptyList()
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext emptyList()
            vlist(j.optJSONArray("items"))
        } catch (e: Exception) { emptyList() }
    }

    suspend fun userSpace(userId: String): Pair<UserInfo?, List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=user_space&uid=" + Uri.encode(userId))
                ?: return@withContext (null to emptyList<VideoItem>())
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext (null to emptyList<VideoItem>())
            val uo = j.optJSONObject("user")
            (if (uo != null) u(uo) else null) to vlist(j.optJSONArray("items"))
        } catch (e: Exception) { null to emptyList() }
    }

    suspend fun deleteWork(sha1: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val s = call(ep() + "?action=delete_work", form("session" to Session.token, "sha1" to sha1))
                ?: return@withContext false to "无响应"
            val j = JSONObject(s)
            if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
            true to ""
        } catch (e: Exception) { false to (e.message ?: "异常") }
    }

    suspend fun updateWork(sha1: String, intro: String?, coverUri: Uri?, ctx: Context?): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
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
                val s = call(ep() + "?action=update_work", b.build()) ?: return@withContext false to "无响应"
                val j = JSONObject(s)
                if (!j.optBoolean("ok", false)) return@withContext false to mapErr(j.optString("error", "失败"), j.optString("tip"))
                true to ""
            } catch (e: Exception) { false to (e.message ?: "异常") }
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

    /** 分享链接：指向服务端的 play.php */
    fun shareLink(item: VideoItem): String {
        val base = BuildConfig.API_BASE.substringBeforeLast('/')
        return if (item.authorName.isNotBlank()) {
            "$base/play.php?video=" + Uri.encode(item.authorName) + "%2F" + Uri.encode(item.title)
        } else {
            "$base/play.php?video-url=" + Uri.encode(item.url)
        }
    }
}
