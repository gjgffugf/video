package com.zilizili.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

class UploadFragment : Fragment() {

    private lateinit var btnPick: Button
    private lateinit var btnPickCover: Button
    private lateinit var ivCover: ImageView
    private lateinit var etTitle: EditText
    private lateinit var etIntro: EditText
    private lateinit var infoBox: LinearLayout
    private lateinit var tvSize: TextView
    private lateinit var tvSha1: TextView
    private lateinit var btnUpload: Button
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStage: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvPercent: TextView

    private var videoUri: Uri? = null
    private var coverUri: Uri? = null
    private var uploading = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        videoUri = uri
        prepare(uri)
    }

    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val sz = Uploader.sizeOf(requireContext(), uri)
        if (sz > MAX_COVER_BYTES) {
            Toast.makeText(requireContext(), "封面不能超过 ${MAX_COVER_BYTES / 1048576} MB", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        coverUri = uri
        ivCover.visibility = View.VISIBLE
        ivCover.load(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_upload, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        btnPick = v.findViewById(R.id.btnPick)
        btnPickCover = v.findViewById(R.id.btnPickCover)
        ivCover = v.findViewById(R.id.ivCover)
        etTitle = v.findViewById(R.id.etTitle)
        etIntro = v.findViewById(R.id.etIntro)
        infoBox = v.findViewById(R.id.infoBox)
        tvSize = v.findViewById(R.id.tvSize)
        tvSha1 = v.findViewById(R.id.tvSha1)
        btnUpload = v.findViewById(R.id.btnUpload)
        tvResult = v.findViewById(R.id.tvResult)
        progressBar = v.findViewById(R.id.progressBar)
        tvStage = v.findViewById(R.id.tvStage)
        tvSpeed = v.findViewById(R.id.tvSpeed)
        tvPercent = v.findViewById(R.id.tvPercent)

        btnPick.setOnClickListener { pickVideo.launch("video/*") }
        btnPickCover.setOnClickListener { pickCover.launch("image/*") }
        btnUpload.setOnClickListener { start() }

        // 未登录不能上传
        if (!Session.isLogin()) {
            btnPick.isEnabled = false
            btnPickCover.isEnabled = false
            btnUpload.isEnabled = false
            tvResult.setTextColor(0xFFEF5350.toInt())
            tvResult.text = "未注册用户不能上传，请先登录"
            tvStage.text = "需要登录"
        }
    }

    override fun onResume() {
        super.onResume()
        // 登录后回到本页要刷新可用状态
        if (Session.isLogin() && !btnPick.isEnabled) {
            btnPick.isEnabled = true
            btnPickCover.isEnabled = true
            tvResult.text = ""
            tvStage.text = "等待选择文件"
        }
    }

    private fun prepare(uri: Uri) {
        tvResult.text = ""
        progressBar.progress = 0
        tvPercent.text = "0%"
        tvSpeed.text = ""
        tvStage.text = "计算指纹…"

        lifecycleScope.launch {
            val size = Uploader.sizeOf(requireContext(), uri)
            val name = Uploader.nameOf(requireContext(), uri)
            if (etTitle.text.isBlank()) etTitle.setText(name)
            val sha1 = Uploader.sha1Of(requireContext(), uri)

            infoBox.visibility = View.VISIBLE
            tvSize.text = "大小 ${fmt(size)}"
            tvSha1.text = "sha1: $sha1"

            if (size <= 0) {
                btnUpload.isEnabled = false
                tvStage.text = "读不到文件"
            } else if (size > MAX_UPLOAD_BYTES) {
                btnUpload.isEnabled = false
                tvStage.text = "文件超过 ${MAX_UPLOAD_BYTES / 1048576} MB"
            } else {
                btnUpload.isEnabled = true
                tvStage.text = "已就绪 · ${fmt(size)}"
            }
        }
    }

    /** 文件名改了就重新计算 sha1 */
    private fun recomputeAfterRename() {
        val uri = videoUri ?: return
        lifecycleScope.launch {
            tvStage.text = "重新计算指纹…"
            val sha1 = Uploader.sha1Of(requireContext(), uri)
            tvSha1.text = "sha1: $sha1"
            tvStage.text = "已就绪"
        }
    }

    private fun start() {
        val uri = videoUri ?: return
        if (!Session.isLogin()) {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            return
        }
        if (uploading) return
        uploading = true
        btnUpload.isEnabled = false
        btnPick.isEnabled = false
        tvResult.setTextColor(0xFFBBBBBB.toInt())
        tvResult.text = ""
        progressBar.progress = 0
        tvPercent.text = "0%"

        val title = etTitle.text.toString().trim().ifBlank { Uploader.nameOf(requireContext(), uri) }
        val intro = etIntro.text.toString().trim()

        lifecycleScope.launch {
            val r = Uploader.upload(requireContext(), uri, title, intro, coverUri) { p ->
                progressBar.progress = p.percent
                tvPercent.text = "${p.percent}%"
                tvSpeed.text = p.speedText()
                tvStage.text = when (p.stage) {
                    "hashing" -> "计算指纹…"
                    "preparing" -> "准备上传…"
                    "uploading" -> "上传中 ${fmt(p.uploadedBytes)} / ${fmt(p.totalBytes)}"
                    "finishing" -> "服务端校验并入库…"
                    "done" -> "完成"
                    else -> p.stage
                }
            }

            uploading = false
            btnPick.isEnabled = true

            if (r.ok) {
                btnUpload.isEnabled = true
                progressBar.progress = 100
                tvPercent.text = "100%"
                tvStage.text = if (r.duplicate) "已存在（秒传）" else "上传完成"
                tvResult.setTextColor(0xFF66BB6A.toInt())
                tvResult.text = r.item?.let { "已发布：@${it.authorName} · ${it.title}" } ?: "上传成功"
                Toast.makeText(requireContext(), "上传成功", Toast.LENGTH_SHORT).show()
                // 通知首页实时推送（不打断当前播放）
                r.item?.let { (activity as? HomeHost)?.onNewVideo(it) }
            } else {
                btnUpload.isEnabled = true
                tvResult.setTextColor(0xFFEF5350.toInt())
                tvResult.text = "上传失败：${r.error}"
                tvStage.text = "失败"
            }
        }
    }

    private fun fmt(b: Long): String {
        val mb = b / 1048576.0
        return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(b / 1024.0)
    }
}

/** 让主页能接收新上传的视频 */
interface HomeHost {
    fun onNewVideo(item: VideoItem)
}
