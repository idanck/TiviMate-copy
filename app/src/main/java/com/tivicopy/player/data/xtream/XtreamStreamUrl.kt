package com.tivicopy.player.data.xtream

import java.net.URLDecoder

/**
 * The Xtream account inside a live stream's address, `http://host:port[/live]/<user>/<pass>/<id>[.ts]`.
 *
 * An M3U playlist exported from an Xtream panel — the owner's daily playlist is one — is not an
 * Xtream source to this app, so it has no account of its own; but every stream in it still names
 * the account, and the panel's `player_api.php` answers for it. That is how a panel the provider
 * closed can say how many connections the account allows (ADR-041) whichever way it was added.
 */
object XtreamStreamUrl {

    class Parts(val server: String, val username: String, val password: String)

    private val PATTERN = Regex("^(https?://[^/?#]+)/(?:live/)?([^/?#]+)/([^/?#]+)/\\d+(?:\\.[A-Za-z0-9]+)?$")

    /** Null for any address that does not have the panel's shape. */
    fun parse(url: String): Parts? {
        val match = PATTERN.find(url.trim()) ?: return null
        val (server, user, pass) = match.destructured
        // The panel puts them in form-encoded ([XtreamUrlCredentials.encode]); the client encodes
        // them again.
        return runCatching {
            Parts(server, URLDecoder.decode(user, "UTF-8"), URLDecoder.decode(pass, "UTF-8"))
        }.getOrNull()
    }
}
