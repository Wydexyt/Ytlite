package by.hradovich.ytlite

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val resolved = ConcurrentHashMap<String, Resolved>()
    private val main = Handler(Looper.getMainLooper())
    private val rn = AtomicInteger()

    private val dataSource: DataSource.Factory by lazy {
        ResolvingDataSource.Factory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
        ) { spec -> youtubeSpec(spec) }
    }

    override fun onCreate() {
        super.onCreate()

        // Буфер до 10 минут: модем скачивает большой кусок и надолго засыпает,
        // вместо того чтобы постоянно понемногу докачивать. Лимит 40 МБ — чтобы
        // с видео не съесть память.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 600_000, 1_000, 2_500)
            .setTargetBufferBytes(40 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(YtSourceFactory())
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Аппаратное декодирование звука DSP, когда видео выключено (фон).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .build()
            )
            .build()
        setVideo(false) // видео включается, только когда открыт экран плеера
        player.addListener(watchTracker)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveResume()
        reportWatchTime()
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun setVideo(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    // ---------- получение потоков ----------

    private var videoClients = 0

    private fun isVideoClient(c: MediaSession.ControllerInfo) = c.connectionHints.getBoolean(HINT_VIDEO, false)

    private val callback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val f = SettableFuture.create<MutableList<MediaItem>>()
            Net.io.execute {
                try {
                    val items = mediaItems.map { prepare(it) }.toMutableList()
                    main.post {
                        items.firstOrNull()?.let { applyQuality(resolved[it.mediaId]) }
                        f.set(items)
                    }
                } catch (e: Exception) {
                    ErrorLog.log("Открытие видео ${mediaItems.firstOrNull()?.mediaId}", e)
                    main.post {
                        // Не продолжать играть предыдущее видео, раз пользователь открыл другое
                        player.stop()
                        player.clearMediaItems()
                        Toast.makeText(this@PlaybackService, "Не удалось открыть видео: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    f.setException(e)
                }
            }
            return f
        }

        /** Открыт экран плеера — включаем видео. */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (isVideoClient(controller)) {
                videoClients++
                setVideo(!audioOnly)
            }
        }

        /** Экран плеера закрыт — видео не нужно, остаётся только звук. */
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (isVideoClient(controller)) {
                videoClients = maxOf(0, videoClients - 1)
                if (videoClients == 0) setVideo(false)
            }
        }
    }

    /** Ограничение качества по умолчанию (по меньшей стороне кадра) и сброс ручного выбора. */
    private fun applyQuality(r: Resolved?) {
        val q = (if (onWifi()) Prefs.qualityWifi else Prefs.qualityMobile).takeIf { it > 0 } ?: Int.MAX_VALUE
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (r?.portrait == true) b.setMaxVideoSize(q, Int.MAX_VALUE) else b.setMaxVideoSize(Int.MAX_VALUE, q)
        player.trackSelectionParameters = b.build()
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun prepare(item: MediaItem): MediaItem {
        val id = item.mediaId
        if (Prefs.sponsorBlock && !segments.containsKey(id)) {
            Net.io.execute { segments[id] = SponsorBlock.segments(id) }
        }
        val r = Streams.get(id)
        resolved[id] = r
        return item.buildUpon()
            .setUri("https://www.youtube.com/watch?v=$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(r.title)
                    .setArtist(r.uploader)
                    .setArtworkUri(r.thumb?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }

    private inner class YtSourceFactory : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val r = resolved[mediaItem.mediaId] ?: throw IllegalStateException("Видео не подготовлено")
            val parts = (r.videos + listOfNotNull(r.audio)).map { build(it, mediaItem) }
            return if (parts.size == 1) parts[0] else MergingMediaSource(true, true, *parts.toTypedArray())
        }
    }

    private fun build(src: Src, item: MediaItem): MediaSource {
        val mpd = src.mpd
        return if (mpd != null) {
            val manifest = DashManifestParser().parse(Uri.parse(src.url), ByteArrayInputStream(mpd.toByteArray()))
            DashMediaSource.Factory(dataSource).createMediaSource(manifest, item)
        } else {
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item.buildUpon().setUri(src.url).build())
        }
    }

    /** Запросы к googlevideo в том виде, в каком их ждёт YouTube (как в NewPipe). */
    private fun youtubeSpec(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.host?.endsWith(".googlevideo.com") != true || uri.path?.startsWith("/videoplayback") != true) return spec
        val url = uri.toString()
        val b = uri.buildUpon()
        val unset = C.LENGTH_UNSET.toLong()
        if (uri.getQueryParameter("range") == null && (spec.position != 0L || spec.length != unset)) {
            val end = if (spec.length == unset) "" else (spec.position + spec.length - 1).toString()
            b.appendQueryParameter("range", "${spec.position}-$end")
        }
        b.appendQueryParameter("rn", rn.incrementAndGet().toString())

        val headers = HashMap(spec.httpRequestHeaders)
        headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else Net.UA
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        return spec.buildUpon()
            .setUri(b.build())
            .setUriPositionOffset(0)
            .setPosition(0)
            .setLength(unset)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
    }

    // ---------- история просмотров в аккаунте ----------

    private var trackedId: String? = null
    @Volatile private var watchUrl: String? = null
    private var cpn = ""

    // ---------- SponsorBlock ----------

    private val segments = ConcurrentHashMap<String, List<Segment>>()
    private val skipMessages = ArrayList<PlayerMessage>()

    private fun scheduleSkips() {
        skipMessages.forEach { it.cancel() }
        skipMessages.clear()
        if (!Prefs.sponsorBlock) return
        val id = player.currentMediaItem?.mediaId ?: return
        val segs = segments[id]
        if (segs == null) {
            // сегменты ещё грузятся — попробуем чуть позже
            main.postDelayed({ if (player.currentMediaItem?.mediaId == id && segments.containsKey(id)) scheduleSkips() }, 1500)
            return
        }
        for (seg in segs) {
            skipMessages += player.createMessage { _, _ ->
                if (Prefs.sponsorBlock && player.currentMediaItem?.mediaId == id && player.currentPosition < seg.end - 500) {
                    player.seekTo(seg.end)
                    val what = SponsorBlock.CATEGORIES[seg.category] ?: "вставка"
                    Toast.makeText(this, "Пропущено: ${what.lowercase()}", Toast.LENGTH_SHORT).show()
                }
            }.setLooper(Looper.getMainLooper()).setPosition(seg.start).setDeleteAfterDelivery(false).send()
        }
    }

    // ---------- продолжение с места, история просмотров ----------

    private fun saveResume() {
        val id = player.currentMediaItem?.mediaId ?: return
        Prefs.saveResume(id, player.currentPosition, player.duration)
    }

    private val watchTracker = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = scheduleSkips()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            ErrorLog.log("Воспроизведение ${player.currentMediaItem?.mediaId} (${error.errorCodeName})", error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && player.playbackState != Player.STATE_BUFFERING) saveResume()
            if (!Auth.loggedIn) return
            val id = player.currentMediaItem?.mediaId ?: return
            if (isPlaying && id != trackedId) {
                trackedId = id
                watchUrl = null
                val c = newCpn().also { cpn = it }
                Net.io.execute {
                    runCatching {
                        val (playback, watchtime) = InnerTube.tracking(id)
                        watchUrl = watchtime
                        playback?.let { InnerTube.ping(it, mapOf("ver" to "2", "cpn" to c)) }
                    }
                }
            } else if (!isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                reportWatchTime()
            }
        }
    }

    private fun reportWatchTime() {
        val url = watchUrl ?: return
        val sec = (player.currentPosition / 1000).toString()
        val c = cpn
        Net.io.execute {
            InnerTube.ping(
                url, mapOf(
                    "ver" to "2", "cpn" to c, "cmt" to sec,
                    "st" to "0", "et" to sec, "el" to "detailpage"
                )
            )
        }
    }

    private fun newCpn(): String = (1..16).map { CPN[Random.nextInt(CPN.length)] }.joinToString("")

    companion object {
        const val HINT_VIDEO = "video"

        /** Режим «только звук»: видео не грузится даже при открытом экране плеера. */
        @Volatile
        var audioOnly = false
        private val POST_BODY = byteArrayOf(0x78, 0)
        private const val CPN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
