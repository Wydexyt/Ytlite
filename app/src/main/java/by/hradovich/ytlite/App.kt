package by.hradovich.ytlite

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: App
            private set
    }

    /** Превью и аватары: общий OkHttp и кэш на диске не больше 100 МБ. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.http)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        ErrorLog.init(this)
        Prefs.init(this)
        // Новая версия — начинаем журнал ошибок с чистого листа
        if (Prefs.lastVersion != BuildConfig.VERSION_NAME) {
            ErrorLog.clear()
            Prefs.lastVersion = BuildConfig.VERSION_NAME
        }
        Auth.init(this)
        Stats.init(this)
        NewPipe.init(NpDownloader)
        // Прогрев: версия клиента и JS-плеер YouTube скачиваются заранее,
        // чтобы первое видео стартовало быстрее.
        Net.io.execute { Streams.warmUp() }
    }
}

object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    /** Качество по умолчанию (по меньшей стороне кадра), 0 = без ограничения. Отдельно для Wi-Fi и мобильной сети. */
    var qualityWifi: Int
        get() = p.getInt("quality", 1080)
        set(v) = p.edit().putInt("quality", v).apply()

    var qualityMobile: Int
        get() = p.getInt("qualityMobile", 480)
        set(v) = p.edit().putInt("qualityMobile", v).apply()

    /** Язык интерфейса: system / ru / en. */
    var language: String
        get() = p.getString("language", "system") ?: "system"
        set(v) = p.edit().putString("language", v).commit().let { }

    var lastVersion: String?
        get() = p.getString("lastVersion", null)
        set(v) = p.edit().putString("lastVersion", v).apply()

    var sponsorBlock: Boolean
        get() = p.getBoolean("sponsorBlock", true)
        set(v) = p.edit().putBoolean("sponsorBlock", v).apply()

    var sponsorCategories: Set<String>
        get() = p.getStringSet("sponsorCategories", null)?.toSet() ?: SponsorBlock.DEFAULT
        set(v) = p.edit().putStringSet("sponsorCategories", v).apply()

    // ---------- продолжение с места остановки ----------

    private val resume: JSONObject by lazy { runCatching { JSONObject(p.getString("resume", "{}")!!) }.getOrDefault(JSONObject()) }

    /** Позиция, с которой продолжить (0 — сначала). */
    @Synchronized
    fun resumePosition(id: String): Long = resume.optJSONArray(id)?.optLong(0) ?: 0

    /** Доля просмотренного 0..100 (для полоски на превью). */
    @Synchronized
    fun watchedPercent(id: String): Int {
        val a = resume.optJSONArray(id) ?: return 0
        val d = a.optLong(1)
        return if (d > 0) (a.optLong(0) * 100 / d).toInt() else 0
    }

    @Synchronized
    fun saveResume(id: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        if (pos < 10_000 || pos > dur - 15_000) resume.remove(id)
        else resume.put(id, JSONArray().put(pos).put(dur).put(System.currentTimeMillis()))
        // храним не больше 300 последних
        if (resume.length() > 300) {
            val keys = resume.keys().asSequence().toList()
                .sortedBy { resume.optJSONArray(it)?.optLong(2) ?: 0 }
            keys.take(resume.length() - 300).forEach { resume.remove(it) }
        }
        p.edit().putString("resume", resume.toString()).apply()
    }

    /** История поисковых запросов, новые сверху. */
    val history: List<String>
        get() = runCatching {
            val a = org.json.JSONArray(p.getString("history", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())

    fun addHistory(q: String) = saveHistory((listOf(q) + history.filter { !it.equals(q, true) }).take(30))

    fun removeHistory(q: String) = saveHistory(history.filter { it != q })

    private fun saveHistory(list: List<String>) =
        p.edit().putString("history", org.json.JSONArray(list).toString()).apply()
}

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Небольшой пул: всё сетевое идёт сюда, никаких постоянных фоновых задач. */
    val io: ExecutorService = Executors.newFixedThreadPool(4)
}

/** Сетевой слой для NewPipeExtractor поверх общего OkHttp. */
object NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
            ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null
        val b = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Net.UA)
        for ((k, values) in request.headers()) {
            b.removeHeader(k)
            for (v in values) b.addHeader(k, v)
        }
        Net.http.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha", request.url())
            return Response(
                r.code, r.message, r.headers.toMultimap(),
                r.body?.string(), r.request.url.toString()
            )
        }
    }
}

/** Язык интерфейса и запросов к YouTube. */
object Lang {
    private fun pref(ctx: Context) =
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("language", "system") ?: "system"

    private fun locale(ctx: Context): java.util.Locale? = when (pref(ctx)) {
        "ru" -> java.util.Locale("ru")
        "en" -> java.util.Locale.ENGLISH
        else -> null
    }

    /** Контекст с выбранным языком (для activity, сервиса, строк вне экранов). */
    fun wrap(ctx: Context): Context {
        val l = locale(ctx) ?: return ctx
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(l)
        return ctx.createConfigurationContext(cfg)
    }

    @Volatile
    private var res: android.content.res.Resources? = null

    /** Строка на языке интерфейса — для кода без своего контекста (ошибки, уведомления). */
    fun str(id: Int, vararg args: Any): String {
        val r = res ?: wrap(App.instance).resources.also { res = it }
        return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    }

    fun changed(ctx: Context) {
        res = null
    }

    /** Язык для YouTube (hl): выбранный или системный. */
    val hl: String
        get() = locale(App.instance)?.language ?: java.util.Locale.getDefault().language.ifEmpty { "en" }

    /** Страна для YouTube (gl). */
    val gl: String
        get() = java.util.Locale.getDefault().country.ifEmpty { "US" }
}
