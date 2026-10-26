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
            val list: List<VideoItem>? = try {
                Api.feed(BuildConfig.API_BASE, PAGE_SIZE)
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                loading = false
                loadingView.visibility = View.GONE

                when {
                    list == null -> {
                        emptyView.text = "拉取失败\n检查服务端地址：\n${BuildConfig.API_BASE}"
                        emptyView.visibility = View.VISIBLE
                    }
                    list.isEmpty() && adapter.size == 0 -> {
                        emptyView.text =
                            "服务端还没有视频\n先访问 sync.php?token=xxx 抓取一批"
                        emptyView.visibility = View.VISIBLE
                    }
                    else -> {
                        adapter.append(list)
                        adapter.setActive(pager.currentItem)
                    }
                }
            }
        }
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
