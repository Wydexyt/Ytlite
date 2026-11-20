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
                        install(act, url)
                    }
                    .setNeutralButton(R.string.skip) { _, _ -> p.edit().putString("skippedTag", tag).apply() }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }
    }

    /**
     * Переход с беты на «Стабильные»: если уже вышла стабильная новее установленной беты —
     * обычное обновление до неё, иначе — откат к прошлой стабильной.
     */
    fun switchToStable(act: Activity) {
        Net.io.execute {
            val newer = runCatching {
                val tag = JSONObject(get("$REPO/latest")).optString("tag_name")
                val latest = parse(tag) ?: return@runCatching false
                val cur = parse(BuildConfig.VERSION_NAME) ?: return@runCatching false
                compare(latest, cur) > 0
            }.getOrDefault(false)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (newer) check(act, force = true) else rollback(act)
            }
        }
    }

    // ---------- скачивание и установка без браузера ----------

    /** Скачать APK с прогрессом и сразу открыть установщик пакетов. */
    fun install(act: Activity, url: String) {
        val d = act.resources.displayMetrics.density
        val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = android.widget.TextView(act).apply { setPadding(0, (8 * d).toInt(), 0, 0) }
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (24 * d).toInt()
            setPadding(p, (12 * d).toInt(), p, 0)
            addView(bar)
            addView(label)
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(act)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()
        Net.io.execute {
            val file = runCatching {
                val dir = java.io.File(act.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = java.io.File(dir, "YTLite.apk")
                Net.http.newCall(Request.Builder().url(url).header("User-Agent", "YTLite").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: throw IllegalStateException("empty")
                    val total = body.contentLength()
                    var done = 0L
                    var shown = -1
                    body.byteStream().use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) throw java.util.concurrent.CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != shown) {
                                    shown = pct
                                    act.runOnUiThread {
                                        bar.isIndeterminate = pct < 0
                                        if (pct >= 0) bar.progress = pct
                                        label.text = "%.1f / %s MB".format(done / 1048576.0, if (total > 0) "%.1f".format(total / 1048576.0) else "?")
                                    }
                                }
                            }
                        }
                    }
                }
                f
            }
            act.runOnUiThread {
                runCatching { dialog.dismiss() }
                if (act.isFinishing) return@runOnUiThread
                file.onSuccess { launchInstaller(act, it) }.onFailure { e ->
                    if (e is java.util.concurrent.CancellationException) return@onFailure
                    ErrorLog.log("Скачивание обновления", e)
                    // запасной путь — как раньше, через браузер
                    android.widget.Toast.makeText(act, R.string.update_download_failed, android.widget.Toast.LENGTH_LONG).show()
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
    }

    private fun launchInstaller(act: Activity, f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android один раз спросит разрешение «Установка из этого источника»
        if (android.os.Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(act, R.string.update_allow_install, android.widget.Toast.LENGTH_LONG).show()
        }
        runCatching { act.startActivity(i) }.onFailure { ErrorLog.log("Установщик", it) }
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
                        install(act, url)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
