package by.hradovich.ytlite

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Один поток: прямая ссылка и (если удалось) DASH-манифест для неё. */
class Src(val url: String, val mpd: String?, val width: Int = 0, val height: Int = 0)

class Resolved(
    val videos: List<Src>,   // все качества; выбирает плеер
    val audio: Src?,
    val title: String,
    val uploader: String,
    val thumb: String?,
) {
    val portrait: Boolean get() = videos.firstOrNull()?.let { it.height > it.width } ?: false
}

object Streams {
    private val pool = Executors.newCachedThreadPool()
    private class Entry(val time: Long, val future: Future<Resolved>)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL = 3L * 60 * 60 * 1000 // ссылки YouTube живут ~6 часов

    /** Начать получение потоков заранее (по тапу на видео). */
    fun prefetch(id: String) {
        future(id)
    }

    fun get(id: String): Resolved = try {
        future(id).get()
    } catch (e: ExecutionException) {
        cache.remove(id)
        throw (e.cause as? Exception) ?: e
    }

    @Synchronized
    private fun future(id: String): Future<Resolved> {
        val now = System.currentTimeMillis()
        cache[id]?.let { if (now - it.time < TTL) return it.future }
        cache.entries.removeIf { now - it.value.time >= TTL }
        val f = pool.submit(Callable { resolve(id) })
        cache[id] = Entry(now, f)
        return f
    }

    fun warmUp() {
        runCatching { InnerTube.clientVersion() }
        // Скачивает и кэширует JS-плеер YouTube (нужен для расшифровки ссылок)
        runCatching {
            org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                .getSignatureTimestamp("dQw4w9WgXcQ")
        }
    }

    private fun resolve(id: String): Resolved {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val dur = info.duration

        // Звук: оригинальная дорожка (не автодубляж), AAC — его чаще умеет декодировать
        // аудио-DSP (offload), процессор при этом спит.
        val audio = info.audioStreams.filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> {
                    it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
                }
                    .thenByDescending { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            )
            .firstOrNull()

        // По одному потоку на каждое качество
        val videos = info.videoOnlyStreams.filter { it.isUrl && it.height > 0 }
            .groupBy { it.height }
            .values.mapNotNull { same ->
                same.sortedWith(
                    compareByDescending<VideoStream> { it.fps }
                        .thenByDescending { it.format == MediaFormat.WEBM }
                ).firstOrNull()
            }
            .sortedByDescending { it.height }

        if (videos.isEmpty() || audio == null) {
            // Раздельных потоков нет — берём обычный (видео+звук вместе)
            val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
                ?: throw IllegalStateException("Нет доступных потоков")
            return Resolved(
                listOf(Src(muxed.content, null, muxed.width, muxed.height)), null,
                info.name, info.uploaderName.orEmpty(), info.thumbnails.maxByOrNull { it.width }?.url
            )
        }

        return Resolved(
            videos = videos.map { Src(it.content, mpd(it.content, it.itagItem, it.deliveryMethod, dur), it.width, it.height) },
            audio = Src(audio.content, mpd(audio.content, audio.itagItem, audio.deliveryMethod, dur)),
            title = info.name,
            uploader = info.uploaderName.orEmpty(),
            thumb = info.thumbnails.maxByOrNull { it.width }?.url,
        )
    }

    private fun mpd(url: String, itag: ItagItem?, method: DeliveryMethod, dur: Long): String? {
        itag ?: return null
        return runCatching {
            when (method) {
                DeliveryMethod.PROGRESSIVE_HTTP ->
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, dur)
                DeliveryMethod.DASH ->
                    YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(url, itag, dur)
                else -> null
            }
        }.getOrNull()
    }
}
