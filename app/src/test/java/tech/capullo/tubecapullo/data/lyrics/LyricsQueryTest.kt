package tech.capullo.tubecapullo.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lyrics query on the shapes of the owner's songs (docs/probes/lyrics-criteria.md section 2):
 * each first-run miss shape gives the artist and the song LRCLIB knows, and the first-run hit
 * shapes still give what they gave.
 */
class LyricsQueryTest {

    private fun assertQuery(artist: String?, song: String, title: String, uploader: String?) = assertEquals("of(\"$title\", $uploader)", LyricsQuery(artist, song), LyricsQuery.of(title, uploader))

    @Test
    fun `CJK brackets name the song and the artist before them`() {
        assertQuery("周杰倫", "晴天", "周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video", "周杰倫 Jay Chou")
        assertQuery("光良", "童話", "光良 Michael Wong【童話】Official Music Video", "滾石唱片 ROCK RECORDS")
        assertQuery(
            "伍佰",
            "挪威的森林",
            "伍佰 Wu Bai&China Blue【挪威的森林 Norwegian forest】Official Music Video",
            "滾石唱片 ROCK RECORDS",
        )
        assertQuery("范逸臣", "放生", "范逸臣 Van Fan《放生》官方MV (Official Music Video)", "Golden Era 金曲年代")
        // A Latin song inside the brackets keeps its words; the group after it is the film's.
        assertQuery(
            "范逸臣",
            "I BELIEVE",
            "范逸臣 Van Fan《I BELIEVE》（電影【我的野蠻女友】中文主題曲）官方MV (Official Music Video)",
            "Golden Era 金曲年代",
        )
        // A band name in Latin letters is the whole artist.
        assertQuery("F4", "在這裡等你", "F4《在這裡等你》Zai Zhe Li Deng Ni (Official Video Karaoke)", "Golden Era 金曲年代")
    }

    @Test
    fun `square brackets with spaces inside name the song, a version tag does not`() {
        assertQuery("張信哲", "過火", "張信哲 Jeff Chang [ 過火 ] 官方完整版 Official MV", "潮水音樂 Tide Music")
        assertQuery(
            "動力火車",
            "忠孝東路走九遍",
            "動力火車 Power Station [ 忠孝東路走九遍 ] Official Music Video",
            "華研國際",
        )
        assertQuery("Eminem", "Lose Yourself", "Eminem - Lose Yourself [HD]", "msvogue23")
    }

    @Test
    fun `a short label before a colon is the artist`() {
        assertQuery(
            "twenty one pilots",
            "Stressed Out",
            "twenty one pilots: Stressed Out [OFFICIAL VIDEO]",
            "Fueled By Ramen and twenty one pilots",
        )
        // "(from Suicide Squad: The Album)" is packaging for a lookup, and its colon is not a
        // separator.
        assertQuery(
            "twenty one pilots",
            "Heathens",
            "twenty one pilots: Heathens (from Suicide Squad: The Album) [OFFICIAL VIDEO]",
            "Fueled By Ramen and twenty one pilots",
        )
        assertQuery(
            "Gym Class Heroes",
            "Stereo Hearts ft. Adam Levine",
            "Gym Class Heroes: Stereo Hearts ft. Adam Levine [OFFICIAL VIDEO]",
            "Fueled By Ramen",
        )
    }

    @Test
    fun `a CJK name keeps no Latin alias, a CJK song keeps no translation`() {
        assertQuery("庾澄慶", "春泥", "庾澄慶 Harlem Yu - 春泥", "HarlemYuVEVO")
        assertQuery(
            "張宇",
            "曲終人散",
            "張宇 Phil Chang -  曲終人散 The Curtain Falls (官方完整版MV)",
            "Timeless Music",
        )
        assertQuery("張雨生", "大海", "張雨生 Tom Chang - 大海 (official 官方完整版MV)", "Timeless Music")
    }

    @Test
    fun `a Chinese name and a song with only a space between`() {
        assertQuery("張學友", "一千個傷心的理由", "張學友 一千個傷心的理由", "andox65")
    }

    @Test
    fun `other separators`() {
        assertQuery("張學友", "聽海", "張學友 ‧ 聽海", "NRenio 雲之鳥")
        assertQuery(
            "Amr Diab",
            "Tamally Maak",
            "@AmrDiab - Tamally Maak | Official Music Video - HD Version | عمرو دياب - تملي معاك",
            "Mazzika - مزيكا",
        )
        assertQuery("江蕙", "夢中的情話", "江蕙 -夢中的情話 MENG CHUNG DE CHING HUA(Official Music Video)", "阿爾發音樂")
        assertQuery("Eminem", "Mockingbird", "Eminem, \"Mockingbird\" (video lyric)", "Old School HipHop")
        assertQuery("Limp bizkit", "Take a look around", "♪ Limp bizkit - Take a look around", "Vincent M")
    }

    @Test
    fun `a title alone takes the channel as the artist`() {
        assertQuery("張學友", "吻別", "吻別", "張學友 Jacky Cheung")
        assertQuery("伍佰", "夢醒時分", "夢醒時分", "伍佰 Wu Bai & China Blue")
        assertQuery("limpbizkit", "Take A Look Around", "Take A Look Around", "limpbizkit")
        // A label channel's VEVO suffix and an auto-generated channel's " - Topic" go.
        assertQuery("ImagineDragons", "Thunder", "Thunder", "ImagineDragonsVEVO")
        assertQuery("Daft Punk", "Get Lucky", "Get Lucky", "Daft Punk - Topic")
    }

    @Test
    fun `the first-run hit shapes still read the same`() {
        assertQuery("Limp Bizkit", "Break Stuff", "Limp Bizkit - Break Stuff (Official Music Video)", "limpbizkit")
        assertQuery(
            "Amr Diab",
            "Nour El Ein",
            "Amr Diab - Nour El Ein | Official Music Video - HD Version | عمرو دياب - نور العين",
            "Mazzika - مزيكا",
        )
        assertQuery(
            "Clean Bandit",
            "Rockabye (feat. Sean Paul & Anne-Marie)",
            "Clean Bandit - Rockabye (feat. Sean Paul & Anne-Marie) [Official Video]",
            "Clean Bandit",
        )
        assertQuery("The Chainsmokers", "Closer ft. Halsey", "The Chainsmokers - Closer (Lyric) ft. Halsey", "The Chainsmokers")
        // A version group with a packaging word goes for the lookup ("Live from the ... Tour").
        assertQuery(
            "Sia",
            "Unstoppable",
            "Sia - Unstoppable (Official Video - Live from the Nostalgic For The Present Tour)",
            "Sia",
        )
        assertQuery("Sia", "Cheap Thrills (Performance Edit)", "Sia - Cheap Thrills (Performance Edit)", "Sia")
        assertQuery("王識賢", "愛到無命不知驚", "王識賢《愛到無命不知驚》官方MV│金曲獎歌曲", "華特音樂")
    }

    @Test
    fun `Song - Artist is turned round when the right side is the channel`() {
        assertQuery("Linkin Park", "Faint", "Faint (Official Music Video) [4K UPGRADE] – Linkin Park", "Linkin Park")
        assertQuery("Linkin Park", "In The End", "In The End [Official HD Music Video] - Linkin Park", "Linkin Park")
        // The channel on the left: the usual order.
        assertQuery("Imagine Dragons", "Natural", "Imagine Dragons - Natural", "ImagineDragons")
    }

    @Test
    fun `no song left gives no query`() {
        assertNull(LyricsQuery.of("(Official Video)", null))
        assertNull(LyricsQuery.of("🤘🎤", "Someone"))
    }

    @Test
    fun `the search text is artist then song, or the song alone`() {
        assertEquals("張學友 聽海", LyricsQuery("張學友", "聽海").searchText)
        assertEquals("聽海", LyricsQuery(null, "聽海").searchText)
    }
}
