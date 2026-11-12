package tech.capullo.tubecapullo.util

import java.util.Locale

/**
 * Title cleanup: strips the packaging uploaders wrap around a song title, so rows and the Player
 * show the song. The raw title stays the record everywhere (station, likes, history). Its
 * matching uses are [key], the same-song rules of every fetch from a song (owner decision
 * 2026-11-12, "Pairs": a related upload of the seed's song is dropped, and of two tracks with one
 * title only the most viewed is queued), and [albumKey], the same-album rule of Home's Full
 * albums row. The lyrics query (data/lyrics/LyricsQuery.kt) reads its artist and song with
 * [clean], [stripTaggedGroups], [splitAtDash], [artistName] and [sameArtist]. It is never used
 * for taste.
 *
 * Three rules, in order:
 * 1. A bracketed group - `(...)`, `[...]` or `【...】` - goes when EVERY word in it is packaging
 *    ([NOISE_WORDS]: "Official Music Video", "Lyric Video", "Visualizer", "HD", "4K"...). A group
 *    holding any other word stays whole. That is what keeps version markers: "(Live at Wembley)",
 *    "(2011 Remaster)", "(Official Live Video)", "(feat. X)".
 * 2. Everything from the first `|` goes: "Song | Album | Channel" keeps "Song".
 * 3. A leading "Artist - " goes when it repeats the uploader, compared case- and punctuation-blind
 *    after dropping the uploader's " - Topic" and "VEVO" suffixes. A prefix naming anyone else
 *    (a lyrics channel's upload, a collaboration) stays.
 *
 * The result is never blank: when cleaning removes everything, the raw title comes back.
 */
object TitleCleaner {

    fun clean(title: String, uploader: String?): String {
        val unwrapped = stripGroups(title) { words -> words.all { it in NOISE_WORDS } }
            .substringBefore('|')
            .let(::tidy)
        return stripUploaderPrefix(unwrapped, uploader).ifBlank { title }
    }

    /**
     * [text] without each bracketed group that holds at least ONE packaging word: a
     * [NOISE_WORDS] word, or a word [isPackaging] accepts. Looser than [clean]'s rule 1, which
     * keeps a group with any other word in it. For a lookup by name (the lyrics query): there a
     * kept "(Official Video - Live from the Tour)" costs the match, and a lost version marker
     * costs little because the lookup also checks the duration.
     */
    fun stripTaggedGroups(text: String, isPackaging: (String) -> Boolean): String {
        val stripped = stripGroups(text) { words ->
            words.any { it in NOISE_WORDS || isPackaging(it) }
        }
        return tidy(stripped)
    }

    /** "Artist - Song" split at the first spaced dash ([ARTIST_PREFIX]): the two sides, or null
     *  when the title has no such dash. Which side is the artist is the caller's call. */
    fun splitAtDash(title: String): Pair<String, String>? {
        val match = ARTIST_PREFIX.find(title) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** The uploader as an artist name: without YouTube's " - Topic" suffix and a label's
     *  trailing "VEVO" ("HarlemYuVEVO" gives "HarlemYu"). Case and spaces stay. */
    fun artistName(uploader: String): String {
        val name = uploader.trim().removeSuffix(TOPIC_SUFFIX)
        val vevo = name.length > VEVO_SUFFIX.length && name.endsWith(VEVO_SUFFIX, ignoreCase = true)
        return if (vevo) {
            name.dropLast(VEVO_SUFFIX.length)
        } else {
            name
        }
    }

    /** [name] names [uploader], compared as rule 3 compares an "Artist - " prefix. */
    fun sameArtist(name: String, uploader: String?): Boolean {
        val key = nameKey(name)
        return uploader != null && key.isNotEmpty() && key == artistKey(uploader)
    }

    /** [clean]'s result compared without case, spaces or punctuation: two uploads with the same
     *  key are one song for the same-song rules. Null when nothing of the title is a letter or a
     *  digit (an emoji-only title), so such titles never match each other. */
    fun key(title: String, uploader: String?): String? = nameKey(clean(title, uploader)).ifEmpty { null }

    /**
     * The album a full-album upload holds: two uploads with the same album key are one album for
     * Home's Full albums row (owner report 2026-11-12: "Hybrid Theory" showed twice). It is [key]
     * after two more rules:
     * 1. Album packaging goes. A bracketed group goes when every word in it is packaging
     *    ([NOISE_WORDS], [ALBUM_WORDS], a year 19xx/20xx or an ordinal such as "20th"):
     *    "(Full Album)", "[FULL ALBUM]", "(20th Anniversary Edition)", "(Remastered 2009)". Outside
     *    brackets, [ALBUM_PHRASE] goes: "- Full Album", "Álbum Completo", a trailing "HD", a year.
     *    A group with any other word stays: "(Live at Wembley)" is a different record.
     * 2. A leading "Artist - " goes when it names the [uploader] or one of [artists] (compared as
     *    rule 3 of the class), so a fan channel's "LINKIN PARK - HYBRID THEORY" matches the
     *    artist's own "Hybrid Theory".
     * When the packaging is all there is, the raw title is used. A year that IS the album name
     * goes too, so two year-named albums of one artist ("1989", "1999") share a key.
     */
    fun albumKey(title: String, uploader: String?, artists: Collection<String>): String? {
        val unpackaged = stripGroups(title) { it.all(::isAlbumPackaging) }
        val bare = tidy(ALBUM_PHRASE.replace(unpackaged, " "))
            .takeIf { text -> text.any { it.isLetterOrDigit() } } ?: title
        val names = (listOfNotNull(uploader) + artists).map(::artistKey)
            .filterTo(HashSet()) { it.isNotEmpty() }
        return nameKey(stripArtistPrefix(clean(bare, null), names)).ifEmpty { null }
    }

    /** The innermost bracketed groups whose words [drop] accepts go, until none is left. A group
     *  with no word at all ("( )", an emoji) stays. */
    private fun stripGroups(title: String, drop: (List<String>) -> Boolean): String {
        var text = title
        // GROUP only matches innermost groups, so loop: "(Official Video (HD))" clears in two
        // passes. Each change replaces a 2+ char group with one space, so the loop terminates.
        while (true) {
            val next = GROUP.replace(text) {
                val words = WORD.findAll(it.groupValues[1].lowercase(Locale.ROOT))
                    .map { word -> word.value }
                    .toList()
                if (words.isNotEmpty() && drop(words)) " " else it.value
            }
            if (next == text) return text
            text = next
        }
    }

    private fun isAlbumPackaging(word: String): Boolean = word in NOISE_WORDS || word in ALBUM_WORDS || YEAR.matches(word) || ORDINAL.matches(word)

    private fun stripUploaderPrefix(title: String, uploader: String?): String {
        val artist = uploader?.let { artistKey(it) }?.takeIf { it.isNotEmpty() } ?: return title
        return stripArtistPrefix(title, setOf(artist))
    }

    /** [title] without a leading "Artist - " whose artist's [nameKey] is in [artists]. */
    private fun stripArtistPrefix(title: String, artists: Set<String>): String {
        if (artists.isEmpty()) return title
        val match = ARTIST_PREFIX.find(title) ?: return title
        return if (nameKey(match.groupValues[1]) in artists) match.groupValues[2] else title
    }

    /** The uploader as a bare artist name: "Daft Punk - Topic" and "DaftPunkVEVO" both give
     *  "daftpunk". An uploader that is only "VEVO" keeps its name rather than becoming empty. */
    private fun artistKey(uploader: String): String {
        val key = nameKey(uploader.trim().removeSuffix(TOPIC_SUFFIX))
        return if (key.length > VEVO_SUFFIX.length && key.endsWith(VEVO_SUFFIX)) {
            key.dropLast(VEVO_SUFFIX.length)
        } else {
            key
        }
    }

    /** Lowercase letters and digits only, so "Taylor Swift" and "TaylorSwift" compare equal. */
    private fun nameKey(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun tidy(text: String): String = text.replace(SPACES, " ").trim().replace(DANGLING, "")

    /**
     * Words that only package a title, never name a version of the song. A group is dropped only
     * when ALL its words are here, so adding a word can only remove more groups; adding a version
     * word (live, remaster, acoustic, mix, edit) would start eating versions. Spanish and French
     * forms are in because Latin and francophone uploads label the same way ("Video Oficial",
     * "Letra", "Clip Officiel"). GUESS: chosen by reading real titles, not measured over a corpus.
     */
    private val NOISE_WORDS = setOf(
        "official",
        "oficial",
        "officiel",
        "music",
        "video",
        "videoclip",
        "clip",
        "audio",
        "mv",
        "m/v",
        "lyric",
        "lyrics",
        "letra",
        "visualizer",
        "visualiser",
        "hd",
        "hq",
        "4k",
    )

    /**
     * Words that only package a full-album upload, for [albumKey]'s groups (with [NOISE_WORDS],
     * a year and an ordinal). Unlike a song's, an album's remaster or anniversary edition is the
     * same album for the row: the listener wants one card for it. GUESS (owner report
     * 2026-11-12): the owner's list, read off real titles, not measured over a corpus. Adding a
     * word merges more uploads into one card; a missing word leaves duplicates.
     */
    private val ALBUM_WORDS = setOf(
        "full",
        "album",
        "albums",
        "álbum",
        "completo",
        "complete",
        "anniversary",
        "edition",
        "remaster",
        "remastered",
        "deluxe",
        "playlist",
    )

    /** Album packaging outside brackets, for [albumKey]: "Hybrid Theory - Full Album",
     *  "Álbum Completo", a trailing "HD", a year. Whole words only. GUESS, as [ALBUM_WORDS]. */
    private val ALBUM_PHRASE = Regex(
        """(?<![\p{L}\p{N}])(full\s+album|[aá]lbum\s+completo|remaster(ed)?|deluxe(\s+edition)?""" +
            """|hd|hq|4k|playlist|(19|20)\d\d)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    private val YEAR = Regex("""(19|20)\d\d""")

    /** "20th", "1st": the number of an anniversary edition. */
    private val ORDINAL = Regex("""\d+(st|nd|rd|th)""")

    /** An innermost bracketed group; group 1 is its content. */
    private val GROUP = Regex("""[(\[【]([^()\[\]【】]*)[)\]】]""")

    /** A word inside a group. The slash keeps "M/V" one word. */
    private val WORD = Regex("""[\p{L}\p{N}/]+""")

    /** "Artist - Song" with a spaced hyphen, en dash or em dash. The spaces are required, so the
     *  hyphen inside "a-ha" or "Anti-Hero" never splits. The lazy first group splits at the FIRST
     *  separator; an artist name that itself holds a spaced dash then fails the uploader match and
     *  the title stays as it was. */
    private val ARTIST_PREFIX = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")

    private val SPACES = Regex("""\s+""")

    /** Separators left dangling at the end once a group or tail is gone: "Song - (Official Video)"
     *  first becomes "Song -". */
    private val DANGLING = Regex("""[\s\-–—|:,]+$""")

    /** YouTube's auto-generated artist channels are named "<Artist> - Topic". */
    private const val TOPIC_SUFFIX = " - Topic"

    /** Label channels append VEVO to the artist ("TaylorSwiftVEVO"); compared after [nameKey]
     *  lowercases, hence lowercase here. */
    private const val VEVO_SUFFIX = "vevo"
}
