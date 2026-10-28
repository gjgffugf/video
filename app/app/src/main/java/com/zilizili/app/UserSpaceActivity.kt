package com.zilizili.app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

/** 他人（或自己）的用户空间：只看，不能修改 */
class UserSpaceActivity : AppCompatActivity() {

    private lateinit var ivAvatar: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvId: TextView
    private lateinit var tvBio: TextView
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    private val items = ArrayList<VideoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_space)

        ivAvatar = findViewById(R.id.ivAvatar)
        tvName = findViewById(R.id.tvName)
        tvId = findViewById(R.id.tvId)
        tvBio = findViewById(R.id.tvBio)
        rv = findViewById(R.id.rvWorks)
        tvEmpty = findViewById(R.id.tvEmpty)

        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = Adapter()

        val uid = intent.getStringExtra("uid").orEmpty()
        if (uid.isBlank()) { Toast.makeText(this, "缺少用户", Toast.LENGTH_SHORT).show(); finish(); return }

        lifecycleScope.launch {
            val (u, list) = Api.userSpace(uid)
            if (u == null) {
                Toast.makeText(this@UserSpaceActivity, "用户不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            tvName.text = "@${u.username}"
            tvId.text = "ID ${u.id}"
            tvBio.text = u.bio.ifBlank { "这个人很懒，还没有简介" }
            if (u.avatar.isNotBlank()) ivAvatar.load(u.avatar) { transformations(CircleCropTransformation()) }

            items.clear()
            items.addAll(list)
            rv.adapter?.notifyDataSetChanged()
            tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.H>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): H {
            val v = layoutInflater.inflate(R.layout.item_work_grid, parent, false)
            return H(v)
        }
        override fun onBindViewHolder(h: H, p: Int) { h.bind(items[p]) }
        override fun getItemCount(): Int = items.size

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            private val thumb: ImageView = v.findViewById(R.id.ivThumb)
            private val title: TextView = v.findViewById(R.id.tvTitle)
            private val like: TextView = v.findViewById(R.id.tvLike)
            fun bind(it: VideoItem) {
                title.text = it.title
                like.text = "♥ ${it.likes}"
                if (it.cover.isNotBlank()) thumb.load(it.cover) else thumb.load(it.url)
                itemView.setOnClickListener {
                    val link = Api.shareLink(it)
                    Toast.makeText(this@UserSpaceActivity, "预览链接已复制", Toast.LENGTH_SHORT).show()
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("zilizili", link))
                }
            }
        }
    }
}
