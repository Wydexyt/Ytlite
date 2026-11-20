package by.hradovich.ytlite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlayerScreen(private val act: MainActivity, val root: View) {

    private lateinit var playerBox: FrameLayout
    private lateinit var view: PlayerView
    private lateinit var settings: ImageButton
    private lateinit var audioBtn: ImageButton
    private lateinit var chapterView: TextView
    private var chapters: List<Pair<Long, String>> = emptyList()
    private var likeStatus = "INDIFFERENT"
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val chapterTick = object : Runnable {
        override fun run() {
            updateChapter()
            if (chapterView.visibility == View.VISIBLE) ticker.postDelayed(this, 1000)
        }
    }
    private lateinit var details: RecyclerView
    private lateinit var overlay: TapOverlay

    private val header = HeaderAdapter()
    private val related = VideoAdapter { openRelated(it) }
    private var relatedContinuation: String? = null
    private var relatedLoading = false
    private var detailsFor: String? = null
    private var current: Details? = null

    private var controller: MediaController? = null

    /** Видео, которое надо открыть, когда подключимся к сервису: id и позиция старта. */
    private var pending: Pair<String, Long>? = null
    private var pendingTitle: String? = null

    /** Ориентация, выставленная кнопкой; сбрасывается, когда телефон реально повернули так же. */
    private var forced: Int? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            view.keepScreenOn = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { loadDetails(it) }
        }

        /** Видео 18+, полученное через аккаунт, YouTube не отдал (403) — открываем веб-плеер. */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = detailsFor ?: return
            if (id in Streams.viaAccount && expanded && web == null &&
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            ) {
                controller?.stop()
                showWeb(id)
            }
        }
    }

    /** Настройка экрана; вызывается из init в конце класса — после инициализации всех полей. */
    private fun setup() {
        playerBox = root.findViewById(R.id.playerBox)
        view = root.findViewById(R.id.playerView)
        settings = root.findViewById(R.id.settings)
        audioBtn = root.findViewById(R.id.audioOnly)
        chapterView = root.findViewById(R.id.chapter)
        details = root.findViewById(R.id.details)

        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        // Кнопка ставит конкретную ориентацию (а не переключает), поэтому повторный вызов безопасен
        view.setFullscreenButtonClickListener { full -> setLandscape(full) }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            settings.visibility = v
            audioBtn.visibility = v
            hideBuiltInSettings()
            ticker.removeCallbacks(chapterTick)
            if (v == View.VISIBLE && chapters.isNotEmpty()) {
                chapterView.visibility = View.VISIBLE
                chapterTick.run()
            } else chapterView.visibility = View.GONE
        })
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setAdMarkerColor(0xFFFFFFFF.toInt())
            setPlayedAdMarkerColor(0xB3FFFFFF.toInt())
        }
        audioBtn.setOnClickListener { toggleAudioOnly() }
        updateAudioBtn()
        hideBuiltInSettings()

        overlay = TapOverlay(act) {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
        }
        view.overlayFrameLayout?.addView(
            overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.drag = dragHandler
        overlay.onPinch = { fill ->
            if (isLandscape) {
                view.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                overlay.showInfo(act.getString(if (fill) R.string.zoom_fill else R.string.zoom_fit))
            }
        }
        settings.setOnClickListener { showSettings() }

        val lm = LinearLayoutManager(act)
        details.layoutManager = lm
        details.adapter = ConcatAdapter(header, related)
        details.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= rv.adapter!!.itemCount - 4) loadMoreRelated()
            }
        })

        setupPanel()
        applyLayout()
    }

    // ---------- связь с плеером ----------

    /** Плеер развёрнут на экране: показываем видео. */
    var expanded = false
        private set

    fun attach(c: MediaController) {
        controller = c
        c.addListener(playerListener)
        if (expanded) bindView()
        if (!openPending()) c.currentMediaItem?.mediaId?.let { loadDetails(it) }
    }

    fun detach() {
        controller?.removeListener(playerListener)
        view.player = null
        overlay.player = null
        controller = null
    }

    fun setExpanded(e: Boolean) {
        expanded = e
        if (e) bindView() else {
            closeWeb()
            view.player = null
            overlay.player = null
            view.keepScreenOn = false
            closePanel(animate = false)
        }
        applyLayout()
    }

    private fun bindView() {
        val c = controller ?: return
        view.player = c
        overlay.player = c
        view.keepScreenOn = c.isPlaying
    }

    /** Открыть видео (из ленты, ссылки, рекомендаций). */
    fun open(id: String, title: String?, start: Long = 0) {
        Streams.prefetch(id)
        pending = id to start
        pendingTitle = title
        openPending()
        checkAgeGate(id)
    }

    /** Открыть ожидающее видео, если плеер подключён. true — если было что открывать. */
    private fun openPending(): Boolean {
        val c = controller ?: return false
        val (id, start) = pending ?: return false
        val title = pendingTitle
        pending = null
        loadDetails(id, title)
        if (c.currentMediaItem?.mediaId == id) {
            if (start > 0) c.seekTo(start)
            c.play()
            return true
        }
        // запомнить, где остановились в предыдущем видео
        c.currentMediaItem?.mediaId?.let { Prefs.saveResume(it, c.currentPosition, c.duration) }
        val from = if (start > 0) start else Prefs.resumePosition(id)
        c.setMediaItem(
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build(),
            from
        )
        c.prepare()
        c.play()
        return true
    }

    // ---------- запасной веб-плеер для видео 18+ ----------

    private var web: android.webkit.WebView? = null

    /** Если наш плеер не может получить видео 18+, открываем его в веб-плеере YouTube с входом в аккаунт. */
    private fun checkAgeGate(id: String) {
        closeWeb()
        if (!Auth.loggedIn) return
        Net.io.execute {
            val e = runCatching { Streams.get(id) }.exceptionOrNull()
            if (e is AgeGateException) act.runOnUiThread { if (detailsFor == id && expanded) showWeb(id) }
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(id: String) {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // cookie активного аккаунта — чтобы YouTube пустил к видео 18+
        Auth.active?.cookies?.split(';')?.forEach { c ->
            val kv = c.trim()
            if ('=' in kv) cm.setCookie("https://www.youtube.com", "$kv; Domain=.youtube.com; Path=/; Secure")
        }
        cm.flush()
        val w = android.webkit.WebView(act).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            webViewClient = android.webkit.WebViewClient()
            webChromeClient = android.webkit.WebChromeClient()
        }
        cm.setAcceptThirdPartyCookies(w, true)
        val holder = root.findViewById<FrameLayout>(R.id.webHolder)
        holder.removeAllViews()
        holder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        root.findViewById<View>(R.id.webBox).visibility = View.VISIBLE
        root.findViewById<View>(R.id.webClose).setOnClickListener {
            closeWeb()
            act.collapsePlayer()
        }
        w.loadUrl("https://m.youtube.com/watch?v=$id")
    }

    /** Веб-плеер в фоне не работает — закрываем при сворачивании или другом видео. */
    private fun closeWeb() {
        val w = web ?: return
        web = null
        root.findViewById<FrameLayout>(R.id.webHolder).removeAllViews()
        w.loadUrl("about:blank")
        w.destroy()
        root.findViewById<View>(R.id.webBox).visibility = View.GONE
    }

    fun release() {
        closeWeb()
        ticker.removeCallbacks(chapterTick)
        orientationListener.disable()
    }

    private fun openRelated(v: Video) {
        details.scrollToPosition(0)
        open(v.id, v.title)
    }

    private fun seekTo(ms: Long) {
        controller?.let {
            it.seekTo(ms)
            it.play()
        }
    }

    // ---------- поделиться ----------

    private fun share() {
        val id = detailsFor ?: return
        val title = current?.title
        val pos = controller?.takeIf { it.currentMediaItem?.mediaId == id }?.currentPosition ?: 0
        if (pos < 5_000) {
            Share.video(act, id, title)
            return
        }
        val s = pos / 1000
        val t = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        AlertDialog.Builder(act)
            .setItems(arrayOf(act.getString(R.string.share_link), act.getString(R.string.share_from, t))) { _, which ->
                Share.video(act, id, title, if (which == 1) pos else 0)
            }
            .show()
    }

    // ---------- лайки ----------

    private fun rate(status: String) {
        val id = detailsFor ?: return
        if (!Auth.loggedIn) {
            Toast.makeText(act, R.string.sign_in_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val old = likeStatus
        likeStatus = status
        header.notifyItemChanged(0)
        Net.io.execute {
            val ok = runCatching { InnerTube.rate(id, status) }.onFailure { ErrorLog.log("Оценка $status", it) }.isSuccess
            if (!ok) act.runOnUiThread {
                if (detailsFor == id) {
                    likeStatus = old
                    header.notifyItemChanged(0)
                }
                Toast.makeText(act, R.string.try_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- главы ----------

    private fun setChapters(list: List<Pair<Long, String>>) {
        chapters = list
        val times = list.drop(1).map { it.first }.toLongArray() // начало первой главы — 0, метка не нужна
        view.setExtraAdGroupMarkers(times, BooleanArray(times.size))
        if (list.isEmpty()) chapterView.visibility = View.GONE
    }

    private fun updateChapter() {
        val c = controller ?: return
        val pos = c.currentPosition
        val ch = chapters.lastOrNull { it.first <= pos }
        chapterView.text = ch?.second?.let { "•  $it" }.orEmpty()
    }

    // ---------- только звук ----------

    private fun toggleAudioOnly() {
        PlaybackService.audioOnly = !PlaybackService.audioOnly
        act.onAudioOnlyChanged()
        updateAudioBtn()
        Toast.makeText(act, if (PlaybackService.audioOnly) R.string.audio_only_on else R.string.audio_only_off, Toast.LENGTH_SHORT).show()
    }

    private fun updateAudioBtn() {
        audioBtn.setColorFilter(if (PlaybackService.audioOnly) act.getColor(R.color.link) else 0xFFFFFFFF.toInt())
    }

    // ---------- вертикальные жесты ----------

    private var dragStartX = 0f
    private var startBrightness = 0f
    private var startVolume = 0

    private val dragHandler = object : DragListener {
        override fun onDragStart(startXFraction: Float) {
            dragStartX = startXFraction
            if (isLandscape) {
                val b = act.window.attributes.screenBrightness
                startBrightness = if (b >= 0) b else
                    Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                startVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            }
        }

        override fun onDrag(dy: Float) {
            if (!isLandscape) {
                act.dragPlayer(dy) // свайп вниз — свернуть в мини-плеер
                return
            }
            val h = playerBox.height.toFloat().coerceAtLeast(1f)
            val delta = -dy / h * 1.2f
            if (dragStartX < 0.5f) {
                val b = (startBrightness + delta).coerceIn(0.01f, 1f)
                act.window.attributes = act.window.attributes.apply { screenBrightness = b }
                overlay.showInfo("☀  ${(b * 100).toInt()}%")
            } else {
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val v = (startVolume + delta * max).toInt().coerceIn(0, max)
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                overlay.showInfo("🔊  $v / $max")
            }
        }

        override fun onDragEnd(dy: Float) {
            if (!isLandscape) act.dragPlayerEnd(dy)
        }
    }

    private val audio by lazy { act.getSystemService(android.media.AudioManager::class.java) }

    // ---------- настройки плеера ----------

    /** Встроенную шестерёнку Media3 прячем — все настройки в нашей кнопке. */
    private fun hideBuiltInSettings() {
        view.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
    }

    private fun showSettings() {
        val c = controller ?: return
        val vs = c.videoSize
        val q = if (vs.height > 0) "${minOf(vs.width, vs.height)}p" else "—"
        val speed = c.playbackParameters.speed
        AlertDialog.Builder(act)
            .setItems(arrayOf(act.getString(R.string.quality_is, q), act.getString(R.string.speed_is, speedName(speed)))) { _, which ->
                if (which == 0) chooseQuality(c) else chooseSpeed(c)
            }
            .show()
    }

    private fun chooseQuality(c: MediaController) {
        TrackSelectionDialogBuilder(act, act.getString(R.string.quality), c, C.TRACK_TYPE_VIDEO)
            .setTrackNameProvider { f ->
                "${minOf(f.width, f.height)}p" + if (f.frameRate > 30) "${f.frameRate.toInt()}" else ""
            }
            .setShowDisableOption(false)
            .setAllowAdaptiveSelections(false)
            .build()
            .show()
    }

    private fun chooseSpeed(c: MediaController) {
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val cur = values.indexOfFirst { it == c.playbackParameters.speed }
        AlertDialog.Builder(act)
            .setTitle(R.string.speed)
            .setSingleChoiceItems(values.map { speedName(it) }.toTypedArray(), cur) { d, i ->
                c.setPlaybackSpeed(values[i])
                d.dismiss()
            }
            .show()
    }

    private fun speedName(s: Float) = if (s == 1f) act.getString(R.string.speed_normal) else "${s.toString().removeSuffix(".0")}x"

    // ---------- описание, канал, рекомендации ----------

    private fun loadDetails(id: String, title: String? = null) {
        if (detailsFor == id) return
        detailsFor = id
        current = null
        setChapters(emptyList())
        closePanel(animate = false)
        resetComments()
        header.set(null, title)
        related.submit(emptyList())
        relatedContinuation = null
        Net.io.execute {
            val r = runCatching { InnerTube.next(id) }.onFailure { ErrorLog.log("Страница видео $id", it) }.getOrNull()
            act.runOnUiThread {
                if (detailsFor != id || r == null) return@runOnUiThread
                current = r
                likeStatus = r.likeStatus
                setChapters(r.chapters)
                header.set(r, null)
                related.submit(r.related.items)
                relatedContinuation = r.related.continuation
                commentsToken = r.commentsToken
            }
        }
    }

    private fun loadMoreRelated() {
        if (relatedLoading) return
        val token = relatedContinuation ?: return
        val id = detailsFor ?: return
        relatedLoading = true
        Net.io.execute {
            val r = runCatching { InnerTube.more("next", token, exclude = id) }
            act.runOnUiThread {
                relatedLoading = false
                if (detailsFor != id) return@runOnUiThread
                r.onSuccess {
                    related.append(it.items)
                    relatedContinuation = it.continuation
                }.onFailure { relatedContinuation = null }
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.VH>() {
        private var d: Details? = null
        private var title: String? = null

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val info: View = v.findViewById(R.id.detInfo)
            val title: TextView = v.findViewById(R.id.detTitle)
            val meta: TextView = v.findViewById(R.id.detMeta)
            val avatar: ImageView = v.findViewById(R.id.detAvatar)
            val channel: TextView = v.findViewById(R.id.detChannel)
            val subs: TextView = v.findViewById(R.id.detSubs)
            val channelRow: View = v.findViewById(R.id.detChannelRow)
            val subscribe: TextView = v.findViewById(R.id.detSubscribe)
            val rateBox: View = v.findViewById(R.id.rateBox)
            val likeBtn: View = v.findViewById(R.id.likeBtn)
            val likeIcon: ImageView = v.findViewById(R.id.likeIcon)
            val likeCount: TextView = v.findViewById(R.id.likeCount)
            val dislikeBtn: View = v.findViewById(R.id.dislikeBtn)
            val dislikeIcon: ImageView = v.findViewById(R.id.dislikeIcon)
            val share: View = v.findViewById(R.id.shareBtn)
            val commentsCard: View = v.findViewById(R.id.commentsCard)
            val commentsHeader: TextView = v.findViewById(R.id.commentsHeader)
            val teaserRow: View = v.findViewById(R.id.teaserRow)
            val teaserAvatar: ImageView = v.findViewById(R.id.teaserAvatar)
            val teaserText: TextView = v.findViewById(R.id.teaserText)
        }

        fun set(details: Details?, fallbackTitle: String?) {
            d = details
            title = fallbackTitle
            notifyItemChanged(0)
        }

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_details, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val d = d
            h.title.text = d?.title?.ifEmpty { null } ?: title.orEmpty()
            h.meta.text = listOf(d?.meta.orEmpty(), if (d != null) act.getString(R.string.more) else "")
                .filter { it.isNotBlank() }.joinToString("  ")
            h.channel.text = d?.channel.orEmpty()
            h.subs.text = d?.subs.orEmpty()
            h.avatar.loadAvatar(d?.avatar)
            h.info.setOnClickListener { if (d != null) openDescription() }
            val ch = d?.channelId
            h.channelRow.setOnClickListener { ch?.let { act.openChannel(it) } }
            h.subscribe.bindSubscribe(ch, d?.subscribed ?: false)

            h.rateBox.visibility = if (d != null && (d.likes.isNotEmpty() || Auth.loggedIn)) View.VISIBLE else View.GONE
            h.likeCount.text = d?.likes.orEmpty().ifEmpty { act.getString(R.string.like) }
            val on = act.getColor(R.color.link)
            val off = act.getColor(R.color.text)
            h.likeIcon.setColorFilter(if (likeStatus == "LIKE") on else off)
            h.dislikeIcon.setColorFilter(if (likeStatus == "DISLIKE") on else off)
            h.likeBtn.setOnClickListener { rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE") }
            h.dislikeBtn.setOnClickListener { rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE") }
            h.share.setOnClickListener { share() }

            val hasComments = d != null && d.commentsToken != null
            h.commentsCard.visibility = if (hasComments) View.VISIBLE else View.GONE
            if (d != null) {
                h.commentsHeader.text = listOf(act.getString(R.string.comments), d.commentsCount).filter { it.isNotBlank() }.joinToString("  ")
                h.teaserRow.visibility = if (d.teaserText.isBlank()) View.GONE else View.VISIBLE
                h.teaserText.text = d.teaserText
                h.teaserAvatar.loadAvatar(d.teaserAvatar)
                h.commentsCard.setOnClickListener { openComments() }
            }
        }
    }

    // ---------- панель описания / комментариев ----------

    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelBack: View
    private lateinit var sortRow: View
    private lateinit var sortTop: TextView
    private lateinit var sortNew: TextView
    private lateinit var descScroll: ScrollView
    private lateinit var commentList: RecyclerView
    private lateinit var panelProgress: ProgressBar
    private lateinit var panelMessage: TextView
    private lateinit var inputRow: View
    private lateinit var input: EditText

    private enum class Mode { NONE, DESC, COMMENTS, REPLIES }
    private var mode = Mode.NONE

    private var commentsToken: String? = null
    private var commentsCont: String? = null
    private var commentsLoaded = false
    private var commentsLoading = false
    private var commentsGen = 0
    private var sortTokens: List<String> = emptyList()
    private var sortIndex = 0
    private var createParams: String? = null
    private val commentsAdapter = CommentAdapter { openReplies(it) }

    private var repliesParent: Comment? = null
    private var repliesCont: String? = null
    private var repliesLoading = false
    private val repliesAdapter = CommentAdapter(null)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanel() {
        panel = root.findViewById(R.id.panel)
        panelTitle = root.findViewById(R.id.panelTitle)
        panelBack = root.findViewById(R.id.panelBack)
        sortRow = root.findViewById(R.id.sortRow)
        sortTop = root.findViewById(R.id.sortTop)
        sortNew = root.findViewById(R.id.sortNew)
        descScroll = root.findViewById(R.id.descScroll)
        commentList = root.findViewById(R.id.commentList)
        panelProgress = root.findViewById(R.id.panelProgress)
        panelMessage = root.findViewById(R.id.panelMessage)
        inputRow = root.findViewById(R.id.inputRow)
        input = root.findViewById(R.id.input)

        root.findViewById<View>(R.id.panelClose).setOnClickListener { closePanel() }
        panelBack.setOnClickListener { openComments() }
        sortTop.setOnClickListener { selectSort(0) }
        sortNew.setOnClickListener { selectSort(1) }
        root.findViewById<View>(R.id.send).setOnClickListener { send() }
        root.findViewById<TextView>(R.id.descText).movementMethod = LinkMovementMethod.getInstance()

        val lm = LinearLayoutManager(act)
        commentList.layoutManager = lm
        commentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= (rv.adapter?.itemCount ?: 0) - 4) {
                    if (mode == Mode.COMMENTS) loadComments(more = true) else if (mode == Mode.REPLIES) loadReplies(more = true)
                }
            }
        })

        // Свайп вниз по шапке панели закрывает её
        var startY = 0f
        root.findViewById<View>(R.id.panelHeader).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { panel.translationY = (e.rawY - startY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (panel.translationY > panel.height * 0.25f) closePanel()
                    else panel.animate().translationY(0f).setDuration(150)
                    true
                }
                else -> false
            }
        }
    }

    private fun showPanel() {
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            panel.translationY = panel.height.takeIf { it > 0 }?.toFloat() ?: 600f
            panel.animate().translationY(0f).setDuration(200)
        }
    }

    private fun closePanel(animate: Boolean = true) {
        if (panel.visibility != View.VISIBLE) return
        hideKeyboard()
        mode = Mode.NONE
        if (animate) {
            panel.animate().translationY(panel.height.toFloat()).setDuration(180)
                .withEndAction { panel.visibility = View.GONE; panel.translationY = 0f }
        } else {
            panel.visibility = View.GONE
            panel.translationY = 0f
        }
    }

    private fun openDescription() {
        val d = current ?: return
        mode = Mode.DESC
        panelTitle.setText(R.string.description)
        panelBack.visibility = View.GONE
        sortRow.visibility = View.GONE
        inputRow.visibility = View.GONE
        commentList.visibility = View.GONE
        panelProgress.visibility = View.GONE
        panelMessage.visibility = View.GONE
        descScroll.visibility = View.VISIBLE
        descScroll.scrollTo(0, 0)

        root.findViewById<TextView>(R.id.descTitle).text = d.title
        val box = root.findViewById<LinearLayout>(R.id.factoids)
        box.removeAllViews()
        for ((value, label) in d.factoids) {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(TextView(act).apply {
                text = value
                textSize = 16f
                setTextColor(act.getColor(R.color.text))
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
            })
            col.addView(TextView(act).apply {
                text = label
                textSize = 12f
                setTextColor(act.getColor(R.color.sub))
                gravity = Gravity.CENTER
            })
            box.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.visibility = if (d.factoids.isEmpty()) View.GONE else View.VISIBLE
        val desc = root.findViewById<TextView>(R.id.descText)
        desc.text = TextUtil.linkify(d.description.ifBlank { act.getString(R.string.no_description) }) { seekTo(it) }
        showPanel()
    }

    // ---------- комментарии ----------

    private fun resetComments() {
        commentsGen++
        commentsToken = null
        commentsCont = null
        commentsLoaded = false
        commentsLoading = false
        sortTokens = emptyList()
        sortIndex = 0
        createParams = null
        commentsAdapter.submit(emptyList())
        repliesParent = null
        repliesAdapter.submit(emptyList())
    }

    private fun openComments() {
        mode = Mode.COMMENTS
        panelTitle.text = listOf(act.getString(R.string.comments), current?.commentsCount.orEmpty()).filter { it.isNotBlank() }.joinToString("  ")
        panelBack.visibility = View.GONE
        descScroll.visibility = View.GONE
        panelMessage.visibility = View.GONE
        commentList.visibility = View.VISIBLE
        commentList.adapter = commentsAdapter
        updateSortRow()
        updateInput()
        showPanel()
        if (!commentsLoaded && !commentsLoading) loadComments(more = false)
    }

    private fun updateSortRow() {
        sortRow.visibility = if (sortTokens.size >= 2) View.VISIBLE else View.GONE
        listOf(sortTop, sortNew).forEachIndexed { i, v ->
            val sel = i == sortIndex
            v.setBackgroundResource(if (sel) R.drawable.chip_selected else R.drawable.chip)
            v.setTextColor(if (sel) 0xFF0F0F0F.toInt() else act.getColor(R.color.text))
        }
    }

    private fun selectSort(i: Int) {
        if (i == sortIndex || i >= sortTokens.size) return
        sortIndex = i
        updateSortRow()
        commentsCont = null
        loadComments(more = false, token = sortTokens[i])
    }

    private fun updateInput() {
        val params = if (mode == Mode.REPLIES) repliesParent?.replyParams else createParams
        val show = Auth.loggedIn && params != null && (mode == Mode.COMMENTS || mode == Mode.REPLIES)
        inputRow.visibility = if (show) View.VISIBLE else View.GONE
        input.hint = if (mode == Mode.REPLIES) act.getString(R.string.reply_to, repliesParent?.author.orEmpty()) else act.getString(R.string.add_comment)
        root.findViewById<ImageView>(R.id.inputAvatar).loadAvatar(Auth.active?.avatar)
    }

    private fun loadComments(more: Boolean, token: String? = null) {
        if (commentsLoading && more) return
        val t = token ?: (if (more) commentsCont else commentsToken) ?: return
        val g = ++commentsGen
        commentsLoading = true
        if (!more) {
            commentsAdapter.submit(emptyList())
            panelProgress.visibility = View.VISIBLE
            panelMessage.visibility = View.GONE
        }
        Net.io.execute {
            val r = runCatching { InnerTube.comments(t) }
            act.runOnUiThread {
                if (g != commentsGen) return@runOnUiThread
                commentsLoading = false
                panelProgress.visibility = View.GONE
                r.onSuccess { p ->
                    if (more) commentsAdapter.append(p.items) else commentsAdapter.submit(p.items)
                    commentsCont = p.continuation
                    commentsLoaded = true
                    if (p.sortTokens.size >= 2) sortTokens = p.sortTokens
                    if (p.createParams != null) createParams = p.createParams
                    if (mode == Mode.COMMENTS) {
                        updateSortRow()
                        updateInput()
                        if (!more && p.items.isEmpty()) showPanelMessage(act.getString(R.string.no_comments))
                    }
                }.onFailure {
                    ErrorLog.log("Комментарии", it)
                    if (!more) showPanelMessage(act.getString(R.string.comments_failed))
                }
            }
        }
    }

    private fun openReplies(c: Comment) {
        mode = Mode.REPLIES
        repliesParent = c
        repliesCont = c.repliesToken
        panelTitle.setText(R.string.replies)
        panelBack.visibility = View.VISIBLE
        sortRow.visibility = View.GONE
        panelMessage.visibility = View.GONE
        repliesAdapter.submit(listOf(c))
        commentList.adapter = repliesAdapter
        updateInput()
        loadReplies(more = false)
    }

    private fun loadReplies(more: Boolean) {
        if (repliesLoading) return
        val t = repliesCont ?: return
        val parent = repliesParent ?: return
        repliesLoading = true
        if (!more) panelProgress.visibility = View.VISIBLE
        Net.io.execute {
            val r = runCatching { InnerTube.comments(t) }
            act.runOnUiThread {
                repliesLoading = false
                panelProgress.visibility = View.GONE
                if (repliesParent !== parent) return@runOnUiThread
                r.onSuccess {
                    repliesAdapter.append(it.items.filter { x -> x.id != parent.id })
                    repliesCont = it.continuation
                }.onFailure { repliesCont = null }
            }
        }
    }

    private fun showPanelMessage(text: String) {
        panelMessage.text = text
        panelMessage.visibility = View.VISIBLE
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val replyTo = if (mode == Mode.REPLIES) repliesParent else null
        val params = (if (replyTo != null) replyTo.replyParams else createParams) ?: return
        input.isEnabled = false
        Net.io.execute {
            val ok = runCatching {
                if (replyTo != null) InnerTube.createReply(params, text) else InnerTube.createComment(params, text)
            }.onFailure { ErrorLog.log("Отправка комментария", it) }.getOrDefault(false)
            act.runOnUiThread {
                input.isEnabled = true
                if (!ok) {
                    Toast.makeText(act, R.string.send_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                input.setText("")
                hideKeyboard()
                val acc = Auth.active
                val mine = Comment(
                    id = "local-${System.currentTimeMillis()}", author = acc?.name.orEmpty(), authorId = null, avatar = acc?.avatar,
                    text = text, time = act.getString(R.string.just_now), likes = "", replies = "", repliesToken = null, replyParams = null,
                )
                if (replyTo != null) repliesAdapter.insert(1, mine) else {
                    commentsAdapter.insert(0, mine)
                    commentList.scrollToPosition(0)
                }
                panelMessage.visibility = View.GONE
            }
        }
    }

    private fun hideKeyboard() =
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)

    private inner class CommentAdapter(private val onReplies: ((Comment) -> Unit)?) :
        RecyclerView.Adapter<CommentAdapter.VH>() {
        private val items = ArrayList<Comment>()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.cAvatar)
            val author: TextView = v.findViewById(R.id.cAuthor)
            val text: TextView = v.findViewById(R.id.cText)
            val likes: TextView = v.findViewById(R.id.cLikes)
            val replies: TextView = v.findViewById(R.id.cReplies)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Comment>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<Comment>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun insert(pos: Int, c: Comment) {
            val p = pos.coerceAtMost(items.size)
            items.add(p, c)
            notifyItemInserted(p)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            // в ответах всё, кроме первого (родительского) комментария, с отступом и мельче
            val nested = onReplies == null && position > 0
            val size = (act.resources.displayMetrics.density * if (nested) 24 else 32).toInt()
            h.avatar.layoutParams = h.avatar.layoutParams.apply { width = size; height = size }
            h.itemView.setPadding(
                (act.resources.displayMetrics.density * if (nested) 56 else 16).toInt(),
                h.itemView.paddingTop, h.itemView.paddingRight, h.itemView.paddingBottom
            )
            h.avatar.loadAvatar(c.avatar)
            val aid = c.authorId
            h.avatar.setOnClickListener(if (aid != null) View.OnClickListener { act.openChannel(aid) } else null)
            h.author.text = listOf(c.author, c.time).filter { it.isNotBlank() }.joinToString(" · ")
            h.text.text = TextUtil.linkify(c.text) { seekTo(it) }
            h.text.movementMethod = LinkMovementMethod.getInstance()
            h.likes.text = c.likes
            val showReplies = onReplies != null && c.repliesToken != null
            h.replies.visibility = if (showReplies) View.VISIBLE else View.GONE
            if (showReplies) {
                h.replies.text = repliesText(c.replies)
                h.replies.setOnClickListener { onReplies?.invoke(c) }
            }
        }
    }

    /** «12 ответов» / «1,2 тыс. ответов» на языке интерфейса. */
    private fun repliesText(n: String): String {
        if (n.isBlank()) return act.getString(R.string.replies)
        val num = n.trim().toIntOrNull()
        return if (num != null) act.resources.getQuantityString(R.plurals.replies, num, n.trim())
        else act.getString(R.string.replies_count, n.trim())
    }

    // ---------- ориентация ----------

    private val isLandscape get() = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Поставить нужную ориентацию. Если она уже такая — ничего не делать (без зацикливания). */
    private fun setLandscape(land: Boolean) {
        if (land == isLandscape) return
        val target = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        forced = target
        act.requestedOrientation = target
        if (autoRotate()) orientationListener.enable()
    }

    private fun autoRotate() =
        Settings.System.getInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** Как в YouTube: после кнопки ориентация держится, пока телефон не повернут так же, потом снова работает автоповорот. */
    private val orientationListener by lazy {
        object : OrientationEventListener(act) {
            override fun onOrientationChanged(deg: Int) {
                val f = forced ?: return disable()
                if (deg == ORIENTATION_UNKNOWN) return
                val physLandscape = deg in 60..120 || deg in 240..300
                val physPortrait = deg <= 20 || deg >= 340
                val matched = if (f == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) physPortrait else physLandscape
                if (matched) {
                    forced = null
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
    }

    /** «Назад» внутри плеера. false — пусть плеер сворачивается. */
    fun onBack(): Boolean = when {
        web != null -> { closeWeb(); false }
        isLandscape -> { setLandscape(false); true }
        mode == Mode.REPLIES -> { openComments(); true }
        panel.visibility == View.VISIBLE -> { closePanel(); true }
        else -> false
    }

    fun onConfigurationChanged() = applyLayout()

    /** Свернуть: сначала вернуть портретную ориентацию. */
    fun prepareCollapse() {
        if (isLandscape) {
            setLandscape(false) // портрет держится, пока телефон не повернут вертикально
            return
        }
        if (forced != null || act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            forced = null
            orientationListener.disable()
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyLayout() {
        val land = isLandscape
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (land) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
            root.findViewById<View>(R.id.below).visibility = View.GONE
        } else {
            lp.height = act.resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
            root.findViewById<View>(R.id.below).visibility = View.VISIBLE
        }
        playerBox.layoutParams = lp
        view.setFullscreenButtonState(land)
        if (!land) view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        // В полноэкранном режиме — под скрытыми системными панелями, чтобы кадр был ровно по центру
        val full = land && expanded
        WindowCompat.setDecorFitsSystemWindows(act.window, !full)

        val ic = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (full) {
            ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ic.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ic.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    init {
        setup()
    }
}
