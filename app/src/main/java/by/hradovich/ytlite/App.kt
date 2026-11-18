package by.hradovich.ytlite

import android.app.Application
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

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Auth.init(this)
        NewPipe.init(NpDownloader)
    }
}

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Небольшой пул: всё сетевое идёт сюда, никаких постоянных фоновых задач. */
    val io: ExecutorService = Executors.newFixedThreadPool(3)
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
