package com.zilizili.app

import android.app.AlertDialog
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

class MineFragment : Fragment() {

    private lateinit var ivAvatar: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvId: TextView
    private lateinit var tvBio: TextView
    private lateinit var btnLogout: Button
    private lateinit var tvToggle: TextView
    private lateinit var boxEdit: LinearLayout
    private lateinit var btnEditAvatar: Button
    private lateinit var btnEditBio: Button
    private lateinit var btnEditName: Button
    private lateinit var rvWorks: RecyclerView
    private lateinit var tvEmpty: TextView

    private val works = ArrayList<VideoItem>()
    private val adapter = WorkAdapter()

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        uploadAvatar(uri)
    }

    private var pendingCoverSha1: String? = null
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val sha1 = pendingCoverSha1 ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val sz = Uploader.sizeOf(requireContext(), uri)
        if (sz > MAX_COVER_BYTES) {
            Toast.makeText(requireContext(), "封面不能超过 ${MAX_COVER_BYTES / 1048576} MB", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val (ok, err) = Api.updateWork(sha1, null, uri, requireContext())
            Toast.makeText(requireContext(), if (ok) "封面已更新" else "失败：$err", Toast.LENGTH_SHORT).show()
            if (ok) loadWorks()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_mine, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        ivAvatar = v.findViewById(R.id.ivAvatar)
        tvName = v.findViewById(R.id.tvName)
        tvId = v.findViewById(R.id.tvId)
        tvBio = v.findViewById(R.id.tvBio)
        btnLogout = v.findViewById(R.id.btnLogout)
        tvToggle = v.findViewById(R.id.tvToggleEdit)
        boxEdit = v.findViewById(R.id.boxEdit)
        btnEditAvatar = v.findViewById(R.id.btnEditAvatar)
        btnEditBio = v.findViewById(R.id.btnEditBio)
        btnEditName = v.findViewById(R.id.btnEditName)
        rvWorks = v.findViewById(R.id.rvWorks)
        tvEmpty = v.findViewById(R.id.tvWorksEmpty)

        rvWorks.layoutManager = GridLayoutManager(requireContext(), 3)
        rvWorks.adapter = adapter

        // 折叠栏
        tvToggle.setOnClickListener {
            val open = boxEdit.visibility == View.VISIBLE
            boxEdit.visibility = if (open) View.GONE else View.VISIBLE
            tvToggle.text = if (open) "▸ 修改我的资料" else "▾ 修改我的资料"
        }

        btnEditAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        btnEditBio.setOnClickListener { showEditDialog(0) }
        btnEditName.setOnClickListener { showEditDialog(1) }

        btnLogout.setOnClickListener {
            lifecycleScope.launch {
                Api.logout()
                Session.clear(requireContext())
                Toast.makeText(requireContext(), "已退出", Toast.LENGTH_SHORT).show()
                goLogin()
            }
        }

        if (!Session.isLogin()) {
            goLogin()
            return
        }
        render()
        loadWorks()
    }

    private fun goLogin() {
        startActivity(Intent(requireContext(), LoginActivity::class.java))
    }

    private fun render() {
        val u = Session.me ?: return
        tvName.text = "@${u.username}"
        tvId.text = "ID ${u.id}"
        tvBio.text = u.bio.ifBlank { "这个人很懒，还没有简介" }
        if (u.avatar.isNotBlank()) {
            ivAvatar.load(u.avatar) { transformations(CircleCropTransformation()) }
        }
    }

    private fun loadWorks() {
        lifecycleScope.launch {
            val list = Api.myWorks()
            works.clear()
            works.addAll(list)
            adapter.notifyDataSetChanged()
            tvEmpty.visibility = if (works.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun uploadAvatar(uri: Uri) {
        val sz = Uploader.sizeOf(requireContext(), uri)
        if (sz > MAX_AVATAR_BYTES) {
            Toast.makeText(requireContext(), "头像不能超过 ${MAX_AVATAR_BYTES / 1048576} MB", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val (ok, err) = Api.uploadAvatar(requireContext(), uri)
            if (ok) {
                val u = Api.lastUser
                if (u != null) Session.save(requireContext(), Session.token, u)
                render()
                Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "失败：$err", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** type: 0=简介 1=用户名 */
    private fun showEditDialog(type: Int) {
        val input = EditText(requireContext()).apply {
            background = resources.getDrawable(R.drawable.bg_edit_dark, null)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 20, 24, 20)
            setSingleLine(type == 1)
            hint = if (type == 0) "新简介" else "新用户名"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (type == 0) "修改简介" else "修改用户名")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val (ok, err) = if (type == 0) Api.updateProfile(null, v) else Api.updateProfile(v, null)
                    if (ok) {
                        val u = Api.lastUser
                        if (u != null) Session.save(requireContext(), Session.token, u)
                        render()
                        Toast.makeText(requireContext(), "已更新", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(requireContext(), "失败：$err", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 作品网格：每行 3 个，长按可删除 / 改简介 / 改封面 */
    private inner class WorkAdapter : RecyclerView.Adapter<WorkAdapter.WH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_work_grid, parent, false)
            return WH(v)
        }

        override fun onBindViewHolder(h: WH, p: Int) { h.bind(works[p]) }
        override fun getItemCount(): Int = works.size

        // 注意：bind 的参数不能叫 it —— 下面点击/长按的 lambda 自带隐式 it（View），
        // 同名会遮蔽外层参数，导致 it.title 之类解析失败。这里显式用 item。
        inner class WH(v: View) : RecyclerView.ViewHolder(v) {
            private val thumb: ImageView = v.findViewById(R.id.ivThumb)
            private val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            private val tvLike: TextView = v.findViewById(R.id.tvLike)

            fun bind(item: VideoItem) {
                tvTitle.text = item.title
                tvLike.text = "\u2665 ${item.likes}"
                if (item.cover.isNotBlank()) thumb.load(item.cover) else thumb.load(item.url)

                itemView.setOnLongClickListener { _ ->
                    val opts = arrayOf("删除作品", "修改简介", "修改封面")
                    AlertDialog.Builder(requireContext())
                        .setTitle(item.title)
                        .setItems(opts) { _, w ->
                            when (w) {
                                0 -> confirmDelete(item)
                                1 -> showEditWorkIntro(item)
                                2 -> { pendingCoverSha1 = item.sha1; pickCover.launch("image/*") }
                            }
                        }
                        .show()
                    true
                }

                itemView.setOnClickListener { _ ->
                    startActivity(Intent(requireContext(), UserSpaceActivity::class.java)
                        .putExtra("uid", item.authorId))
                }
            }
        }
    }

    private fun confirmDelete(it: VideoItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除作品")
            .setMessage("确定删除《${it.title}》？不可恢复")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val (ok, err) = Api.deleteWork(it.sha1)
                    Toast.makeText(requireContext(), if (ok) "已删除" else "失败：$err", Toast.LENGTH_SHORT).show()
                    if (ok) loadWorks()
                }
            }
            .show()
    }

    private fun showEditWorkIntro(it: VideoItem) {
        val input = EditText(requireContext()).apply {
            background = resources.getDrawable(R.drawable.bg_edit_dark, null)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 20, 24, 20)
            setText(it.intro)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改简介")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val (ok, err) = Api.updateWork(it.sha1, v, null, null)
                    Toast.makeText(requireContext(), if (ok) "已更新" else "失败：$err", Toast.LENGTH_SHORT).show()
                    if (ok) loadWorks()
                }
            }
            .show()
    }
}
