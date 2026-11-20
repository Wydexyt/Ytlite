package by.hradovich.ytlite

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Строка текста: время начала (мс, -1 — без таймкодов) и текст. */
class LyricLine(val time: Long, val text: String)

/**
 * Текст песни для караоке. Синхронизированный — из LRCLIB (открытая база с таймкодами),
 * иначе обычный — из YouTube Music.
 */
object Lyrics {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()

    fun get(videoId: String, title: String, artist: String, durationSec: Long, ytLyricsId: String?): List<LyricLine> {
        cache[videoId]?.let { return it }
        val synced = runCatching { lrclib(title, artist, durationSec) }.onFailure { ErrorLog.log("LRCLIB $title", it) }.getOrNull()
        val lines = synced ?: Music.lyrics(videoId, ytLyricsId)?.let { plain ->
            plain.lines().map { LyricLine(-1, it.trim()) }
        } ?: emptyList()
        if (lines.isNotEmpty()) cache[videoId] = lines
        return lines
    }

    /** «Song (Official Video) [HD]» → «Song»; «Artist - Topic» → «Artist». */
    private fun clean(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        .replace(Regex("(?i)\\s*-\\s*topic$"), "").trim()

    private fun request(path: String, params: Map<String, String>): String? {
        val url = "https://lrclib.net/api/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("User-Agent", "YT Lite (https://github.com/Wydexyt/Ytlite)").build()
        Net.http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IllegalStateException("LRCLIB ${r.code}")
            return r.body?.string()
        }
    }

    private fun lrclib(title: String, artist: String, durationSec: Long): List<LyricLine>? {
        val t = clean(title)
        val a = clean(artist)
        // точное совпадение по названию, исполнителю и длительности
        if (durationSec > 0) {
            request("get", mapOf("track_name" to t, "artist_name" to a, "duration" to durationSec.toString()))
                ?.let { JSONObject(it).optString("syncedLyrics") }
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return parse(it) }
        }
        // иначе поиск: ближайший по длительности с таймкодами
        val arr = JSONArray(request("search", mapOf("track_name" to t, "artist_name" to a)) ?: return null)
        val best = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } }
            .minByOrNull { if (durationSec > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec) else 0.0 }
            ?: return null
        if (durationSec > 0 && kotlin.math.abs(best.optDouble("duration", 0.0) - durationSec) > 8) return null
        return parse(best.optString("syncedLyrics"))
    }

    /** Формат LRC: «[01:23.45] строка». */
    private fun parse(lrc: String): List<LyricLine>? {
        val re = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)$")
        val out = lrc.lines().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            val (mm, ss, frac, text) = m.destructured
            val ms = when (frac.length) {
                0 -> 0
                1 -> frac.toInt() * 100
                2 -> frac.toInt() * 10
                else -> frac.take(3).toInt()
            }
            LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, text.trim())
        }.sortedBy { it.time }
        return out.takeIf { it.isNotEmpty() }
    }
}
