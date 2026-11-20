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

    /** Долгий тап по треку: в очередь. */
    fun menu(anchor: View, item: MusicItem) {
        if (item.type != MusicItem.Type.SONG) return
        val m = PopupMenu(anchor.context, anchor)
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
            }
            true
        }
        m.show()
    }
}

/** Полки YouTube Music: заголовок + карусель карточек или список треков. */
class MusicSectionsAdapter(private val act: MainActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed class Row
    private class Title(val text: String) : Row()
    private class Carousel(val items: List<MusicItem>) : Row()
    private class Track(val item: MusicItem, val section: List<MusicItem>) : Row()

    private val rows = ArrayList<Row>()
    private val pool = RecyclerView.RecycledViewPool()

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
        v.findViewById<ImageView>(R.id.mrThumb).load(item.thumb) {
            crossfade(false)
            if (item.type == MusicItem.Type.ARTIST) transformations(CircleCropTransformation())
        }
        v.setOnClickListener { MusicNav.open(act, item, section) }
        v.setOnLongClickListener { MusicNav.menu(it, item); true }
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
            v.findViewById<ImageView>(R.id.mcThumb).load(item.thumb) {
                crossfade(false)
                if (item.type == MusicItem.Type.ARTIST) transformations(CircleCropTransformation())
            }
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

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        chips.forEachIndexed { i, c -> c.setOnClickListener { select(i) } }
        mark()
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

    private fun show(i: Int) {
        message.visibility = View.GONE
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
        adapter.submit(emptyList())
        progress.visibility = View.VISIBLE
        val g = ++gen
        Net.io.execute {
            val r = runCatching {
                if (i == 0) Music.home() else {
                    val liked = MusicSection("", listOf(
                        MusicItem(MusicItem.Type.PLAYLIST, "VLLM", Lang.str(R.string.music_liked), "", null)
                    ), list = true)
                    listOf(liked) + Music.library()
                }
            }.onFailure { ErrorLog.log("Музыка", it) }
            act.runOnUiThread {
                if (g != gen) return@runOnUiThread
                progress.visibility = View.GONE
                r.onSuccess {
                    cache[i] = it
                    adapter.submit(it)
                    if (it.isEmpty()) {
                        message.setText(R.string.music_empty)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    message.text = act.getString(R.string.channel_error, it.message)
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
        view.findViewById<View>(R.id.back).setOnClickListener { act.closePage(this) }
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
                    if (query == null) {
                        view.findViewById<TextView>(R.id.topTitle).text = it.title
                        header.notifyItemInserted(0)
                    }
                    // в альбоме у треков нет своих обложек — подставляем обложку альбома
                    sections.submit(if (it.cover == null) it.sections else it.sections.map { s ->
                        MusicSection(s.title, s.items.map { m ->
                            if (m.thumb == null && m.type == MusicItem.Type.SONG)
                                MusicItem(m.type, m.id, m.title, m.subtitle.ifEmpty { it.subtitle.substringBefore(" · ") }, it.cover, m.playlistId)
                            else m
                        }, s.list)
                    })
                    if (it.sections.isEmpty()) {
                        message.setText(R.string.music_empty)
                        message.visibility = View.VISIBLE
                    }
                }.onFailure {
                    message.text = act.getString(R.string.channel_error, it.message)
                    message.visibility = View.VISIBLE
                }
            }
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
            val sub = v.findViewById<TextView>(R.id.mhSubscribe)
            val ch = p.channelId
            if (ch != null && type == MusicItem.Type.ARTIST) sub.bindSubscribe(ch, p.subscribed) else sub.visibility = View.GONE
            v.findViewById<View>(R.id.mhButtons).visibility = if (p.tracks.isEmpty()) View.GONE else View.VISIBLE
            v.findViewById<View>(R.id.mhPlay).setOnClickListener { MusicNav.playTracks(act, p.tracks, 0) }
            v.findViewById<View>(R.id.mhShuffle).setOnClickListener { MusicNav.playTracks(act, p.tracks, 0, shuffle = true) }
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
    private var controller: MediaController? = null
    private val ui = Handler(Looper.getMainLooper())
    private var dragging = false

    val visible get() = root.visibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (visible && controller?.isPlaying == true) ui.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    private val queueListener = { updateQueue() }

    init {
        root.findViewById<View>(R.id.muClose).setOnClickListener { hide() }
        root.findViewById<View>(R.id.muPrev).setOnClickListener { controller?.seekToPrevious() }
        root.findViewById<View>(R.id.muNext).setOnClickListener { controller?.seekToNext() }
        root.findViewById<View>(R.id.muTimer).setOnClickListener { chooseSleep() }
        root.findViewById<View>(R.id.muLyrics).setOnClickListener { showLyrics() }
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
        update()
    }

    fun detach() {
        controller?.removeListener(listener)
        Queue.unlisten(queueListener)
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
            val ok = runCatching { InnerTube.rate(id, status) }.onFailure { ErrorLog.log("Оценка $id", it) }.isSuccess
            if (!ok) act.runOnUiThread {
                if (infoFor == id) {
                    likeStatus = old
                    updateLike()
                }
                Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLyrics() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        val known = lyricsId.takeIf { infoFor == id }
        Toast.makeText(act, R.string.loading, Toast.LENGTH_SHORT).show()
        Net.io.execute {
            val text = runCatching { Music.lyrics(id, known) }.onFailure { ErrorLog.log("Текст песни $id", it) }.getOrNull()
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                val pad = (24 * act.resources.displayMetrics.density).toInt()
                val body = TextView(act).apply {
                    this.text = text ?: act.getString(R.string.no_lyrics)
                    textSize = 16f
                    setLineSpacing(0f, 1.25f)
                    setTextIsSelectable(true)
                    setPadding(pad, pad / 2, pad, pad)
                }
                AlertDialog.Builder(act)
                    .setTitle(title.text)
                    .setView(android.widget.ScrollView(act).apply { addView(body) })
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }
}
