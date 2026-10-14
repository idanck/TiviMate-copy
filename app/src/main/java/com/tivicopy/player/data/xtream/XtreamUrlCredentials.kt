package com.tivicopy.player.data.xtream

import java.net.URLEncoder

/**
 * Keeps an Xtream account's username and password out of the URLs this app stores (docs/qa,
 * finding 9).
 *
 * The panel puts both into every live stream's path, `/live/<user>/<pass>/<id>.ts`, and into the
 * guide's address, `xmltv.php?username=<user>&password=<pass>`. The `playlists` row keeps the two
 * encrypted ([com.tivicopy.player.data.prefs.SecureStore]), but every channel row and the guide
 * address carried them in the clear right beside it. [com.tivicopy.player.data.db.Dao] now writes
 * those URLs with a placeholder where the account was and puts the account back as it reads them,
 * so everything above the database — player, recorder, catch-up — still gets a playable address.
 *
 * Matching is on the exact text [XtreamClient] builds: the account form-encoded between two path
 * separators, or as the pair of query parameters. Never on the bare username, which can be short
 * enough to turn up inside anything.
 */
object XtreamUrlCredentials {

    const val USER = "{xc-user}"
    const val PASS = "{xc-pass}"

    /** One source's account, in the form it takes inside the URLs built from it. */
    class Account(username: String, password: String) {
        val user: String = encode(username)
        val pass: String = encode(password)

        /** An account missing either half matches nothing rather than every `//` in a URL. */
        val usable: Boolean get() = user.isNotEmpty() && pass.isNotEmpty()
    }

    fun mask(url: String, account: Account?): String {
        if (account == null || !account.usable) return url
        return url
            .replace("/${account.user}/${account.pass}/", "/$USER/$PASS/")
            .replace("username=${account.user}&password=${account.pass}", "username=$USER&password=$PASS")
    }

    fun unmask(url: String, account: Account?): String {
        if (account == null || !account.usable || !url.contains(USER)) return url
        return url
            .replace("/$USER/$PASS/", "/${account.user}/${account.pass}/")
            .replace("username=$USER&password=$PASS", "username=${account.user}&password=${account.pass}")
    }

    /** The encoding [XtreamClient] puts the account in its URLs with. */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
