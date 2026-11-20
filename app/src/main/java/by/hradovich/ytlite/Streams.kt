package by.hradovich.ytlite

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
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

/** Видео 18+, которое наш плеер не смог получить: можно открыть в веб-плеере. */
class AgeGateException(message: String) : Exception(message)

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

    /** Видео, полученные от имени аккаунта (18+): при 403 их открываем в веб-плеере. */
    val viaAccount: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Забыть ссылки видео — следующий запрос получит свежие (возможно, с другого сервера). */
    fun invalidate(id: String) {
        cache.remove(id)
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
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        } catch (e: Exception) {
            val age = e is AgeRestrictedContentException ||
                e.message?.contains("age", ignoreCase = true) == true ||
                e.cause is AgeRestrictedContentException
            if (!age) throw e
            // Видео 18+: NewPipe ходит анонимно, поэтому запрашиваем от имени аккаунта
            if (!Auth.loggedIn) throw AgeGateException(Lang.str(R.string.err_age_login))
            ErrorLog.log("18+ $id: пробую через аккаунт", e)
            return try {
                AccountStreams.resolve(id).also { viaAccount += id }
            } catch (e2: Exception) {
                throw AgeGateException(e2.message ?: Lang.str(R.string.err_unavailable))
            }
        }
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
                ?: throw IllegalStateException(Lang.str(R.string.err_no_streams))
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

/**
 * Потоки для видео 18+: запрос /player от имени аккаунта (клиент YouTube для ТВ, он принимает
 * cookie и не требует PO-токена), расшифровка ссылок — средствами NewPipe.
 */
object AccountStreams {
    /** Варианты ТВ-клиента (как в yt-dlp): Samsung Tizen обходит «Необходимо перезагрузить страницу». */
    private class Client(val version: String, val ua: String, val device: Map<String, String> = emptyMap())

    private val CLIENTS = listOf(
        Client(
            "5.20260707",
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
        ),
        Client(
            "7.20260707.07.00",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
                "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        ),
    )

    fun resolve(id: String): Resolved {
        var last: Exception? = null
        for (c in CLIENTS) {
            try {
                return resolveWith(id, c)
            } catch (e: Exception) {
                last = e
                ErrorLog.log("18+ $id: клиент ТВ ${c.version}", e)
            }
        }
        throw last ?: IllegalStateException(Lang.str(R.string.err_unavailable))
    }

    private fun resolveWith(id: String, c: Client): Resolved {
        val client = JSONObject().put("clientName", "TVHTML5").put("clientVersion", c.version).put("hl", Lang.hl).put("gl", Lang.gl)
        c.device.forEach { (k, v) -> client.put(k, v) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject()
                .put("signatureTimestamp", YoutubeJavaScriptPlayerManager.getSignatureTimestamp(id))
                .put("html5Preference", "HTML5_PREF_WANTS")))
        val rb = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", c.ua)
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", c.version)
        Auth.headers().forEach { (k, v) -> rb.header(k, v) }
        val j = Net.http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("YouTube ответил ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        val ps = j.optJSONObject("playabilityStatus")
        if (ps?.optString("status") != "OK") {
            throw IllegalStateException(Lang.str(R.string.err_unavailable) + ": " + (ps?.optString("reason")?.ifEmpty { null } ?: ps?.optString("status")))
        }
        val details = j.optJSONObject("videoDetails")
        val dur = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0
        val sd = j.optJSONObject("streamingData") ?: throw IllegalStateException("no streamingData")
        val formats = sd.optJSONArray("adaptiveFormats") ?: org.json.JSONArray()

        class F(val o: JSONObject, val url: String, val itag: ItagItem)
        val list = ArrayList<F>()
        var withUrl = 0
        var withCipher = 0
        var unsupported = 0
        var firstError: Throwable? = null
        for (i in 0 until formats.length()) {
            val o = formats.optJSONObject(i) ?: continue
            if (o.has("url")) withUrl++
            if (o.has("signatureCipher")) withCipher++
            if (!ItagItem.isSupported(o.optInt("itag"))) unsupported++
            val f = runCatching { format(id, o, dur) }.onFailure { if (firstError == null) firstError = it }.getOrNull() ?: continue
            list += f.let { (url, item) -> F(o, url, item) }
        }
        val stats = "adaptive=${formats.length()} url=$withUrl cipher=$withCipher unsupported=$unsupported ok=${list.size} " +
            "muxed=${sd.optJSONArray("formats")?.length() ?: 0} sabr=${sd.has("serverAbrStreamingUrl")}"
        if (list.isEmpty()) ErrorLog.log("18+ $id: потоки не разобраны ($stats)", firstError ?: IllegalStateException(stats))
        val videos = list.filter { it.o.optString("mimeType").startsWith("video/") && it.o.optInt("height") > 0 }
            .groupBy { it.o.optInt("height") }
            .values.mapNotNull { same -> same.maxWithOrNull(compareBy<F>({ it.o.optInt("fps") }, { it.o.optString("mimeType").startsWith("video/webm") })) }
            .sortedByDescending { it.o.optInt("height") }
        val audio = list.filter { it.o.optString("mimeType").startsWith("audio/") }
            .sortedWith(
                compareByDescending<F> { it.o.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") ?: true }
                    .thenByDescending { it.o.optString("mimeType").startsWith("audio/mp4") }
                    .thenByDescending { it.o.optInt("bitrate") }
            ).firstOrNull()
        if (videos.isEmpty() || audio == null) {
            // Запасной вариант: совмещённый поток (видео+звук, обычно 360p)
            val muxed = sd.optJSONArray("formats")
            if (muxed != null) for (i in 0 until muxed.length()) {
                val o = muxed.optJSONObject(i) ?: continue
                val url = runCatching { plainUrl(id, o) }.onFailure { if (firstError == null) firstError = it }.getOrNull() ?: continue
                return Resolved(
                    videos = listOf(Src(url, null, o.optInt("width"), o.optInt("height"))),
                    audio = null,
                    title = details?.optString("title").orEmpty(),
                    uploader = details?.optString("author").orEmpty(),
                    thumb = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.let { it.optJSONObject(it.length() - 1)?.optString("url") },
                )
            }
            throw IllegalStateException(Lang.str(R.string.err_no_streams) + " ($stats)" + (firstError?.let { ": ${it.message}" } ?: ""))
        }

        fun src(f: F) = Src(
            f.url, runCatching { YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(f.url, f.itag, dur) }.getOrNull(),
            f.o.optInt("width"), f.o.optInt("height")
        )
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return Resolved(
            videos = videos.map { src(it) },
            audio = src(audio),
            title = details?.optString("title").orEmpty(),
            uploader = details?.optString("author").orEmpty(),
            thumb = thumbs?.let { it.optJSONObject(it.length() - 1)?.optString("url") },
        )
    }

    /** Готовая ссылка: подпись и параметр n расшифрованы. */
    private fun plainUrl(id: String, o: JSONObject): String? {
        var url = o.optString("url")
        if (url.isEmpty()) {
            val raw = o.optString("signatureCipher").ifEmpty { o.optString("cipher") }
            if (raw.isEmpty()) return null
            val cipher = android.net.Uri.parse("?$raw")
            val s = cipher.getQueryParameter("s") ?: return null
            val sp = cipher.getQueryParameter("sp") ?: "sig"
            val base = cipher.getQueryParameter("url") ?: return null
            url = android.net.Uri.parse(base).buildUpon()
                .appendQueryParameter(sp, YoutubeJavaScriptPlayerManager.deobfuscateSignature(id, s))
                .build().toString()
        }
        // Параметр n обязателен: без расшифровки YouTube отвечает 403, поэтому ошибка здесь
        // означает «наш плеер не может» → видео уйдёт в веб-плеер
        return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id, url)
    }

    /** Расшифрованная ссылка и описание потока для построения DASH-манифеста. */
    private fun format(id: String, o: JSONObject, dur: Long): Pair<String, ItagItem>? {
        val itagId = o.optInt("itag")
        if (!ItagItem.isSupported(itagId)) return null
        val url = plainUrl(id, o) ?: return null

        val item = ItagItem(ItagItem.getItag(itagId))
        item.setBitrate(o.optInt("bitrate"))
        item.setWidth(o.optInt("width"))
        item.setHeight(o.optInt("height"))
        if (o.has("fps")) item.setFps(o.optInt("fps"))
        o.optJSONObject("initRange")?.let { item.setInitStart(it.optString("start").toInt()); item.setInitEnd(it.optString("end").toInt()) }
        o.optJSONObject("indexRange")?.let { item.setIndexStart(it.optString("start").toInt()); item.setIndexEnd(it.optString("end").toInt()) }
        o.optString("contentLength").toLongOrNull()?.let { item.setContentLength(it) }
        item.setApproxDurationMs(o.optString("approxDurationMs").toLongOrNull() ?: (dur * 1000))
        item.setQuality(o.optString("quality"))
        Regex("codecs=\"([^\"]+)\"").find(o.optString("mimeType"))?.let { item.setCodec(it.groupValues[1]) }
        o.optString("audioSampleRate").toIntOrNull()?.let { item.setSampleRate(it) }
        if (o.has("audioChannels")) item.setAudioChannels(o.optInt("audioChannels"))
        return url to item
    }
}
