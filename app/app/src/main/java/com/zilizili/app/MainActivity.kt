package com.zilizili.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

        findViewById<Button>(R.id.btnUploadEntry).setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java))
        }

        loadMore()

        // 每次启动检查更新：直连 + 多代理镜像，全程 App 内完成
        checkUpdate()
    }

    private fun loadMore() {
        if (loading) return
        loading = true

        if (adapter.size == 0) {
            loadingView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 三级降级：OkHttp 直连 -> WebView 取 Cookie 重试 -> WebView 内 fetch
            val result = Api.feedSmart(this@MainActivity, BuildConfig.API_BASE, PAGE_SIZE)

            withContext(Dispatchers.Main) {
                loading = false
                loadingView.visibility = View.GONE

                when {
                    !result.ok -> showError(result)
                    result.items.isEmpty() && adapter.size == 0 -> {
                        emptyView.text =
                            "服务端还没有视频\n\n点右下角「上传」添加，或访问\napi.php?action=import&token=你的令牌\n导入一批"
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
        sb.append("拉取失败\n\n")
        sb.append("原因：").append(result.error)

        if (result.waf.isNotBlank()) {
            sb.append("\n拦截方：").append(result.waf)
        }
        sb.append("\n\n地址：").append(BuildConfig.API_BASE)

        if (result.rawHead.isNotBlank()) {
            sb.append("\n\n—— 服务端实际返回 ——\n")
            sb.append(result.rawHead.take(300))
        }

        sb.append("\n\n")
        if (result.waf.contains("iFastNet")) {
            // iFastNet 免费版的浏览器验证是强制的，没有开关，也没有办法从 App 侧通过
            sb.append("iFastNet 免费版强制开启浏览器验证，\n")
            sb.append("官方明确说明无法关闭，\n")
            sb.append("因此 App / API 调用在免费版上必然被拦。\n\n")
            sb.append("可行方案：\n")
            sb.append("1. 升级 iFastNet 付费主机\n")
            sb.append("2. 换用对象存储（七牛/R2）\n")
            sb.append("3. 换一家允许 API 的主机")
        } else if (result.isJsChallenge) {
            // 这类页面必须执行 JS 才能通过，HTTP 客户端不可能通过
            sb.append("这是需要执行 JS 的人机验证页。\n")
            sb.append("浏览器能过是因为它会跑 JS，App 不会。\n\n")
            sb.append("解决（需在网关侧配置）：\n")
            sb.append("1. 给 /api.php 路径加放行规则\n")
            sb.append("2. 或把手机 IP 加入白名单\n")
            sb.append("3. 或临时改用局域网地址测试")
        } else if (result.error.contains("解析失败") || result.error.contains("拦截")) {
            sb.append("浏览器能开但 App 连不上，通常是网关按请求头判断。\n")
            sb.append("请到网关放行手机 IP，或改用局域网地址测试。")
        } else {
            sb.append("点击重试")
        }

        emptyView.text = sb.toString()
        emptyView.visibility = View.VISIBLE
    }

    // ---------- 应用内更新 ----------

    private fun checkUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = Updater.check() ?: return@launch
            if (!Updater.hasUpdate(info)) return@launch
            withContext(Dispatchers.Main) {
                if (!isFinishing) showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val pb = view.findViewById<ProgressBar>(R.id.pbUpdate)
        val tvPercent = view.findViewById<TextView>(R.id.tvUpdatePercent)
        val tvChangelog = view.findViewById<TextView>(R.id.tvChangelog)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val btnRow = view.findViewById<View>(R.id.btnRow)
        val btnLater = view.findViewById<Button>(R.id.btnLater)
        val btnUpdate = view.findViewById<Button>(R.id.btnUpdate)

        tvTitle.text = "发现新版本 v${info.versionName}"
        tvChangelog.text = buildString {
            append("当前：v${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})\n")
            append("新版：v${info.versionName} (${info.versionCode})")
            if (info.changelog.isNotBlank()) append("\n\n").append(info.changelog)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(!info.force)
            .create()

        // 强制更新时不给"稍后"
        btnLater.visibility = if (info.force) View.GONE else View.VISIBLE
        btnLater.setOnClickListener { dialog.dismiss() }

        btnUpdate.setOnClickListener {
            btnRow.visibility = View.GONE
            pb.visibility = View.VISIBLE
            tvPercent.visibility = View.VISIBLE
            pb.progress = 0
            tvPercent.text = "0%"

            lifecycleScope.launch {
                val apk: File? = Updater.downloadApk(this@MainActivity, info) { p ->
                    pb.progress = p.percent
                    tvPercent.text = "${p.percent}%  (${formatSize(p.downloadedBytes)} / ${formatSize(p.totalBytes)})"
                }

                if (apk != null) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "下载完成，开始安装", Toast.LENGTH_SHORT).show()
                    Updater.install(this@MainActivity, apk)
                } else {
                    tvPercent.text = "下载失败，请检查网络后重试"
                    btnRow.visibility = View.VISIBLE
                    pb.visibility = View.GONE
                }
            }
        }

        dialog.show()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return when {
            mb >= 1 -> "%.1f MB".format(mb)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
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
