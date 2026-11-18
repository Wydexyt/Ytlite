package by.hradovich.ytlite

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

/** Страница поверх лент внутри главного окна (канал, статистика). Мини-плеер и вкладки остаются видны. */
interface AppPage {
    val view: View
    /** Обработать «назад» внутри страницы; false — закрыть страницу. */
    fun onBack(): Boolean = false
    fun onResume() {}
}

/** Страница канала: шапка с подпиской и видео с догрузкой. */
class ChannelPage(private val act: MainActivity, parent: ViewGroup, private val id: String) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_channel, parent, false)
    private val list: RecyclerView = view.findViewById(R.id.list)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val message: TextView = view.findViewById(R.id.message)
    private val header = HeaderAdapter()
    private val videos = VideoAdapter { v -> act.openVideo(v.id, v.title) }
    private var info: ChannelInfo? = null
    private var continuation: String? = null
    private var loading = false

    init {
        view.findViewById<View>(R.id.back).setOnClickListener { act.closePage(this) }
        val lm = LinearLayoutManager(act)
        list.layoutManager = lm
        list.adapter = ConcatAdapter(header, videos)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= rv.adapter!!.itemCount - 5) loadMore()
            }
        })
        videos.showSkeleton()
        progress.visibility = View.GONE
        Net.io.execute {
            val r = runCatching { InnerTube.channel(id) }
            act.runOnUiThread {
                progress.visibility = View.GONE
                r.onSuccess {
                    info = it
                    view.findViewById<TextView>(R.id.topTitle).text = it.title
                    header.notifyItemInserted(0)
                    videos.submit(it.videos.items)
                    continuation = it.videos.continuation
                }.onFailure {
                    ErrorLog.log("Канал $id", it)
                    videos.submit(emptyList())
                    message.text = act.getString(R.string.channel_error, it.message)
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() = videos.refreshProgress()

    private fun loadMore() {
        if (loading) return
        val token = continuation ?: return
        loading = true
        Net.io.execute {
            val r = runCatching { InnerTube.more("browse", token) }
            act.runOnUiThread {
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
}

/** Экран «Статистика»: время просмотра, фон, сэкономленный трафик, SponsorBlock, каналы. */
class StatsPage(private val act: MainActivity, parent: ViewGroup) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_stats, parent, false)
    private val body: android.widget.LinearLayout = view.findViewById(R.id.statsBody)
    private val chips = listOf<TextView>(view.findViewById(R.id.pWeek), view.findViewById(R.id.pMonth), view.findViewById(R.id.pAll))
    private val periods = listOf<Int?>(7, 30, null)
    private var selected = 0
    private val density = act.resources.displayMetrics.density

    init {
        view.findViewById<View>(R.id.back).setOnClickListener { act.closePage(this) }
        chips.forEachIndexed { i, c -> c.setOnClickListener { select(i) } }
        select(0)
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun select(i: Int) {
        selected = i
        chips.forEachIndexed { k, c ->
            c.setBackgroundResource(if (k == i) R.drawable.chip_selected else R.drawable.chip)
            c.setTextColor(if (k == i) 0xFF0F0F0F.toInt() else act.getColor(R.color.text))
        }
        render(Stats.summary(periods[i]))
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) act.getString(R.string.dur_hm, min / 60, min % 60) else act.getString(R.string.dur_m, min)
    }

    private fun render(s: Stats.Summary) {
        body.removeAllViews()
        card(act.getString(R.string.stat_watch), duration(s.watch))
        body.addView(BarChart(act, s.perDay).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(4) }
        })
        card(act.getString(R.string.stat_bg), duration(s.bg))
        card(
            act.getString(R.string.stat_saved), "≈ " + android.text.format.Formatter.formatShortFileSize(act, s.saved),
            act.getString(R.string.stat_saved_hint)
        )
        card(act.getString(R.string.stat_sb), act.getString(R.string.stat_sb_value, s.skips, duration(s.skipMs)))
        card(act.getString(R.string.stat_videos), s.videos.toString())
        if (s.topChannels.isNotEmpty()) {
            label(act.getString(R.string.stat_top_channels))
            s.topChannels.forEach { (name, ms) -> row(name, duration(ms)) }
        }
    }

    private fun label(text: String) = body.addView(TextView(act).apply {
        this.text = text
        setTextColor(act.getColor(R.color.sub))
        textSize = 13f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun card(title: String, value: String, hint: String? = null) {
        label(title)
        body.addView(TextView(act).apply {
            text = value
            setTextColor(act.getColor(R.color.text))
            textSize = 24f
            paint.isFakeBoldText = true
        })
        if (hint != null) body.addView(TextView(act).apply {
            text = hint
            setTextColor(act.getColor(R.color.sub))
            textSize = 11f
        })
    }

    private fun row(left: String, right: String) {
        val r = android.widget.LinearLayout(act).apply { setPadding(0, dp(4), 0, dp(4)) }
        r.addView(TextView(act).apply {
            text = left
            setTextColor(act.getColor(R.color.text))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(act).apply {
            text = right
            setTextColor(act.getColor(R.color.sub))
            textSize = 15f
        })
        body.addView(r)
    }
}

/** Простой столбчатый график времени просмотра по дням. */
@android.annotation.SuppressLint("ViewConstructor")
class BarChart(ctx: android.content.Context, private val data: List<Pair<java.util.Date, Long>>) : View(ctx) {
    private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8304A.toInt() }
    private val empty = android.graphics.Paint().apply { color = 0xFF272727.toInt() }

    override fun onDraw(c: android.graphics.Canvas) {
        if (data.isEmpty()) return
        val max = data.maxOf { it.second }.coerceAtLeast(1)
        val w = width.toFloat() / data.size
        val gap = (w * 0.25f).coerceAtMost(6f)
        data.forEachIndexed { i, (_, v) ->
            val h = if (v > 0) (height * v / max.toFloat()).coerceAtLeast(4f) else 3f
            c.drawRect(i * w + gap / 2, height - h, (i + 1) * w - gap / 2, height.toFloat(), if (v > 0) bar else empty)
        }
    }
}
