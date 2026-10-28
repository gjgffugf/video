package com.zilizili.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private lateinit var pager: ViewPager2
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var adapter: FeedAdapter

    private var loadingMore = false

    companion object {
        private const val PAGE_SIZE = 5
        private const val PREFETCH = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        pager = v.findViewById(R.id.pager)
        loading = v.findViewById(R.id.loading)
        empty = v.findViewById(R.id.empty)

        adapter = FeedAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onOpenUser = { uid ->
                startActivity(Intent(requireContext(), UserSpaceActivity::class.java)
                    .putExtra("uid", uid))
            },
            onOpenComments = { item ->
                CommentSheet(requireContext(), item, viewLifecycleOwner.lifecycleScope) {
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                }.show()
            },
            onNeedLogin = {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            },
            onPrefetch = { loadMore() }
        )

        pager.adapter = adapter
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.offscreenPageLimit = 2

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 刷新直链 + 预缓存下一个都在 adapter.setActive 里做了
                // （ViewPager2 没有 findViewHolderForAdapterPosition 这个公开方法）
                adapter.setActive(position)
                if (position >= adapter.size - PREFETCH) loadMore()
            }
        })

        empty.setOnClickListener { if (adapter.size == 0) loadMore() }

        if (adapter.size == 0) loadMore()
    }

    fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        if (adapter.size == 0) {
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val r = Api.feed(PAGE_SIZE)
            loading.visibility = View.GONE
            loadingMore = false
            if (!r.ok) {
                if (adapter.size == 0) {
                    empty.text = buildString {
                        append("拉取失败\n\n原因：").append(r.error)
                        if (r.waf.isNotBlank()) append("\n拦截方：").append(r.waf)
                    }
                    empty.visibility = View.VISIBLE
                }
                return@launch
            }
            empty.visibility = View.GONE
            adapter.append(r.items)
            adapter.setActive(pager.currentItem)
        }
    }

    /** 上传完成后由外部调用：把新视频插到最前面 */
    fun pushNew(item: VideoItem) {
        adapter.append(listOf(item))
        pager.post { pager.currentItem = 0 }
    }

    override fun onPause() { super.onPause() }
}
