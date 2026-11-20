package by.hradovich.ytlite

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

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
        instance = this

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
        applySound()
        Queue.listen(queueListener)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        Prefs.listen(prefsListener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EXPAND, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Кнопки «следующее/предыдущее» (в уведомлении, на экране блокировки и в плеере) — наша очередь
        session = MediaSession.Builder(this, QueuePlayer(player))
            .setCallback(callback)
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        Queue.unlisten(queueListener)
        main.removeCallbacksAndMessages(null)
        releaseEffects()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        Prefs.unlisten(prefsListener)
        closeSeg()
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
            playToken++ // открыто вручную — отменить ещё не начавшееся автовоспроизведение
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
                        // 18+ со входом откроется в веб-плеере — сообщение не нужно
                        if (!(e is AgeGateException && Auth.loggedIn)) {
                            Toast.makeText(this@PlaybackService, getString(R.string.open_failed, e.message), Toast.LENGTH_LONG).show()
                        }
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
                setVideo(!audioOnly && !isMusic(player.currentMediaItem))
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

    // ---------- повтор при сетевой ошибке ----------

    private val retried = HashSet<String>()

    /**
     * Сервер видео не отвечает или ссылка протухла: один раз запрашиваем ссылки заново
     * (YouTube часто даёт другой узел) и продолжаем с того же места.
     */
    private fun retryWithFreshLinks(id: String, error: androidx.media3.common.PlaybackException) {
        val code = error.errorCode
        val network = code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (!network || id in Streams.viaAccount || !retried.add(id)) return
        val item = player.currentMediaItem ?: return
        val pos = player.currentPosition
        Streams.invalidate(id)
        Net.io.execute {
            val r = runCatching { Streams.get(id) }.onFailure { ErrorLog.log("Повтор $id", it) }.getOrNull() ?: return@execute
            main.post {
                if (player.currentMediaItem?.mediaId != id) return@post
                resolved[id] = r
                applyQuality(r)
                player.setMediaItem(item, pos)
                player.prepare()
                player.play()
            }
        }
    }

    // ---------- качество: сразу при смене сети или настройки ----------

    private var lastWifi: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (wifi != lastWifi) {
                val changed = lastWifi != null
                lastWifi = wifi
                if (changed) main.post { reapplyQuality() }
            }
        }
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "quality" || key == "qualityMobile") main.post { reapplyQuality() }
        if (key == "boostDb" || key == "nightMode") main.post { applySound() }
        if (key == "normalize") main.post { applyVolume() }
        if (key == "musicRepeat") main.post { applyRepeat() }
    }

    private fun reapplyQuality() {
        val id = player.currentMediaItem?.mediaId ?: return
        applyQuality(resolved[id])
        qualityFor = null
        if (selectQuality()) qualityFor = id
    }

    /** Видео, для которого качество уже выбрано автоматически (дальше — только ручной выбор). */
    private var qualityFor: String? = null

    /**
     * Явно выбрать поток нужного качества: самый высокий не выше настройки (по меньшей стороне кадра),
     * а если такого нет — самый низкий. Надёжнее мягкого ограничения размера.
     */
    private fun selectQuality(): Boolean {
        val q = (if (onWifi()) Prefs.qualityWifi else Prefs.qualityMobile).takeIf { it > 0 } ?: Int.MAX_VALUE
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        if (groups.isEmpty()) return false
        fun h(g: androidx.media3.common.Tracks.Group) = g.getTrackFormat(0).let { minOf(it.width, it.height) }
        val best = groups.filter { h(it) in 1..q }.maxByOrNull { h(it) } ?: groups.minByOrNull { h(it) } ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(best.mediaTrackGroup, 0))
            .build()
        return true
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
                item.mediaMetadata.buildUpon()
                    .setTitle(r.title)
                    .setArtist(item.mediaMetadata.artist ?: r.uploader)
                    .setArtworkUri(item.mediaMetadata.artworkUri ?: r.thumb?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }

    private inner class YtSourceFactory : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val r = resolved[mediaItem.mediaId] ?: throw IllegalStateException("not prepared")
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
                    val what = SponsorBlock.CATEGORIES[seg.category]?.let { getString(it) } ?: getString(R.string.sb_segment)
                    Toast.makeText(this, getString(R.string.sb_skipped, what.lowercase()), Toast.LENGTH_SHORT).show()
                    Stats.skipped(seg.end - seg.start)
                }
            }.setLooper(Looper.getMainLooper()).setPosition(seg.start).setDeleteAfterDelivery(false).send()
        }
    }

    // ---------- продолжение с места, история просмотров ----------

    private fun saveResume() {
        val id = player.currentMediaItem?.mediaId ?: return
        Prefs.saveResume(id, player.currentPosition, player.duration)
    }

    // ---------- статистика (только по событиям плеера) ----------

    private var segStart = 0L
    private var segBg = false
    private var segBitrate = 0L

    private fun videoOff() = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)

    /** Примерный битрейт видео, которое грузилось бы сейчас (для оценки сэкономленного трафика). */
    private fun videoBitrate(): Long {
        val r = player.currentMediaItem?.mediaId?.let { resolved[it] }
        val q = (if (onWifi()) Prefs.qualityWifi else Prefs.qualityMobile).takeIf { it > 0 } ?: Int.MAX_VALUE
        val h = r?.videos?.map { minOf(it.width, it.height) }?.filter { it <= q }?.maxOrNull() ?: 720
        return when {
            h >= 2160 -> 16_000_000L
            h >= 1440 -> 9_000_000L
            h >= 1080 -> 4_500_000L
            h >= 720 -> 2_500_000L
            h >= 480 -> 1_200_000L
            h >= 360 -> 700_000L
            else -> 300_000L
        }
    }

    private fun startSeg() {
        segStart = android.os.SystemClock.elapsedRealtime()
        segBg = videoOff()
        segBitrate = if (segBg) videoBitrate() else 0
    }

    private fun closeSeg() {
        if (segStart == 0L) return
        val dt = android.os.SystemClock.elapsedRealtime() - segStart
        segStart = 0
        Stats.add(dt, if (segBg) dt else 0, if (segBg) dt * segBitrate / 8 / 1000 else 0, player.mediaMetadata.artist?.toString())
    }

    private val watchTracker = object : Player.Listener {
        /** Как только у видео известны доступные качества — выбрать по настройке (один раз на видео). */
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (qualityFor != id && selectQuality()) qualityFor = id
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scheduleSkips()
            if (mediaItem == null) return
            Stats.video()
            val md = mediaItem.mediaMetadata
            val music = isMusic(mediaItem)
            Queue.played(QItem(mediaItem.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.artworkUri?.toString(), music))
            player.setPlaybackSpeed(if (music) 1f else Prefs.speed)
            if (music) setVideo(false) // у музыки видео не грузим вообще
            applyRepeat()
            applyVolume()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded()
            if (state == Player.STATE_READY) prefetchNext()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = applySound()

        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
            // включили/выключили видео посреди воспроизведения — новый отрезок
            if (player.isPlaying && segBg != videoOff()) {
                closeSeg()
                startSeg()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            ErrorLog.log("Воспроизведение $id (${error.errorCodeName})", error)
            if (id != null) retryWithFreshLinks(id, error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startSeg()
                player.currentMediaItem?.mediaId?.let { retried.remove(it) } // заиграло — повтор снова доступен
            } else closeSeg()
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

    // ---------- очередь и автовоспроизведение ----------

    /** Пока играет текущее — заранее получить ссылки на следующее в очереди, чтобы переключение было мгновенным. */
    private fun prefetchNext() {
        Queue.items.firstOrNull()?.let { Streams.prefetch(it.id) }
    }

    private val queueListener: () -> Unit = { if (player.isPlaying) prefetchNext() }

    /** Растёт при каждом новом открытии: устаревшие фоновые загрузки не перебивают выбор пользователя. */
    private var playToken = 0

    val currentIsMusic: Boolean get() = isMusic(player.currentMediaItem)

    /** Включить элемент очереди (потоки получаем в фоне). */
    fun play(q: QItem) {
        val token = ++playToken
        Streams.prefetch(q.id)
        Net.io.execute {
            val item = runCatching {
                prepare(
                    MediaItem.Builder().setMediaId(q.id).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(q.title).setArtist(q.channel.ifEmpty { null })
                            .setArtworkUri(q.thumb?.let { Uri.parse(it) })
                            .setExtras(android.os.Bundle().apply { putBoolean(EXTRA_MUSIC, q.music) })
                            .build()
                    ).build()
                )
            }.onFailure { ErrorLog.log("Очередь ${q.id}", it) }.getOrNull()
            main.post {
                if (token != playToken) return@post
                if (item == null) {
                    // не открылось (например, 18+) — дальше по очереди
                    Toast.makeText(this, getString(R.string.open_failed, q.title), Toast.LENGTH_SHORT).show()
                    playNext(fromUser = false)
                    return@post
                }
                saveResume()
                applyQuality(resolved[q.id])
                qualityFor = null
                player.setMediaItem(item, if (q.music) 0 else Prefs.resumePosition(q.id))
                player.prepare()
                player.play()
            }
        }
    }

    /** Добавить в очередь; если ничего не играет — сразу включить. */
    fun enqueue(q: QItem, next: Boolean) {
        if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
            play(q)
            return
        }
        if (next) Queue.addNext(q) else Queue.addLast(q)
    }

    private fun onEnded() {
        if (sleepAtEnd) {
            sleepAtEnd = false
            listeners()
            return
        }
        playNext(fromUser = false)
    }

    /**
     * Следующее: из очереди, иначе — по автовоспроизведению (рекомендации для видео, «радио» для музыки).
     * Кнопка «следующее» работает всегда, даже с выключенным автовоспроизведением.
     */
    fun playNext(fromUser: Boolean) {
        Queue.poll()?.let { play(it); return }
        val cur = player.currentMediaItem ?: return
        val music = isMusic(cur)
        // музыка: «повторять всё» — список заново
        if (music && Prefs.musicRepeat == 1 && Queue.source.isNotEmpty()) {
            val list = if (Queue.shuffled) Queue.source.shuffled() else Queue.source
            Queue.replace(list.drop(1))
            play(list[0])
            return
        }
        if (!fromUser && !(if (music) Prefs.musicRadio else Prefs.autoplay)) return
        val token = ++playToken
        val seen = Queue.history.map { it.id }.toSet()
        Net.io.execute {
            val next = runCatching { autoNext(cur.mediaId, music, seen) }
                .onFailure { ErrorLog.log("Автовоспроизведение ${cur.mediaId}", it) }.getOrNull()
            main.post {
                if (token != playToken || next.isNullOrEmpty()) return@post
                // для музыки «радио» — сразу несколько треков в очередь
                Queue.addAll(next.drop(1))
                play(next.first())
            }
        }
    }

    private fun autoNext(id: String, music: Boolean, seen: Set<String>): List<QItem> =
        if (music) Music.radio(id).filter { it.id !in seen }
        else InnerTube.next(id).related.items.filter { !it.isChannel && it.id !in seen }.take(1).map { QItem.of(it) }

    /** «Назад»: в начало ролика, а в самом начале — к предыдущему. */
    fun playPrevious() {
        val cur = player.currentMediaItem
        val h = Queue.history
        if (player.currentPosition > 3000 || cur == null || h.size < 2) {
            player.seekTo(0)
            return
        }
        val now = h.removeAt(h.size - 1)
        val prev = h.removeAt(h.size - 1)
        Queue.addNext(now)
        play(prev)
    }

    private val NAV = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    private inner class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        private fun withNav(c: Player.Commands) = c.buildUpon().addAll(*NAV).build()
        private val wrapped = HashMap<Player.Listener, Player.Listener>()

        /**
         * ExoPlayer сообщает подписчикам свои команды — без «следующего» (в плеере один ролик).
         * Без подмены сессия передаёт это экранам, и кнопка «Следующий» в приложении перестаёт работать.
         */
        override fun addListener(listener: Player.Listener) {
            val w = object : Player.Listener by listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
                    listener.onAvailableCommandsChanged(withNav(availableCommands))
            }
            wrapped[listener] = w
            super.addListener(w)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(wrapped.remove(listener) ?: listener)
        }

        override fun getAvailableCommands(): Player.Commands = withNav(super.getAvailableCommands())
        override fun isCommandAvailable(command: Int) = command in NAV || super.isCommandAvailable(command)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = playNext(fromUser = true)
        override fun seekToNextMediaItem() = playNext(fromUser = true)
        override fun seekToPrevious() = playPrevious()
        override fun seekToPreviousMediaItem() = playPrevious()
    }

    private fun applyRepeat() {
        player.repeatMode = if (isMusic(player.currentMediaItem) && Prefs.musicRepeat == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // ---------- звук: выравнивание, усиление, ночной режим ----------

    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var dynamics: android.media.audiofx.AudioEffect? = null
    private var fade = 1f

    private fun releaseEffects() {
        runCatching { enhancer?.release() }
        runCatching { dynamics?.release() }
        enhancer = null
        dynamics = null
    }

    /** Эффекты висят на аудиосессии плеера. С ними звук идёт через процессор — offload выключаем. */
    private fun applySound() {
        releaseEffects()
        val session = player.audioSessionId
        val boost = Prefs.boostDb
        val night = Prefs.nightMode && android.os.Build.VERSION.SDK_INT >= 28
        if (session != C.AUDIO_SESSION_ID_UNSET) {
            if (boost > 0) enhancer = runCatching {
                android.media.audiofx.LoudnessEnhancer(session).apply { setTargetGain(boost * 100); enabled = true }
            }.onFailure { ErrorLog.log("Усиление звука", it) }.getOrNull()
            if (night && android.os.Build.VERSION.SDK_INT >= 28) dynamics = runCatching { nightMode(session) }
                .onFailure { ErrorLog.log("Ночной режим", it) }.getOrNull()
        }
        val offload = enhancer == null && dynamics == null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder().setAudioOffloadMode(
                    if (offload) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                ).build()
            ).build()
    }

    /** Компрессор: тихое громче, громкое тише (как «ночной режим» в телевизорах) + ограничитель от перегруза. */
    @androidx.annotation.RequiresApi(28)
    private fun nightMode(session: Int): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, true, 1, false, 0, true
        ).build()
        return DynamicsProcessing(0, session, cfg).apply {
            setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20000f, 5f, 200f, 4f, -30f, 6f, -90f, 1f, 0f, 9f))
            setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
            enabled = true
        }
    }

    /** Громкие ролики приглушаем до эталона YouTube (только ослабление — работает и с offload). */
    private fun applyVolume() {
        val loud = player.currentMediaItem?.mediaId?.let { resolved[it]?.loudnessDb } ?: 0f
        val base = if (Prefs.normalize && loud > 0) Math.pow(10.0, -loud / 20.0).toFloat() else 1f
        player.volume = base * fade
    }

    // ---------- таймер сна ----------

    /** Когда остановить (elapsedRealtime), 0 — таймер выключен. */
    var sleepAt = 0L
        private set
    var sleepAtEnd = false
        private set
    private val sleepListeners = ArrayList<() -> Unit>()

    fun listenSleep(l: () -> Unit) { sleepListeners += l }
    fun unlistenSleep(l: () -> Unit) { sleepListeners -= l }
    private fun listeners() = sleepListeners.toList().forEach { it() }

    private val sleepRun = object : Runnable {
        override fun run() {
            val left = sleepAt - android.os.SystemClock.elapsedRealtime()
            if (left > FADE_MS) {
                main.postDelayed(this, left - FADE_MS)
                return
            }
            // плавное затухание за 10 секунд, потом пауза
            fade = (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
            applyVolume()
            if (left > 0) main.postDelayed(this, 250) else {
                player.pause()
                sleepAt = 0
                fade = 1f
                applyVolume()
                listeners()
            }
        }
    }

    /** minutes > 0 — через N минут; 0 — выключить; -1 — в конце текущего видео. */
    fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRun)
        fade = 1f
        applyVolume()
        sleepAt = 0
        sleepAtEnd = minutes < 0
        if (minutes > 0) {
            sleepAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
            main.post(sleepRun)
        }
        listeners()
    }

    private fun newCpn(): String = (1..16).map { CPN[Random.nextInt(CPN.length)] }.joinToString("")

    companion object {
        const val HINT_VIDEO = "video"
        const val EXTRA_MUSIC = "music"
        private const val FADE_MS = 10_000L

        fun isMusic(item: MediaItem?) = item?.mediaMetadata?.extras?.getBoolean(EXTRA_MUSIC) == true

        /** Сервис работает в том же процессе — экраны обращаются к нему напрямую (только из главного потока). */
        var instance: PlaybackService? = null
            private set

        /** Режим «только звук»: видео не грузится даже при открытом экране плеера. */
        @Volatile
        var audioOnly = false
        private val POST_BODY = byteArrayOf(0x78, 0)
        private const val CPN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
