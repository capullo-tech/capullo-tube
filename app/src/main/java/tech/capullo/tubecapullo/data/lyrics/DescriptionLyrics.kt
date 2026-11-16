package tech.capullo.tubecapullo.data.lyrics

import java.util.Locale

object DescriptionLyrics {

    internal const val MIN_LINES = 12

    internal const val MAX_LINE_CHARS = 60

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

    private fun hasChorus(lines: List<String>): Boolean {
        val keys = lines.filter { it.isNotEmpty() }.map(::key)
        val seen = HashSet<Pair<String, String>>()
        return keys.zipWithNext().any { pair -> !seen.add(pair) }
    }

    private fun key(line: String): String = line.lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private val REJECT = Regex(
        """https?://|www\.|#\S|@\w|\b\d{1,2}:\d{2}\b""",
        RegexOption.IGNORE_CASE,
    )

    private val CREDIT_WORD = Regex(
        """\b(producer|produced|composer|lyricist|director|directed|mixed|mastered)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val CJK_CREDITS = listOf("作詞", "作词", "作曲", "編曲", "编曲", "製作", "制作")

    private val CJK_CREDIT_LABEL = Regex("""^[詞词曲]{1,2}\s*([:：/／]|\s)""")

    private val LABEL = Regex("""^[^:：]{1,24}[:：]\s*\S""")

    private val EMPTY_LABEL = Regex("""^[^:：]{1,24}[:：]$""")

    private val BRACKETED = Regex("""^[\[(（【].*[\])）】]$""")

    private val LYRICS_HEADING = Regex(
        """^(lyrics?|歌詞|歌词)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
}
