package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest
import okhttp3.Response as OkResponse

// A Charts page that links no trending chart, as the Global page does.
private val SERVED_EMPTY_CHART = YouTubeTrendingChartTest::class.java.getResource("/charts/music-charts-global.json")!!.readText()

// NewPipe checks its hardcoded YouTube Music client version once, and takes any 200 longer than 500 chars.
private val CLIENT_VERSION_OK = "{" + " ".repeat(600) + "}"

class YouTubeTrendingChartTest {
    private fun client(answer: (OkRequest) -> OkResponse) = YouTubeExtractorClient(
        TubeCapulloDownloader(
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain -> answer(chain.request()) })
                .build(),
        ),
    )

    private fun answer(request: OkRequest, code: Int, body: String): OkResponse = OkResponse.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("stubbed")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun OkRequest.text(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    @Test
    fun `concurrent regions each get the status of their own chart request`() {
        val bothInFlight = CyclicBarrier(2)
        val client = client { request ->
            when {
                request.url.encodedPath != "/youtubei/v1/browse" -> answer(request, 200, CLIENT_VERSION_OK)
                else -> {
                    bothInFlight.await(10, TimeUnit.SECONDS)
                    if (""""gl":"NI"""" in request.text()) answer(request, 403, "{}") else answer(request, 200, SERVED_EMPTY_CHART)
                }
            }
        }

        val (refused, served) = runBlocking {
            listOf("NI", "US").map { async { client.trendingMusic(it) } }.awaitAll()
        }

        assertEquals(403, refused.httpStatus)
        assertTrue(refused.videos.isFailure)
        assertEquals(200, served.httpStatus)
        assertEquals(emptyList<YtVideo>(), served.videos.getOrThrow())
    }

    @Test
    fun `a chart host that never answered reports 0 and keeps the transport fault`() {
        val client = client { throw UnknownHostException("Unable to resolve host \"music.youtube.com\"") }

        val chart = runBlocking { client.trendingMusic("NI") }

        assertEquals(0, chart.httpStatus)
        val causes = generateSequence(chart.videos.exceptionOrNull()) { it.cause }
        assertTrue(causes.any { it is UnknownHostException })
    }
}
