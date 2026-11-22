package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.transform.CircleCropTransformation

/** Что делать по нажатию на трек, альбом, плейлист, исполнителя или микс. */
object MusicNav {
    fun open(act: MainActivity, item: MusicItem, list: List<MusicItem>? = null) {
        when (item.type) {
            MusicItem.Type.SONG -> {
                // трек из списка (альбом, плейлист) — остальные в очередь; отдельный трек — «радио» по нему
                val songs = list?.filter { it.type == MusicItem.Type.SONG }
                if (songs != null && songs.size > 1) playTracks(act, songs.map { it.toQueue() }, songs.indexOf(item).coerceAtLeast(0))
                else playSong(act, item.toQueue(), item.playlistId)
            }
            MusicItem.Type.MIX -> playMix(act, item.playlistId ?: item.id, null)
            else -> act.openMusicPage(item.id, item.type)
        }
    }

    /** Список с позиции start (или вперемешку). Остальное — в очередь, список запоминается для повтора. */
    fun playTracks(act: MainActivity, tracks: List<QItem>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        Queue.source = tracks
        val order = if (shuffle) tracks.shuffled() else tracks.drop(start.coerceIn(0, tracks.size - 1))
        Queue.replace(order.drop(1))
        if (Queue.shuffled != shuffle) Queue.setShuffle(shuffle)
        play(act, order[0])
    }

    /** Отдельный трек: сразу играет, очередь — «радио» (или микс, из которого он открыт). */
    fun playSong(act: MainActivity, q: QItem, playlistId: String?) {
        Queue.source = emptyList()
        Queue.clear()
        play(act, q)
        if (!Prefs.musicRadio && playlistId == null) return
        Net.io.execute {
            val list = runCatching {
                if (playlistId != null) Music.watchPlaylist(q.id, playlistId).filter { it.id != q.id } else Music.radio(q.id)
            }.onFailure { ErrorLog.log("Радио ${q.id}", it) }.getOrNull() ?: return@execute
            act.runOnUiThread { if (Queue.items.isEmpty()) Queue.replace(list) }
        }
    }

    fun playMix(act: MainActivity, playlistId: String, videoId: String?) {
        Net.io.execute {
            val list = runCatching { Music.watchPlaylist(videoId, playlistId) }.onFailure { ErrorLog.log("Микс $playlistId", it) }.getOrNull()
            act.runOnUiThread {
                if (list.isNullOrEmpty()) Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
                else playTracks(act, list, 0)
            }
        }
    }

    private fun play(act: MainActivity, q: QItem) {
        val svc = PlaybackService.instance
        if (svc == null) {
            Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
            return
        }
        svc.play(q)
        act.showMusicPlayer(q)
    }

    /** Лайк трека (в «Понравившуюся музыку») или снять лайк. */
    fun toggleLike(act: android.app.Activity, id: String, liked: Boolean) {
        if (!Auth.loggedIn) {
            Toast.makeText(act, R.string.sign_in_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val now = !liked
        Likes.seen(id, now)
        if (now) Toast.makeText(act, R.string.music_liked_toast, Toast.LENGTH_SHORT).show()
        Net.io.execute {
            runCatching { Music.rate(id, if (now) "LIKE" else "INDIFFERENT") }.onSuccess {
                act.runOnUiThread { (act as? MainActivity)?.onMusicLibraryChanged() }
            }.onFailure {
                ErrorLog.log("Лайк $id", it)
                Likes.seen(id, liked)
                act.runOnUiThread { Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** Долгий тап по треку: в очередь, лайк. */
    fun menu(anchor: View, item: MusicItem) {
        if (item.type != MusicItem.Type.SONG) return
        val m = PopupMenu(anchor.context, anchor)
        val liked = Likes.liked(item.id, item.liked)
        m.menu.add(0, 4, 0, if (liked) R.string.unlike else R.string.like)
        m.menu.add(0, 1, 0, R.string.play_next)
        m.menu.add(0, 2, 1, R.string.add_to_queue)
        m.menu.add(0, 3, 2, R.string.share)
        m.setOnMenuItemClickListener {
            val q = item.toQueue()
            when (it.itemId) {
                1, 2 -> {
                    val svc = PlaybackService.instance
                    if (svc != null) svc.enqueue(q, next = it.itemId == 1)
                    else if (it.itemId == 1) Queue.addNext(q) else Queue.addLast(q)
                    Toast.makeText(anchor.context, if (it.itemId == 1) R.string.queued_next else R.string.queued, Toast.LENGTH_SHORT).show()
                }
                3 -> Share.video(anchor.context, item.id, item.title)
                4 -> (anchor.context as? android.app.Activity)?.let { a -> toggleLike(a, item.id, liked) }
            }
            true
        }
        m.show()
    }
}

/** Полки YouTube Music: заголовок + карусель карточек или список треков. */
class MusicSectionsAdapter(private val act: MainActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        private const val LIKES = "likes"
    }

    private sealed class Row
    private class Title(val text: String) : Row()
    private class Carousel(val items: List<MusicItem>) : Row()
    private class Track(val item: MusicItem, val section: List<MusicItem>) : Row()

    private val rows = ArrayList<Row>()
    private val pool = RecyclerView.RecycledViewPool()
    private val ui = Handler(Looper.getMainLooper())
    private val likesListener: () -> Unit = { ui.post { notifyItemRangeChanged(0, rows.size, LIKES) } }

    override fun onAttachedToRecyclerView(rv: RecyclerView) = Likes.listen(likesListener)
    override fun onDetachedFromRecyclerView(rv: RecyclerView) = Likes.unlisten(likesListener)

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val r = rows[position]
        if (payloads.contains(LIKES)) {
            if (r is Track) bindLike(h.itemView, r.item)
            return
        }
        onBindViewHolder(h, position)
    }

    /** Обложка; у встроенных плейлистов — тёмная плитка с белой иконкой. */
    private fun bindCover(img: ImageView, item: MusicItem, iconPadDp: Int, sizeDp: Int) {
        val icon = Music.systemIcon(item.id)
        if (icon != 0) {
            img.dispose()
            img.setImageResource(icon)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (iconPadDp * act.resources.displayMetrics.density).toInt()
            img.setPadding(p, p, p, p)
            return
        }
        img.setPadding(0, 0, 0, 0)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        // картинку — под размер карточки, откуда бы элемент ни пришёл (исполнители в библиотеке приходят строками со 120 px)
        val px = (sizeDp * act.resources.displayMetrics.density).toInt()
        img.load(item.thumb?.let { Music.sized(it, px) }) {
            crossfade(false)
            if (item.type == MusicItem.Type.ARTIST) transformations(CircleCropTransformation())
        }
    }

    private fun bindLike(v: View, item: MusicItem) {
        val btn = v.findViewById<ImageButton>(R.id.mrLike)
        if (item.type != MusicItem.Type.SONG) {
            btn.visibility = View.GONE
            return
        }
        val liked = Likes.liked(item.id, item.liked)
        btn.visibility = View.VISIBLE
        btn.setColorFilter(if (liked) act.getColor(R.color.link) else 0x66FFFFFF)
        btn.contentDescription = act.getString(if (liked) R.string.unlike else R.string.like)
        btn.setOnClickListener { MusicNav.toggleLike(act, item.id, Likes.liked(item.id, item.liked)) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(sections: List<MusicSection>) {
        rows.clear()
        for (s in sections) {
            if (s.title.isNotBlank()) rows += Title(s.title)
            if (s.list) s.items.forEach { rows += Track(it, s.items) } else rows += Carousel(s.items)
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Title -> 0
        is Carousel -> 1
        is Track -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val v = when (viewType) {
            0 -> inf.inflate(R.layout.item_music_title, parent, false)
            1 -> (inf.inflate(R.layout.item_music_carousel, parent, false) as RecyclerView).apply {
                layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)
                setRecycledViewPool(pool)
                addOnItemTouchListener(HorizontalGrab())
            }
            else -> inf.inflate(R.layout.item_music_row, parent, false)
        }
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Title -> (h.itemView as TextView).text = r.text
            is Carousel -> (h.itemView as RecyclerView).adapter = CardAdapter(r.items)
            is Track -> bindRow(h.itemView, r.item, r.section)
        }
    }

    private fun bindRow(v: View, item: MusicItem, section: List<MusicItem>) {
        v.findViewById<TextView>(R.id.mrTitle).text = item.title
        v.findViewById<TextView>(R.id.mrSub).text = item.subtitle
        bindCover(v.findViewById(R.id.mrThumb), item, 10, 48)
        v.setOnClickListener { MusicNav.open(act, item, section) }
        v.setOnLongClickListener { MusicNav.menu(it, item); true }
        bindLike(v, item)
    }

    private inner class CardAdapter(val items: List<MusicItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_music_card, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val v = h.itemView
            v.findViewById<TextView>(R.id.mcTitle).text = item.title
            v.findViewById<TextView>(R.id.mcSub).text = item.subtitle
            bindCover(v.findViewById(R.id.mcThumb), item, 44, 140)
            v.setOnClickListener { MusicNav.open(act, item) }
            v.setOnLongClickListener { MusicNav.menu(it, item); true }
        }
    }
}

/**
 * Горизонтальный список внутри вкладок: пока палец ведёт вбок и списку есть куда листать — жест его,
 * вкладки не перелистываются. Вертикальный жест отдаём общей прокрутке.
 */
class HorizontalGrab : RecyclerView.OnItemTouchListener {
    private var x0 = 0f
    private var y0 = 0f
    private var decided = false

    override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                x0 = e.x
                y0 = e.y
                decided = false
                rv.parent.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> if (!decided) {
                val dx = e.x - x0
                val dy = e.y - y0
                val slop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
                if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                    decided = true
                    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    val canScroll = rv.canScrollHorizontally(if (dx < 0) 1 else -1)
                    rv.parent.requestDisallowInterceptTouchEvent(horizontal && canScroll)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                rv.parent.requestDisallowInterceptTouchEvent(false)
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}

/** Вкладка «Музыка»: главная YouTube Music и библиотека. */
class MusicTab(private val act: MainActivity, parent: ViewGroup) {
    val root: View = LayoutInflater.from(act).inflate(R.layout.page_music, parent, false)
    private val list: RecyclerView = root.findViewById(R.id.mList)
    private val progress: ProgressBar = root.findViewById(R.id.mProgress)
    private val message: TextView = root.findViewById(R.id.mMessage)
    private val chips = listOf<TextView>(root.findViewById(R.id.mHome), root.findViewById(R.id.mLibrary))
    private val adapter = MusicSectionsAdapter(act)
    private val cache = arrayOfNulls<List<MusicSection>>(2)
    private var selected = 0
    private var gen = 0
    var loaded = false
        private set

    private val refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout = root.findViewById(R.id.mRefresh)

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        chips.forEachIndexed { i, c -> c.setOnClickListener { select(i) } }
        mark()
        refresh.setColorSchemeColors(0xFFFF0033.toInt())
        refresh.setProgressBackgroundColorSchemeColor(0xFF272727.toInt())
        refresh.setOnRefreshListener {
            cache[selected] = null
            show(selected, pull = true)
        }
    }

    /** Что-то сохранили или лайкнули — библиотеку перезагрузить при следующем показе (или сразу, если открыта). */
    fun libraryChanged() {
        cache[1] = null
        if (loaded && selected == 1) show(1, pull = true)
    }

    private fun mark() = chips.forEachIndexed { k, c ->
        c.setBackgroundResource(if (k == selected) R.drawable.chip_selected else R.drawable.chip)
        c.setTextColor(if (k == selected) 0xFF0F0F0F.toInt() else act.getColor(R.color.text))
    }

    fun load(force: Boolean = false) {
        loaded = true
        if (force) cache.fill(null)
        show(selected)
    }

    fun reset() {
        cache.fill(null)
        if (loaded) show(selected)
    }

    fun scrollTop() = list.scrollToPosition(0)

    private fun select(i: Int) {
        if (i == selected) return scrollTop()
        selected = i
        mark()
        show(i)
    }

    private fun show(i: Int, pull: Boolean = false) {
        message.visibility = View.GONE
        if (!pull) refresh.isRefreshing = false
        cache[i]?.let {
            progress.visibility = View.GONE
            adapter.submit(it)
            return
        }
        if (i == 1 && !Auth.loggedIn) {
            adapter.submit(emptyList())
            progress.visibility = View.GONE
            message.setText(R.string.sign_in_needed)
            message.visibility = View.VISIBLE
            return
        }
        if (!pull) {
            adapter.submit(emptyList())
            progress.visibility = View.VISIBLE
        }
        val g = ++gen
        Net.io.execute {
            val r = runCatching {
                // «Понравившаяся музыка» приходит в плейлистах сама, с обложкой
                if (i == 0) Music.home() else Music.library()
            }.onFailure { ErrorLog.log("Музыка", it) }
            act.runOnUiThread {
                if (g != gen) return@runOnUiThread
                progress.visibility = View.GONE
                refresh.isRefreshing = false
                r.onSuccess {
                    cache[i] = it
                    adapter.submit(it)
                    if (it.isEmpty()) {
                        message.setText(R.string.music_empty)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    message.text = act.errorText(R.string.channel_error, it)
                    message.visibility = View.VISIBLE
                }
            }
        }
    }
}

/** Альбом, плейлист, исполнитель или результаты поиска музыки. */
class MusicPageView(
    private val act: MainActivity,
    parent: ViewGroup,
    private val browseId: String?,
    private val type: MusicItem.Type?,
    private val query: String? = null,
) : AppPage {
    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_channel, parent, false)
    private val list: RecyclerView = view.findViewById(R.id.list)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val message: TextView = view.findViewById(R.id.message)
    private val sections = MusicSectionsAdapter(act)
    private val header = Header()
    private var page: MusicPage? = null

    init {
        view.findViewById<View>(R.id.back).setOnClickListener {
            closed = true
            act.closePage(this)
        }
        view.findViewById<TextView>(R.id.topTitle).text = query.orEmpty()
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = ConcatAdapter(header, sections)
        Net.io.execute {
            val r = runCatching {
                if (query != null) MusicPage("", "", null, Music.search(query), emptyList()) else Music.page(browseId!!)
            }.onFailure { ErrorLog.log("Музыка: ${browseId ?: query}", it) }
            act.runOnUiThread {
                progress.visibility = View.GONE
                r.onSuccess {
                    page = it
                    saved = it.saved
                    if (query == null) {
                        view.findViewById<TextView>(R.id.topTitle).text = it.title
                        header.notifyItemInserted(0)
                    }
                    shown = it.sections.map { s -> MusicSection(s.title, s.items.map { m -> withCover(m) }, s.list, s.moreId) }
                    tracks = it.tracks
                    sections.submit(shown)
                    it.continuation?.let { t -> loadRest(t) }
                    if (it.sections.isEmpty()) {
                        message.setText(R.string.music_empty)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    message.text = act.errorText(R.string.channel_error, it)
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    private var shown: List<MusicSection> = emptyList()
    private var saved = false
    private var tracks: List<QItem> = emptyList()
    private var closed = false

    override fun onBack(): Boolean {
        closed = true
        return false
    }

    /** В альбоме у треков нет своих обложек — подставляем обложку альбома. */
    private fun withCover(m: MusicItem): MusicItem {
        val p = page ?: return m
        return if (m.thumb == null && m.type == MusicItem.Type.SONG && p.cover != null)
            MusicItem(m.type, m.id, m.title, m.subtitle.ifEmpty { p.subtitle.substringBefore(" · ") }, p.cover, m.playlistId)
        else m
    }

    /** Длинный плейлист («Понравившаяся музыка» и т. п.): догружаем остальное в фоне, порциями. */
    private fun loadRest(first: String) {
        Net.io.execute {
            var token: String? = first
            var guard = 0
            while (token != null && guard++ < 30 && !closed) {
                val r = runCatching { Music.more(token!!) }.onFailure { ErrorLog.log("Музыка: продолжение", it) }.getOrNull() ?: break
                val (items, next) = r
                if (items.isEmpty()) break
                token = next
                act.runOnUiThread {
                    val i = shown.indexOfFirst { it.list && it.items.any { m -> m.type == MusicItem.Type.SONG } }
                    if (i < 0) return@runOnUiThread
                    val s = shown[i]
                    val add = items.map { withCover(it) }.filter { a -> s.items.none { it.id == a.id } }
                    shown = shown.toMutableList().also { it[i] = MusicSection(s.title, s.items + add, true, s.moreId) }
                    tracks = tracks + add.filter { it.type == MusicItem.Type.SONG }.map { it.toQueue(page?.cover) }
                    sections.submit(shown)
                }
            }
        }
    }

    /** «Воспроизвести всё»: у исполнителя — полный список его треков, иначе — загруженное. */
    private fun playAll(shuffle: Boolean) {
        val all = page?.allTracksId
        if (all == null) {
            MusicNav.playTracks(act, tracks, 0, shuffle)
            return
        }
        Toast.makeText(act, R.string.loading, Toast.LENGTH_SHORT).show()
        Net.io.execute {
            val list = runCatching { Music.allTracks(all) }.onFailure { ErrorLog.log("Все треки $all", it) }.getOrNull()
            act.runOnUiThread { MusicNav.playTracks(act, list?.takeIf { it.isNotEmpty() } ?: tracks, 0, shuffle) }
        }
    }

    private inner class Header : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (page == null || query != null) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_music_header, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val p = page ?: return
            val v = h.itemView
            v.findViewById<ImageView>(R.id.mhCover).load(p.cover) {
                crossfade(false)
                if (type == MusicItem.Type.ARTIST) transformations(CircleCropTransformation())
            }
            v.findViewById<TextView>(R.id.mhTitle).text = p.title
            v.findViewById<TextView>(R.id.mhSub).text = p.subtitle
            val save = v.findViewById<TextView>(R.id.mhSave)
            val pl = p.savePlaylistId
            if (pl != null && Auth.loggedIn) {
                save.visibility = View.VISIBLE
                fun render() {
                    save.text = act.getString(if (saved) R.string.in_library else R.string.save_to_library)
                    save.setBackgroundResource(if (saved) R.drawable.chip else R.drawable.chip_selected)
                    save.setTextColor(if (saved) act.getColor(R.color.text) else 0xFF0F0F0F.toInt())
                }
                render()
                save.setOnClickListener {
                    val now = !saved
                    saved = now
                    render()
                    Net.io.execute {
                        runCatching { Music.savePlaylist(pl, now) }.onSuccess {
                            act.runOnUiThread { act.onMusicLibraryChanged() }
                        }.onFailure {
                            ErrorLog.log("Сохранить $pl", it)
                            act.runOnUiThread {
                                saved = !now
                                render()
                                Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else save.visibility = View.GONE
            val sub = v.findViewById<TextView>(R.id.mhSubscribe)
            val ch = p.channelId
            if (ch != null && type == MusicItem.Type.ARTIST) sub.bindSubscribe(ch, p.subscribed) else sub.visibility = View.GONE
            v.findViewById<View>(R.id.mhButtons).visibility = if (p.tracks.isEmpty() && p.allTracksId == null) View.GONE else View.VISIBLE
            v.findViewById<View>(R.id.mhPlay).setOnClickListener { playAll(false) }
            v.findViewById<View>(R.id.mhShuffle).setOnClickListener { playAll(true) }
        }
    }
}

/** Музыкальный плеер на весь экран: обложка, перемотка, повтор, перемешивание, очередь, текст. */
class MusicScreen(private val act: MainActivity, val root: View) {
    private val cover: ImageView = root.findViewById(R.id.muCover)
    private val title: TextView = root.findViewById(R.id.muTitle)
    private val artist: TextView = root.findViewById(R.id.muArtist)
    private val seek: SeekBar = root.findViewById(R.id.muSeek)
    private val pos: TextView = root.findViewById(R.id.muPos)
    private val dur: TextView = root.findViewById(R.id.muDur)
    private val play: ImageButton = root.findViewById(R.id.muPlay)
    private val shuffle: ImageButton = root.findViewById(R.id.muShuffle)
    private val repeat: ImageButton = root.findViewById(R.id.muRepeat)
    private val radio: TextView = root.findViewById(R.id.muRadio)
    private val queueBtn: TextView = root.findViewById(R.id.muQueue)
    private val likeBtn: ImageButton = root.findViewById(R.id.muLike)
    private val dislikeBtn: ImageButton = root.findViewById(R.id.muDislike)
    private var infoFor: String? = null
    private var likeStatus = "INDIFFERENT"
    private var lyricsId: String? = null
    private val lyricsList: RecyclerView = root.findViewById(R.id.muLyricsList)
    private var lines: List<LyricLine> = emptyList()
    private var linesFor: String? = null
    private var current = -1
    private var controller: MediaController? = null
    private val ui = Handler(Looper.getMainLooper())
    private var dragging = false

    val visible get() = root.visibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            updateLyricLine()
            if (visible && controller?.isPlaying == true) ui.postDelayed(this, if (lyricsShown) 200 else 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    private val queueListener = { updateQueue() }
    private val likesListener: () -> Unit = {
        ui.post {
            val id = infoFor ?: return@post
            val l = Likes.liked(id, likeStatus == "LIKE")
            if (l != (likeStatus == "LIKE")) {
                likeStatus = if (l) "LIKE" else "INDIFFERENT"
                updateLike()
            }
        }
    }

    init {
        root.findViewById<View>(R.id.muClose).setOnClickListener { hide() }
        // сервис в том же процессе — очередь листаем напрямую
        root.findViewById<View>(R.id.muPrev).setOnClickListener {
            PlaybackService.instance?.playPrevious() ?: controller?.seekToPrevious()
        }
        root.findViewById<View>(R.id.muNext).setOnClickListener {
            PlaybackService.instance?.playNext(fromUser = true) ?: controller?.seekToNext()
        }
        root.findViewById<View>(R.id.muTimer).setOnClickListener { chooseSleep() }
        root.findViewById<View>(R.id.muLyrics).setOnClickListener { toggleLyrics() }
        likeBtn.setOnClickListener { rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE") }
        dislikeBtn.setOnClickListener { rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE") }
        play.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        queueBtn.setOnClickListener { showQueue(act) }
        shuffle.setOnClickListener {
            Queue.setShuffle(!Queue.shuffled)
            updateModes()
        }
        repeat.setOnClickListener {
            Prefs.musicRepeat = (Prefs.musicRepeat + 1) % 3
            updateModes()
            Toast.makeText(act, when (Prefs.musicRepeat) {
                1 -> R.string.repeat_all
                2 -> R.string.repeat_one
                else -> R.string.repeat_off
            }, Toast.LENGTH_SHORT).show()
        }
        radio.setOnClickListener {
            Prefs.musicRadio = !Prefs.musicRadio
            updateModes()
            Toast.makeText(act, if (Prefs.musicRadio) R.string.radio_on else R.string.radio_off, Toast.LENGTH_SHORT).show()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) pos.text = time(duration() * p / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                controller?.seekTo(duration() * sb.progress / 1000)
            }
        })
        // обложка — квадрат по ширине экрана
        cover.post { cover.maxHeight = cover.width }
    }

    fun attach(c: MediaController) {
        controller = c
        c.addListener(listener)
        Queue.listen(queueListener)
        Likes.listen(likesListener)
        update()
    }

    fun detach() {
        controller?.removeListener(listener)
        Queue.unlisten(queueListener)
        Likes.unlisten(likesListener)
        controller = null
        ui.removeCallbacks(tick)
    }

    /** Трек, который ещё загружается: пока показываем его название и обложку. */
    private var waiting: QItem? = null
    private var waitingAt = 0L

    fun show(q: QItem? = null) {
        if (q != null) {
            waiting = q
            waitingAt = android.os.SystemClock.elapsedRealtime()
            title.text = q.title
            artist.text = q.channel
            if (cover.tag != q.thumb) {
                cover.tag = q.thumb
                cover.load(q.thumb?.let { big(it) }) { crossfade(true) }
            }
        }
        if (visible) {
            update()
            return
        }
        root.visibility = View.VISIBLE
        root.translationY = root.height.takeIf { it > 0 }?.toFloat() ?: act.resources.displayMetrics.heightPixels.toFloat()
        root.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).setListener(null)
        update()
        updateQueue()
    }

    fun hide() {
        if (!visible) return
        waiting = null
        root.animate().translationY(root.height.toFloat()).setDuration(220).withEndAction {
            root.visibility = View.GONE
            act.onMusicHidden()
        }
        ui.removeCallbacks(tick)
    }

    private fun duration() = controller?.duration?.takeIf { it > 0 } ?: 0L

    private fun update() {
        val c = controller ?: return
        if (!visible) return
        val item = c.currentMediaItem
        val w = waiting
        if (w != null) {
            // ещё грузится (не дольше 20 с — вдруг не открылся и играет следующий)
            if (item?.mediaId != w.id && android.os.SystemClock.elapsedRealtime() - waitingAt < 20_000) return
            waiting = null
        }
        if (item == null || !PlaybackService.isMusic(item)) {
            hide()
            return
        }
        val md = c.mediaMetadata
        title.text = md.title ?: item.mediaMetadata.title
        artist.text = md.artist ?: item.mediaMetadata.artist ?: ""
        val art = (md.artworkUri ?: item.mediaMetadata.artworkUri)?.toString()
        if (cover.tag != art) {
            cover.tag = art
            cover.load(art?.let { big(it) }) { crossfade(true) }
        }
        play.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        loadInfo(item.mediaId)
        if (lyricsShown && linesFor != item.mediaId) loadLines()
        updateModes()
        updateProgress()
        ui.removeCallbacks(tick)
        if (c.isPlaying) ui.postDelayed(tick, 500)
    }

    /** Обложки YouTube Music масштабируются параметром в ссылке: просим крупную. */
    private fun big(url: String) = Music.sized(url, 720)

    private fun updateProgress() {
        val c = controller ?: return
        val d = duration()
        if (!dragging) {
            seek.progress = if (d > 0) (c.currentPosition * 1000 / d).toInt() else 0
            pos.text = time(c.currentPosition)
        }
        dur.text = if (d > 0) time(d) else ""
    }

    private fun updateModes() {
        val on = act.getColor(R.color.link)
        shuffle.setColorFilter(if (Queue.shuffled) on else 0xFFFFFFFF.toInt())
        repeat.setImageResource(if (Prefs.musicRepeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.setColorFilter(if (Prefs.musicRepeat != 0) on else 0xFFFFFFFF.toInt())
        radio.setBackgroundResource(if (Prefs.musicRadio) R.drawable.chip_selected else R.drawable.chip)
        radio.setTextColor(if (Prefs.musicRadio) 0xFF0F0F0F.toInt() else act.getColor(R.color.text))
    }

    private fun updateQueue() {
        queueBtn.text = if (Queue.items.isEmpty()) act.getString(R.string.queue) else act.getString(R.string.queue_is, Queue.items.size)
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun chooseSleep() {
        val values = intArrayOf(0, 15, 30, 45, 60, -1)
        val names = values.map {
            when {
                it == 0 -> act.getString(R.string.off)
                it < 0 -> act.getString(R.string.sleep_end_track)
                else -> act.getString(R.string.minutes, it)
            }
        }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle(R.string.sleep)
            .setItems(names) { _, i ->
                PlaybackService.instance?.setSleep(values[i])
                if (values[i] != 0) Toast.makeText(act, act.getString(R.string.sleep_set, names[i].lowercase()), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Оценка и ссылка на текст — один запрос на трек. */
    private fun loadInfo(id: String) {
        if (infoFor == id) return
        infoFor = id
        likeStatus = "INDIFFERENT"
        lyricsId = null
        updateLike()
        Net.io.execute {
            val info = runCatching { Music.trackInfo(id) }.onFailure { ErrorLog.log("Трек $id", it) }.getOrNull() ?: return@execute
            act.runOnUiThread {
                if (infoFor != id) return@runOnUiThread
                likeStatus = info.likeStatus
                lyricsId = info.lyricsId
                Likes.seen(id, info.likeStatus == "LIKE")
                updateLike()
            }
        }
    }

    private fun updateLike() {
        val on = 0xFFFFFFFF.toInt()
        val off = 0x99FFFFFF.toInt()
        likeBtn.setColorFilter(if (likeStatus == "LIKE") act.getColor(R.color.link) else off)
        dislikeBtn.setColorFilter(if (likeStatus == "DISLIKE") on else off)
    }

    private fun rate(status: String) {
        val id = infoFor ?: return
        if (!Auth.loggedIn) {
            Toast.makeText(act, R.string.sign_in_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val old = likeStatus
        likeStatus = status
        updateLike()
        if (status == "LIKE") Toast.makeText(act, R.string.music_liked_toast, Toast.LENGTH_SHORT).show()
        Net.io.execute {
            val ok = runCatching { Music.rate(id, status) }.onSuccess { act.runOnUiThread { act.onMusicLibraryChanged() } }.onFailure { ErrorLog.log("Оценка $id", it) }.isSuccess
            if (!ok) act.runOnUiThread {
                if (infoFor == id) {
                    likeStatus = old
                    updateLike()
                }
                Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- текст песни (караоке) ----------

    private val lyricsShown get() = lyricsList.visibility == View.VISIBLE

    private fun toggleLyrics() {
        if (lyricsShown) {
            lyricsList.visibility = View.GONE
            return
        }
        if (lyricsList.adapter == null) {
            lyricsList.layoutManager = LinearLayoutManager(act)
            lyricsList.adapter = lyricsAdapter
        }
        lyricsList.visibility = View.VISIBLE
        loadLines()
        update()
    }

    private fun loadLines() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val id = item.mediaId
        if (linesFor == id) return
        linesFor = id
        lines = emptyList()
        current = -1
        lyricsAdapter.message = act.getString(R.string.loading)
        val title = (c.mediaMetadata.title ?: item.mediaMetadata.title)?.toString().orEmpty()
        val artistName = (c.mediaMetadata.artist ?: item.mediaMetadata.artist)?.toString().orEmpty()
        val dur = duration() / 1000
        val known = lyricsId.takeIf { infoFor == id }
        Net.io.execute {
            val r = runCatching { Lyrics.get(id, title, artistName, dur, known) }.onFailure { ErrorLog.log("Текст песни $id", it) }
            act.runOnUiThread {
                if (linesFor != id) return@runOnUiThread
                lines = r.getOrNull().orEmpty()
                lyricsAdapter.message = if (lines.isEmpty()) act.getString(R.string.no_lyrics) else null
                updateLyricLine(force = true)
            }
        }
    }

    /** Подсветить текущую строку и держать её по центру. */
    private fun updateLyricLine(force: Boolean = false) {
        if (!lyricsShown || lines.isEmpty() || lines[0].time < 0) return
        val pos = controller?.currentPosition ?: return
        val i = lines.indexOfLast { it.time <= pos + 300 }
        if (i == current && !force) return
        val old = current
        current = i
        if (old >= 0) lyricsAdapter.notifyItemChanged(old)
        if (i >= 0) {
            lyricsAdapter.notifyItemChanged(i)
            (lyricsList.layoutManager as LinearLayoutManager).let { lm ->
                val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(act) {
                    override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snap: Int) =
                        (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
                }
                scroller.targetPosition = i
                lm.startSmoothScroll(scroller)
            }
        }
    }

    private val lyricsAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var message: String? = null
            @SuppressLint("NotifyDataSetChanged")
            set(v) { field = v; notifyDataSetChanged() }

        override fun getItemCount() = if (message != null) 1 else lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val tv = h.itemView as TextView
            val m = message
            if (m != null) {
                tv.text = m
                tv.setTextColor(act.getColor(R.color.sub))
                tv.setOnClickListener(null)
                return
            }
            val line = lines[position]
            tv.text = line.text.ifEmpty { "♪" }
            val synced = line.time >= 0
            tv.setTextColor(
                when {
                    !synced -> act.getColor(R.color.text)
                    position == current -> 0xFFFFFFFF.toInt()
                    else -> 0x66FFFFFF
                }
            )
            tv.textSize = if (synced) 22f else 17f
            // тап по строке — перемотать к ней
            tv.setOnClickListener(if (synced) View.OnClickListener { controller?.seekTo(line.time); controller?.play() } else null)
        }
    }

}
