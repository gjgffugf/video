package com.zilizili.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadActivity : AppCompatActivity() {

    private lateinit var btnPick: Button
    private lateinit var btnUpload: Button
    private lateinit var infoBox: LinearLayout
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvName: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvSha1: TextView
    private lateinit var tvStage: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvResult: TextView

    private var pickedUri: Uri? = null
    private var pickedSize: Long = 0L
    private var pickedSha1: String = ""
    private var uploading = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedUri = uri
        prepare(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)

        btnPick = findViewById(R.id.btnPick)
        btnUpload = findViewById(R.id.btnUpload)
        infoBox = findViewById(R.id.infoBox)
        progressBox = findViewById(R.id.progressBox)
        progressBar = findViewById(R.id.progressBar)
        tvName = findViewById(R.id.tvName)
        tvSize = findViewById(R.id.tvSize)
        tvSha1 = findViewById(R.id.tvSha1)
        tvStage = findViewById(R.id.tvStage)
        tvPercent = findViewById(R.id.tvPercent)
        tvResult = findViewById(R.id.tvResult)

        btnPick.setOnClickListener {
            picker.launch("video/*")
        }

        btnUpload.setOnClickListener {
            val uri = pickedUri ?: return@setOnClickListener
            startUpload(uri)
        }
    }

    /** 选完文件先算大小和 sha1，让用户在上传前看到指纹 */
    private fun prepare(uri: Uri) {
        tvResult.text = ""
        progressBox.visibility = View.GONE
        btnUpload.isEnabled = false

        lifecycleScope.launch {
            val (name, size, sha1) = withContext(Dispatchers.IO) {
                Triple(
                    Uploader.nameOf(this@UploadActivity, uri),
                    Uploader.sizeOf(this@UploadActivity, uri),
                    Uploader.sha1Of(this@UploadActivity, uri)
                )
            }

            pickedSize = size
            pickedSha1 = sha1

            infoBox.visibility = View.VISIBLE
            tvName.text = name
            tvSize.text = formatSize(size)
            tvSha1.text = "sha1: $sha1"

            if (size <= 0) {
                btnUpload.isEnabled = false
                tvResult.setTextColor(0xFFEF5350.toInt())
                tvResult.text = "读不到文件大小，换个文件试试"
            } else if (size > MAX_UPLOAD_BYTES) {
                btnUpload.isEnabled = false
                tvResult.setTextColor(0xFFEF5350.toInt())
                tvResult.text = "文件 ${formatSize(size)} 超过上限 ${MAX_UPLOAD_BYTES / 1048576} MB"
            } else {
                btnUpload.isEnabled = true
            }
        }
    }

    private fun startUpload(uri: Uri) {
        if (uploading) return
        uploading = true
        btnUpload.isEnabled = false
        btnPick.isEnabled = false
        progressBox.visibility = View.VISIBLE
        tvResult.setTextColor(0xFFBBBBBB.toInt())
        tvResult.text = ""

        lifecycleScope.launch {
            val result = Uploader.upload(this@UploadActivity, uri, tvName.text.toString()) { p ->
                progressBar.progress = p.percent
                tvPercent.text = "${p.percent}%"
                tvStage.text = when (p.stage) {
                    "hashing"    -> "计算指纹 ${formatSize(p.uploadedBytes)} / ${formatSize(p.totalBytes)}"
                    "preparing"  -> "准备上传…"
                    "uploading"  -> "上传中 ${formatSize(p.uploadedBytes)} / ${formatSize(p.totalBytes)}"
                    "finishing"  -> "校验并入库…"
                    "done"       -> "完成"
                    else         -> p.stage
                }
            }

            uploading = false
            btnPick.isEnabled = true

            if (result.ok) {
                progressBar.progress = 100
                tvPercent.text = "100%"
                tvStage.text = if (result.instant) "秒传完成" else "上传完成"
                tvResult.setTextColor(0xFF66BB6A.toInt())
                tvResult.text = buildString {
                    if (result.instant) append("文件已存在，秒传成功\n")
                    append("sha1: ").append(result.sha1).append("\n")
                    append(result.url)
                }
                Toast.makeText(this@UploadActivity, "上传成功", Toast.LENGTH_SHORT).show()
            } else {
                tvResult.setTextColor(0xFFEF5350.toInt())
                tvResult.text = "上传失败：${result.error}"
                btnUpload.isEnabled = true
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return when {
            mb >= 1 -> "%.1f MB".format(mb)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
