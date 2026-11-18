package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.Request
import org.json.JSONObject

/** Проверка новой сборки в релизах GitHub (не чаще раза в 6 часов). */
object Updates {
    private const val API = "https://api.github.com/repos/Wydexyt/Ytlite/releases/latest"
    private const val PERIOD = 6L * 60 * 60 * 1000

    /** "0.1.5" → [0,1,5]; старые теги вида "4" → [0,1,4]. */
    private fun parse(v: String): List<Int>? {
        val parts = v.trim().split('.').map { it.toIntOrNull() ?: return null }
        return if (parts.size == 1) listOf(0, 1, parts[0]) else parts
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun check(act: Activity, force: Boolean = false) {
        val p = act.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last", 0) < PERIOD) return
        p.edit().putLong("last", now).apply()
        Net.io.execute {
            val r = runCatching {
                Net.http.newCall(
                    Request.Builder().url(API)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "YTLite")
                        .build()
                ).execute().use { JSONObject(it.body?.string().orEmpty()) }
            }.getOrNull() ?: return@execute
            val tag = r.optString("tag_name").removePrefix("v")
            val latest = parse(tag) ?: return@execute
            val current = parse(BuildConfig.VERSION_NAME) ?: return@execute
            val assets = r.optJSONArray("assets") ?: return@execute
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url") ?: return@execute
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (compare(latest, current) <= 0) {
                    if (force) AlertDialog.Builder(act).setMessage("Установлена последняя версия (${BuildConfig.VERSION_NAME})")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                if (!force && p.getString("skippedTag", null) == tag) return@runOnUiThread
                AlertDialog.Builder(act)
                    .setTitle("Доступно обновление $tag")
                    .setMessage(r.optString("body").substringBefore("\n---").trim().take(1500).ifEmpty { "Новая версия YT Lite" } +
                        "\n\nСейчас установлена ${BuildConfig.VERSION_NAME}.")
                    .setPositiveButton("Скачать") { _, _ ->
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNeutralButton("Пропустить") { _, _ -> p.edit().putString("skippedTag", tag).apply() }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }
}
