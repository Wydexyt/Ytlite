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

data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val meta: String,      // просмотры · дата (для канала — подписчики)
    val duration: String,
    val thumb: String?,
    val avatar: String?,
    val channelId: String? = null,
    val watched: Int = 0,          // % просмотрено (из аккаунта YouTube)
    val isChannel: Boolean = false, // карточка канала в поиске (id = channelId)
    val subscribed: Boolean = false,
    val notInterestedToken: String? = null,
)

class ChannelInfo(
    val id: String,
    val title: String,
    val avatar: String?,
    val banner: String?,
    val meta: String, // @handle · подписчики · видео
    val subscribed: Boolean,
    val videos: Page,
)

class Page(val items: List<Video>, val continuation: String?)

class Details(
    val title: String,
    val meta: String,
    val channel: String,
    val avatar: String?,
    val subs: String,
    val description: String,
    val factoids: List<Pair<String, String>>, // значение → подпись (лайки, просмотры, дата)
    val commentsCount: String,
    val teaserText: String,
    val teaserAvatar: String?,
    val commentsToken: String?,
    val related: Page,
    val channelId: String?,
    val subscribed: Boolean,
    val likes: String,
    val likeStatus: String, // LIKE / DISLIKE / INDIFFERENT
    val chapters: List<Pair<Long, String>>,
)

class Comment(
    val id: String,
    val author: String,
    val authorId: String?,
    val avatar: String?,
    val text: String,
    val time: String,
    val likes: String,
    val replies: String,
    val repliesToken: String?,
    val replyParams: String?,
)

class CommentPage(
    val items: List<Comment>,
    val continuation: String?,
    val sortTokens: List<String>, // [популярные, новые]
    val createParams: String?,
    val count: String,
)

/** Авторизованные запросы к внутреннему API YouTube: ленты, поиск, рекомендации, история. */
object InnerTube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private const val FALLBACK_VERSION = "2.20251110.01.00"

    @Volatile
    private var version: String? = null

    fun clientVersion(): String = version
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

    /** Поиск: видео и каналы вперемешку, как в YouTube. */
    fun search(query: String): Page = parse(post("search", JSONObject().put("query", query)))

    fun more(endpoint: String, token: String, exclude: String? = null): Page {
        val p = parse(post(endpoint, JSONObject().put("continuation", token)))
        return if (exclude == null) p else Page(p.items.filter { it.id != exclude }, p.continuation)
    }

    /** Страница видео: описание, канал, комментарии и рекомендации. */
    fun next(videoId: String): Details {
        val j = post("next", JSONObject().put("videoId", videoId))
        val p = find(j, "videoPrimaryInfoRenderer")
        val s = find(j, "videoSecondaryInfoRenderer")
        val owner = s?.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")
        val vc = p?.optJSONObject("viewCount")?.optJSONObject("videoViewCountRenderer")
        val views = (vc?.optJSONObject("shortViewCount") ?: vc?.optJSONObject("viewCount")).text()
        val date = (p?.optJSONObject("relativeDateText") ?: p?.optJSONObject("dateText")).text()
        val desc = s?.optJSONObject("attributedDescription")?.optString("content")?.takeIf { it.isNotEmpty() }
            ?: s?.optJSONObject("description").text()

        val factoids = ArrayList<Pair<String, String>>()
        find(j, "videoDescriptionHeaderRenderer")?.optJSONArray("factoid")?.let { a ->
            for (i in 0 until a.length()) {
                val f = find(a.opt(i), "factoidRenderer") ?: continue
                factoids += f.optJSONObject("value").text() to f.optJSONObject("label").text()
            }
        }

        val entry = find(j, "commentsEntryPointHeaderRenderer")
        val teaser = entry?.let { find(it, "commentsEntryPointTeaserRenderer") }
        val commentsToken = (findWhere(j) { it.optString("sectionIdentifier") == "comment-item-section" }
            ?: findWhere(j) { it.optString("panelIdentifier") == "engagement-panel-comments-section" })
            ?.let { find(it, "continuationCommand") }?.optString("token")?.takeIf { it.isNotEmpty() }

        val related = parse(j)
        val likeVm = find(j, "likeButtonViewModel")
        val likes = likeVm?.let { find(it, "defaultButtonViewModel") }?.let { find(it, "buttonViewModel") }
            ?.optString("title").orEmpty()
        val chapters = ArrayList<Pair<Long, String>>()
        find(j, "multiMarkersPlayerBarRenderer")?.let { findArray(it, "chapters") }?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i)?.optJSONObject("chapterRenderer") ?: continue
                chapters += c.optLong("timeRangeStartMillis") to c.optJSONObject("title").text()
            }
        }
        return Details(
            channelId = owner?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?: owner?.let { find(it, "browseEndpoint") }?.optString("browseId"),
            subscribed = isSubscribed(j),
            likes = likes,
            likeStatus = find(j, "likeStatusEntity")?.optString("likeStatus")
                ?: likeVm?.optString("likeStatus")?.takeIf { it.isNotEmpty() } ?: "INDIFFERENT",
            chapters = chapters,
            title = p?.optJSONObject("title").text(),
            meta = listOf(views, date).filter { it.isNotBlank() }.joinToString(" · "),
            channel = owner?.optJSONObject("title").text(),
            avatar = pickThumb(owner?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), 88),
            subs = owner?.optJSONObject("subscriberCountText").text(),
            description = desc,
            factoids = factoids,
            commentsCount = entry?.optJSONObject("commentCount").text(),
            teaserText = teaser?.optJSONObject("teaserContent").text(),
            teaserAvatar = pickThumb(teaser?.optJSONObject("teaserAvatar")?.optJSONArray("thumbnails"), 88),
            commentsToken = commentsToken,
            related = Page(related.items.filter { it.id != videoId }, related.continuation),
        )
    }

    // ---------- комментарии ----------

    fun comments(token: String): CommentPage {
        val j = post("next", JSONObject().put("continuation", token))
        val ents = HashMap<String, JSONObject>()
        j.optJSONObject("frameworkUpdates")?.optJSONObject("entityBatchUpdate")?.optJSONArray("mutations")?.let { m ->
            for (i in 0 until m.length()) {
                val x = m.optJSONObject(i) ?: continue
                val payload = x.optJSONObject("payload") ?: continue
                ents[x.optString("entityKey")] = payload
            }
        }
        val out = ArrayList<Comment>()
        var cont: String? = null
        var header: JSONObject? = null

        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("commentThreadRenderer")?.let { t ->
                        val token = t.optJSONObject("replies")?.let { find(it, "continuationCommand") }
                            ?.optString("token")?.takeIf { it.isNotEmpty() }
                        val c = t.optJSONObject("commentViewModel")?.optJSONObject("commentViewModel")
                            ?.let { fromViewModel(it, ents, token) }
                            ?: t.optJSONObject("comment")?.optJSONObject("commentRenderer")
                                ?.let { fromCommentRenderer(it, token) }
                        c?.let { out += it }
                        return
                    }
                    n.optJSONObject("commentViewModel")?.takeIf { it.has("commentKey") }?.let { vm ->
                        fromViewModel(vm, ents, null)?.let { out += it }
                        return
                    }
                    n.optJSONObject("commentRenderer")?.let { r ->
                        fromCommentRenderer(r, null)?.let { out += it }
                        return
                    }
                    n.optJSONObject("commentsHeaderRenderer")?.let {
                        header = it
                        return
                    }
                    n.optJSONObject("continuationItemRenderer")?.let { c ->
                        find(c, "continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { cont = it }
                        return
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "frameworkUpdates" && k != "responseContext") walk(n.opt(k))
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(j)

        val h = header
        val sort = ArrayList<String>()
        h?.optJSONObject("sortMenu")?.let { sm ->
            val items = findArray(sm, "subMenuItems")
            if (items != null) for (i in 0 until items.length()) {
                find(items.opt(i), "continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { sort += it }
            }
        }
        return CommentPage(
            items = out,
            continuation = cont,
            sortTokens = sort,
            createParams = h?.let { find(it, "createCommentEndpoint") }?.optString("createCommentParams")?.takeIf { it.isNotEmpty() },
            count = (h?.optJSONObject("countText") ?: h?.optJSONObject("commentsCount")).text(),
        )
    }

    private fun fromViewModel(vm: JSONObject, ents: Map<String, JSONObject>, token: String?): Comment? {
        val p = ents[vm.optString("commentKey")]?.optJSONObject("commentEntityPayload") ?: return null
        val props = p.optJSONObject("properties")
        val author = p.optJSONObject("author")
        val tb = p.optJSONObject("toolbar")
        val surface = ents[vm.optString("commentSurfaceKey")]
        return Comment(
            id = props?.optString("commentId").orEmpty(),
            author = author?.optString("displayName").orEmpty(),
            authorId = author?.optString("channelId")?.takeIf { it.isNotEmpty() },
            avatar = author?.optString("avatarThumbnailUrl")?.takeIf { it.isNotEmpty() }?.let { fixUrl(it) },
            text = props?.optJSONObject("content")?.optString("content").orEmpty(),
            time = props?.optString("publishedTime").orEmpty(),
            likes = tb?.optString("likeCountNotliked")?.trim().orEmpty(),
            replies = tb?.optString("replyCount")?.trim().orEmpty(),
            repliesToken = token,
            replyParams = surface?.let { find(it, "createCommentReplyEndpoint") }?.optString("createReplyParams")
                ?.takeIf { it.isNotEmpty() },
        )
    }

    private fun fromCommentRenderer(r: JSONObject, token: String?): Comment? {
        val id = r.optString("commentId").ifEmpty { return null }
        return Comment(
            id = id,
            author = r.optJSONObject("authorText").text(),
            authorId = r.optJSONObject("authorEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId"),
            avatar = pickThumb(r.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails"), 88),
            text = r.optJSONObject("contentText").text(),
            time = r.optJSONObject("publishedTimeText").text(),
            likes = r.optJSONObject("voteCount").text(),
            replies = r.optInt("replyCount").takeIf { it > 0 }?.toString().orEmpty(),
            repliesToken = token,
            replyParams = find(r, "createCommentReplyEndpoint")?.optString("createReplyParams")?.takeIf { it.isNotEmpty() },
        )
    }

    fun createComment(params: String, text: String): Boolean =
        ok(post("comment/create_comment", JSONObject().put("createCommentParams", params).put("commentText", text)))

    fun createReply(params: String, text: String): Boolean =
        ok(post("comment/create_comment_reply", JSONObject().put("createReplyParams", params).put("commentText", text)))

    private fun ok(j: JSONObject) = j.optJSONArray("actions") != null || j.toString().contains("STATUS_SUCCEEDED")

    // ---------- подписки, лайки, каналы ----------

    /** Ошибка HTTP выбросит исключение — иначе считаем, что получилось. */
    fun subscribe(channelId: String, on: Boolean) {
        post(
            if (on) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId))
        )
    }

    /** status: LIKE, DISLIKE или INDIFFERENT (снять оценку). */
    fun rate(videoId: String, status: String) {
        val ep = when (status) {
            "LIKE" -> "like/like"
            "DISLIKE" -> "like/dislike"
            else -> "like/removelike"
        }
        post(ep, JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    private fun isSubscribed(j: JSONObject): Boolean =
        find(j, "subscribeButtonRenderer")?.optBoolean("subscribed")
            ?: find(j, "subscriptionStateEntity")?.optBoolean("subscribed")
            ?: false

    /** Страница канала: шапка + вкладка «Видео». */
    fun channel(id: String): ChannelInfo {
        val home = post("browse", JSONObject().put("browseId", id).put("params", "EgZ2aWRlb3PyBgQKAjoA"))
        val ph = find(home, "pageHeaderViewModel")
        val c4 = find(home, "c4TabbedHeaderRenderer")
        val meta = find(home, "channelMetadataRenderer")
        val title = ph?.optJSONObject("title")?.let { find(it, "text") }?.optString("content")?.takeIf { it.isNotEmpty() }
            ?: c4?.optJSONObject("title").text().ifEmpty { meta?.optString("title").orEmpty() }
        val avatar = ph?.optJSONObject("image")?.let { find(it, "avatarViewModel") }
            ?.optJSONObject("image")?.optJSONArray("sources")?.let { pickThumb(it, 176) }
            ?: pickThumb(c4?.optJSONObject("avatar")?.optJSONArray("thumbnails"), 176)
            ?: pickThumb(meta?.optJSONObject("avatar")?.optJSONArray("thumbnails"), 176)
        val banner = ph?.optJSONObject("banner")?.let { find(it, "image") }?.optJSONArray("sources")?.let { pickThumb(it, 1080) }
            ?: pickThumb(c4?.optJSONObject("banner")?.optJSONArray("thumbnails"), 1080)
        val parts = ArrayList<String>()
        val rows = ph?.optJSONObject("metadata")?.let { findArray(it, "metadataRows") }
        if (rows != null) for (i in 0 until rows.length()) {
            val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (k in 0 until ps.length()) {
                ps.optJSONObject(k)?.optJSONObject("text")?.optString("content")?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
        }
        if (parts.isEmpty() && c4 != null) {
            parts += listOf(c4.optJSONObject("channelHandleText").text(), c4.optJSONObject("subscriberCountText").text())
                .filter { it.isNotBlank() }
        }
        val videos = parse(home)
        return ChannelInfo(
            id = id,
            title = title,
            avatar = avatar,
            banner = banner,
            meta = parts.joinToString(" · "),
            subscribed = isSubscribed(home),
            videos = Page(videos.items.map { it.copy(channel = "", avatar = null) }, videos.continuation),
        )
    }

    /** Токен пункта «Не интересует» из меню карточки. */
    private fun notInterested(items: JSONArray?): String? {
        if (items == null) return null
        for (i in 0 until items.length()) {
            val item = items.opt(i) ?: continue
            val s = item.toString()
            if (s.contains("NOT_INTERESTED") || s.contains("Не интересует") || s.contains("Not interested")) {
                find(item, "feedbackEndpoint")?.optString("feedbackToken")?.takeIf { t -> t.isNotEmpty() }?.let { t -> return t }
            }
        }
        return null
    }

    fun notInterested(token: String) {
        post("feedback", JSONObject().put("feedbackTokens", JSONArray().put(token)).put("isFeedbackTokenUnencrypted", false))
    }

    // ---------- подсказки поиска ----------

    fun suggestions(q: String): List<String> {
        val url = "https://suggestqueries-clients6.youtube.com/complete/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("client", "youtube")
            .addQueryParameter("ds", "yt")
            .addQueryParameter("hl", "ru")
            .addQueryParameter("gl", "by")
            .addQueryParameter("q", q)
            .build()
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", Net.UA).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val start = body.indexOf('(')
            val end = body.lastIndexOf(')')
            if (start < 0 || end <= start) return emptyList()
            val arr = JSONArray(body.substring(start + 1, end)).optJSONArray(1) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONArray(it)?.optString(0)?.takeIf { s -> s.isNotEmpty() } }
        }
    }

    /** Имя и аватар активного аккаунта. */
    fun accountInfo(): Pair<String, String?>? {
        val h = find(post("account/account_menu", JSONObject()), "activeAccountHeaderRenderer") ?: return null
        val name = h.optJSONObject("accountName").text()
        return name to pickThumb(h.optJSONObject("accountPhoto")?.optJSONArray("thumbnails"), 88)
    }

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
    private val SKIP = setOf(
        "responseContext", "frameworkUpdates", "topbar", "reelShelfRenderer",
        "trackingParams", "playerOverlays", "videoPrimaryInfoRenderer", "videoSecondaryInfoRenderer",
        "engagementPanels"
    )

    /** Прямые трансляции и предстоящие премьеры не показываем. */
    private fun isLive(o: JSONObject): Boolean {
        val s = o.toString()
        return s.contains("LIVE_NOW") || s.contains("BADGE_STYLE_LIVE") || s.contains("\"style\":\"LIVE\"") ||
            s.contains("UPCOMING")
    }

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
                    n.optJSONObject("channelRenderer")?.let { fromChannel(it) }?.let { out.putIfAbsent(it.id, it) }
                    n.optJSONObject("continuationItemRenderer")?.let { c ->
                        (c.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                            ?: find(c, "continuationCommand"))
                            ?.optString("token")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { cont = it }
                    }
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
        if (isLive(v)) return null
        val channel = (v.optJSONObject("ownerText")
            ?: v.optJSONObject("shortBylineText")
            ?: v.optJSONObject("longBylineText")).text()
        val meta = listOf(
            (v.optJSONObject("shortViewCountText") ?: v.optJSONObject("viewCountText")).text(),
            v.optJSONObject("publishedTimeText").text(),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val avatarArr = v.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: v.optJSONObject("channelThumbnail")?.optJSONArray("thumbnails")
        return Video(
            id = id,
            title = v.optJSONObject("title").text(),
            channel = channel,
            meta = meta,
            duration = v.optJSONObject("lengthText").text(),
            thumb = pickThumb(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), 640),
            avatar = pickThumb(avatarArr, 68),
            channelId = (v.optJSONObject("ownerText") ?: v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText"))
                ?.let { find(it, "browseEndpoint") }?.optString("browseId")?.takeIf { it.isNotEmpty() },
            watched = find(v, "thumbnailOverlayResumePlaybackRenderer")?.optInt("percentDurationWatched") ?: 0,
            notInterestedToken = notInterested(v.optJSONObject("menu")?.let { findArray(it, "items") }),
        )
    }

    private fun fromChannel(c: JSONObject): Video? {
        val id = c.optString("channelId").ifEmpty { return null }
        // В новом поиске в subscriberCountText лежит @handle, а подписчики — в videoCountText
        val meta = listOf(c.optJSONObject("subscriberCountText").text(), c.optJSONObject("videoCountText").text())
            .filter { it.isNotBlank() }.joinToString(" · ")
        return Video(
            id = id, title = c.optJSONObject("title").text(), channel = "", meta = meta, duration = "",
            thumb = null, avatar = pickThumb(c.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), 176),
            channelId = id, isChannel = true, subscribed = isSubscribed(c),
        )
    }

    private fun fromLockup(l: JSONObject): Video? {
        if (l.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l.optString("contentId").ifEmpty { return null }
        if (isLive(l)) return null
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
        val avatar = md?.optJSONObject("image")?.let { find(it, "avatarViewModel") }
            ?.optJSONObject("image")?.optJSONArray("sources")
        return Video(
            id = id,
            title = md?.optJSONObject("title")?.optString("content").orEmpty(),
            channel = parts.firstOrNull().orEmpty(),
            meta = parts.drop(1).joinToString(" · "),
            duration = img?.let { findBadge(it) }.orEmpty(),
            thumb = pickThumb(img?.optJSONObject("image")?.optJSONArray("sources"), 640),
            avatar = pickThumb(avatar, 68),
            channelId = md?.optJSONObject("image")?.let { find(it, "browseEndpoint") }?.optString("browseId")
                ?.takeIf { it.isNotEmpty() },
            watched = img?.let { find(it, "thumbnailOverlayProgressBarViewModel") }?.optInt("startPercent") ?: 0,
            notInterestedToken = notInterested(findArray(l, "listItems")),
        )
    }

    /** Первый объект с данным ключом где угодно в дереве. */
    private fun find(n: Any?, key: String): JSONObject? = when (n) {
        is JSONObject -> n.optJSONObject(key)
            ?: n.keys().asSequence().firstNotNullOfOrNull { find(n.opt(it), key) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { find(n.opt(it), key) }
        else -> null
    }

    private fun findArray(n: Any?, key: String): JSONArray? = when (n) {
        is JSONObject -> n.optJSONArray(key)
            ?: n.keys().asSequence().firstNotNullOfOrNull { findArray(n.opt(it), key) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { findArray(n.opt(it), key) }
        else -> null
    }

    /** Первый объект, подходящий под условие. */
    private fun findWhere(n: Any?, pred: (JSONObject) -> Boolean): JSONObject? = when (n) {
        is JSONObject -> if (pred(n)) n else n.keys().asSequence().firstNotNullOfOrNull { findWhere(n.opt(it), pred) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { findWhere(n.opt(it), pred) }
        else -> null
    }

    private fun fixUrl(u: String) = if (u.startsWith("//")) "https:$u" else u

    /** Длительность у новых карточек лежит в бейдже на превью. */
    private fun findBadge(n: JSONObject): String? =
        find(n, "thumbnailBadgeViewModel")?.optString("text")?.takeIf { it.isNotEmpty() }

    private fun pickThumb(a: JSONArray?, target: Int): String? {
        if (a == null) return null
        var best: JSONObject? = null
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            if (best == null || abs(t.optInt("width") - target) < abs(best.optInt("width") - target)) best = t
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
