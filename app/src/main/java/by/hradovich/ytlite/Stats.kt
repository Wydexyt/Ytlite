package by.hradovich.ytlite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Статистика просмотра. Обновляется только по событиям плеера (старт/пауза/смена видео) —
 * без таймеров и опроса, поэтому на батарею не влияет.
 */
object Stats {
    private lateinit var p: SharedPreferences
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var days = JSONObject()      // "2026-11-19" → {w, b, s, k, km, v}
    private var channels = JSONObject()  // название канала → мс

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        days = runCatching { JSONObject(p.getString("days", "{}")!!) }.getOrDefault(JSONObject())
        channels = runCatching { JSONObject(p.getString("channels", "{}")!!) }.getOrDefault(JSONObject())
    }

    private fun today(): JSONObject {
        val k = day.format(Date())
        return days.optJSONObject(k) ?: JSONObject().also { days.put(k, it) }
    }

    private fun inc(o: JSONObject, key: String, v: Long) = o.put(key, o.optLong(key) + v)

    private fun save() {
        // храним не больше ~400 дней
        if (days.length() > 400) days.keys().asSequence().toList().sorted().take(days.length() - 400).forEach { days.remove(it) }
        p.edit().putString("days", days.toString()).putString("channels", channels.toString()).apply()
    }

    /** Отрезок воспроизведения: всего, из них без видео, оценка сэкономленного трафика. */
    @Synchronized
    fun add(watchMs: Long, bgMs: Long, savedBytes: Long, channel: String?) {
        if (!::p.isInitialized || watchMs <= 0) return
        val t = today()
        inc(t, "w", watchMs)
        inc(t, "b", bgMs)
        inc(t, "s", savedBytes)
        if (!channel.isNullOrBlank()) channels.put(channel, channels.optLong(channel) + watchMs)
        save()
    }

    @Synchronized
    fun video() {
        if (!::p.isInitialized) return
        inc(today(), "v", 1)
        save()
    }

    @Synchronized
    fun skipped(ms: Long) {
        if (!::p.isInitialized) return
        val t = today()
        inc(t, "k", 1)
        inc(t, "km", ms)
        save()
    }

    class Summary(
        val watch: Long, val bg: Long, val saved: Long, val skips: Long, val skipMs: Long, val videos: Long,
        val perDay: List<Pair<Date, Long>>, val topChannels: List<Pair<String, Long>>,
    )

    /** periodDays = null — за всё время. */
    @Synchronized
    fun summary(periodDays: Int?): Summary {
        val keys = days.keys().asSequence().toList()
        val from = periodDays?.let {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -(it - 1))
            day.format(c.time)
        }
        val inRange = keys.filter { from == null || it >= from }
        fun sum(k: String) = inRange.sumOf { days.optJSONObject(it)?.optLong(k) ?: 0 }
        // по дням для графика: последние periodDays (или 30) дней, включая пустые
        val n = periodDays ?: 30
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -(n - 1))
        val perDay = (0 until n).map {
            val d = c.time
            val v = days.optJSONObject(day.format(d))?.optLong("w") ?: 0
            c.add(Calendar.DAY_OF_YEAR, 1)
            d to v
        }
        val top = channels.keys().asSequence().map { it to channels.optLong(it) }.sortedByDescending { it.second }.take(5).toList()
        return Summary(sum("w"), sum("b"), sum("s"), sum("k"), sum("km"), sum("v"), perDay, top)
    }
}
