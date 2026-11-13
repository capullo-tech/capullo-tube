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

internal const val CARDS_PER_ROW = 10

internal const val LISTEN_AGAIN_CARDS = 10

internal const val LISTEN_AGAIN_AGE_DAYS = 30

internal const val LISTEN_AGAIN_MIN_PLAYS = 3

private const val DAY_MS = 24 * 60 * 60 * 1000L

sealed interface HomeSection {
    val key: String

    data class Continue(val stations: List<ContinueCard>) : HomeSection {
        override val key: String get() = "continue"
    }

    data class ListenAgain(val cards: List<YtVideo>, val albumIds: Set<String>) : HomeSection {
        override val key: String get() = "listen-again"
    }

    data class BecauseLiked(val seed: YtVideo, val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "liked-${seed.videoId}"
    }

    data class FullAlbums(val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "full-albums"
    }

    data class Trending(val region: String, val cards: List<YtVideo>) : HomeSection {
        override val key: String get() = "trending-$region"
    }
}

data class ContinueCard(
    val stationId: Long,
    val source: PlaySource,
    val video: YtVideo,
    val hint: String,
)

data class TrendingFetch(val region: String, val cards: List<YtVideo>, val error: String? = null)

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

internal fun trendingError(trending: List<TrendingFetch>): String? = trending.takeIf { fetches -> fetches.isNotEmpty() && fetches.all { it.error != null } }
    ?.first()?.error

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

internal fun continueCards(stations: List<StationSnapshot>, playingStationId: Long, max: Int): List<ContinueCard> = stations.asSequence()
    .filter { it.id != playingStationId }
    .take(max)
    .map { ContinueCard(it.id, it.source, it.leftVideo, continueHint(it)) }
    .toList()

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

internal fun continueIds(cards: List<ContinueCard>): Set<String> = cards.flatMapTo(HashSet()) { card ->
    listOfNotNull(card.video.videoId, (card.source as? PlaySource.Album)?.video?.videoId)
}
