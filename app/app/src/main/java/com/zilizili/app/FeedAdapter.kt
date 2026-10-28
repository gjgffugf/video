package com.zilizili.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedAdapter(
    private val scope: CoroutineScope,
    private val onOpenUser: (String) -> Unit,          // 作者 Id
    private val onOpenComments: (VideoItem) -> Unit,
    private val onNeedLogin: () -> Unit,
    private val onPrefetch: (Int) -> Unit              // 剩余不足时请求更多
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    private val items = ArrayList<VideoItem>()

    /** 已点赞集合（本地即时反馈，服务端为准） */
    private val likedSet = HashSet<String>()

    val size: Int get() = items.size

    fun append(list: List<VideoItem>) {
        val start = items.size
        val exist = items.map { it.sha1 }.toSet()
        list.forEach { if (!exist.contains(it.sha1)) items.add(it) }
        if (items.size > start) notifyItemRangeInserted(start, items.size - start)
    }

    fun clear() {
        items.clear()
        likedSet.clear()
        notifyDataSetChanged()
    }

    fun get(i: Int): VideoItem? = items.getOrNull(i)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, pos: Int) { h.bind(items[pos]) }
    override fun getItemCount(): Int = items.size

    private var activePos = -1

    fun setActive(pos: Int) {
        if (pos == activePos) return
        val old = activePos
        activePos = pos
        if (old >= 0) notifyItemChanged(old, PAYLOAD_ACTIVE)
        if (pos >= 0) {
            notifyItemChanged(pos, PAYLOAD_ACTIVE)
            // 滑到这一页时刷新该视频的最新直链
            refreshUrlAt(pos)
            // 提前缓存下一个
            if (pos + 1 < items.size) refreshUrlAt(pos + 1)
        }
        // 剩余不足时请求下一批
        if (pos >= items.size - 2) onPrefetch(pos)
    }

    /** 取最新直链并更新本地数据（播放中不会被打断） */
    private fun refreshUrlAt(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { Api.videoInfo(it.sha1) }
                ?: return@launch
            if (fresh.url.isBlank()) return@launch
            it.url = fresh.url
            it.likes = fresh.likes
            it.commentCount = fresh.commentCount
            it.intro = fresh.intro.ifBlank { it.intro }
        }
    }

    fun pauseAll() { for (i in 0 until items.size) {} }
    fun resumeActive() {}
    fun releaseAll() { for (i in 0 until itemCount) {} }

    companion object { private const val PAYLOAD_ACTIVE = "active" }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        private val coverView: ImageView = itemView.findViewById(R.id.coverView)
        private val bufferingBox: View = itemView.findViewById(R.id.bufferingBox)
        private val seekBar: SeekBar = itemView.findViewById(R.id.seekBar)
        private val tvPos: TextView = itemView.findViewById(R.id.tvPos)
        private val tvDur: TextView = itemView.findViewById(R.id.tvDur)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvAuthor)
        private val tvIntro: TextView = itemView.findViewById(R.id.tvIntro)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val ivAuthorAvatar: ImageView = itemView.findViewById(R.id.ivAuthorAvatar)
        private val btnLike: LinearLayout = itemView.findViewById(R.id.btnLike)
        private val tvLikeIcon: TextView = itemView.findViewById(R.id.tvLikeIcon)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)
        private val btnComment: LinearLayout = itemView.findViewById(R.id.btnComment)
        private val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)
        private val btnShare: LinearLayout = itemView.findViewById(R.id.btnShare)

        private var player: ExoPlayer? = null
        private var bound: VideoItem? = null
        private var tickJob: Job? = null
        private var seeking = false

        @SuppressLint("SetTextI18n")
        fun bind(item: VideoItem) {
            bound = item

            tvAuthor.text = if (item.authorName.isNotBlank()) "@${item.authorName}" else "@匿名"
            tvIntro.text = item.intro.ifBlank { item.title }
            tvDate.text = item.createdAt.ifBlank { "" }
            tvLikeCount.text = item.likes.toString()
            tvCommentCount.text = item.commentCount.toString()
            tvLikeIcon.setTextColor(
                if (likedSet.contains(item.sha1)) 0xFFFF3B5C.toInt() else 0xFFFFFFFF.toInt()
            )

            // 封面与头像
            if (item.cover.isNotBlank()) {
                coverView.visibility = View.VISIBLE
                coverView.load(item.cover) { crossfade(false) }
            } else coverView.visibility = View.GONE

            val av = item.authorAvatar
            if (av.isNotBlank()) {
                ivAvatar.load(av) { transformations(CircleCropTransformation()); placeholder(R.drawable.bg_circle_gray) }
                ivAuthorAvatar.load(av) { transformations(CircleCropTransformation()); placeholder(R.drawable.bg_circle_gray) }
            }

            // 作者头像/名字 -> 用户空间
            val uid = item.authorId
            if (uid.isNotBlank()) {
                ivAvatar.setOnClickListener { _ -> onOpenUser(uid) }
                ivAuthorAvatar.setOnClickListener { _ -> onOpenUser(uid) }
                tvAuthor.setOnClickListener { _ -> onOpenUser(uid) }
            }

            // 点赞
            btnLike.setOnClickListener { _ ->
                if (!Session.isLogin()) { onNeedLogin(); return@setOnClickListener }
                val cur = bound ?: return@setOnClickListener
                val nowLiked = !likedSet.contains(cur.sha1)
                // 先本地即时反馈
                if (nowLiked) likedSet.add(cur.sha1) else likedSet.remove(cur.sha1)
                tvLikeIcon.setTextColor(if (nowLiked) 0xFFFF3B5C.toInt() else 0xFFFFFFFF.toInt())
                tvLikeCount.text = ((cur.likes + if (nowLiked) 1 else -1).coerceAtLeast(0)).toString()
                scope.launch {
                    val (ok, n) = Api.like(cur.sha1, cur.authorId)
                    if (ok) {
                        cur.likes = n
                        tvLikeCount.text = n.toString()
                    } else {
                        // 失败回滚
                        if (nowLiked) likedSet.remove(cur.sha1) else likedSet.add(cur.sha1)
                        tvLikeIcon.setTextColor(if (nowLiked) 0xFFFFFFFF.toInt() else 0xFFFF3B5C.toInt())
                        tvLikeCount.text = cur.likes.toString()
                    }
                }
            }

            // 评论
            btnComment.setOnClickListener { _ -> onOpenComments(item) }

            // 分享 = 复制链接
            btnShare.setOnClickListener { _ ->
                val link = Api.shareLink(item)
                val cm = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zilizili", link))
                Toast.makeText(itemView.context, "链接已复制", Toast.LENGTH_SHORT).show()
            }

            // 进度条拖动
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val pl = player ?: return
                    val d = pl.duration
                    if (d > 0) tvPos.text = fmt(p * d / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    val pl = player ?: return
                    val d = pl.duration
                    val p = seekBar.progress
                    if (d > 0) pl.seekTo(p * d / 1000)
                }
            })

            setActive(bindingAdapterPosition == activePos)
        }

        fun setActive(active: Boolean) {
            if (active) {
                ensurePlayer()
                player?.playWhenReady = true
                startTick()
            } else {
                player?.playWhenReady = false
                tickJob?.cancel()
                tickJob = null
            }
        }

        private fun ensurePlayer() {
            if (player != null) {
                val it = bound ?: return
                if (player?.currentMediaItem?.mediaId != it.sha1) prepare(it)
                return
            }
            val it = bound ?: return
            val p = ExoPlayer.Builder(itemView.context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        OkHttpDataSource.Factory(Api.httpClient).setUserAgent(Browser.UA)
                    )
                )
                .build()
                .also { exo ->
                    exo.repeatMode = Player.REPEAT_MODE_ONE
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> bufferingBox.visibility = View.VISIBLE
                                Player.STATE_READY -> {
                                    bufferingBox.visibility = View.GONE
                                    coverView.visibility = View.GONE
                                }
                                else -> bufferingBox.visibility = View.GONE
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            bufferingBox.visibility = View.GONE
                        }
                    })
                    playerView.player = exo
                }
            player = p
            prepare(it)
        }

        private fun prepare(it: VideoItem) {
            player?.setMediaItem(MediaItem.Builder().setMediaId(it.sha1).setUri(it.url).build())
            player?.prepare()
            player?.playWhenReady = true
        }

        /** 滑动到下一页时刷新直链：先取最新 item 再播 */
        fun refreshUrl() {
            val it = bound ?: return
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { Api.videoInfo(it.sha1) }
                if (fresh != null && fresh.url.isNotBlank()) {
                    it.likes = fresh.likes
                    it.commentCount = fresh.commentCount
                    if (player?.currentMediaItem?.mediaId != it.sha1) prepare(it)
                }
            }
        }

        private fun startTick() {
            tickJob?.cancel()
            tickJob = scope.launch {
                while (true) {
                    val pl = player ?: break
                    val d = pl.duration
                    val pos = pl.currentPosition
                    if (d > 0) {
                        tvDur.text = fmt(d)
                        if (!seeking) {
                            seekBar.progress = ((pos * 1000) / d).toInt().coerceIn(0, 1000)
                            tvPos.text = fmt(pos)
                        }
                    }
                    delay(300)
                }
            }
        }

        fun release() {
            tickJob?.cancel(); tickJob = null
            playerView.player = null
            player?.release()
            player = null
        }
    }

    override fun onViewRecycled(h: VH) { h.release() }

    private fun fmt(ms: Long): String {
        if (ms < 0) return "00:00"
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
