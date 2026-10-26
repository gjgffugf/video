package com.zilizili.app

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: FeedAdapter

    private var loading = false

    companion object {
        private const val PAGE_SIZE = 5
        private const val PREFETCH_THRESHOLD = 2
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        loadingView = findViewById(R.id.loading)
        emptyView = findViewById(R.id.empty)
        pager = findViewById(R.id.pager)

        adapter = FeedAdapter()
        pager.adapter = adapter
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.offscreenPageLimit = 2

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setActive(position)
                if (position >= adapter.size - PREFETCH_THRESHOLD) {
                    loadMore()
                }
            }
        })

        // 点错误提示可重试
        emptyView.setOnClickListener {
            if (adapter.size == 0) loadMore()
        }

        loadMore()
    }

    private fun loadMore() {
        if (loading) return
        loading = true

        if (adapter.size == 0) {
            loadingView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = Api.feed(BuildConfig.API_BASE, PAGE_SIZE)

            withContext(Dispatchers.Main) {
                loading = false
                loadingView.visibility = View.GONE

                when {
                    !result.ok -> showError(result)
                    result.items.isEmpty() && adapter.size == 0 -> {
                        emptyView.text =
                            "服务端还没有视频\n\n先访问\napi.php?action=import&token=你的令牌\n导入一批，然后点此重试"
                        emptyView.visibility = View.VISIBLE
                    }
                    else -> {
                        emptyView.visibility = View.GONE
                        adapter.append(result.items)
                        adapter.setActive(pager.currentItem)
                    }
                }
            }
        }
    }

    /** 把真实失败原因显示出来，方便排查 WAF 拦截 */
    private fun showError(result: FeedResult) {
        val sb = StringBuilder()
        sb.append("拉取失败\n")
        sb.append("原因：").append(result.error).append("\n\n")
        sb.append("地址：").append(BuildConfig.API_BASE)

        if (result.rawHead.isNotBlank()) {
            sb.append("\n\n—— 服务端实际返回 ——\n")
            sb.append(result.rawHead.take(160))
        }

        val isBlocked = result.error.contains("拦截") || result.error.contains("解析失败")
        if (isBlocked) {
            sb.append("\n\n提示：浏览器能开但 App 连不上，通常是网关按请求头判断。")
            sb.append("\n请到网关把手机 IP 放行，或改用局域网/本地地址测试。")
        }

        emptyView.text = sb.toString()
        emptyView.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseAll()
    }

    override fun onResume() {
        super.onResume()
        adapter.resumeActive()
    }

    override fun onDestroy() {
        adapter.releaseAll()
        super.onDestroy()
    }
}
