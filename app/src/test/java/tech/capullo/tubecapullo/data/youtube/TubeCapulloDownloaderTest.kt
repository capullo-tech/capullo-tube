package tech.capullo.tubecapullo.data.youtube

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import okhttp3.Response as OkResponse

private const val CHART_URL = "https://charts.youtube.com/charts/TrendingVideos/RightNow"

class TubeCapulloDownloaderTest {
    private fun downloader(code: Int, body: String = "{}"): TubeCapulloDownloader {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    OkResponse.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("stubbed")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return TubeCapulloDownloader(client)
    }

    private fun get(url: String) = Request.newBuilder().get(url).build()

    @Test
    fun `a refused chart request is reported as the chart host's status`() {
        val downloader = downloader(403)

        val response = downloader.execute(get(CHART_URL))

        assertEquals(403, response.responseCode())
        assertEquals(403, YouTubeExtractorClient(downloader).lastChartsStatus())
    }

    @Test
    fun `a served chart request reports 200, so an empty chart is the app's own doing`() {
        val downloader = downloader(200)

        downloader.execute(get(CHART_URL))

        assertEquals(200, YouTubeExtractorClient(downloader).lastChartsStatus())
    }

    @Test
    fun `a chart host that was never asked has no status`() {
        assertNull(YouTubeExtractorClient(downloader(200)).lastChartsStatus())
    }

    @Test
    fun `statuses are per host, so another YouTube call cannot mask the chart host`() {
        val downloader = downloader(403)

        downloader.execute(get(CHART_URL))
        downloader.execute(get("https://www.youtube.com/watch?v=abc"))

        assertEquals(403, YouTubeExtractorClient(downloader).lastChartsStatus())
        assertEquals(403, downloader.lastStatus("www.youtube.com"))
    }
}
