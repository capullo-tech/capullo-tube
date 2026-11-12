package tech.capullo.tubecapullo.data.lyrics

import tech.capullo.tubecapullo.util.TitleCleaner

/**
 * What LRCLIB is asked for: the artist and the song read from a YouTube title and its channel
 * name. [artist] is null when neither names one; LRCLIB's search then gets the song alone.
 */
data class LyricsQuery(val artist: String?, val title: String) {

    /** LRCLIB's search text: "artist song". */
    val searchText: String get() = listOfNotNull(artist, title).joinToString(" ")

    companion object {

        /**
         * The query for one upload, or null when no song title is left. [TitleCleaner.clean]
         * first drops the packaging groups and the `|` tail (with no uploader, so a leading
         * "Artist - " stays: here it is the answer). Then the first shape that matches names the
         * artist and the song:
         *
         * 1. `Artist《Song》` or `Artist【Song】` (the first such bracket),
         * 2. `Artist [ Song ]` (spaces inside the brackets; a plain `[Live]` is a version),
         * 3. `Artist - Song` ([TitleCleaner.splitAtDash]); `Song - Artist` when the right side is
         *    the channel and the left is not ("Faint - Linkin Park" on Linkin Park's channel),
         * 4. `Artist ‧ Song` (a spaced middle dot), or `Artist -Song` before a CJK song,
         * 5. `Artist, "Song"`,
         * 6. `artist: song` (a label of at most [COLON_ARTIST_MAX_WORDS] words before the colon),
         * 7. `歌手 歌名`: two CJK words, the first of them a name of [CJK_NAME_LENGTH] characters,
         * 8. else the channel is the artist ([TitleCleaner.artistName]) and the title is the song.
         *
         * Then each side is tidied: a CJK name keeps no Latin alias after it ("張學友 Jacky
         * Cheung", "晴天 Sunny Day"), an `@Handle` artist splits at its capitals, and the song
         * loses each group with a packaging word in it ([TitleCleaner.stripTaggedGroups]).
         */
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

        /** The (artist, song) the first matching shape gives, or null for "no shape". */
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

        /** Group 1 before the bracket is the artist, group 2 inside it the song; null when
         *  nothing names an artist before the bracket (`【Song】Artist`). */
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
            // "♪ Limp bizkit", "@AmrDiab": what precedes the first letter is decoration.
            val name = trimmed.dropWhile { !it.isLetterOrDigit() }
            return dropLatinAlias(if (handle) name.replace(CAMEL_HUMP, "$1 $2") else name)
        }

        private fun tidySong(raw: String): String = dropLatinAlias(
            TitleCleaner.stripTaggedGroups(raw, ::isLookupPackaging),
        )
            .trim()
            .trimEnd(*TRAILING_SEPARATORS)

        /** A word of a group that only packages the upload for a lookup, beyond
         *  TitleCleaner's NOISE_WORDS: "(from Suicide Squad: The Album)", "(官方完整版MV)",
         *  "(歌词版)", "(中字MV)". A CJK word has no spaces, so a CJK term counts anywhere in it. */
        private fun isLookupPackaging(word: String): Boolean = word in LATIN_PACKAGING ||
            CJK_PACKAGING.any { it in word }

        /** "張學友 Jacky Cheung" -> "張學友", "晴天 Sunny Day" -> "晴天": CJK text followed by a
         *  space and a Latin alias or translation. Text with no CJK, or with Latin before its
         *  CJK ("F4", "F.I.R. 飛兒樂團"), stays. */
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

        private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in CJK_SCRIPTS

        private val CJK_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
        )

        /** Chinese personal names are 2 or 3 characters, 4 with a compound surname. A longer
         *  first word is more likely part of the song (or a band), so rule 7 leaves it and the
         *  channel names the artist. Judgement, not measured: the owner's songs had one upload
         *  of this shape ("張學友 一千個傷心的理由"). */
        private val CJK_NAME_LENGTH = 2..4

        /** Rule 6: "twenty one pilots: Stressed Out" has 3 words before the colon; a longer
         *  label is more likely a sentence with a colon in it. Judgement, not measured. */
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

        /** `Artist《Song》` / `Artist【Song】`: the first such bracket. */
        private val CJK_BRACKET = Regex("""^(.*?)\s*[《【]\s*([^》】]+?)\s*[》】]""")

        /** `Artist [ Song ]`: a space inside each bracket. */
        private val SPACED_BRACKET = Regex("""^(.+?)\s+\[\s+([^\]]+?)\s+]""")

        private val MIDDLE_DOT = Regex("""^(.+?)\s+[‧·・]\s+(.+)$""")

        /** `Artist -Song`: a dash spaced on the left only; [split] checks the song is CJK. */
        private val TIGHT_DASH = Regex("""^(.+?)\s+[-–—](\S.*)$""")

        private val QUOTED = Regex("""^(.+?),?\s+["“](.+?)["”]""")

        /** `artist: song`; the label holds no bracket, so "Song (from X: Y)" is not split. */
        private val COLON = Regex("""^([^:()\[\]【】]+):\s+(.+)$""")

        /** A lowercase letter followed by a capital: "AmrDiab" splits to "Amr Diab". */
        private val CAMEL_HUMP = Regex("""(\p{Ll})(\p{Lu})""")

        private val SPACES = Regex("""\s+""")

        private val TRAILING_SEPARATORS = charArrayOf(' ', '-', '–', '—', ':', '|', '~', ',')
    }
}
