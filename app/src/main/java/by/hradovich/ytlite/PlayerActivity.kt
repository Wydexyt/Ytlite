package by.hradovich.ytlite

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
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
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var view: PlayerView
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val keepScreen = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            view.keepScreenOn = isPlaying
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        setContentView(view)
        applyFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { open(it) }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            setVideo(c, true)
            c.addListener(keepScreen)
            view.keepScreenOn = c.isPlaying
            view.player = c
            open(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.let {
            setVideo(it, false) // ушли с экрана — дальше только звук
            it.removeListener(keepScreen)
        }
        view.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    private fun open(c: MediaController) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        intent.removeExtra(EXTRA_ID)
        if (c.currentMediaItem?.mediaId == id) {
            c.play()
            return
        }
        c.setMediaItem(
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build())
                .build()
        )
        c.prepare()
        c.play()
    }

    private fun setVideo(c: MediaController, enabled: Boolean) {
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreen()
    }

    private fun applyFullscreen() {
        val ic = WindowCompat.getInsetsController(window, window.decorView)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
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
