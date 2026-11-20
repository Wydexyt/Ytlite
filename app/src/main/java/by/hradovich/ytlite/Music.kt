package by.hradovich.ytlite

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import kotlin.math.abs

class MusicItem(
    val type: Type,
    val id: String,          // videoId (трек), browseId (альбом, плейлист, исполнитель) или playlistId (микс)
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val playlistId: String? = null,
) {
    enum class Type { SONG, ALBUM, PLAYLIST, ARTIST, MIX }

    fun toQueue(cover: String? = null) = QItem(id, title, subtitle.substringBefore(" • "), thumb ?: cover, music = true)
}

class MusicSection(val title: String, val items: List<MusicItem>, val list: Boolean)

class MusicPage(val title: String, val subtitle: String, val cover: String?, val sections: List<MusicSection>, val tracks: List<QItem>)

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

    // ---------- главная, поиск, библиотека, страницы ----------

    fun home(): List<MusicSection> = sections(post("browse", JSONObject().put("browseId", "FEmusic_home")))

    fun search(q: String): List<MusicSection> = sections(post("search", JSONObject().put("query", q)))

    /** Библиотека: понравившиеся треки, плейлисты, альбомы, исполнители. */
    fun library(): List<MusicSection> {
        val out = ArrayList<MusicSection>()
        fun part(browseId: String, title: String, filter: (MusicItem) -> Boolean = { true }) {
            runCatching {
                val items = sections(post("browse", JSONObject().put("browseId", browseId))).flatMap { it.items }.filter(filter)
                if (items.isNotEmpty()) out += MusicSection(title, items, list = false)
            }.onFailure { ErrorLog.log("Музыка: $browseId", it) }
        }
        part("FEmusic_liked_playlists", Lang.str(R.string.music_playlists)) { it.type != MusicItem.Type.SONG }
        part("FEmusic_liked_albums", Lang.str(R.string.music_albums))
        part("FEmusic_library_corpus_track_artists", Lang.str(R.string.music_artists))
        return out
    }

    /** Альбом, плейлист или исполнитель. */
    fun page(browseId: String): MusicPage {
        val j = post("browse", JSONObject().put("browseId", browseId))
        val h = HEADERS.firstNotNullOfOrNull { find(j, it) }
        val title = h?.optJSONObject("title").text()
        val strap = h?.optJSONObject("straplineTextOne").text()
        val sub = listOf(strap, h?.optJSONObject("subtitle").text(), h?.optJSONObject("secondSubtitle").text())
            .filter { !it.isNullOrBlank() }.joinToString(" · ")
        val cover = thumb(h?.optJSONObject("thumbnail") ?: h?.optJSONObject("foregroundThumbnail"))
        val secs = sections(j)
        // у треков альбома нет своих обложек и исполнителя — берём из шапки
        val tracks = secs.filter { it.list }.flatMap { it.items }.filter { it.type == MusicItem.Type.SONG }
            .map { QItem(it.id, it.title, it.subtitle.substringBefore(" • ").ifEmpty { strap.orEmpty() }, it.thumb ?: cover, music = true) }
        return MusicPage(title, sub, cover, secs, tracks)
    }

    /** Текст песни, если YouTube Music его отдаёт. */
    fun lyrics(videoId: String): String? {
        val next = post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        var id: String? = null
        walk(next) { o ->
            o.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("MPLYt") }?.let { if (id == null) id = it }
        }
        val b = post("browse", JSONObject().put("browseId", id ?: return null))
        return find(b, "musicDescriptionShelfRenderer")?.let {
            val text = it.optJSONObject("description").text()
            val src = it.optJSONObject("footer").text()
            if (text.isBlank()) null else if (src.isBlank()) text else "$text\n\n$src"
        }
    }

    private val HEADERS = listOf(
        "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer",
        "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )

    private val SHELVES = setOf(
        "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer", "musicShelfRenderer",
        "musicPlaylistShelfRenderer", "gridRenderer", "musicCardShelfRenderer",
    )

    /** Полки страницы: карусели (карточки) и списки (треки). */
    fun sections(root: JSONObject): List<MusicSection> {
        val out = ArrayList<MusicSection>()
        fun visit(n: Any?) {
            when (n) {
                is JSONObject -> {
                    for (k in SHELVES) {
                        val r = n.optJSONObject(k) ?: continue
                        shelf(k, r)?.let { out += it }
                        return
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "responseContext" && k != "frameworkUpdates" && k !in HEADERS) visit(n.opt(k))
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) visit(n.opt(i))
            }
        }
        visit(root)
        return out
    }

    private fun shelf(kind: String, r: JSONObject): MusicSection? {
        if (kind == "musicCardShelfRenderer") {
            // «Лучший результат» в поиске
            val nav = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                ?: r.optJSONObject("onTap") ?: return null
            val item = fromNav(nav, r.optJSONObject("title").text(), r.optJSONObject("subtitle").text(), thumb(r.optJSONObject("thumbnail"))) ?: return null
            return MusicSection(Lang.str(R.string.music_top_result), listOf(item), list = true)
        }
        val header = r.optJSONObject("header")
        val title = (r.optJSONObject("title") ?: header?.let { find(it, "title") }).text()
        val arr = r.optJSONArray("contents") ?: r.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow(it) }
                ?: o.optJSONObject("musicResponsiveListItemRenderer")?.let { responsive(it) }
        }
        if (items.isEmpty()) return null
        val list = kind == "musicShelfRenderer" || kind == "musicPlaylistShelfRenderer" ||
            (kind == "musicCarouselShelfRenderer" && items.all { it.type == MusicItem.Type.SONG } &&
                arr.optJSONObject(0)?.has("musicResponsiveListItemRenderer") == true)
        return MusicSection(title, items, list)
    }

    private fun twoRow(r: JSONObject): MusicItem? {
        val nav = r.optJSONObject("navigationEndpoint") ?: return null
        return fromNav(nav, r.optJSONObject("title").text(), r.optJSONObject("subtitle").text(), thumb(r.optJSONObject("thumbnailRenderer")))
    }

    private fun responsive(r: JSONObject): MusicItem? {
        val cols = r.optJSONArray("flexColumns") ?: JSONArray()
        fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text").text()
        val title = col(0)
        val sub = (1 until cols.length()).map { col(it) }.filter { it.isNotBlank() }.joinToString(" • ")
        val th = thumb(r.optJSONObject("thumbnail"), 120)
        val vid = r.optJSONObject("playlistItemData")?.optString("videoId")?.ifEmpty { null }
            ?: find(r.optJSONObject("overlay"), "watchEndpoint")?.optString("videoId")?.ifEmpty { null }
            ?: cols.optJSONObject(0)?.let { find(it, "watchEndpoint") }?.optString("videoId")?.ifEmpty { null }
        if (vid != null) return MusicItem(MusicItem.Type.SONG, vid, title, sub, th)
        val nav = r.optJSONObject("navigationEndpoint") ?: return null
        return fromNav(nav, title, sub, th)
    }

    private fun fromNav(nav: JSONObject, title: String, sub: String, th: String?): MusicItem? {
        nav.optJSONObject("watchEndpoint")?.let { w ->
            val v = w.optString("videoId").ifEmpty { null }
            val pl = w.optString("playlistId").ifEmpty { null }
            return if (v != null) MusicItem(MusicItem.Type.SONG, v, title, sub, th, pl)
            else pl?.let { MusicItem(MusicItem.Type.MIX, it, title, sub, th, it) }
        }
        nav.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")?.ifEmpty { null }?.let {
            return MusicItem(MusicItem.Type.MIX, it, title, sub, th, it)
        }
        val b = nav.optJSONObject("browseEndpoint") ?: return null
        val id = b.optString("browseId").ifEmpty { return null }
        val pageType = find(b, "browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
        val type = when {
            pageType.endsWith("ARTIST") || pageType.endsWith("USER_CHANNEL") || id.startsWith("UC") -> MusicItem.Type.ARTIST
            pageType.endsWith("ALBUM") || id.startsWith("MPRE") -> MusicItem.Type.ALBUM
            pageType.endsWith("PLAYLIST") || id.startsWith("VL") -> MusicItem.Type.PLAYLIST
            else -> return null // подкасты, настроения и прочее — пока не поддерживаем
        }
        return MusicItem(type, id, title, sub, th)
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
