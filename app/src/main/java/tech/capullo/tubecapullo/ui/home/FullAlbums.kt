package tech.capullo.tubecapullo.ui.home

import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.passesRelatedGate
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import tech.capullo.tubecapullo.util.TitleCleaner

// Home's "Full albums" row (PLAN "Home v2 and albums", owner decision 2026-11-12): discovery only.
// Long videos in the related lists of the newest liked songs (the compilations the radio's
// length window drops), kept when they look like an album and pass the related gate; liked
// albums and liked songs left out. When that finds too few, a search for "<artist> full album"
// of the most liked artists fills the row. One card per album, whichever list its uploads came
// from (owner report 2026-11-12). The criteria and the probe run that set the constants:
// docs/probes/home-v2-criteria.md section 3 and docs/probes/home-v2-results.md (2026-11-12).

/**
 * Full albums window, seconds: 25 to 120 minutes. The PLAN's GUESS, confirmed by the probe of
 * 2026-11-12 (Q3): 104 of 108 album-titled items from 15 to 150 minutes (0.96) lay inside it,
 * over the 0.80 its rule asked; p10 37.9 min, p90 88.5 min. Lower minimum: EPs and long mixes
 * (the 3 items at 22 to 24 min) come in. Higher maximum: multi-hour compilations come in.
 */
internal const val ALBUM_MIN_SECONDS = 25 * 60L
internal const val ALBUM_MAX_SECONDS = 120 * 60L

/**
 * Cards in the Full albums row: 12. GUESS (owner report 2026-11-12: the row showed 6, too few to
 * browse), not measured; its own value, not [CARDS_PER_ROW], because an album card is a whole
 * listening session and the row is discovery, so more choice per load is the point. Higher: more
 * fallback searches to fill it and cards nobody scrolls to; lower: the short row the owner
 * reported.
 */
internal const val FULL_ALBUM_CARDS = 12

/**
 * Newest liked songs whose related lists the row scans per load: 6. GUESS: the probe's Q5
 * (docs/probes/home-v2-results.md, 2026-11-12) found a median of 1 album candidate per seed, so
 * 6 seeds expect about half of [FULL_ALBUM_CARDS] from the related lists and leave the rest to
 * the fallback; the probe's own rule (reach 4 cards) gave 4 before the cap rose to 12. Each seed
 * costs one StreamInfo fetch (the newest are shared with the "More like" rows). Higher: more
 * fetches per Home load; lower: the fallback search carries more of the row.
 */
internal const val FULL_ALBUM_SEEDS = 6

/**
 * Long, gated items without an album title whose chapters the row checks per load (one
 * StreamInfo fetch each, in parallel): 8, the cap of the probe's Q4b rule (median 3 such items
 * per seed times [FULL_ALBUM_SEEDS] = 18, capped at 8 by cost). 29 of 104 candidates (0.28)
 * qualified by chapters alone, so the checks are kept. Higher: more fetches; lower: fewer
 * chaptered albums found.
 */
internal const val FULL_ALBUM_CHAPTER_CHECKS = 8

/**
 * Cards below which the row searches "<artist> full album": [FULL_ALBUM_CARDS], so the search
 * tops every short row up to the cap. GUESS (owner report 2026-11-12). With the probe's median
 * of 1 related candidate per seed the search runs on most loads. Lower: fewer search requests
 * and shorter rows.
 */
internal const val FULL_ALBUMS_MIN_CARDS = FULL_ALBUM_CARDS

/**
 * Artists the fallback searches, the most liked first. GUESS: 3. The probe (Q5) found a median
 * of 7 album candidates per "<artist> full album" query (min 2, docs/probes/home-v2-results.md);
 * uploads of one album collapse into one card, so 3 queries (median 21 candidates) are what is
 * likely to fill [FULL_ALBUM_CARDS] after the related cards. Higher: one more search request
 * each; lower: a short row when the related lists find few albums.
 */
internal const val FULL_ALBUM_ARTISTS = 3

internal fun YtVideo.inAlbumWindow(): Boolean = durationSeconds in ALBUM_MIN_SECONDS..ALBUM_MAX_SECONDS

/** The word "album" in a title, in the scripts that write it as a word (Latin, Cyrillic) or as
 *  a run of characters (Japanese, Korean, Chinese, Arabic). */
internal fun hasAlbumTitle(title: String): Boolean = ALBUM_WORD.containsMatchIn(title) || ALBUM_SCRIPT_WORDS.any { it in title }

/** The album test (probe Q4): an album title, or YouTube chapters ([chaptered] ids). */
internal fun looksLikeAlbum(video: YtVideo, chaptered: Set<String>): Boolean = hasAlbumTitle(video.title) || video.videoId in chaptered

/** The uploader as an artist name for the fallback search: "Daft Punk - Topic" and
 *  "DaftPunkVEVO" give "Daft Punk" and "DaftPunk". */
internal fun artistName(uploader: String): String = UpNextText.channelName(uploader).removeSuffix("VEVO").trim().ifEmpty { uploader }

/**
 * The long items of the [fetches] (one related list per seed, or a search as a list with unknown
 * seed views) that can be albums: in the album window, through the related gate, not in
 * [excludedIds] (liked songs and liked albums), each once. The lists interleave by rank (the
 * first of each, then the second of each), so every seed reaches the row's first cards.
 */
internal fun fullAlbumCandidates(fetches: List<YtRelated>, excludedIds: Set<String>): List<YtVideo> {
    val lists = fetches.map { fetch ->
        fetch.videos.filter {
            it.inAlbumWindow() && passesRelatedGate(it, fetch.seedViews) && it.videoId !in excludedIds
        }
    }
    val seen = HashSet<String>()
    return buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            lists.forEach { list -> list.getOrNull(rank)?.takeIf { seen.add(it.videoId) }?.let(::add) }
        }
    }
}

/** The candidates whose chapters decide them (no album title), at most [limit], in order. */
internal fun chapterChecks(candidates: List<YtVideo>, limit: Int): List<YtVideo> = candidates.filterNot { hasAlbumTitle(it.title) }.take(limit)

/** The [candidates] that look like albums ([looksLikeAlbum]), in order. */
internal fun albumsOf(candidates: List<YtVideo>, chaptered: Set<String>): List<YtVideo> = candidates.filter { looksLikeAlbum(it, chaptered) }

/** The row's [cards], and the [dupes]: uploads dropped because another upload of the same album
 *  got the card. */
internal data class FullAlbumCards(val cards: List<YtVideo>, val dupes: Int)

/**
 * The row's cards from [albums] (the related lists' albums first, then the fallback's): one per
 * album ([TitleCleaner.albumKey]; [artists] are the names whose "Artist - " prefix goes besides
 * the uploader), each album in the place where it first appears, at most [FULL_ALBUM_CARDS]. Of
 * one album's uploads the card is the verified one, else the most viewed (a tie: the first). An
 * upload without a key is an album of its own.
 */
internal fun fullAlbumCards(albums: List<YtVideo>, artists: Collection<String>): FullAlbumCards {
    val byAlbum = LinkedHashMap<String, YtVideo>()
    albums.forEach { video ->
        // "id:" cannot clash with an album key, which holds only letters and digits.
        val key = TitleCleaner.albumKey(video.title, video.uploader, artists) ?: "id:${video.videoId}"
        val kept = byAlbum[key]
        if (kept == null || PREFERRED_UPLOAD.compare(video, kept) > 0) byAlbum[key] = video
    }
    return FullAlbumCards(byAlbum.values.take(FULL_ALBUM_CARDS), dupes = albums.size - byAlbum.size)
}

/** Of two uploads of one album: the verified one, else the most viewed. */
private val PREFERRED_UPLOAD = compareBy<YtVideo>({ it.uploaderVerified }, { it.viewCount })

/** The fallback's artists: the uploaders of [likes] (liked songs) as [artistName]s, the most
 *  liked first (a tie: the newest like first), at most [FULL_ALBUM_ARTISTS]. */
internal fun fullAlbumArtists(likes: List<YtVideo>): List<String> {
    val counts = LinkedHashMap<String, Int>()
    likes.forEach { like -> like.uploader?.let { artistName(it) }?.let { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts.entries.sortedByDescending { it.value }.take(FULL_ALBUM_ARTISTS).map { it.key }
}

private val ALBUM_WORD = Regex(
    """(?<![\p{L}\p{N}])(albums?|álbum(es)?|albüm|альбом\p{L}*)(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)

private val ALBUM_SCRIPT_WORDS = listOf("アルバム", "앨범", "专辑", "專輯", "ألبوم", "البوم")
