package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening the database is the first thing `Application.onCreate` does, so anything that throws
 * there kills the process on every launch — which is exactly what a `PRAGMA journal_mode=WAL`
 * issued through `execSQL` used to do: the pragma answers with the mode it chose, and `execSQL`
 * refuses any statement that returns a row.
 *
 * This exercises the cold-start sequence against a fresh, empty database, which is the state a
 * newly installed app is in.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, not this app's: the subject here is the database, and
// `App.onCreate` would drag WorkManager and the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class DatabaseTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install opens the database and answers the cold-start queries`() {
        val dao = Dao(Database(context))

        // The three reads `App.onCreate` -> `RecordingScheduler.rearmAll` and the first frame
        // make before anything is on screen.
        assertTrue(dao.pendingRecordings(System.currentTimeMillis()).isEmpty())
        assertTrue(dao.activeRecordings().isEmpty())
        assertTrue(dao.playlists().isEmpty())
    }

    @Test
    fun `every schema statement survives a second open`() {
        // `onCreate` runs once, but `onUpgrade` replays the whole schema, so each statement has
        // to be idempotent. A second helper over the same file proves the file is reusable.
        Dao(Database(context)).playlists()
        val reopened = Dao(Database(context))

        assertTrue(reopened.playlists().isEmpty())
        assertTrue(reopened.recordings().isEmpty())
    }
}
