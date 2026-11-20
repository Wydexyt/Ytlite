package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** «Смотреть позже» и плейлисты аккаунта. */
object Library {
    const val WATCH_LATER = "WL"
    const val LIKED = "LL"

    fun addToWatchLater(ctx: Context, videoId: String) {
        Net.io.execute {
            val ok = runCatching { InnerTube.addToPlaylist(WATCH_LATER, videoId) }.onFailure { ErrorLog.log("Смотреть позже", it) }.isSuccess
            (ctx as? Activity)?.runOnUiThread {
                Toast.makeText(ctx, if (ok) R.string.watch_later_added else R.string.try_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Выбор плейлиста: отметки — где видео уже есть; снятие отметки убирает. */
    fun saveToPlaylist(act: Activity, videoId: String) {
        Net.io.execute {
            val r = runCatching { InnerTube.playlistsFor(videoId) }.onFailure { ErrorLog.log("Плейлисты для $videoId", it) }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                val list = r.getOrNull()
                if (list == null) {
                    Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val checked = list.map { it.third }.toBooleanArray()
                AlertDialog.Builder(act)
                    .setTitle(R.string.save_to_playlist)
                    .setMultiChoiceItems(list.map { it.second }.toTypedArray(), checked) { _, i, on ->
                        val id = list[i].first
                        Net.io.execute {
                            val ok = runCatching {
                                if (on) InnerTube.addToPlaylist(id, videoId) else InnerTube.removeFromPlaylist(id, videoId)
                            }.onFailure { ErrorLog.log("Плейлист $id", it) }.isSuccess
                            act.runOnUiThread {
                                Toast.makeText(
                                    act,
                                    if (!ok) act.getString(R.string.try_again)
                                    else act.getString(if (on) R.string.saved_to else R.string.removed_from, list[i].second),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNeutralButton(R.string.new_playlist) { _, _ -> createPlaylist(act, videoId) }
                    .setPositiveButton(R.string.done, null)
                    .show()
            }
        }
    }

    private fun createPlaylist(act: Activity, videoId: String) {
        val input = EditText(act).apply { setHint(R.string.playlist_name) }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        AlertDialog.Builder(act)
            .setTitle(R.string.new_playlist)
            .setView(android.widget.FrameLayout(act).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                Net.io.execute {
                    val ok = runCatching { InnerTube.createPlaylist(name, videoId) }.onFailure { ErrorLog.log("Новый плейлист", it) }.isSuccess
                    act.runOnUiThread {
                        Toast.makeText(act, if (ok) act.getString(R.string.saved_to, name) else act.getString(R.string.try_again), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Включить список: с выбранного видео, остальные — в очередь. */
    fun playFrom(act: MainActivity, list: List<Video>, start: Int, shuffle: Boolean = false) {
        val items = list.filter { !it.isPlaylist && !it.isChannel }.map { QItem.of(it) }
        if (items.isEmpty()) return
        val ordered = if (shuffle) items.shuffled() else items.drop(start.coerceIn(0, items.size - 1))
        Queue.replace(ordered.drop(1))
        act.openVideo(ordered[0].id, ordered[0].title)
        Toast.makeText(act, act.getString(R.string.queue_is, Queue.items.size), Toast.LENGTH_SHORT).show()
    }
}

/** Вкладка «Библиотека»: История, «Смотреть позже», «Понравившиеся» и плейлисты аккаунта — компактным списком. */
class LibraryTab(private val act: MainActivity, parent: ViewGroup) {
    val root: View = LayoutInflater.from(act).inflate(R.layout.page_music, parent, false)
    private val list: RecyclerView = root.findViewById(R.id.mList)
    private val progress: ProgressBar = root.findViewById(R.id.mProgress)
    private val message: TextView = root.findViewById(R.id.mMessage)
    private var gen = 0
    var loaded = false
        private set

    /** Строка: иконка (для встроенных разделов) или превью плейлиста. */
    private class Entry(val id: String, val title: String, val sub: String, val thumb: String?, val icon: Int = 0)

    private val items = ArrayList<Entry>()

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_music_row, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val e = items[position]
            val v = h.itemView
            v.findViewById<TextView>(R.id.mrTitle).text = e.title
            v.findViewById<TextView>(R.id.mrSub).apply {
                text = e.sub
                visibility = if (e.sub.isEmpty()) View.GONE else View.VISIBLE
            }
            val img = v.findViewById<android.widget.ImageView>(R.id.mrThumb)
            if (e.icon != 0) {
                img.setImageResource(e.icon)
                img.scaleType = android.widget.ImageView.ScaleType.CENTER
            } else {
                img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                img.load(e.thumb) { crossfade(false) }
            }
            v.setOnClickListener { if (e.id == HISTORY) act.openHistory() else act.openPlaylist(e.id) }
        }
    }

    init {
        // чипы «Главная/Библиотека» из макета музыки здесь не нужны
        (root.findViewById<View>(R.id.mHome).parent as View).visibility = View.GONE
        root.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.mRefresh).apply {
            setColorSchemeColors(0xFFFF0033.toInt())
            setProgressBackgroundColorSchemeColor(0xFF272727.toInt())
            setOnRefreshListener {
                isRefreshing = false
                reset()
            }
        }
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
    }

    fun scrollTop() = list.smoothScrollToPosition(0)

    fun reset() {
        loaded = false
        load()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun load() {
        loaded = true
        val g = ++gen
        items.clear()
        message.visibility = View.GONE
        if (!Auth.loggedIn) {
            adapter.notifyDataSetChanged()
            progress.visibility = View.GONE
            message.setText(R.string.sign_in_needed)
            message.visibility = View.VISIBLE
            return
        }
        items += Entry(HISTORY, act.getString(R.string.tab_history), "", null, R.drawable.ic_history)
        items += Entry(Library.WATCH_LATER, act.getString(R.string.watch_later), "", null, R.drawable.ic_timer)
        items += Entry(Library.LIKED, act.getString(R.string.liked_videos), "", null, R.drawable.ic_like)
        adapter.notifyDataSetChanged()
        progress.visibility = View.VISIBLE
        Net.io.execute {
            val r = runCatching { InnerTube.playlists() }.onFailure { ErrorLog.log("Плейлисты", it) }
            act.runOnUiThread {
                if (g != gen) return@runOnUiThread
                progress.visibility = View.GONE
                r.onSuccess { pls ->
                    val start = items.size
                    pls.forEach { p ->
                        items += Entry(p.id, p.title, listOf(p.channel, p.duration).filter { it.isNotBlank() }.joinToString(" · "), p.thumb)
                    }
                    adapter.notifyItemRangeInserted(start, items.size - start)
                }
            }
        }
    }

    companion object {
        private const val HISTORY = "history"
    }
}

/** История просмотров — лента внутри страницы. */
class HistoryPage(private val act: MainActivity, parent: ViewGroup) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_host, parent, false)
    private val feed: Feed

    init {
        view.findViewById<View>(R.id.back).setOnClickListener { act.closePage(this) }
        view.findViewById<TextView>(R.id.topTitle).setText(R.string.tab_history)
        val box = view.findViewById<ViewGroup>(R.id.content)
        feed = Feed(act, act.getString(R.string.tab_history), box, { act.openVideo(it.id, it.title) }, true, "browse") {
            InnerTube.browse("FEhistory")
        }
        box.addView(feed.root)
        feed.load()
    }

    override fun onResume() = feed.refreshProgress()
}

/** Плейлист: «Воспроизвести всё», «Перемешать», видео с догрузкой; в своих — «Убрать из плейлиста». */
class PlaylistPage(private val act: MainActivity, parent: ViewGroup, private val id: String) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_channel, parent, false)
    private val list: RecyclerView = view.findViewById(R.id.list)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val message: TextView = view.findViewById(R.id.message)
    private var info: PlaylistInfo? = null
    private val loaded = ArrayList<Video>()
    private val header = HeaderAdapter()
    private val videos = VideoAdapter(
        onClick = { v -> Library.playFrom(act, loaded, loaded.indexOfFirst { it.id == v.id }) },
        onRemove = { v ->
            loaded.removeAll { it.id == v.id }
            Net.io.execute {
                runCatching { InnerTube.removeFromPlaylist(id, v.id) }.onFailure {
                    ErrorLog.log("Убрать из плейлиста", it)
                    act.runOnUiThread { Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show() }
                }
            }
        },
    )
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
            val r = runCatching { InnerTube.playlist(id) }
            act.runOnUiThread {
                r.onSuccess {
                    info = it
                    videos.removable = it.editable
                    view.findViewById<TextView>(R.id.topTitle).text = it.title
                    header.notifyItemInserted(0)
                    loaded += it.videos.items
                    videos.submit(it.videos.items)
                    continuation = it.videos.continuation
                    if (it.videos.items.isEmpty()) {
                        message.setText(R.string.playlist_empty)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    ErrorLog.log("Плейлист $id", it)
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
                    loaded += it.items
                    videos.append(it.items)
                    continuation = it.continuation
                }.onFailure { continuation = null }
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.plTitle)
            val meta: TextView = v.findViewById(R.id.plMeta)
            val play: View = v.findViewById(R.id.plPlay)
            val shuffle: View = v.findViewById(R.id.plShuffle)
        }

        override fun getItemCount() = if (info == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_header, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val i = info ?: return
            h.title.text = i.title
            h.meta.text = i.meta
            h.meta.visibility = if (i.meta.isEmpty()) View.GONE else View.VISIBLE
            h.play.setOnClickListener { Library.playFrom(act, loaded, 0) }
            h.shuffle.setOnClickListener { Library.playFrom(act, loaded, 0, shuffle = true) }
        }
    }
}

/** Открыть плейлист из любого места интерфейса. */
fun Context.openPlaylist(id: String) {
    (this as? MainActivity)?.openPlaylist(id)
}
