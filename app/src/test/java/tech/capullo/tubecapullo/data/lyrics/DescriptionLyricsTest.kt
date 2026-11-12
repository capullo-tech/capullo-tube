package tech.capullo.tubecapullo.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.capullo.tubecapullo.data.lyrics.DescriptionLyrics.MIN_LINES

/**
 * The description detector's strict rule (docs/probes/lyrics-criteria.md section 3): a lyrics
 * block passes; credits, a link list, a tracklist and a block without a chorus do not.
 */
class DescriptionLyricsTest {

    private val verse1 = listOf(
        "我站在風裡等你",
        "看那雲走過天際",
        "你說的話還在耳邊",
        "像昨天一樣清晰",
    )
    private val chorus = listOf(
        "請你不要走",
        "留下來陪我",
        "這一夜好長",
        "我只要你",
    )
    private val verse2 = listOf(
        "街燈一盞一盞亮起",
        "照不亮我的心裡",
        "你留下的那封信",
        "我還收在抽屜裡",
    )

    private val song = (verse1 + "" + chorus + "" + verse2 + "" + "[Chorus]" + chorus).joinToString("\n")

    /** [song] as the detector returns it: the section label gone. */
    private val songPlain = (verse1 + "" + chorus + "" + verse2 + "" + chorus).joinToString("\n")

    @Test
    fun `a lyrics block between promo text and credits is returned plain`() {
        val description = listOf(
            "官方MV 正式上線！",
            "Listen on Spotify: https://open.spotify.com/track/abc",
            "",
            song,
            "",
            "詞：林夕",
            "曲：陳小霞",
            "編曲：Terence Teo",
            "Producer: Someone",
            "#新歌 #MV",
        ).joinToString("\n")
        assertEquals(songPlain, DescriptionLyrics.extract(description))
    }

    @Test
    fun `a credits block is not lyrics`() {
        val credits = """
            作詞 Lyricist：林夕
            作曲 Composer：陳小霞
            編曲 Arranger：Terence Teo
            製作人 Producer：Someone
            Director: Jane Doe
            Mixed by Studio A
            Mastered by Studio B
            Guitar: Player One
            Bass: Player Two
            Drums: Player Three
            Keys: Player Four
            Strings: The Orchestra
            Photography: Someone Else
            Label: Golden Era
            Guitar: Player One
        """.trimIndent()
        assertNull(DescriptionLyrics.extract(credits))
    }

    @Test
    fun `each credits and link shape ends the lyrics`() {
        listOf(
            "作曲 陳小霞",
            "曲 陳小霞",
            "詞／曲：林夕",
            "Mixed by Studio A",
            "Produced by Someone",
            "Guitar: Player One",
            "Follow us https://example.com",
            "www.example.com",
            "#新歌",
            "@artist",
            "1:23 the second song",
        ).forEach { line ->
            assertEquals(line, songPlain, DescriptionLyrics.extract("$song\n$line\n${chorus.first()}"))
        }
    }

    /** [lines], then its first two lines again: a chorus by the pair rule, so only the line
     *  rules can refuse it. */
    private fun withChorus(lines: List<String>): String = (lines + lines.take(2)).joinToString("\n")

    @Test
    fun `a link list, handles, a tracklist and hashtags are not lyrics`() {
        assertNull(DescriptionLyrics.extract(withChorus((1..14).map { "Follow us https://example.com/page$it" })))
        assertNull(DescriptionLyrics.extract(withChorus((1..14).map { "Shop www.example$it.com" })))
        assertNull(DescriptionLyrics.extract(withChorus((1..14).map { "@artist$it" })))
        assertNull(DescriptionLyrics.extract(withChorus((1..14).map { "$it:00 Song number $it" })))
        assertNull(DescriptionLyrics.extract(withChorus((1..14).map { "#tag$it" })))
        // The same lines without the marks are a block: the marks are what refuse them.
        assertEquals(
            withChorus((1..14).map { "Song number $it" }),
            DescriptionLyrics.extract(withChorus((1..14).map { "Song number $it" })),
        )
    }

    @Test
    fun `a block needs a chorus of two lines repeated together`() {
        // A credits list of names, one name under two roles (the first probe run's false
        // accept): one line repeats, no pair does.
        val names = listOf(
            "Jason Koenig", "Ryan Lewis", "Mego Lin", "", "Stedicam Operators", "Chris Duerkopp",
            "Rhys Duncan", "", "Production Assistants", "Linda Simonsen", "Jackie Ganger",
            "Edward Tran", "", "Editing & Color Grading", "Ryan Lewis", "Erik Hustad",
        )
        assertNull(DescriptionLyrics.extract(names.joinToString("\n")))
        val poem = (1..20).map { "line number $it of a poem" }
        assertNull(DescriptionLyrics.extract((poem + poem[3]).joinToString("\n")))
        assertEquals(withChorus(poem), DescriptionLyrics.extract(withChorus(poem)))
    }

    @Test
    fun `a block needs the minimum line count`() {
        val short = (1..MIN_LINES - 3).map { "sung line $it" }
        assertNull(DescriptionLyrics.extract(withChorus(short)))
        val enough = short + "one more sung line"
        assertEquals(withChorus(enough), DescriptionLyrics.extract(withChorus(enough)))
    }

    @Test
    fun `a long line breaks the block`() {
        val half = (1..8).map { "sung line $it" }
        val prose = "This is a sentence of prose that runs far past any sung line of a song."
        assertNull(DescriptionLyrics.extract(listOf(withChorus(half), prose, withChorus(half)).joinToString("\n")))
    }

    @Test
    fun `two songs in one description give none, one song split in two gives the longer part`() {
        val other = (1..14).map { "another song line $it" }
        val medley = listOf(song, "曲名：我是真的愛上你", withChorus(other)).joinToString("\n")
        assertNull(DescriptionLyrics.extract(medley))
        // A long sung line splits one song: both parts pass and share the chorus.
        val long = "This sung line runs on and on past the length that a short line may have"
        val split = listOf(song, long, withChorus(chorus + verse2 + verse1)).joinToString("\n")
        assertEquals(songPlain, DescriptionLyrics.extract(split))
    }

    @Test
    fun `headings and rules around the lyrics are skipped`() {
        val text = listOf("LYRICS", "×=×=×=×=×", song, "-----", "Lyrics for \"Song\"").joinToString("\n")
        assertEquals(songPlain, DescriptionLyrics.extract(text))
    }
}
