package by.hradovich.ytlite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Страница канала: шапка с подпиской и видео с догрузкой. */
class ChannelActivity : Activity() {

    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private val header = HeaderAdapter()
    private val videos = VideoAdapter { v ->
        Streams.prefetch(v.id)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, v.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, v.title)
        )
    }
    private var info: ChannelInfo? = null
    private var continuation: String? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        val lm = LinearLayoutManager(this)
        list.layoutManager = lm
        list.adapter = ConcatAdapter(header, videos)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= rv.adapter!!.itemCount - 5) loadMore()
            }
        })

        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        Net.io.execute {
            val r = runCatching { InnerTube.channel(id) }
            runOnUiThread {
                progress.visibility = View.GONE
                r.onSuccess {
                    info = it
                    findViewById<TextView>(R.id.topTitle).text = it.title
                    header.notifyItemInserted(0)
                    videos.submit(it.videos.items)
                    continuation = it.videos.continuation
                }.onFailure {
                    ErrorLog.log("Канал $id", it)
                    message.text = "Не удалось открыть канал: ${it.message}"
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        videos.refreshProgress()
    }

    private fun loadMore() {
        if (loading) return
        val token = continuation ?: return
        loading = true
        Net.io.execute {
            val r = runCatching { InnerTube.more("browse", token) }
            runOnUiThread {
                loading = false
                r.onSuccess {
                    videos.append(it.items.map { v -> v.copy(channel = "", avatar = null) })
                    continuation = it.continuation
                }.onFailure { continuation = null }
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val banner: ImageView = v.findViewById(R.id.chBanner)
            val avatar: ImageView = v.findViewById(R.id.chAvatar)
            val title: TextView = v.findViewById(R.id.chTitle)
            val meta: TextView = v.findViewById(R.id.chMeta)
            val subscribe: TextView = v.findViewById(R.id.chSubscribe)
        }

        override fun getItemCount() = if (info == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_header, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val i = info ?: return
            h.title.text = i.title
            h.meta.text = i.meta
            h.avatar.loadAvatar(i.avatar)
            if (i.banner != null) {
                h.banner.visibility = View.VISIBLE
                h.banner.load(i.banner) { crossfade(false) }
            }
            h.subscribe.bindSubscribe(i.id, i.subscribed)
        }
    }

    companion object {
        const val EXTRA_ID = "channel"

        fun open(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, ChannelActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
