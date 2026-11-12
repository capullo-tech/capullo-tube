package tech.capullo.tubecapullo.data.youtube

/** A real extractor client for the network-gated probes. */
internal fun probeClient(): YouTubeExtractorClient = YouTubeExtractorClient(TubeCapulloDownloader())
