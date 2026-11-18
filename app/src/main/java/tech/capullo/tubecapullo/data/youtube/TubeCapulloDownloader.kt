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
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

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

        val started = System.nanoTime()
        val tag = "DIAG http ${request.httpMethod()} ${url.substringAfter("://").substringBefore('?').take(80)}"
        println("$tag start thread=${Thread.currentThread().name}")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            println("$tag failed ms=${(System.nanoTime() - started) / 1_000_000} error=${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        println("$tag headers code=${response.code} ms=${(System.nanoTime() - started) / 1_000_000}")

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = try {
            response.body?.string()
        } catch (e: Exception) {
            println("$tag body-failed ms=${(System.nanoTime() - started) / 1_000_000} error=${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        println("$tag done bytes=${body?.length} ms=${(System.nanoTime() - started) / 1_000_000}")

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
