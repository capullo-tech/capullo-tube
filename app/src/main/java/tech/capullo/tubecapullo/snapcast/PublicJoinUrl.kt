package tech.capullo.tubecapullo.snapcast

import tech.capullo.audio.snapcast.JoinUrl
import java.net.URI
import java.net.URISyntaxException

private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80

/**
 * The public link's join URL, as the multiroom QR's Cloudflare chip encodes it: the tunnel's
 * base URL (`https://<host>`) through [JoinUrl.build]; an unparsable one comes back as-is. Mirrors
 * capullo-audio-ui `ListenQrDialog`'s private `publicJoinUrl`, which the library does not export.
 */
fun publicJoinUrl(tunnelUrl: String): String {
    val u = try {
        URI(tunnelUrl)
    } catch (e: URISyntaxException) {
        return tunnelUrl
    }
    val host = u.host ?: return tunnelUrl
    val https = (u.scheme ?: return tunnelUrl).equals("https", ignoreCase = true)
    val port = when {
        u.port != -1 -> u.port
        https -> HTTPS_PORT
        else -> HTTP_PORT
    }
    return try {
        JoinUrl.build(host, port, https)
    } catch (e: IllegalArgumentException) {
        tunnelUrl
    }
}
