package tech.capullo.tubecapullo.util

import android.content.Context
import android.content.Intent
import tech.capullo.tubecapullo.data.youtube.YtVideo

object ShareLink {
    fun youtuBeUrl(videoId: String): String = "https://youtu.be/$videoId"

    fun share(context: Context, video: YtVideo) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, youtuBeUrl(video.videoId))
        }
        context.startActivity(Intent.createChooser(send, "Share song"))
    }
}
