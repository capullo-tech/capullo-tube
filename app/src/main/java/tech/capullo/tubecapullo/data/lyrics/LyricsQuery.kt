package tech.capullo.tubecapullo.data.lyrics

import tech.capullo.tubecapullo.util.TitleCleaner

data class LyricsQuery(val artist: String?, val title: String) {

    val searchText: String get() = listOfNotNull(artist, title).joinToString(" ")

    companion object {

        fun of(title: String, uploader: String?): LyricsQuery? {
            val text = TitleCleaner.clean(title, null)
            val split = split(text, uploader)
            val song = tidySong(split?.second ?: text).takeIf { it.any(Char::isLetterOrDigit) }
                ?: return null
            val artist = (split?.first ?: uploader?.let(TitleCleaner::artistName))
                ?.let(::tidyArtist)
                ?.takeIf { it.any(Char::isLetterOrDigit) }
            return LyricsQuery(artist, song)
        }

        private fun split(text: String, uploader: String?): Pair<String, String>? {
            bracketed(CJK_BRACKET, text)?.let { return it }
            bracketed(SPACED_BRACKET, text)?.let { return it }
            TitleCleaner.splitAtDash(text)?.let { (left, right) ->
                val reversed = TitleCleaner.sameArtist(right, uploader) &&
                    !TitleCleaner.sameArtist(left, uploader)
                return if (reversed) right to left else left to right
            }
            MIDDLE_DOT.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
            TIGHT_DASH.find(text)?.takeIf { isCjk(it.groupValues[2].first()) }
                ?.let { return it.groupValues[1] to it.groupValues[2] }
            QUOTED.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
            COLON.find(text)
                ?.takeIf { it.groupValues[1].trim().split(SPACES).size <= COLON_ARTIST_MAX_WORDS }
                ?.let { return it.groupValues[1] to it.groupValues[2] }
            return cjkNameAndSong(text)
        }

        private fun bracketed(shape: Regex, text: String): Pair<String, String>? {
            val match = shape.find(text) ?: return null
            val artist = match.groupValues[1].trimEnd(*TRAILING_SEPARATORS)
            return if (artist.any(Char::isLetterOrDigit)) artist to match.groupValues[2] else null
        }

        private fun cjkNameAndSong(text: String): Pair<String, String>? {
            val words = text.trim().split(SPACES)
            if (words.size != 2 || !words.all { word -> word.all(::isCjk) }) return null
            return if (words[0].length in CJK_NAME_LENGTH) words[0] to words[1] else null
        }

        private fun tidyArtist(raw: String): String {
            val trimmed = raw.trim().trimEnd(*TRAILING_SEPARATORS)
            val handle = trimmed.startsWith('@')

            val name = trimmed.dropWhile { !it.isLetterOrDigit() }
            return dropLatinAlias(if (handle) name.replace(CAMEL_HUMP, "$1 $2") else name)
        }

        private fun tidySong(raw: String): String = dropLatinAlias(
            TitleCleaner.stripTaggedGroups(raw, ::isLookupPackaging),
        )
            .trim()
            .trimEnd(*TRAILING_SEPARATORS)

        private fun isLookupPackaging(word: String): Boolean = word in LATIN_PACKAGING ||
            CJK_PACKAGING.any { it in word }

        private fun dropLatinAlias(text: String): String {
            val lastCjk = text.indexOfLast(::isCjk)
            if (lastCjk < 0) return text
            val tail = text.substring(lastCjk + 1)
            return if (tail.firstOrNull()?.isWhitespace() == true && tail.any(Char::isLetter)) {
                text.substring(0, lastCjk + 1)
            } else {
                text
            }
        }

        private fun isCjk(c: Char): Boolean = when (c) {
            in '\u1100'..'\u11FF',
            in '\u2E80'..'\u2EFF',
            in '\u2F00'..'\u2FD5',
            '\u3005',
            '\u3007',
            in '\u3021'..'\u3029',
            in '\u302E'..'\u302F',
            in '\u3038'..'\u303B',
            in '\u3041'..'\u3096',
            in '\u309D'..'\u309F',
            in '\u30A1'..'\u30FA',
            in '\u30FD'..'\u30FF',
            in '\u3131'..'\u318E',
            in '\u31F0'..'\u31FF',
            in '\u3200'..'\u321E',
            in '\u3260'..'\u327E',
            in '\u32D0'..'\u32FE',
            in '\u3300'..'\u3357',
            in '\u3400'..'\u4DBF',
            in '\u4E00'..'\u9FFF',
            in '\uA960'..'\uA97F',
            in '\uAC00'..'\uD7A3',
            in '\uD7B0'..'\uD7FF',
            in '\uF900'..'\uFAFF',
            in '\uFF66'..'\uFF6F',
            in '\uFF71'..'\uFF9D',
            in '\uFFA0'..'\uFFDC',
            -> true

            else -> false
        }

        private val CJK_NAME_LENGTH = 2..4

        private const val COLON_ARTIST_MAX_WORDS = 4

        private val LATIN_PACKAGING = setOf("from")

        private val CJK_PACKAGING = listOf(
            "官方",
            "完整版",
            "歌詞",
            "歌词",
            "中字",
            "字幕",
            "主題曲",
            "主题曲",
            "插曲",
            "電影",
            "电影",
        )

        private val CJK_BRACKET = Regex("""^(.*?)\s*[《【]\s*([^》】]+?)\s*[》】]""")

        private val SPACED_BRACKET = Regex("""^(.+?)\s+\[\s+([^\]]+?)\s+]""")

        private val MIDDLE_DOT = Regex("""^(.+?)\s+[‧·・]\s+(.+)$""")

        private val TIGHT_DASH = Regex("""^(.+?)\s+[-–—](\S.*)$""")

        private val QUOTED = Regex("""^(.+?),?\s+["“](.+?)["”]""")

        private val COLON = Regex("""^([^:()\[\]【】]+):\s+(.+)$""")

        private val CAMEL_HUMP = Regex("""(\p{Ll})(\p{Lu})""")

        private val SPACES = Regex("""\s+""")

        private val TRAILING_SEPARATORS = charArrayOf(' ', '-', '–', '—', ':', '|', '~', ',')
    }
}
