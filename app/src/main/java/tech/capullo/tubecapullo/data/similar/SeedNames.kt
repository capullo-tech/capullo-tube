package tech.capullo.tubecapullo.data.similar

import tech.capullo.tubecapullo.data.lyrics.LyricsQuery
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.util.TitleCleaner
import java.text.Normalizer

/**
 * [trusted] is false when the artist is only the uploader's name (a label such as T-Series
 * uploading "Tum Hi Ho"): good enough to ask Last.fm for the song, not to ask for the artist's
 * neighbours.
 */
data class NameQuery(val artist: String, val title: String, val trusted: Boolean = true)

/**
 * The artist and title tries for one YouTube video, best first. Last.fm answers "Track not
 * found" for "Despacito ft. Daddy Yankee" or "GANGNAM STYLE(강남스타일) M/V", so after the
 * parsed names come the same names without featured artists, bracketed text, an M/V tag, and
 * with only the first artist of a "Grupo Frontera x Bad Bunny" credit.
 */
object SeedNames {
    fun queries(video: YtVideo): List<NameQuery> {
        val parsedNames = mutableListOf<NameQuery>()
        val uploader = video.uploader
        if (uploader != null && uploader.trim().endsWith(TOPIC_SUFFIX)) {
            val artist = TitleCleaner.artistName(uploader).trim()
            val title = TitleCleaner.clean(video.title, null)
            if (artist.isNotEmpty() && title.isNotEmpty()) parsedNames += NameQuery(artist, title)
        }
        val parsed = LyricsQuery.of(video.title, uploader)
        parsed?.artist?.let { artist ->
            parsedNames += NameQuery(artist, parsed.title, trusted = key(artist) in key(video.title))
        }
        val bare = parsedNames.mapNotNull { query ->
            NameQuery(firstArtist(query.artist), bareTitle(query.title), query.trusted)
                .takeIf { it.artist.isNotEmpty() && it.title.isNotEmpty() }
        }
        return (parsedNames + bare).distinctBy { key(it.artist) to key(it.title) }
    }

    fun firstArtist(credit: String): String = credit.split(CREDIT_SEPARATOR, limit = 2).first().trim()

    fun bareTitle(title: String): String {
        var text = title
        while (true) {
            val next = BRACKETS.replace(text, " ")
            if (next == text) break
            text = next
        }
        text = FEATURING.replace(text, "")
        text = MV_TAG.replace(text, "")
        return text.replace(SPACES, " ").trim().trimEnd('-', '–', '—', ':', ',').trim()
    }

    /** Letters and digits, lowercase, accents folded: "Prófugos" and "Profugos" share a key. */
    fun key(name: String): String = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
        .filter { it.isLetterOrDigit() }

    private const val TOPIC_SUFFIX = " - Topic"

    private val CREDIT_SEPARATOR = Regex(
        """\s*,\s*|\s+(?:x|&|feat\.?|ft\.?|featuring)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKETS = Regex("""\s*[(\[【「][^()\[\]【】「」]*[)\]】」]""")

    private val FEATURING = Regex("""\s+(?:ft\.?|feat\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

    private val MV_TAG = Regex("""\s+m/?v$""", RegexOption.IGNORE_CASE)

    private val SPACES = Regex("""\s+""")
}
