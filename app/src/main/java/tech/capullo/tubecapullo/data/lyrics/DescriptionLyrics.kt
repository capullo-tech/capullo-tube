package tech.capullo.tubecapullo.data.lyrics

import java.util.Locale

/**
 * Lyrics pasted into a video description, found by a strict rule (docs/probes/lyrics-criteria.md
 * section 3, tightened in section 3b): the last source, after LRCLIB, so a wrong card costs
 * more than a missing one.
 *
 * A non-blank line is a lyric line when it is short ([MAX_LINE_CHARS]) and has no link, hashtag,
 * `@` handle or timestamp, no credits label (作詞, 作曲, 編曲, Producer, Mixed...) and no
 * "Label: value" shape. A block is a run of lyric lines; blank lines, lines with no letter
 * ("-----") and section labels (`[Chorus]`, `Chorus:`, "Lyrics") do not break it, any other
 * line does. A block passes with at least [MIN_LINES] lyric lines and a chorus: two lines that
 * follow each other twice, in the same order. The longest passing block is the lyrics, unless
 * another passing block shares no line with it: then the description holds two songs (a
 * medley, or a second song's lyrics) and none is returned, because the one that plays is not
 * known.
 */
object DescriptionLyrics {

    /** Lyric lines a block needs. GUESS (owner decision 2026-11-12), from the first probe's
     *  "12 or more short lines" count: a song has more, a credits list or a short note has
     *  fewer. Lower and a long credits list or tracklist can pass; higher and short songs are
     *  missed. */
    internal const val MIN_LINES = 12

    /** The longest lyric line, characters. GUESS: the first probe's "short line". A sung line is
     *  rarely longer; a sentence of prose often is. Lower drops long rap lines (and breaks their
     *  block); higher lets prose in. */
    internal const val MAX_LINE_CHARS = 60

    /** The plain lyrics in [description], or null when no block passes. */
    fun extract(description: String): String? {
        val blocks = mutableListOf<List<String>>()
        var block = mutableListOf<String>()
        for (raw in description.lineSequence()) {
            val line = raw.trim()
            when {
                key(line).isEmpty() -> if (block.lastOrNull()?.isNotEmpty() == true) block += ""
                isSectionLabel(line) -> Unit
                isLyricLine(line) -> block += line
                else -> {
                    blocks += block
                    block = mutableListOf()
                }
            }
        }
        blocks += block
        val passing = blocks.filter { lines -> lineCount(lines) >= MIN_LINES && hasChorus(lines) }
        val lyrics = passing.maxByOrNull(::lineCount) ?: return null
        val lyricKeys = lyrics.mapNotNullTo(HashSet()) { line -> key(line).ifEmpty { null } }
        val twoSongs = passing.any { other ->
            other !== lyrics && other.none { key(it) in lyricKeys }
        }
        return if (twoSongs) null else lyrics.joinToString("\n").trim()
    }

    private fun isLyricLine(line: String): Boolean = line.length <= MAX_LINE_CHARS &&
        !REJECT.containsMatchIn(line) &&
        !CREDIT_WORD.containsMatchIn(line) &&
        !CJK_CREDIT_LABEL.containsMatchIn(line) &&
        CJK_CREDITS.none { it in line } &&
        !LABEL.containsMatchIn(line)

    private fun lineCount(lines: List<String>): Int = lines.count { it.isNotEmpty() }

    private fun isSectionLabel(line: String): Boolean = BRACKETED.matches(line) ||
        EMPTY_LABEL.matches(line) ||
        LYRICS_HEADING.containsMatchIn(line)

    /**
     * A chorus: two lines that follow each other twice, in that order (blank lines between
     * stanzas skipped). One repeated line is not enough: the first probe run (2026-11-12,
     * docs/probes/lyrics-results.md) accepted a credits list of names, where one name ("Ryan
     * Lewis") came back under a second role. A chorus repeats lines together.
     */
    private fun hasChorus(lines: List<String>): Boolean {
        val keys = lines.filter { it.isNotEmpty() }.map(::key)
        val seen = HashSet<Pair<String, String>>()
        return keys.zipWithNext().any { pair -> !seen.add(pair) }
    }

    /** A line compared without case, spaces or punctuation. */
    private fun key(line: String): String = line.lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    /** A link, a hashtag, an `@` handle, or a timestamp (1:23, 01:23:45). */
    private val REJECT = Regex(
        """https?://|www\.|#\S|@\w|\b\d{1,2}:\d{2}\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The credits roles in Latin script, as whole words, anywhere in the line. */
    private val CREDIT_WORD = Regex(
        """\b(producer|produced|composer|lyricist|director|directed|mixed|mastered)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Credits roles in CJK script that a sung line almost never holds: 作詞 (lyrics by),
     *  作曲 (music by), 編曲 (arranger), 製作 (producer), traditional and simplified. */
    private val CJK_CREDITS = listOf("作詞", "作词", "作曲", "編曲", "编曲", "製作", "制作")

    /** 詞 and 曲 alone are common in sung lines too, so only as a label at the line's start:
     *  "詞：", "曲 :", "詞／曲：", "詞曲：", "曲 陳小霞". */
    private val CJK_CREDIT_LABEL = Regex("""^[詞词曲]{1,2}\s*([:：/／]|\s)""")

    /** "Label: value": a short label, a colon, then text. */
    private val LABEL = Regex("""^[^:：]{1,24}[:：]\s*\S""")

    /** "Chorus:", "歌詞：": a label with nothing after it. */
    private val EMPTY_LABEL = Regex("""^[^:：]{1,24}[:：]$""")

    /** "[Chorus]", "(Verse 2)", "【副歌】". */
    private val BRACKETED = Regex("""^[\[(（【].*[\])）】]$""")

    /** A heading over the lyrics: "Lyrics", "LYRICS:", "Lyrics for "Song"", "歌詞". */
    private val LYRICS_HEADING = Regex(
        """^(lyrics?|歌詞|歌词)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
}
