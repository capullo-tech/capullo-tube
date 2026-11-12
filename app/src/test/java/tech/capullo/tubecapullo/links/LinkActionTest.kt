package tech.capullo.tubecapullo.links

import org.junit.Assert.assertEquals
import org.junit.Test

/** The incoming-link decision table (owner decisions 2026-11-11), row by row. */
class LinkActionTest {

    private val id = "dQw4w9WgXcQ"
    private val video = LinkTarget.Video(id)
    private val listId = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"
    private val bools = listOf(false, true)

    @Test
    fun `a video plays now when nothing plays`() {
        assertEquals(
            LinkAction.PlayNow(id),
            decideLinkAction(video, somethingPlays = false, askFirst = false),
        )
    }

    @Test
    fun `a video plays next when something plays`() {
        assertEquals(
            LinkAction.PlayNext(id),
            decideLinkAction(video, somethingPlays = true, askFirst = false),
        )
    }

    @Test
    fun `with ask on a video always asks, offering play next only while something plays`() {
        assertEquals(
            LinkAction.Ask(id, canPlayNext = true),
            decideLinkAction(video, somethingPlays = true, askFirst = true),
        )
        assertEquals(
            LinkAction.Ask(id, canPlayNext = false),
            decideLinkAction(video, somethingPlays = false, askFirst = true),
        )
    }

    @Test
    fun `a playlist opens in Search whatever plays and whatever the ask setting`() {
        for (plays in bools) {
            for (ask in bools) {
                assertEquals(
                    "plays=$plays ask=$ask",
                    LinkAction.OpenPlaylist(listId),
                    decideLinkAction(LinkTarget.Playlist(listId), plays, ask),
                )
            }
        }
    }

    @Test
    fun `a shared Mix opens in Search as a playlist, never as its song`() {
        val mix = IncomingLinks.find("https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1")
        for (plays in bools) {
            for (ask in bools) {
                assertEquals(
                    "plays=$plays ask=$ask",
                    LinkAction.OpenPlaylist("RD$id"),
                    decideLinkAction(mix, plays, ask),
                )
            }
        }
    }

    @Test
    fun `links that are not a video or playlist are rejected with their own reason`() {
        for (plays in bools) {
            for (ask in bools) {
                assertEquals(
                    LinkAction.Reject(RejectReason.NOT_A_VIDEO),
                    decideLinkAction(LinkTarget.OtherYouTube, plays, ask),
                )
                assertEquals(
                    LinkAction.Reject(RejectReason.NOT_YOUTUBE),
                    decideLinkAction(LinkTarget.NotYouTube, plays, ask),
                )
            }
        }
    }
}
