package by.hradovich.ytlite

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

class Segment(val start: Long, val end: Long, val category: String)

/** SponsorBlock (sponsor.ajay.app): отрезки с рекламными вставками и прочим, что можно пропустить. */
object SponsorBlock {
    /** Категории SponsorBlock и их названия. */
    val CATEGORIES = linkedMapOf(
        "sponsor" to R.string.sb_sponsor,
        "selfpromo" to R.string.sb_selfpromo,
        "interaction" to R.string.sb_interaction,
        "intro" to R.string.sb_intro,
        "outro" to R.string.sb_outro,
        "preview" to R.string.sb_preview,
        "music_offtopic" to R.string.sb_music_offtopic,
        "filler" to R.string.sb_filler,
    )
    val DEFAULT = setOf("sponsor", "selfpromo", "interaction")

    /** Отрезки выбранных категорий; пусто, если их нет или сервис недоступен. */
    fun segments(videoId: String): List<Segment> = runCatching {
        val cats = Prefs.sponsorCategories
        if (cats.isEmpty()) return@runCatching emptyList()
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", JSONArray(cats.toList()).toString())
            .build()
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", "YTLite").build()).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            val a = JSONArray(r.body?.string().orEmpty())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val seg = o.optJSONArray("segment") ?: return@mapNotNull null
                val start = (seg.optDouble(0) * 1000).toLong()
                val end = (seg.optDouble(1) * 1000).toLong()
                if (end - start >= 1000) Segment(start, end, o.optString("category")) else null
            }.sortedBy { it.start }
        }
    }.getOrDefault(emptyList())
}
