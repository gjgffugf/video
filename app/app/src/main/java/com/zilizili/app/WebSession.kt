package com.zilizili.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.resume

/**
 * 浏览器内核会话
 *
 * 为什么需要它：iFastNet 免费版强制开启 aes.js 浏览器验证，
 * 要求访问者能执行 JS。OkHttp 只是个 HTTP 客户端，不会执行 JS，
 * 所以永远拿到那张验证页 HTML。
 *
 * WebView 就是 Chromium 内核，会执行 JS、管理 Cookie、处理重定向 ——
 * 它做的事和用户拿手机浏览器打开页面完全一样。
 * 我们在里面过完验证，再把 Cookie 交给 OkHttp 用。
 */
object WebSession {

    @Volatile
    var lastCookies: String = ""
        private set

    /** 预热是否已完成（完成后 OkHttp 带上 Cookie 就能直接通） */
    @Volatile
    var warmed: Boolean = false
        private set

    fun originOf(endpoint: String): String {
        return try {
            val u = URI(endpoint)
            val s = u.scheme
            val h = u.host
            if (s.isNullOrBlank() || h.isNullOrBlank()) return ""
            val p = u.port
            val port = if (p == -1 || p == 80 || p == 443) "" else ":$p"
            "$s://$h$port"
        } catch (e: Exception) {
            ""
        }
    }

    private fun safeDestroy(wv: WebView) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(ctx: Context): WebView {
        val wv = WebView(ctx.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.userAgentString = Browser.UA
        // 让验证页把 Cookie 正常写进 CookieManager
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        return wv
    }

    /**
     * 第 1 步：用 WebView 打开站点首页，让它完成验证并把 Cookie 存下来。
     * 成功返回 Cookie 串，并把 warmed 置为 true。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun warmUp(context: Context, origin: String): String =
        withContext(Dispatchers.Main) {
            if (origin.isBlank()) return@withContext ""

            suspendCancellableCoroutine { cont ->
                val wv = newWebView(context)
                cont.invokeOnCancellation { safeDestroy(wv) }

                val cm = CookieManager.getInstance()
                val handler = Handler(Looper.getMainLooper())

                // 验证页往往会 reload 一次，所以轮询几轮等 Cookie 落地
                val poll = object : Runnable {
                    var n = 0
                    override fun run() {
                        val raw = cm.getCookie(origin) ?: ""
                        if (raw.isNotBlank()) {
                            lastCookies = raw
                            warmed = true
                            cm.flush()
                            if (cont.isActive) cont.resume(raw)
                            safeDestroy(wv)
                        } else if (n++ < 4) {
                            // 别等太久：验证页一般 1~2 秒内就落地 Cookie
                            handler.postDelayed(this, 700)
                        } else {
                            if (cont.isActive) cont.resume("")
                            safeDestroy(wv)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        handler.removeCallbacks(poll)
                        handler.postDelayed(poll, 400)
                    }
                }
                wv.loadUrl(origin + "/")
            }
        }

    /** 在 WebView 上下文里执行 JS 并把结果回传 */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun eval(context: Context, origin: String, js: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val wv = newWebView(context)
                cont.invokeOnCancellation { safeDestroy(wv) }
                var done = false

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun post(text: String) {
                        if (done) return
                        done = true
                        if (cont.isActive) cont.resume(text ?: "")
                        safeDestroy(wv)
                    }
                }, "zlzBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val raw = CookieManager.getInstance().getCookie(origin) ?: ""
                        if (raw.isNotBlank()) { lastCookies = raw; warmed = true }
                        view.evaluateJavascript(js, null)
                    }
                }

                // 兜底：JS 迟迟不回调就放弃，别把请求挂死
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!done) {
                        done = true
                        if (cont.isActive) cont.resume("")
                        safeDestroy(wv)
                    }
                }, 20000)

                // 先加载同源页面，保证 fetch 走同源并带上验证 Cookie
                wv.loadUrl(origin + "/")
            }
        }

    /** GET：在浏览器上下文里取接口响应 */
    suspend fun fetchGet(context: Context, origin: String, apiUrl: String): String {
        val js = """
            (function(){
              fetch('${jsStr(apiUrl)}', {
                credentials: 'include',
                headers: { 'Accept': 'application/json' }
              })
              .then(function(r){ return r.text(); })
              .then(function(t){ window.zlzBridge.post(t); })
              .catch(function(e){ window.zlzBridge.post(''); });
            })();
        """.trimIndent()
        return eval(context, origin, js)
    }

    /** POST 表单：在浏览器上下文里提交 */
    suspend fun fetchPostForm(
        context: Context, origin: String, apiUrl: String, params: Map<String, String>
    ): String {
        val body = params.entries.joinToString("&") {
            "${urlEnc(it.key)}=${urlEnc(it.value)}"
        }
        val js = """
            (function(){
              fetch('${jsStr(apiUrl)}', {
                method: 'POST',
                credentials: 'include',
                headers: {
                  'Content-Type': 'application/x-www-form-urlencoded',
                  'Accept': 'application/json'
                },
                body: '${jsStr(body)}'
              })
              .then(function(r){ return r.text(); })
              .then(function(t){ window.zlzBridge.post(t); })
              .catch(function(e){ window.zlzBridge.post(''); });
            })();
        """.trimIndent()
        return eval(context, origin, js)
    }

    /** 把字符串安全地塞进 JS 单引号里 */
    private fun jsStr(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
