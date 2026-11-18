package by.hradovich.ytlite

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/** SponsorBlock (sponsor.ajay.app): отрезки с рекламными вставками авторов. */
object SponsorBlock {
    private const val CATEGORIES = "[\"sponsor\",\"selfpromo\",\"interaction\"]"

    /** Список [начало, конец] в миллисекундах; пусто, если отрезков нет или сервис недоступен. */
    fun segments(videoId: String): List<LongArray> = runCatching {
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", CATEGORIES)
            .build()
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", "YTLite").build()).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            val a = JSONArray(r.body?.string().orEmpty())
            (0 until a.length()).mapNotNull { i ->
                val seg = a.optJSONObject(i)?.optJSONArray("segment") ?: return@mapNotNull null
                val start = (seg.optDouble(0) * 1000).toLong()
                val end = (seg.optDouble(1) * 1000).toLong()
                if (end - start >= 1000) longArrayOf(start, end) else null
            }.sortedBy { it[0] }
        }
    }.getOrDefault(emptyList())
}
