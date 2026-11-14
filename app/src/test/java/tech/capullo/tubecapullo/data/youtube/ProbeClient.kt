package tech.capullo.tubecapullo.data.youtube

internal fun probeClient(): YouTubeExtractorClient = YouTubeExtractorClient(TubeCapulloDownloader())
