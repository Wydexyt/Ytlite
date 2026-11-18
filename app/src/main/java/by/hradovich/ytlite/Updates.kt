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
            val version = r.optString("tag_name").removePrefix("v").toIntOrNull() ?: return@execute
            val assets = r.optJSONArray("assets") ?: return@execute
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url") ?: return@execute
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (version <= BuildConfig.VERSION_CODE) {
                    if (force) AlertDialog.Builder(act).setMessage("Установлена последняя версия (v${BuildConfig.VERSION_CODE})")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                if (!force && p.getInt("skipped", 0) == version) return@runOnUiThread
                AlertDialog.Builder(act)
                    .setTitle("Доступно обновление v$version")
                    .setMessage(r.optString("name").ifEmpty { "Новая версия YT Lite" } +
                        "\n\nСейчас установлена v${BuildConfig.VERSION_CODE}.")
                    .setPositiveButton("Скачать") { _, _ ->
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNeutralButton("Пропустить") { _, _ -> p.edit().putInt("skipped", version).apply() }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }
}
