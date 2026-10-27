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
 * 思路：既然手机浏览器能打开 api.php，那就在 App 里放一个隐藏的 WebView
 * （就是 Chromium 内核，跟 Chrome 一样会执行 JS、管理 Cookie）。
 * 它能自然通过"需要执行 JS"的验证页，我们再把拿到的 Cookie / 响应回传给原生层。
 *
 * 这不是破解任何验证 —— WebView 就是一个正常的浏览器组件，
 * 它做的事和用户在手机浏览器里打开页面完全一样。
 */
object WebSession {

    /** 最近一次拿到的 Cookie 串，形如 "a=1; b=2" */
    @Volatile
    var lastCookies: String = ""
        private set

    /** 取站点根地址 */
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

    /**
     * 第 1 步：用 WebView 打开站点首页，让它完成验证并把 Cookie 存下来。
     * 返回 Cookie 串（失败返回空串）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun warmUp(context: Context, origin: String): String =
        withContext(Dispatchers.Main) {
            if (origin.isBlank()) return@withContext ""

            suspendCancellableCoroutine { cont ->
                val appCtx = context.applicationContext
                val wv = WebView(appCtx)
                cont.invokeOnCancellation { safeDestroy(wv) }

                val cm = CookieManager.getInstance()
                val handler = Handler(Looper.getMainLooper())

                // 验证页通常会 reload 一次，所以轮询几轮等 Cookie 落地
                val poll = object : Runnable {
                    var n = 0
                    override fun run() {
                        val raw = cm.getCookie(origin) ?: ""
                        if (raw.isNotBlank()) {
                            lastCookies = raw
                            if (cont.isActive) cont.resume(raw)
                            safeDestroy(wv)
                        } else if (n++ < 5) {
                            handler.postDelayed(this, 1000)
                        } else {
                            if (cont.isActive) cont.resume("")
                            safeDestroy(wv)
                        }
                    }
                }

                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = Browser.UA
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        handler.removeCallbacks(poll)
                        handler.postDelayed(poll, 800)
                    }
                }
                wv.loadUrl(origin + "/")
            }
        }

    /**
     * 第 2 步：直接在 WebView 里用 JS fetch 请求接口，把响应文本回传。
     * 请求发生在浏览器上下文里 —— Cookie、同源策略、JS 验证全部自动搞定。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchText(context: Context, origin: String, apiUrl: String): String =
        withContext(Dispatchers.Main) {
            if (origin.isBlank()) return@withContext ""

            suspendCancellableCoroutine { cont ->
                val appCtx = context.applicationContext
                val wv = WebView(appCtx)
                cont.invokeOnCancellation { safeDestroy(wv) }

                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = Browser.UA

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun post(text: String) {
                        // JS 线程回调，resume 可以跨线程；销毁 WebView 必须回主线程
                        if (cont.isActive) cont.resume(text ?: "")
                        safeDestroy(wv)
                    }
                }, "zlzBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val cm = CookieManager.getInstance()
                        val raw = cm.getCookie(origin) ?: ""
                        if (raw.isNotBlank()) lastCookies = raw

                        val js = """
                            (function(){
                              fetch('$apiUrl', {
                                credentials: 'include',
                                headers: { 'Accept': 'application/json' }
                              })
                              .then(function(r){ return r.text(); })
                              .then(function(t){ window.zlzBridge.post(t); })
                              .catch(function(e){ window.zlzBridge.post(''); });
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js, null)
                    }
                }

                // 先加载同源页面，保证 fetch 走同源 + 带上验证 Cookie
                wv.loadUrl(origin + "/")
            }
        }
}
