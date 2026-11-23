package tech.capullo.tubecapullo.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request as OkRequest

@Singleton
class TubeCapulloDownloader @Inject constructor(http: OkHttpClient) : Downloader() {
    private val client = http.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val recorder = ThreadLocal<MutableMap<String, Int>>()

    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = OkRequest.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val okRequest = builder.build()
        val response = client.newCall(okRequest).execute()
        recorder.get()?.put(okRequest.url.host, response.code)

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = response.body?.string()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    /**
     * Runs [block], recording into [statuses] the HTTP status each host answered this thread's requests
     * with. The key is the host that was asked, so a chart request redirected to a /sorry or consent
     * page still answers for music.youtube.com. NewPipe fetches synchronously on the caller's thread
     * through this one global downloader, so concurrent callers each see only their own requests.
     */
    fun <T> recordStatuses(statuses: MutableMap<String, Int>, block: () -> T): T {
        val outer = recorder.get()
        recorder.set(statuses)
        try {
            return block()
        } finally {
            if (outer == null) recorder.remove() else recorder.set(outer)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
