package com.zilizili.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

/**
 * 上下滑动的视频流适配器。
 * 每一页一个 ExoPlayer，只有当前页在播，滑走即暂停，回收即释放。
 */
class FeedAdapter : RecyclerView.Adapter<FeedAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()
    private val players = HashMap<Int, ExoPlayer>()

    private var activePosition = 0

    val size: Int get() = items.size

    fun append(list: List<VideoItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == activePosition)
        holder.player?.let { players[position] = it }
    }

    override fun onViewRecycled(holder: VH) {
        players.remove(holder.bindingAdapterPosition)
        holder.release()
    }

    override fun getItemCount(): Int = items.size

    /** 切换当前播放页 */
    fun setActive(position: Int) {
        activePosition = position
        players.forEach { (pos, player) ->
            if (pos == position) {
                if (!player.isPlaying) {
                    player.playWhenReady = true
                    player.play()
                }
            } else {
                player.pause()
            }
        }
    }

    /** App 退后台时全部暂停 */
    fun pauseAll() {
        players.values.forEach { it.pause() }
    }

    /** App 回前台恢复当前页 */
    fun resumeActive() {
        players[activePosition]?.let {
            it.playWhenReady = true
            it.play()
        }
    }

    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        private val titleView: TextView = itemView.findViewById(R.id.title)
        private val metaView: TextView = itemView.findViewById(R.id.meta)
        private val loadingView: ProgressBar = itemView.findViewById(R.id.itemLoading)
        private val likeCount: TextView = itemView.findViewById(R.id.likeCount)

        var player: ExoPlayer? = null
            private set

        init {
            likeCount.text = "0"
            itemView.findViewById<View>(R.id.btnLike).setOnClickListener {
                val n = likeCount.text.toString().toIntOrNull() ?: 0
                likeCount.text = (n + 1).toString()
                it.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                    .withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                    .start()
            }
            itemView.findViewById<View>(R.id.btnComment).setOnClickListener {
                Toast.makeText(itemView.context, "评论功能留给你扩展", Toast.LENGTH_SHORT).show()
            }
            itemView.findViewById<View>(R.id.btnShare).setOnClickListener {
                Toast.makeText(itemView.context, "分享功能留给你扩展", Toast.LENGTH_SHORT).show()
            }
        }

        fun bind(item: VideoItem, active: Boolean) {
            titleView.text = item.title
            metaView.text = buildString {
                append("#").append(item.sha1.take(8))
                if (item.size > 0) append("  ·  ").append(formatSize(item.size))
            }

            val p = player ?: ExoPlayer.Builder(itemView.context).build().also { exo ->
                exo.repeatMode = Player.REPEAT_MODE_ONE
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loadingView.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingView.visibility = View.GONE
                    }
                })
                playerView.player = exo
                player = exo
            }

            if (p.currentMediaItem?.mediaId != item.sha1) {
                p.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(item.sha1)
                        .setUri(item.url)
                        .build()
                )
                p.prepare()
            }
            p.playWhenReady = active
        }

        fun release() {
            playerView.player = null
            player?.release()
            player = null
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }
}
