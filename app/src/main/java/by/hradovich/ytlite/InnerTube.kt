package by.hradovich.ytlite

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import kotlin.math.abs

data class Video(val id: String, val title: String, val channel: String, val meta: String, val thumb: String?)

class Page(val items: List<Video>, val continuation: String?)

/** Авторизованные запросы к внутреннему API YouTube: ленты, поиск, отметка просмотров. */
object InnerTube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private const val FALLBACK_VERSION = "2.20251110.01.00"

    @Volatile
    private var version: String? = null

    private fun clientVersion(): String = version
        ?: (runCatching { YoutubeParsingHelper.getClientVersion() }.getOrNull() ?: FALLBACK_VERSION)
            .also { version = it }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val ver = clientVersion()
        body.put(
            "context", JSONObject().put(
                "client", JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", ver)
                    .put("hl", "ru")
                    .put("gl", "BY")
            )
        )
        val rb = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Net.UA)
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", ver)
        Auth.headers().forEach { (k, v) -> rb.header(k, v) }
        Net.http.newCall(rb.build()).execute().use { r ->
            Auth.merge(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException("YouTube ответил ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    fun browse(browseId: String): Page = parse(post("browse", JSONObject().put("browseId", browseId)))

    /** params = фильтр «только видео». */
    fun search(query: String): Page =
        parse(post("search", JSONObject().put("query", query).put("params", "EgIQAQ%3D%3D")))

    fun more(endpoint: String, token: String): Page = parse(post(endpoint, JSONObject().put("continuation", token)))

    /** Ссылки статистики, через которые YouTube записывает просмотр в историю. */
    fun tracking(videoId: String): Pair<String?, String?> {
        val t = post("player", JSONObject().put("videoId", videoId)).optJSONObject("playbackTracking")
            ?: return null to null
        return t.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl") to
            t.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")
    }

    fun ping(url: String, params: Map<String, String>) {
        val u = url.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> setQueryParameter(k, v) }
        }?.build() ?: return
        val rb = Request.Builder().url(u).header("User-Agent", Net.UA)
        Auth.headers().forEach { (k, v) -> rb.header(k, v) }
        runCatching { Net.http.newCall(rb.build()).execute().close() }
    }

    // ---------- разбор ответов ----------

    private val VIDEO_KEYS = listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer")
    private val SKIP = setOf("responseContext", "frameworkUpdates", "topbar", "reelShelfRenderer", "trackingParams")

    private fun parse(root: JSONObject): Page {
        val out = LinkedHashMap<String, Video>()
        var cont: String? = null

        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    for (key in VIDEO_KEYS) {
                        n.optJSONObject(key)?.let { fromRenderer(it) }?.let { out.putIfAbsent(it.id, it) }
                    }
                    n.optJSONObject("lockupViewModel")?.let { fromLockup(it) }?.let { out.putIfAbsent(it.id, it) }
                    n.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { cont = it }
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k !in SKIP) walk(n.opt(k))
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }

        walk(root)
        return Page(out.values.toList(), cont)
    }

    private fun fromRenderer(v: JSONObject): Video? {
        val id = v.optString("videoId").ifEmpty { return null }
        val channel = (v.optJSONObject("ownerText")
            ?: v.optJSONObject("shortBylineText")
            ?: v.optJSONObject("longBylineText")).text()
        val meta = listOf(
            v.optJSONObject("lengthText").text(),
            (v.optJSONObject("shortViewCountText") ?: v.optJSONObject("viewCountText")).text(),
            v.optJSONObject("publishedTimeText").text(),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        return Video(
            id, v.optJSONObject("title").text(), channel, meta,
            pickThumb(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        )
    }

    private fun fromLockup(l: JSONObject): Video? {
        if (l.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l.optString("contentId").ifEmpty { return null }
        val md = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val parts = mutableListOf<String>()
        val rows = md?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        if (rows != null) for (i in 0 until rows.length()) {
            val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (j in 0 until ps.length()) {
                val t = ps.optJSONObject(j)?.optJSONObject("text")?.optString("content").orEmpty()
                if (t.isNotBlank()) parts += t
            }
        }
        val img = l.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
        val meta = (listOfNotNull(img?.let { findBadge(it) }) + parts.drop(1)).joinToString(" · ")
        return Video(
            id,
            md?.optJSONObject("title")?.optString("content").orEmpty(),
            parts.firstOrNull().orEmpty(),
            meta,
            pickThumb(img?.optJSONObject("image")?.optJSONArray("sources"))
        )
    }

    /** Длительность у новых карточек лежит в бейдже на превью. */
    private fun findBadge(n: Any?): String? = when (n) {
        is JSONObject -> n.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?: n.keys().asSequence().firstNotNullOfOrNull { findBadge(n.opt(it)) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { findBadge(n.opt(it)) }
        else -> null
    }

    /** Превью ~360px шириной: хватает для списка и не тянет лишний трафик. */
    private fun pickThumb(a: JSONArray?): String? {
        if (a == null) return null
        var best: JSONObject? = null
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            if (best == null || abs(t.optInt("width") - 360) < abs(best.optInt("width") - 360)) best = t
        }
        val u = best?.optString("url")?.takeIf { it.isNotEmpty() } ?: return null
        return if (u.startsWith("//")) "https:$u" else u
    }

    private fun JSONObject?.text(): String {
        if (this == null) return ""
        optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = optJSONArray("runs") ?: return optString("content")
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
}
