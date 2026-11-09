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

/**
 * NewPipeExtractor's [Downloader] SPI, backed by OkHttp. The SPI is blocking by design, which is
 * why this is OkHttp and not a suspend client - and why every caller goes through
 * [YouTubeExtractorClient], which does the dispatcher switch once.
 */
@Singleton
class TubeCapulloDownloader @Inject constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = OkRequest.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(url)
            // Without a browser-shaped UA, YouTube serves a different (or no) page. The extractor
            // sets its own headers per-request on top of this.
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            // An explicitly empty value list means "remove this header", per the SPI contract.
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val response = client.newCall(builder.build()).execute()

        // 429 is YouTube throttling this IP. The extractor has a dedicated exception for it so
        // callers can say "slow down" rather than "this video is broken".
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        // body is non-null for a real response; read it fully here because the SPI hands back a
        // String and the connection has to be released either way.
        val body = response.body?.string()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    private companion object {
        // Matches what NewPipe itself sends. Kept current-ish deliberately: a stale UA is one of
        // the ways extraction quietly starts returning degraded pages. MIRRORED by
        // PlaybackService.STREAM_USER_AGENT (googlevideo wants the stream fetched by the same UA
        // that resolved it) - move both together.
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
