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

/** Один поток: прямая ссылка и (если удалось) DASH-манифест для неё. */
class Src(val url: String, val mpd: String?)

class Resolved(
    val video: Src?,
    val audio: Src?,
    val title: String,
    val uploader: String,
    val thumb: String?,
)

object Streams {
    /** Потолок качества. 1080p на экране телефона более чем достаточно. */
    @Volatile
    var maxHeight = 1080

    fun resolve(id: String): Resolved {
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

        val video = info.videoOnlyStreams.filter { it.isUrl && it.height in 1..maxHeight }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.format == MediaFormat.WEBM }
            )
            .firstOrNull()

        // Если раздельных потоков нет — берём обычный (видео+звук вместе).
        val muxed = if (video == null || audio == null) {
            info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
        } else null

        return Resolved(
            video = if (muxed != null) Src(muxed.content, null)
            else video?.let { Src(it.content, mpd(it.content, it.itagItem, it.deliveryMethod, dur)) },
            audio = if (muxed != null) null
            else audio?.let { Src(it.content, mpd(it.content, it.itagItem, it.deliveryMethod, dur)) },
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
