package tech.capullo.tubecapullo.util

import java.util.Locale

object TitleCleaner {
    fun clean(title: String, uploader: String?): String {
        val unwrapped = stripGroups(title) { words -> words.all { it in NOISE_WORDS } }
            .substringBefore('|')
            .let(::tidy)
        return stripUploaderPrefix(unwrapped, uploader).ifBlank { title }
    }

    fun stripTaggedGroups(text: String, isPackaging: (String) -> Boolean): String {
        val stripped = stripGroups(text) { words ->
            words.any { it in NOISE_WORDS || isPackaging(it) }
        }
        return tidy(stripped)
    }

    fun splitAtDash(title: String): Pair<String, String>? {
        val match = ARTIST_PREFIX.find(title) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    fun artistName(uploader: String): String {
        val name = uploader.trim().removeSuffix(TOPIC_SUFFIX)
        val vevo = name.length > VEVO_SUFFIX.length && name.endsWith(VEVO_SUFFIX, ignoreCase = true)
        return if (vevo) {
            name.dropLast(VEVO_SUFFIX.length)
        } else {
            name
        }
    }

    fun sameArtist(name: String, uploader: String?): Boolean {
        val key = nameKey(name)
        return uploader != null && key.isNotEmpty() && key == artistKey(uploader)
    }

    fun key(title: String, uploader: String?): String? = nameKey(clean(title, uploader)).ifEmpty { null }

    fun albumKey(title: String, uploader: String?, artists: Collection<String>): String? {
        val unpackaged = stripGroups(title) { it.all(::isAlbumPackaging) }
        val bare = tidy(ALBUM_PHRASE.replace(unpackaged, " "))
            .takeIf { text -> text.any { it.isLetterOrDigit() } } ?: title
        val names = (listOfNotNull(uploader) + artists).map(::artistKey)
            .filterTo(HashSet()) { it.isNotEmpty() }
        return nameKey(stripArtistPrefix(clean(bare, null), names)).ifEmpty { null }
    }

    private fun stripGroups(title: String, drop: (List<String>) -> Boolean): String {
        var text = title
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

    private fun stripArtistPrefix(title: String, artists: Set<String>): String {
        if (artists.isEmpty()) return title
        val match = ARTIST_PREFIX.find(title) ?: return title
        return if (nameKey(match.groupValues[1]) in artists) match.groupValues[2] else title
    }

    private fun artistKey(uploader: String): String {
        val key = nameKey(uploader.trim().removeSuffix(TOPIC_SUFFIX))
        return if (key.length > VEVO_SUFFIX.length && key.endsWith(VEVO_SUFFIX)) {
            key.dropLast(VEVO_SUFFIX.length)
        } else {
            key
        }
    }

    private fun nameKey(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun tidy(text: String): String = text.replace(SPACES, " ").trim().replace(DANGLING, "")

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

    private val ALBUM_PHRASE = Regex(
        """(?<![\p{L}\p{N}])(full\s+album|[aá]lbum\s+completo|remaster(ed)?|deluxe(\s+edition)?""" +
            """|hd|hq|4k|playlist|(19|20)\d\d)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    private val YEAR = Regex("""(19|20)\d\d""")

    private val ORDINAL = Regex("""\d+(st|nd|rd|th)""")

    private val GROUP = Regex("""[(\[【]([^()\[\]【】]*)[)\]】]""")

    private val WORD = Regex("""[\p{L}\p{N}/]+""")

    private val ARTIST_PREFIX = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")

    private val SPACES = Regex("""\s+""")

    private val DANGLING = Regex("""[\s\-–—|:,]+$""")

    private const val TOPIC_SUFFIX = " - Topic"

    private const val VEVO_SUFFIX = "vevo"
}
