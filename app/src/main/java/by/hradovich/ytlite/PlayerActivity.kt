package by.hradovich.ytlite

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
class PlayerActivity : Activity() {

    private lateinit var playerBox: FrameLayout
    private lateinit var view: PlayerView
    private lateinit var quality: ImageButton
    private lateinit var details: RecyclerView
    private lateinit var overlay: TapOverlay

    private val header = HeaderAdapter()
    private val related = VideoAdapter { openRelated(it) }
    private var relatedContinuation: String? = null
    private var relatedLoading = false
    private var detailsFor: String? = null

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Ориентация, выставленная кнопкой; сбрасывается, когда телефон реально повернули так же. */
    private var forced: Int? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            view.keepScreenOn = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { loadDetails(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerBox = findViewById(R.id.playerBox)
        view = findViewById(R.id.playerView)
        quality = findViewById(R.id.quality)
        details = findViewById(R.id.details)

        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.setFullscreenButtonClickListener { toggleOrientation() }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            quality.visibility = v
        })

        overlay = TapOverlay(this) {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
        }
        view.overlayFrameLayout?.addView(
            overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        quality.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            TrackSelectionDialogBuilder(this, "Качество", c, C.TRACK_TYPE_VIDEO)
                .setTrackNameProvider { f -> "${minOf(f.width, f.height)}p" + if (f.frameRate > 30) "${f.frameRate.toInt()}" else "" }
                .setShowDisableOption(false)
                .setAllowAdaptiveSelections(false)
                .build()
                .show()
        }

        val lm = LinearLayoutManager(this)
        details.layoutManager = lm
        details.adapter = ConcatAdapter(header, related)
        details.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= rv.adapter!!.itemCount - 4) loadMoreRelated()
            }
        })

        applyLayout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { open(it) }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token)
            .setConnectionHints(bundleOf(PlaybackService.HINT_VIDEO to true)) // сервис включит видео
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            view.keepScreenOn = c.isPlaying
            view.player = c
            overlay.player = c
            if (!open(c)) c.currentMediaItem?.mediaId?.let { loadDetails(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        view.player = null
        overlay.player = null
        future?.let { MediaController.releaseFuture(it) } // сервис отключит видео, звук продолжится
        future = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        orientationListener.disable()
        super.onDestroy()
    }

    /** Открыть видео из intent. true — если было что открывать. */
    private fun open(c: MediaController): Boolean {
        val id = intent.getStringExtra(EXTRA_ID) ?: return false
        val title = intent.getStringExtra(EXTRA_TITLE)
        intent.removeExtra(EXTRA_ID)
        loadDetails(id, title)
        if (c.currentMediaItem?.mediaId == id) {
            c.play()
            return true
        }
        c.setMediaItem(
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        )
        c.prepare()
        c.play()
        return true
    }

    private fun openRelated(v: Video) {
        Streams.prefetch(v.id)
        details.scrollToPosition(0)
        setIntent(Intent(intent).putExtra(EXTRA_ID, v.id).putExtra(EXTRA_TITLE, v.title))
        controller?.let { open(it) }
    }

    // ---------- описание и рекомендации ----------

    private fun loadDetails(id: String, title: String? = null) {
        if (detailsFor == id) return
        detailsFor = id
        header.set(null, title)
        related.submit(emptyList())
        relatedContinuation = null
        Net.io.execute {
            val r = runCatching { InnerTube.next(id) }.getOrNull()
            runOnUiThread {
                if (detailsFor != id || r == null) return@runOnUiThread
                header.set(r, null)
                related.submit(r.related.items)
                relatedContinuation = r.related.continuation
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
            runOnUiThread {
                relatedLoading = false
                if (detailsFor != id) return@runOnUiThread
                r.onSuccess {
                    related.append(it.items)
                    relatedContinuation = it.continuation
                }.onFailure { relatedContinuation = null }
            }
        }
    }

    private class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.VH>() {
        private var d: Details? = null
        private var title: String? = null
        private var expanded = false

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.detTitle)
            val meta: TextView = v.findViewById(R.id.detMeta)
            val avatar: ImageView = v.findViewById(R.id.detAvatar)
            val channel: TextView = v.findViewById(R.id.detChannel)
            val subs: TextView = v.findViewById(R.id.detSubs)
            val desc: TextView = v.findViewById(R.id.detDesc)
        }

        fun set(details: Details?, fallbackTitle: String?) {
            d = details
            title = fallbackTitle
            expanded = false
            notifyItemChanged(0)
        }

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_details, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val d = d
            h.title.text = d?.title?.ifEmpty { null } ?: title.orEmpty()
            h.meta.text = d?.meta.orEmpty()
            h.channel.text = d?.channel.orEmpty()
            h.subs.text = d?.subs.orEmpty()
            h.avatar.loadAvatar(d?.avatar)
            h.desc.text = d?.description.orEmpty()
            h.desc.visibility = if (d?.description.isNullOrBlank()) View.GONE else View.VISIBLE
            h.desc.maxLines = if (expanded) Int.MAX_VALUE else 3
            h.desc.setOnClickListener {
                expanded = !expanded
                h.desc.maxLines = if (expanded) Int.MAX_VALUE else 3
            }
        }
    }

    // ---------- ориентация ----------

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleOrientation() {
        val target = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        forced = target
        requestedOrientation = target
        if (autoRotate()) orientationListener.enable()
    }

    private fun autoRotate() =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** Как в YouTube: после кнопки ориентация держится, пока телефон не повернут так же, потом снова работает автоповорот. */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                val f = forced ?: return disable()
                if (deg == ORIENTATION_UNKNOWN) return
                val physLandscape = deg in 60..120 || deg in 240..300
                val physPortrait = deg <= 20 || deg >= 340
                val matched = if (f == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) physPortrait else physLandscape
                if (matched) {
                    forced = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isLandscape) toggleOrientation() else super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun applyLayout() {
        val land = isLandscape
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (land) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
            details.visibility = View.GONE
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
            details.visibility = View.VISIBLE
        }
        playerBox.layoutParams = lp
        view.setFullscreenButtonState(land)

        val ic = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ic.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ic.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
    }
}
