package tech.capullo.tubecapullo.ui.home

import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.history.PlayStat
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationSnapshot
import tech.capullo.tubecapullo.station.chapterAt
import tech.capullo.tubecapullo.station.inTrackWindow
import tech.capullo.tubecapullo.station.pairUp
import tech.capullo.tubecapullo.station.songLists
import tech.capullo.tubecapullo.util.formatDuration
import kotlin.random.Random

/**
 * Cards in one "More like" row ([FULL_ALBUM_CARDS] caps the Full albums row). GUESS, unmeasured:
 * 10 fills about four screen widths of 160dp cards, which is as far as a listener scrolls a row
 * before going back to the list. A fetch gives ~50 Mix tracks and about 20 related ones, so
 * after liked tracks and the length window 10 is nearly always reachable. Higher only adds cards
 * nobody scrolls to; lower makes a row run dry after one swipe.
 */
internal const val CARDS_PER_ROW = 10

/**
 * Cards in the Listen again row (owner decision 2026-11-11). GUESS, unmeasured: the same four
 * screen widths as [CARDS_PER_ROW]. Higher shows more of the pool at once, so a load's random
 * pick changes less between loads; lower hides most of it.
 */
internal const val LISTEN_AGAIN_CARDS = 10

/**
 * Listen again's age, days: a liked song or album, or a song played [LISTEN_AGAIN_MIN_PLAYS]
 * times, shows once it has not played (nor, for a like, been liked) for this long. GUESS (PLAN
 * "Home v2 and albums"), unmeasured. Lower: the row repeats what the listener heard last week.
 * Higher: the row stays empty for months on a new install.
 */
internal const val LISTEN_AGAIN_AGE_DAYS = 30

/** Plays that make an unliked song a Listen again pick. GUESS (PLAN), unmeasured: 3 plays are a
 *  song the listener came back to, not one a radio passed once. Lower: radio filler shows;
 *  higher: few songs ever qualify. */
internal const val LISTEN_AGAIN_MIN_PLAYS = 3

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** One Home section: a header over a horizontal card row. [key] is its stable LazyColumn key. */
sealed interface HomeSection {
    val key: String

    /** Stations the listener left unfinished, newest first: tap resumes one where it was. */
    data class Continue(val stations: List<ContinueCard>) : HomeSection {
        override val key: String get() = "continue"
    }

    /** Older songs and albums ([listenAgainCards]); [albumIds] marks the albums, which play as
     *  albums. */
    data class ListenAgain(val cards: List<YtVideo>, val albumIds: Set<String>) : HomeSection {
        override val key: String get() = "listen-again"
    }

    /** "More like <seed>": songs like [seed], one of the newest likes (its Mix and related
     *  videos in pairs). */
    data class BecauseLiked(val seed: YtVideo, val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "liked-${seed.videoId}"
    }

    /** Long videos that look like albums, found near the listener's likes (FullAlbums.kt). */
    data class FullAlbums(val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "full-albums"
    }

    /** The trending-music chart of [region] (ISO 3166-1 alpha-2; the device default is resolved
     *  to its country before the fetch): the cold-start source. */
    data class Trending(val region: String, val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "trending-$region"
    }
}

/** A Continue card: the stored station [stationId], what it plays ([source]), the song it
 *  stopped at ([video]) and how far it got ([hint]). */
data class ContinueCard(
    val stationId: Long,
    val source: PlaySource,
    val video: YtVideo,
    val hint: String,
)

/** One Trending chart's fetch: its [cards], or the listener-readable [error] when it failed. */
data class TrendingFetch(val region: String, val cards: List<YtVideo>, val error: String? = null)

/**
 * The Home sections in display order (PLAN "Home v2 and albums"): Continue, Listen again, the
 * "More like" rows (newest like first), Full albums, then one Trending row per chart in the
 * chosen order. An empty section is left out, and so is a failed chart, so with no stations,
 * no likes and no history the Trending rows are the only sections. [trendingError] decides
 * whether the failures need a banner.
 */
internal fun homeSections(
    continueCards: List<ContinueCard>,
    listenAgain: HomeSection.ListenAgain?,
    becauseLiked: List<HomeSection.BecauseLiked>,
    fullAlbums: List<YtVideo>,
    trending: List<TrendingFetch>,
): List<HomeSection> = buildList {
    if (continueCards.isNotEmpty()) add(HomeSection.Continue(continueCards))
    listenAgain?.takeIf { it.cards.isNotEmpty() }?.let(::add)
    addAll(becauseLiked.filter { it.cards.isNotEmpty() })
    if (fullAlbums.isNotEmpty()) add(HomeSection.FullAlbums(fullAlbums))
    trending.filter { it.error == null && it.cards.isNotEmpty() }
        .forEach { add(HomeSection.Trending(it.region, it.cards)) }
}

/**
 * The error banner's text: the first chart's error, but only when every chart failed. One
 * country the chart does not serve, or one flaky fetch, hides its own row and nothing more; when
 * all fail, the cause is usually shared (no network, the bot check) and the listener needs the
 * retry.
 */
internal fun trendingError(trending: List<TrendingFetch>): String? = trending.takeIf { fetches -> fetches.isNotEmpty() && fetches.all { it.error != null } }
    ?.first()?.error

/**
 * The cards for one "More like" row from the liked [seed]'s fetch: the station's shared rules
 * ([songLists]: the related gate, the seed's own song, the length window, one copy per title),
 * with every liked track left out (the row is for discovery, and the seed itself is liked); then
 * the pair merge ([pairUp]: one Mix and one related card per pair, which first drawn from
 * [random]), at most [CARDS_PER_ROW].
 */
internal fun becauseLikedCards(
    seed: YtVideo,
    mix: List<YtVideo>,
    related: YtRelated,
    likedIds: Set<String>,
    random: Random,
): List<YtVideo> {
    val lists = songLists(seed, mix, related, excludedIds = likedIds)
    return pairUp(lists.mix, lists.related, random).take(CARDS_PER_ROW)
}

/**
 * The Continue cards from the stored [stations] (newest first): every station but the one
 * playing ([playingStationId]), at most [max]. A finished list is not here: it rolled into a
 * radio as its last song started, and its row now holds that radio.
 */
internal fun continueCards(stations: List<StationSnapshot>, playingStationId: Long, max: Int): List<ContinueCard> = stations.asSequence()
    .filter { it.id != playingStationId }
    .take(max)
    .map { ContinueCard(it.id, it.source, it.leftVideo, continueHint(it)) }
    .toList()

/**
 * How far a stored station got: "Chapter 4 of 12" for an album with chapters, "Song 3 of 12"
 * for a list, else the playhead in its song ("1:23 of 3:45", or "1:23 in" when the length is
 * unknown).
 */
internal fun continueHint(station: StationSnapshot): String {
    val source = station.source
    if (source is PlaySource.Album && source.chapters.isNotEmpty()) {
        val at = chapterAt(source.chapters, station.positionMs)
        return "Chapter ${at + 1} of ${source.chapters.size}"
    }
    if (source.isCollection && station.roundPos >= 0 && station.round.isNotEmpty()) {
        return "Song ${station.roundPos + 1} of ${station.round.size}"
    }
    val at = formatDuration(station.positionMs / 1000) ?: "0:00"
    return formatDuration(station.leftVideo.durationSeconds)?.let { "$at of $it" } ?: "$at in"
}

/**
 * The Listen again cards (PLAN "Home v2 and albums"): older items only.
 *  - A liked song or album ([likedSongs], [likedAlbums]) liked [LISTEN_AGAIN_AGE_DAYS] or more
 *    days before [nowEpochMs] and not played since that age either ([plays]; never played
 *    counts as not played).
 *  - A song played [LISTEN_AGAIN_MIN_PLAYS] or more times and not in the last
 *    [LISTEN_AGAIN_AGE_DAYS] days, in the radio's length window (a long video played as a song
 *    is not a song pick).
 * Never [playingId], and never a song or album of a Continue card ([continueIds]). The order is
 * random per load: [seed] (drawn once per load) ranks each id, so the row stays put while plays
 * and likes change under it, and the next load picks again. At most [LISTEN_AGAIN_CARDS].
 */
internal fun listenAgainCards(
    likedSongs: List<LikedEntry>,
    likedAlbums: List<LikedEntry>,
    plays: List<PlayStat>,
    nowEpochMs: Long,
    playingId: String?,
    continueIds: Set<String>,
    seed: Int,
): HomeSection.ListenAgain {
    val cutoff = nowEpochMs - LISTEN_AGAIN_AGE_DAYS * DAY_MS
    val lastPlayed = plays.associate { it.video.videoId to it.lastPlayedAtEpochMs }
    fun old(id: String) = (lastPlayed[id] ?: Long.MIN_VALUE) <= cutoff
    val likedIds = (likedSongs + likedAlbums).mapTo(HashSet()) { it.video.videoId }
    val picks = LinkedHashMap<String, YtVideo>()
    (likedSongs + likedAlbums)
        .filter { it.likedAtEpochMs <= cutoff && old(it.video.videoId) }
        .forEach { picks.putIfAbsent(it.video.videoId, it.video) }
    plays
        .filter {
            it.video.videoId !in likedIds && it.playCount >= LISTEN_AGAIN_MIN_PLAYS &&
                it.lastPlayedAtEpochMs <= cutoff && it.video.inTrackWindow()
        }
        .forEach { picks.putIfAbsent(it.video.videoId, it.video) }
    val cards = picks.values
        .filter { it.videoId != playingId && it.videoId !in continueIds }
        .sortedBy { Random(seed xor it.videoId.hashCode()).nextInt() }
        .take(LISTEN_AGAIN_CARDS)
    val albumIds = likedAlbums.mapTo(HashSet()) { it.video.videoId }
    return HomeSection.ListenAgain(cards, cards.mapNotNullTo(HashSet()) { it.videoId.takeIf { id -> id in albumIds } })
}

/** The ids a Continue card stands for, which Listen again leaves out: each card's song and,
 *  for an album, the album. */
internal fun continueIds(cards: List<ContinueCard>): Set<String> = cards.flatMapTo(HashSet()) { card ->
    listOfNotNull(card.video.videoId, (card.source as? PlaySource.Album)?.video?.videoId)
}
