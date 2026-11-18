package tech.capullo.tubecapullo.data.youtube

import okhttp3.OkHttpClient

internal fun probeClient(): YouTubeExtractorClient = YouTubeExtractorClient(TubeCapulloDownloader(OkHttpClient()))
