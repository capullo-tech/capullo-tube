package tech.capullo.tubecapullo.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.CollectionKind
import tech.capullo.tubecapullo.data.youtube.YtCollection

/** What the search box fetches under each chip, and the collection rows' second line. */
class SearchFiltersTest {

    @Test
    fun `search terms follow the chip`() {
        assertEquals(
            SearchRequest.Songs("daft punk"),
            searchRequest("  daft punk ", SearchFilter.SONGS),
        )
        assertEquals(
            SearchRequest.Collections("daft punk", CollectionKind.PLAYLISTS),
            searchRequest("daft punk", SearchFilter.PLAYLISTS),
        )
        assertEquals(
            SearchRequest.Collections("daft punk", CollectionKind.ALBUMS),
            searchRequest("daft punk", SearchFilter.ALBUMS),
        )
        assertEquals(
            SearchRequest.Collections("daft punk", CollectionKind.CHANNELS),
            searchRequest("daft punk", SearchFilter.CHANNELS),
        )
    }

    @Test
    fun `a pasted link ignores the chip`() {
        val playlistId = "PLSdoVPM5WnndLX6Ngmb8wktMF61dJirKl"
        SearchFilter.entries.forEach { filter ->
            assertEquals(
                SearchRequest.PlaylistLink(playlistId),
                searchRequest("https://music.youtube.com/playlist?list=$playlistId", filter),
            )
            assertEquals(
                SearchRequest.VideoLink("dQw4w9WgXcQ"),
                searchRequest("https://youtu.be/dQw4w9WgXcQ", filter),
            )
        }
    }

    @Test
    fun `a watch link from a playlist lists the playlist`() {
        assertEquals(
            SearchRequest.PlaylistLink("PLSdoVPM5WnndLX6Ngmb8wktMF61dJirKl"),
            searchRequest(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLSdoVPM5WnndLX6Ngmb8wktMF61dJirKl",
                SearchFilter.CHANNELS,
            ),
        )
    }

    @Test
    fun `a blank box asks for nothing`() {
        assertNull(searchRequest("   ", SearchFilter.ALBUMS))
    }

    private fun collection(uploader: String?, itemCount: Long?) = YtCollection("https://example/list", "Discovery", uploader, null, itemCount)

    @Test
    fun `a collection row names its owner and size when known`() {
        assertEquals("Daft Punk · 14 songs", collectionDetails(collection("Daft Punk", 14)))
        assertEquals("Daft Punk", collectionDetails(collection("Daft Punk", null)))
        assertEquals("1 song", collectionDetails(collection(null, 1)))
        assertNull(collectionDetails(collection(null, null)))
    }
}
