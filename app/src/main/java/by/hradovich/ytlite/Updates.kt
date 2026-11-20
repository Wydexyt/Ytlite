package by.hradovich.ytlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Обновления из релизов GitHub (не чаще раза в 6 часов).
 * Канал «Стабильные» — обычные релизы; «Тестовые» — ещё и предрелизы (бета).
 * У каждой беты есть файл отката: стабильная версия с номером выше беты — ставится поверх без потери данных.
 */
object Updates {
    private const val REPO = "https://api.github.com/repos/Wydexyt/Ytlite/releases"
    private const val PERIOD = 6L * 60 * 60 * 1000

    val isBeta: Boolean get() = BuildConfig.VERSION_NAME.contains("-")

    /** Версия: числа + номер беты (null — стабильная). "0.3.0-beta.2" → ([0,3,0], 2); старые теги "4" → ([0,1,4], null). */
    private class Ver(val nums: List<Int>, val pre: Int?)

    private fun parse(v: String): Ver? {
        val t = v.trim().removePrefix("v")
        val nums = t.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
        val pre = if ('-' in t) t.substringAfterLast('.').toIntOrNull() ?: 0 else null
        return Ver(if (nums.size == 1) listOf(0, 1, nums[0]) else nums, pre)
    }

    private fun compare(a: Ver, b: Ver): Int {
        for (i in 0 until maxOf(a.nums.size, b.nums.size)) {
            val d = a.nums.getOrElse(i) { 0 } - b.nums.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        // при равных числах стабильная новее любой беты
        return when {
            a.pre == b.pre -> 0
            a.pre == null -> 1
            b.pre == null -> -1
            else -> a.pre - b.pre
        }
    }

    private fun get(url: String): String = Net.http.newCall(
        Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "YTLite").build()
    ).execute().use {
        if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
        it.body?.string().orEmpty()
    }

    private fun apk(release: JSONObject, rollback: Boolean): String? {
        val assets = release.optJSONArray("assets") ?: return null
        return (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { val n = it.optString("name"); n.endsWith(".apk") && n.contains("-rollback") == rollback }
            ?.optString("browser_download_url")
    }

    private fun releases(): List<JSONObject> {
        val list = JSONArray(get("$REPO?per_page=30"))
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("draft") }
    }

    fun check(act: Activity, force: Boolean = false) {
        val p = act.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last", 0) < PERIOD) return
        p.edit().putLong("last", now).apply()
        fun fail(e: Throwable?) {
            e?.let { ErrorLog.log("Проверка обновлений", it) }
            if (force) act.runOnUiThread {
                if (!act.isFinishing) AlertDialog.Builder(act).setMessage(R.string.update_check_failed)
                    .setPositiveButton("OK", null).show()
            }
        }
        Net.io.execute {
            // Стабильный канал — последний релиз; тестовый — самая новая версия среди релизов и предрелизов
            val r = runCatching {
                if (Prefs.channel == "beta") {
                    releases().filter { parse(it.optString("tag_name")) != null }
                        .maxWithOrNull { a, b -> compare(parse(a.optString("tag_name"))!!, parse(b.optString("tag_name"))!!) }
                        ?: throw IllegalStateException("no releases")
                } else JSONObject(get("$REPO/latest"))
            }.getOrElse { fail(it); return@execute }
            val tag = r.optString("tag_name").removePrefix("v")
            val latest = parse(tag) ?: return@execute fail(null)
            val current = parse(BuildConfig.VERSION_NAME) ?: return@execute fail(null)
            val url = apk(r, rollback = false) ?: return@execute fail(null)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (compare(latest, current) <= 0) {
                    if (force) AlertDialog.Builder(act).setMessage(act.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                if (!force && p.getString("skippedTag", null) == tag) return@runOnUiThread
                AlertDialog.Builder(act)
                    .setTitle(act.getString(R.string.update_available, tag))
                    .setMessage(r.optString("body").substringBefore("\n---").trim().take(1500).ifEmpty { act.getString(R.string.update_new) } +
                        "\n\n" + act.getString(R.string.update_installed, BuildConfig.VERSION_NAME))
                    .setPositiveButton(R.string.download) { _, _ ->
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNeutralButton(R.string.skip) { _, _ -> p.edit().putString("skippedTag", tag).apply() }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }
    }

    /** С беты — обратно на стабильную: файл отката из релиза установленной беты (или последней беты). */
    fun rollback(act: Activity) {
        Net.io.execute {
            val url = runCatching {
                val own = runCatching { JSONObject(get("$REPO/tags/v${BuildConfig.VERSION_NAME}")) }.getOrNull()
                own?.let { apk(it, rollback = true) }
                    ?: releases().filter { it.optBoolean("prerelease") }.firstNotNullOfOrNull { apk(it, rollback = true) }
            }.onFailure { ErrorLog.log("Откат", it) }.getOrNull()
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (url == null) {
                    AlertDialog.Builder(act).setMessage(R.string.rollback_unavailable).setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val name = Uri.parse(url).lastPathSegment.orEmpty().removePrefix("YTLite-").removeSuffix("-rollback.apk")
                AlertDialog.Builder(act)
                    .setTitle(R.string.rollback_title)
                    .setMessage(act.getString(R.string.rollback_message, name))
                    .setPositiveButton(R.string.download) { _, _ ->
                        Prefs.channel = "stable"
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
