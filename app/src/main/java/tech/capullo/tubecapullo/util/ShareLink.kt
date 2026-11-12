package tech.capullo.tubecapullo.util

import android.content.Context
import android.content.Intent
import tech.capullo.tubecapullo.data.youtube.YtVideo

/**
 * Share a track as a bare YouTube link through the system chooser. Link only (owner decision
 * 2026-11-11): messengers build their own preview card from it, and a title line would duplicate
 * that card.
 */
object ShareLink {

    /** YouTube's short link for [videoId]. Every YouTube client and link preview resolves it. */
    fun youtuBeUrl(videoId: String): String = "https://youtu.be/$videoId"

    /** Open the system share chooser for [video]. [context] should be an Activity context; the
     *  chooser then joins the app's task instead of needing FLAG_ACTIVITY_NEW_TASK. */
    fun share(context: Context, video: YtVideo) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, youtuBeUrl(video.videoId))
        }
        context.startActivity(Intent.createChooser(send, "Share song"))
    }
}
