package by.hradovich.ytlite

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import kotlin.math.abs

/** Запросы к YouTube Music (клиент WEB_REMIX) от имени активного аккаунта. */
object Music {
    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private const val ORIGIN = "https://music.youtube.com"
    private const val FALLBACK_VERSION = "1.20251110.01.00"

    @Volatile
    private var version: String? = null

    private fun clientVersion(): String = version
        ?: (runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }.getOrNull() ?: FALLBACK_VERSION)
            .also { version = it }

    fun post(endpoint: String, body: JSONObject): JSONObject {
        val ver = clientVersion()
        body.put(
            "context", JSONObject().put(
                "client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", ver)
                    .put("hl", Lang.hl)
                    .put("gl", Lang.gl)
            )
        )
        val rb = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Net.UA)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", ver)
            .header("Referer", "$ORIGIN/")
        Auth.headers(ORIGIN).forEach { (k, v) -> rb.header(k, v) }
        Net.http.newCall(rb.build()).execute().use { r ->
            Auth.merge(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException(Lang.str(R.string.err_http, r.code))
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    /** «Радио» по треку: похожие треки, как микс в YouTube Music. Первый элемент — сам трек, его пропускаем. */
    fun radio(videoId: String): List<QItem> = watchPlaylist(videoId, "RDAMVM$videoId").filter { it.id != videoId }

    /** Очередь проигрывания из /next: плейлист или радио. */
    fun watchPlaylist(videoId: String?, playlistId: String): List<QItem> {
        val body = JSONObject().put("playlistId", playlistId).put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        if (videoId != null) body.put("videoId", videoId)
        val out = LinkedHashMap<String, QItem>()
        walk(post("next", body)) { o ->
            o.optJSONObject("playlistPanelVideoRenderer")?.let { panelItem(it) }?.let { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    private fun panelItem(r: JSONObject): QItem? {
        val id = r.optString("videoId").ifEmpty { return null }
        val by = r.optJSONObject("shortBylineText").text().ifEmpty { r.optJSONObject("longBylineText").text().substringBefore(" • ") }
        return QItem(id, r.optJSONObject("title").text(), by, thumb(r.optJSONObject("thumbnail")), music = true)
    }

    // ---------- разбор ----------

    fun walk(n: Any?, f: (JSONObject) -> Unit) {
        when (n) {
            is JSONObject -> {
                f(n)
                val keys = n.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "responseContext" && k != "frameworkUpdates") walk(n.opt(k), f)
                }
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), f)
        }
    }

    fun find(n: Any?, key: String): JSONObject? = when (n) {
        is JSONObject -> n.optJSONObject(key) ?: n.keys().asSequence().firstNotNullOfOrNull { find(n.opt(it), key) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { find(n.opt(it), key) }
        else -> null
    }

    /** Самая крупная обложка (у YouTube Music — квадратные, до 544 px). */
    fun thumb(n: JSONObject?, target: Int = 544): String? {
        val a = n?.let { findArray(it, "thumbnails") } ?: return null
        var best: JSONObject? = null
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            if (best == null || abs(t.optInt("width") - target) < abs(best.optInt("width") - target)) best = t
        }
        val u = best?.optString("url")?.takeIf { it.isNotEmpty() } ?: return null
        return if (u.startsWith("//")) "https:$u" else u
    }

    private fun findArray(n: Any?, key: String): JSONArray? = when (n) {
        is JSONObject -> n.optJSONArray(key) ?: n.keys().asSequence().firstNotNullOfOrNull { findArray(n.opt(it), key) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { findArray(n.opt(it), key) }
        else -> null
    }

    fun JSONObject?.text(): String {
        if (this == null) return ""
        optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
}
