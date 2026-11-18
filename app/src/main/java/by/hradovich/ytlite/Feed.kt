package by.hradovich.ytlite

import android.app.Activity
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Одна лента (вкладка или результаты поиска): список, догрузка, обновление свайпом. */
class Feed(
    private val act: Activity,
    private val name: String,
    parent: ViewGroup,
    onOpen: (Video) -> Unit,
    private val needsLogin: Boolean,
    private val endpoint: String,
    private val loader: () -> Page,
) {
    val root: View = LayoutInflater.from(act).inflate(R.layout.page_feed, parent, false)
    private val adapter = VideoAdapter(onOpen)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val refresh: SwipeRefreshLayout = root.findViewById(R.id.refresh)
    private val progress: ProgressBar = root.findViewById(R.id.progress)
    private val message: TextView = root.findViewById(R.id.message)
    private val lm = LinearLayoutManager(act)

    private var continuation: String? = null
    private var loading = false
    private var generation = 0
    var loaded = false
        private set

    /** Вызывается один раз после первой загрузки (успешной или нет). */
    var onFirstLoad: (() -> Unit)? = null

    init {
        list.layoutManager = lm
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadMore()
            }
        })
        refresh.setColorSchemeColors(0xFFFF0033.toInt())
        refresh.setProgressBackgroundColorSchemeColor(0xFF272727.toInt())
        refresh.setOnRefreshListener { load(pull = true) }
    }

    fun scrollTop() = list.smoothScrollToPosition(0)

    fun refreshProgress() = adapter.refreshProgress()

    fun reset() {
        generation++
        adapter.submit(emptyList())
        continuation = null
        loading = false
        loaded = false
        progress.visibility = View.GONE
        message.visibility = View.GONE
        refresh.isRefreshing = false
    }

    fun showMessage(text: String) {
        progress.visibility = View.GONE
        refresh.isRefreshing = false
        message.text = text
        message.visibility = View.VISIBLE
    }

    fun load(pull: Boolean = false) {
        val g = ++generation
        continuation = null
        message.visibility = View.GONE
        if (needsLogin && !Auth.loggedIn) {
            adapter.submit(emptyList())
            loaded = true
            showMessage("Войдите в аккаунт")
            return
        }
        if (!pull) {
            adapter.submit(emptyList())
            progress.visibility = View.VISIBLE
        }
        loading = true
        Net.io.execute {
            val r = runCatching { loader() }
            act.runOnUiThread {
                if (g != generation) return@runOnUiThread
                loading = false
                loaded = true
                onFirstLoad?.let { cb -> onFirstLoad = null; act.window.decorView.post(cb) }
                progress.visibility = View.GONE
                refresh.isRefreshing = false
                r.onSuccess {
                    adapter.submit(it.items)
                    continuation = it.continuation
                    list.scrollToPosition(0)
                    if (it.items.isEmpty()) showMessage("Здесь пусто")
                }.onFailure {
                    ErrorLog.log("Лента: $name", it)
                    showMessage("Ошибка: ${it.message}")
                }
            }
        }
    }

    private fun loadMore() {
        if (loading) return
        val token = continuation ?: return
        loading = true
        val g = generation
        Net.io.execute {
            val r = runCatching { InnerTube.more(endpoint, token) }
            act.runOnUiThread {
                if (g != generation) return@runOnUiThread
                loading = false
                r.onSuccess {
                    adapter.append(it.items)
                    continuation = it.continuation
                }.onFailure {
                    ErrorLog.log("Лента: $name (догрузка)", it)
                    continuation = null
                }
            }
        }
    }
}

/** Подсказки и история поиска. */
class SuggestionAdapter(
    private val onPick: (String) -> Unit,
    private val onInsert: (String) -> Unit,
    private val onRemove: (String) -> Unit,
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
    private var items: List<Pair<String, Boolean>> = emptyList() // текст, из истории

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.sIcon)
        val text: TextView = v.findViewById(R.id.sText)
        val insert: ImageButton = v.findViewById(R.id.sInsert)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun set(list: List<Pair<String, Boolean>>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val (text, fromHistory) = items[position]
        h.text.text = text
        h.icon.setImageResource(if (fromHistory) R.drawable.ic_history else R.drawable.ic_search)
        h.itemView.setOnClickListener { onPick(text) }
        h.insert.setOnClickListener { onInsert(text) }
        h.itemView.setOnLongClickListener {
            if (fromHistory) onRemove(text)
            fromHistory
        }
    }
}

object TextUtil {
    private val TIMESTAMP = Regex("(?<![\\d:/=.])(?:(\\d{1,2}):)?(\\d{1,2}):([0-5]\\d)(?![\\d:])")

    /** Ссылки и кликабельные таймкоды. */
    fun linkify(text: String, onSeek: (Long) -> Unit): CharSequence {
        val s = SpannableString(text)
        Linkify.addLinks(s, Linkify.WEB_URLS)
        for (m in TIMESTAMP.findAll(text)) {
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val min = m.groupValues[2].toLong()
            val sec = m.groupValues[3].toLong()
            val ms = (h * 3600 + min * 60 + sec) * 1000
            s.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onSeek(ms)
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = 0xFF3EA6FF.toInt()
                    ds.isUnderlineText = false
                }
            }, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }
}

/** Разбор ссылок YouTube: id видео и время старта. */
object Links {
    private val ID = Regex("[A-Za-z0-9_-]{11}")

    fun parse(uri: Uri?): Pair<String, Long>? {
        uri ?: return null
        val host = uri.host?.lowercase() ?: return null
        val seg = uri.pathSegments
        val id = when {
            host.endsWith("youtu.be") -> seg.firstOrNull()
            seg.firstOrNull() == "watch" -> uri.getQueryParameter("v")
            seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed", "v") -> seg[1]
            else -> null
        }?.takeIf { ID.matches(it) } ?: return null
        val t = uri.getQueryParameter("t") ?: uri.getQueryParameter("start")
            ?: uri.fragment?.substringAfter("t=", "")
        return id to parseTime(t)
    }

    fun parseText(text: String?): Pair<String, Long>? =
        text?.let { Regex("https?://\\S+").findAll(it).firstNotNullOfOrNull { m -> parse(Uri.parse(m.value)) } }

    private fun parseTime(t: String?): Long {
        if (t.isNullOrEmpty()) return 0
        t.toLongOrNull()?.let { return it * 1000 }
        var total = 0L
        Regex("(\\d+)([hms])").findAll(t).forEach {
            val v = it.groupValues[1].toLong()
            total += when (it.groupValues[2]) {
                "h" -> v * 3600
                "m" -> v * 60
                else -> v
            }
        }
        return total * 1000
    }
}
