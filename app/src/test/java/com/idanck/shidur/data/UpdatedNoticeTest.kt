package com.idanck.shidur.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.data.update.UpdatedNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only the update this app asked for announces itself (FR-UPD-09): a sideload replaces the
 * package as well, and a mark left by an install that never happened must not fire later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdatedNoticeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("update_notice", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a replacement with no mark is a sideload and stays quiet`() {
        assertFalse(UpdatedNotice.consume(context, installedVersionCode = 200, nowUtc = 1_000))
    }

    @Test
    fun `the expected update announces itself once`() {
        UpdatedNotice.expect(context, versionCode = 145, nowUtc = 1_000)
        assertTrue(UpdatedNotice.consume(context, installedVersionCode = 145, nowUtc = 61_000))
        assertFalse(UpdatedNotice.consume(context, installedVersionCode = 145, nowUtc = 62_000))
    }

    @Test
    fun `an older build than the one expected is not the update`() {
        UpdatedNotice.expect(context, versionCode = 145, nowUtc = 1_000)
        assertFalse(UpdatedNotice.consume(context, installedVersionCode = 144, nowUtc = 2_000))
    }

    @Test
    fun `a mark older than the hand-off window has lapsed`() {
        UpdatedNotice.expect(context, versionCode = 145, nowUtc = 1_000)
        val late = 1_000 + UpdatedNotice.WINDOW_MS + 1
        assertFalse(UpdatedNotice.consume(context, installedVersionCode = 145, nowUtc = late))
    }

    @Test
    fun `a failed install forgets the mark`() {
        UpdatedNotice.expect(context, versionCode = 145, nowUtc = 1_000)
        UpdatedNotice.forget(context)
        assertFalse(UpdatedNotice.consume(context, installedVersionCode = 145, nowUtc = 2_000))
    }
}
