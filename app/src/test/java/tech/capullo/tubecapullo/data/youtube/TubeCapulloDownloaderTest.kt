package tech.capullo.tubecapullo.data.youtube

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import okhttp3.Request as OkRequest
import okhttp3.Response as OkResponse

private const val CHART_URL = "https://charts.youtube.com/youtubei/v1/browse?alt=json&prettyPrint=false"

class TubeCapulloDownloaderTest {
    private fun downloader(answer: (OkRequest) -> OkResponse): TubeCapulloDownloader = TubeCapulloDownloader(
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> answer(chain.request()) })
            .build(),
    )

    private fun answer(request: OkRequest, code: Int): OkResponse = OkResponse.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("stubbed")
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun get(url: String) = Request.newBuilder().get(url).build()

    @Test
    fun `each host keeps its own status, so another YouTube call cannot mask the chart host`() {
        val downloader = downloader { answer(it, if (it.url.host == "charts.youtube.com") 403 else 200) }
        val statuses = HashMap<String, Int>()

        downloader.recordStatuses(statuses) {
            downloader.execute(get(CHART_URL))
            downloader.execute(get("https://www.youtube.com/watch?v=abc"))
        }

        assertEquals(mapOf("charts.youtube.com" to 403, "www.youtube.com" to 200), statuses)
    }

    @Test
    fun `a chart request redirected to Google's sorry page answers for the chart host`() {
        val downloader = downloader { asked ->
            answer(asked.newBuilder().url("https://www.google.com/sorry/index").build(), 429)
        }
        val statuses = HashMap<String, Int>()

        assertThrows(ReCaptchaException::class.java) {
            downloader.recordStatuses(statuses) { downloader.execute(get(CHART_URL)) }
        }

        assertEquals(mapOf("charts.youtube.com" to 429), statuses)
    }
}
