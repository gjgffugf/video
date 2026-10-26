package com.zilizili.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

/** 与 PHP 服务端通信 */
object Api {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取随机推送列表
     * @param endpoint api.php 的完整地址，例如 https://xfan.ccccocccc.cc/api.php
     */
    fun feed(endpoint: String, count: Int): List<VideoItem> {
        val url = endpoint.trimEnd('/') + "?action=feed&count=$count"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = JSONObject(body)
            if (!root.optBoolean("ok", true)) {
                throw java.io.IOException(root.optString("error", "server error"))
            }
            val arr = root.optJSONArray("items") ?: return emptyList()

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
            return out
        }
    }
}
