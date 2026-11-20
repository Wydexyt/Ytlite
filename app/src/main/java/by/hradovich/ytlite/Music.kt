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
    val liked: Boolean = false, // трек уже в «Понравившейся музыке» (если YouTube Music это сообщил)
) {
    enum class Type { SONG, ALBUM, PLAYLIST, ARTIST, MIX }

    fun toQueue(cover: String? = null) = QItem(id, title, subtitle.substringBefore(" • "), thumb ?: cover, music = true)
}

class MusicSection(
    val title: String,
    val items: List<MusicItem>,
    val list: Boolean,
    val moreId: String? = null,        // «Все» у полки: полный список (у исполнителя — все его треки)
    val continuation: String? = null,  // продолжение длинного списка
)

class MusicPage(
    val title: String,
    val subtitle: String,
    val cover: String?,
    val sections: List<MusicSection>,
    val tracks: List<QItem>,
    val channelId: String? = null, // исполнитель: на кого подписываться
    val subscribed: Boolean = false,
    val allTracksId: String? = null, // исполнитель: плейлист со всеми треками
    val savePlaylistId: String? = null, // альбом/плейлист: что сохранять в библиотеку
    val saved: Boolean = false,
    val continuation: String? = null, // длинный плейлист: продолжение списка треков
)

/** Что лайкнуто в этой сессии (и что YouTube Music пометил лайкнутым) — для кнопок в строках и в плеере. */
object Likes {
    private val state = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun liked(id: String, default: Boolean = false) = state[id] ?: default

    fun seen(id: String, liked: Boolean) {
        if (state.put(id, liked) != liked) listeners.forEach { it() }
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
}

/** Запросы к YouTube Music (клиент WEB_REMIX) от имени активного аккаунта. */
object Music {
    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private const val ORIGIN = "https://music.youtube.com"
    private const val FALLBACK_VERSION = "1.20251110.01.00"

    @Volatile
    private var version: String? = null

    /** Заранее, при запуске: версия клиента (скачивается страница music.youtube.com). */
    fun warmUp() {
        runCatching { clientVersion() }
    }

    private fun clientVersion(): String = version
        ?: (runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }.getOrNull() ?: FALLBACK_VERSION)
            .also { version = it }

    fun post(endpoint: String, body: JSONObject, query: String = ""): JSONObject {
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
            .url("$BASE$endpoint?prettyPrint=false$query")
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
        val cover = thumb(h?.optJSONObject("thumbnail") ?: h?.optJSONObject("foregroundThumbnail"), 544)
        val sub0 = find(j, "subscribeButtonRenderer")
        val save = saveInfo(j, h, browseId)
        val secs = sections(j)
        // у треков альбома нет своих обложек и исполнителя — берём из шапки
        val tracks = secs.filter { it.list }.flatMap { it.items }.filter { it.type == MusicItem.Type.SONG }
            .map { QItem(it.id, it.title, it.subtitle.substringBefore(" • ").ifEmpty { strap.orEmpty() }, it.thumb ?: cover, music = true) }
        val songs = secs.firstOrNull { it.list && it.items.any { i -> i.type == MusicItem.Type.SONG } }
        return MusicPage(
            title, sub, cover, secs, tracks,
            channelId = sub0?.optString("channelId")?.ifEmpty { null }, subscribed = sub0?.optBoolean("subscribed") == true,
            allTracksId = songs?.moreId?.takeIf { it != browseId },
            savePlaylistId = save?.first,
            saved = save?.second == true,
            continuation = songs?.continuation,
        )
    }

    /** Что сохранять в библиотеку и сохранено ли уже: кнопка-лайк в шапке альбома или плейлиста. */
    private fun saveInfo(j: JSONObject, h: JSONObject?, browseId: String): Pair<String, Boolean>? {
        if (browseId == "VLLM" || browseId.startsWith("UC")) return null
        // кнопка-переключатель с likeEndpoint — та, что в YouTube Music «Сохранить в библиотеку»
        var found: Pair<String, Boolean>? = null
        walk(h ?: j) { o ->
            if (found != null) return@walk
            val t = o.optJSONObject("toggleButtonRenderer") ?: return@walk
            val pl = find(t, "likeEndpoint")?.optJSONObject("target")?.optString("playlistId")?.ifEmpty { null } ?: return@walk
            found = pl to t.optBoolean("isToggled")
        }
        found?.let { return it }
        val pl = find(h, "watchEndpoint")?.optString("playlistId")?.ifEmpty { null }
            ?: browseId.takeIf { it.startsWith("VL") }?.removePrefix("VL") ?: return null
        return pl to false
    }

    fun savePlaylist(playlistId: String, save: Boolean) {
        post(if (save) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("playlistId", playlistId)))
    }

    /** Продолжение длинного списка: новые треки и следующий токен. */
    fun more(token: String): Pair<List<MusicItem>, String?> {
        // старый формат продолжений — в параметрах ссылки, новый — в теле запроса
        val j = if (token.startsWith("ctoken:")) {
            val t = java.net.URLEncoder.encode(token.removePrefix("ctoken:"), "UTF-8")
            post("browse", JSONObject(), "&ctoken=$t&continuation=$t&type=next")
        } else post("browse", JSONObject().put("continuation", token))
        val items = ArrayList<MusicItem>()
        walk(j) { o -> o.optJSONObject("musicResponsiveListItemRenderer")?.let { responsive(it) }?.let { items += it } }
        return items to nextToken(j)
    }

    /** Все треки списка (с догрузкой), не больше limit. */
    fun allTracks(browseId: String, limit: Int = 1000): List<QItem> {
        val p = page(browseId)
        val out = ArrayList(p.tracks)
        var token = p.continuation
        var guard = 0
        while (token != null && out.size < limit && guard++ < 30) {
            val (items, next) = more(token)
            if (items.isEmpty()) break
            out += items.filter { it.type == MusicItem.Type.SONG }.map { it.toQueue(p.cover) }
            token = next
        }
        return out.distinctBy { it.id }
    }

    private fun nextToken(n: JSONObject): String? {
        find(n, "continuationCommand")?.optString("token")?.ifEmpty { null }?.let { return it }
        find(n, "nextContinuationData")?.optString("continuation")?.ifEmpty { null }?.let { return "ctoken:$it" }
        return null
    }

    /** Лайк через YouTube Music — так трек попадает в «Понравившуюся музыку». */
    fun rate(videoId: String, status: String) {
        Likes.seen(videoId, status == "LIKE")
        val ep = when (status) {
            "LIKE" -> "like/like"
            "DISLIKE" -> "like/dislike"
            else -> "like/removelike"
        }
        post(ep, JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    /** Оценка трека (LIKE / DISLIKE / INDIFFERENT) и ссылка на текст песни. */
    class TrackInfo(val likeStatus: String, val lyricsId: String?)

    fun trackInfo(videoId: String): TrackInfo {
        val next = post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        var id: String? = null
        walk(next) { o ->
            o.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("MPLYt") }?.let { if (id == null) id = it }
        }
        val like = find(next, "likeButtonRenderer")?.optString("likeStatus")?.ifEmpty { null } ?: "INDIFFERENT"
        return TrackInfo(like, id)
    }

    /** Текст песни, если YouTube Music его отдаёт. */
    fun lyrics(videoId: String, lyricsId: String? = null): String? {
        val id = lyricsId ?: trackInfo(videoId).lyricsId
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
        // «Все»: ссылка в заголовке полки или кнопка под ней
        val more = (r.optJSONObject("title") ?: header)?.let { find(it, "browseEndpoint") }?.optString("browseId")?.ifEmpty { null }
            ?: r.optJSONObject("bottomEndpoint")?.let { find(it, "browseEndpoint") ?: it.optJSONObject("browseEndpoint") }
                ?.optString("browseId")?.ifEmpty { null }
        val cont = if (list) nextToken(JSONObject().put("c", r.optJSONArray("continuations") ?: JSONArray()).put("a", arr.optJSONObject(arr.length() - 1) ?: JSONObject())) else null
        return MusicSection(title, items, list, more, cont)
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
        if (vid != null) {
            val liked = find(r.optJSONObject("menu"), "likeButtonRenderer")?.optString("likeStatus") == "LIKE"
            if (liked) Likes.seen(vid, true)
            return MusicItem(MusicItem.Type.SONG, vid, title, sub, th, liked = liked)
        }
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

    /** Обложка нужного размера: у YouTube Music размер задаётся в ссылке (=wN-hN), лишнего не качаем. */
    fun thumb(n: JSONObject?, target: Int = 226): String? = pick(n, target)?.let { sized(it, target) }

    fun sized(url: String, px: Int): String {
        if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
        // обложки: «=w120-h120-l90-rj», фото исполнителей и каналов: «=s120-c-k-…»
        val wh = Regex("=w\\d+-h\\d+[^/]*$")
        if (wh.containsMatchIn(url)) return url.replace(wh, "=w$px-h$px-l90-rj")
        val sq = Regex("=s\\d+([^/]*)$")
        return if (sq.containsMatchIn(url)) url.replace(sq) { "=s$px" + it.groupValues[1] } else url
    }

    /** Встроенные плейлисты YouTube Music: вместо цветных обложек — своя монохромная иконка. */
    fun systemIcon(id: String): Int = when (id.removePrefix("VL")) {
        "LM" -> R.drawable.ic_like
        "SE" -> R.drawable.ic_podcast
        else -> 0
    }

    private fun pick(n: JSONObject?, target: Int): String? {
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
