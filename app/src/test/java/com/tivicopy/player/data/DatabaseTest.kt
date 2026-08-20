package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import org.junit.After
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

    private val opened = mutableListOf<Database>()

    /** Every helper here opens a real connection, so each one is closed again. */
    private fun database(): Database = Database(context).also(opened::add)

    @After
    fun closeHelpers() {
        opened.forEach(Database::close)
        opened.clear()
    }

    @Test
    fun `a fresh install opens the database and answers the cold-start queries`() {
        val dao = Dao(database())

        // The three reads `App.onCreate` -> `RecordingScheduler.rearmAll` and the first frame
        // make before anything is on screen.
        assertTrue(dao.pendingRecordings(System.currentTimeMillis()).isEmpty())
        assertTrue(dao.activeRecordings().isEmpty())
        assertTrue(dao.playlists().isEmpty())
    }

    @Test
    fun `every schema statement survives being replayed`() {
        // `onCreate` runs once, but `onUpgrade` replays the whole schema over a database that
        // already has the tables, so each statement has to be idempotent. Reopening the file
        // does not reach that path — the version has not moved — so the replay is done here
        // directly, against the same statements and the same populated database.
        val helper = database()
        val db = helper.writableDatabase
        Database.SCHEMA.forEach(db::execSQL)

        val reopened = Dao(database())
        assertTrue(reopened.playlists().isEmpty())
        assertTrue(reopened.recordings().isEmpty())
    }
}
