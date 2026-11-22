package by.hradovich.ytlite

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал ошибок: пишется автоматически в файл приложения, никуда не отправляется. */
object ErrorLog {
    private const val MAX_BYTES = 256 * 1024
    private lateinit var file: File
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "errors.log")
        // Падения приложения: записать и передать системе как обычно
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("ПАДЕНИЕ (${thread.name})", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Записать ошибку; where — где произошло, например «Лента: Подписки». */
    fun log(where: String, e: Throwable) {
        if (e.isOffline()) return // нет интернета — не ошибка приложения
        runCatching { write(where, e) }
    }

    @Synchronized
    private fun write(where: String, e: Throwable) {
        if (!::file.isInitialized) return
        val trace = e.stackTraceToString().lineSequence().take(25).joinToString("\n")
        file.appendText("[${time.format(Date())}] ${BuildConfig.VERSION_NAME} | $where\n$trace\n\n")
        // Держим только свежую половину, когда файл разрастается
        if (file.length() > MAX_BYTES) {
            val text = file.readText()
            file.writeText(text.substring(text.length / 2).substringAfter("\n\n["). let { "[$it" })
        }
    }

    @Synchronized
    fun read(): String = if (::file.isInitialized && file.exists()) file.readText() else ""

    @Synchronized
    fun clear() {
        if (::file.isInitialized) file.delete()
    }

    /** Весь журнал с данными об устройстве — для копирования. */
    fun export(): String =
        "YT Lite ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
            read().ifEmpty { "No errors" }
}

/** Нет интернета: имя сервера не находится или нет соединения. */
fun Throwable.isOffline(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is java.net.UnknownHostException || t is java.net.NoRouteToHostException ||
            (t is java.net.ConnectException && !Net.online())
        ) return true
        t = t.cause
    }
    return false
}

/** Текст ошибки для экрана: без интернета — понятное «Нет подключения». */
fun android.content.Context.errorText(res: Int, t: Throwable): String =
    if (t.isOffline() || !Net.online()) getString(R.string.no_connection) else getString(res, t.message)
