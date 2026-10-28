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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), HomeHost {

    private lateinit var tabHome: TextView
    private lateinit var tabUpload: TextView
    private lateinit var tabMine: TextView

    private var home: HomeFragment? = null
    private var upload: UploadFragment? = null
    private var mine: MineFragment? = null
    private var current = TAB_HOME

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_UPLOAD = 1
        private const val TAB_MINE = 2
        private const val BLUE = 0xFF2F7BFF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Session.load(this)

        tabHome = findViewById(R.id.tabHome)
        tabUpload = findViewById(R.id.tabUpload)
        tabMine = findViewById(R.id.tabMine)

        tabHome.setOnClickListener { select(TAB_HOME) }
        tabUpload.setOnClickListener { select(TAB_UPLOAD) }
        tabMine.setOnClickListener { select(TAB_MINE) }

        select(TAB_HOME)
        checkUpdate()
    }

    private fun select(tab: Int) {
        // 上传需要登录
        if (tab == TAB_UPLOAD && !Session.isLogin()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        current = tab
        tabHome.setTextColor(if (tab == TAB_HOME) BLUE else WHITE)
        tabUpload.setTextColor(if (tab == TAB_UPLOAD) BLUE else WHITE)
        tabMine.setTextColor(if (tab == TAB_MINE) BLUE else WHITE)

        val f: Fragment = when (tab) {
            TAB_UPLOAD -> upload ?: UploadFragment().also { upload = it }
            TAB_MINE -> mine ?: MineFragment().also { mine = it }
            else -> home ?: HomeFragment().also { home = it }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        Session.load(this)
        // 登录后自动跳到上传页
        if (current == TAB_HOME && Session.isLogin() && intent.getBooleanExtra("goUpload", false)) {
            select(TAB_UPLOAD)
        }
    }

    /** 上传完成后首页实时推送，不打断正在播放的视频 */
    override fun onNewVideo(item: VideoItem) {
        home?.pushNew(item)
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

        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(!info.force).create()
        btnLater.visibility = if (info.force) View.GONE else View.VISIBLE
        btnLater.setOnClickListener { dialog.dismiss() }

        btnUpdate.setOnClickListener {
            btnRow.visibility = View.GONE
            pb.visibility = View.VISIBLE
            tvPercent.visibility = View.VISIBLE
            lifecycleScope.launch {
                val apk: File? = Updater.downloadApk(this@MainActivity, info) { p ->
                    pb.progress = p.percent
                    tvPercent.text = "${p.percent}%"
                }
                if (apk != null) {
                    dialog.dismiss()
                    Updater.install(this@MainActivity, apk)
                } else {
                    tvPercent.text = "下载失败"
                    btnRow.visibility = View.VISIBLE
                    pb.visibility = View.GONE
                }
            }
        }
        dialog.show()
    }
}
