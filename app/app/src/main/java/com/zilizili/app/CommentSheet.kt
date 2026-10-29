package com.zilizili.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 评论区：从手机底部向上弹出的抽屉，可滑动。
 * 用普通 Dialog 自己实现，避免引入 Material 主题依赖。
 */
class CommentSheet(
    context: Context,
    private val item: VideoItem,
    private val scope: CoroutineScope,
    private val onNeedLogin: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar) {

    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var btnSend: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLikeCount: TextView

    private val adapter = CommentAdapter()
    private val data = ArrayList<CommentItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_comment)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.4f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply {
                // 高度最多占屏幕 70%
                height = (context.resources.displayMetrics.heightPixels * 0.7).toInt()
            }
        }

        list = findViewById(R.id.commentList)
        input = findViewById(R.id.etComment)
        btnSend = findViewById(R.id.btnSend)
        tvCount = findViewById(R.id.tvCommentTitle)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvLikeCount = findViewById(R.id.tvSheetLikeCount)

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        tvLikeCount.text = item.likes.toString()
        tvCount.text = "评论 ${item.commentCount}"

        findViewById<ImageView>(R.id.btnClose).setOnClickListener { dismiss() }

        btnSend.setOnClickListener { send() }

        load()
    }

    private fun load() {
        scope.launch {
            val (cs, likes) = Api.comments(item.sha1, item.authorId)
            data.clear()
            data.addAll(cs)
            adapter.notifyDataSetChanged()
            tvEmpty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            tvCount.text = "评论 ${data.size}"
            item.commentCount = data.size
            if (likes > 0) { item.likes = likes; tvLikeCount.text = likes.toString() }
        }
    }

    private fun send() {
        if (!Session.isLogin()) { onNeedLogin(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show(); return }
        // 逗号是服务端分隔符，输入时替换掉
        val safe = text.replace(',', '，').replace('\n', ' ')
        btnSend.isEnabled = false
        scope.launch {
            val (ok, err) = Api.addComment(item.sha1, item.authorId, safe)
            btnSend.isEnabled = true
            if (ok) {
                input.setText("")
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                load()
            } else {
                Toast.makeText(context, "发送失败：$err", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 评论列表：作者的评论带红底白字"作者"头衔 */
    private inner class CommentAdapter : RecyclerView.Adapter<CommentAdapter.CH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comment, parent, false)
            return CH(v)
        }

        override fun onBindViewHolder(h: CH, p: Int) { h.bind(data[p]) }
        override fun getItemCount(): Int = data.size

        inner class CH(v: View) : RecyclerView.ViewHolder(v) {
            private val ivA: ImageView = v.findViewById(R.id.ivCmtAvatar)
            private val tvName: TextView = v.findViewById(R.id.tvCmtName)
            private val tvText: TextView = v.findViewById(R.id.tvCmtText)
            private val badge: TextView = v.findViewById(R.id.tvCmtAuthorBadge)

            fun bind(c: CommentItem) {
                tvName.text = if (c.author.isNotBlank()) "@${c.author}" else "@匿名"
                tvText.text = c.text
                // 作者头衔：红底白字
                badge.visibility = if (c.isAuthor) View.VISIBLE else View.GONE
                // 头像：只能靠名字反查，拿不到就显示占位
                ivA.setImageResource(R.drawable.bg_circle_gray)
                if (c.author.isNotBlank()) {
                    scope.launch {
                        val (u, _) = Api.userSpace(c.author)
                        if (u != null && u.avatar.isNotBlank()) {
                            ivA.load(u.avatar) { transformations(CircleCropTransformation()) }
                        }
                    }
                }
            }
        }
    }
}
