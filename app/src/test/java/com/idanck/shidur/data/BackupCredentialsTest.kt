package com.idanck.shidur.data

import com.idanck.shidur.data.backup.BackupCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NFR-SEC-07: a backup written without credentials carries none, including inside addresses.
 * Until 2026-10-13 only the username and password columns were left out, while the same values
 * went out in an Xtream playlist's xmltv.php guide address and in M3U get.php addresses.
 */
class BackupCredentialsTest {

    @Test
    fun `strips an Xtream guide address`() {
        assertEquals(
            "http://panel.example:8080/xmltv.php",
            BackupCredentials.strip("http://panel.example:8080/xmltv.php?username=alice&password=s3cret"),
        )
    }

    @Test
    fun `strips an M3U panel address and keeps the other parameters in order`() {
        assertEquals(
            "http://panel.example/get.php?type=m3u_plus&output=ts",
            BackupCredentials.strip(
                "http://panel.example/get.php?username=alice&type=m3u_plus&Password=s3cret&output=ts",
            ),
        )
    }

    @Test
    fun `strips credentials in front of the host`() {
        assertEquals(
            "https://host.example/list.m3u",
            BackupCredentials.strip("https://alice:s3cret@host.example/list.m3u"),
        )
    }

    @Test
    fun `leaves an address without credentials alone`() {
        listOf(
            "https://il-epg.idanck.com/channels.m3u",
            "http://host.example/list.m3u?region=il#top",
            "http://host.example/a@b/list.m3u",
            "content://com.android.providers/document/42",
        ).forEach { assertEquals(it, BackupCredentials.strip(it)) }
    }

    @Test
    fun `keeps a fragment after the query`() {
        assertEquals(
            "http://host.example/get.php?type=m3u#x",
            BackupCredentials.strip("http://host.example/get.php?username=a&type=m3u&password=b#x"),
        )
    }
}
