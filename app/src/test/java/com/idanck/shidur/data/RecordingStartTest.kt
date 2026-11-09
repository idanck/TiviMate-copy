package com.idanck.shidur.data

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.model.Recording
import com.idanck.shidur.data.model.RecordingState
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.record.RecordingScheduler
import com.idanck.shidur.record.RecordingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-REC-14/15 on Android 12+. A recording's foreground service is refused when it is started
 * from the background without an exemption (an inexact alarm, or a boot broadcast on Android 15),
 * and until 2026-10-13 that refusal was thrown straight out of the alarm receiver and out of
 * `Application.onCreate`, taking the app down. The QA report (docs/qa) found it in the code.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and
// the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class RecordingStartTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: Database
    private lateinit var dao: Dao

    @Before
    fun open() {
        app.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        database = Database(app)
        dao = Dao(database)
    }

    @After
    fun close() {
        database.close()
    }

    /** What Android 12+ does to a foreground-service start from the background with no exemption. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? =
            throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
    }

    private class Capturing(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startForegroundService(service: Intent): ComponentName? {
            started += service
            return service.component
        }
    }

    private fun scheduler(context: Context) = RecordingScheduler(context, dao, Settings(app))

    /** A recording that began a minute ago and runs for the next hour. */
    private fun recording(state: RecordingState = RecordingState.SCHEDULED): Long {
        val now = System.currentTimeMillis()
        return dao.upsertRecording(
            Recording(
                channelId = 1,
                channelName = "QA News 01",
                title = "Show",
                startUtc = now - 60_000,
                endUtc = now + 3_600_000,
                state = state,
            ),
        )
    }

    @Test
    fun `a start Android refuses marks the recording failed instead of crashing`() {
        val id = recording()

        scheduler(Refusing(app)).startNow(id)

        val after = dao.recording(id)!!
        assertEquals(RecordingState.FAILED, after.state)
        assertFalse(after.message.isNullOrBlank())
    }

    @Test
    fun `a scheduled recording starts the recorder`() {
        val id = recording()
        val context = Capturing(app)

        scheduler(context).startNow(id)

        assertEquals(id, context.started.single().getLongExtra(RecordingService.EXTRA_RECORDING_ID, 0))
        assertEquals(RecordingState.SCHEDULED, dao.recording(id)!!.state)
    }

    @Test
    fun `a recording cancelled before its alarm starts nothing`() {
        val id = recording(RecordingState.CANCELLED)
        val context = Capturing(app)

        scheduler(context).startNow(id)

        assertTrue(context.started.isEmpty())
        assertEquals(RecordingState.CANCELLED, dao.recording(id)!!.state)
    }

    @Test
    fun `re-arming at start-up survives a refusal`() {
        // The device was off through the start, so re-arming begins what is left of it — from
        // Application.onCreate, where an uncaught refusal crashed every launch until it ended.
        val id = recording()

        scheduler(Refusing(app)).rearmAll()

        assertEquals(RecordingState.FAILED, dao.recording(id)!!.state)
    }
}
