package com.tivicopy.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The remote's half of a settings slider: where one press of left or right takes the value.
 *
 * Before this, the row on a television handed the keys to Material's slider, which with no steps
 * moves by 1% of the range. Every setting stored as a whole number then truncated that straight
 * back: ten minutes became 10.59 and was saved as 10 again, so the screensaver's delay, for one,
 * could never be raised from the remote at all. The composable cannot be rendered here (no
 * instrumentation tier, docs/10); the arithmetic can.
 */
class SettingsSliderStepTest {

    private val minutes = 1f..60f

    @Test
    fun `a whole-number setting moves one whole step each way`() {
        assertEquals(11f, steppedValue(10f, towardsEnd = true, step = 1f, range = minutes), 0f)
        assertEquals(9f, steppedValue(10f, towardsEnd = false, step = 1f, range = minutes), 0f)
    }

    @Test
    fun `every press raises a whole-number setting by one once it is stored as an int`() {
        for (v in 1 until 60) {
            val next = steppedValue(v.toFloat(), towardsEnd = true, step = 1f, range = minutes)
            assertEquals("from $v", v + 1, next.toInt())
        }
        for (v in 2..60) {
            val next = steppedValue(v.toFloat(), towardsEnd = false, step = 1f, range = minutes)
            assertEquals("from $v", v - 1, next.toInt())
        }
    }

    @Test
    fun `it stops at either end`() {
        assertEquals(60f, steppedValue(60f, towardsEnd = true, step = 1f, range = minutes), 0f)
        assertEquals(1f, steppedValue(1f, towardsEnd = false, step = 1f, range = minutes), 0f)
        assertEquals(60f, steppedValue(58f, towardsEnd = true, step = 1f, range = minutes, times = 5), 0f)
    }

    @Test
    fun `a value between grid points goes to the next one that way`() {
        val scale = 0.5f..2.5f
        assertEquals(1.4f, steppedValue(1.37f, towardsEnd = true, step = 0.1f, range = scale), 1e-4f)
        assertEquals(1.3f, steppedValue(1.37f, towardsEnd = false, step = 0.1f, range = scale), 1e-4f)
    }

    @Test
    fun `fractional steps walk the whole range without drifting off it`() {
        val opacity = 0.3f..1f
        var v = 0.3f
        repeat(14) { press ->
            val next = steppedValue(v, towardsEnd = true, step = 0.05f, range = opacity)
            assertEquals("press ${press + 1}", 0.3f + 0.05f * (press + 1), next, 1e-4f)
            v = next
        }
        assertEquals(1f, steppedValue(v, towardsEnd = true, step = 0.05f, range = opacity), 0f)
        repeat(14) { v = steppedValue(v, towardsEnd = false, step = 0.05f, range = opacity) }
        assertEquals(0.3f, v, 1e-4f)
    }

    @Test
    fun `a held key moves five steps at a time, but only across a long range`() {
        val buffer = 1_000f..120_000f
        assertEquals(1, keyStepMultiplier(repeatCount = 0, range = buffer, step = 1_000f))
        assertEquals(1, keyStepMultiplier(repeatCount = 9, range = buffer, step = 1_000f))
        assertEquals(5, keyStepMultiplier(repeatCount = 10, range = buffer, step = 1_000f))
        assertEquals(1, keyStepMultiplier(repeatCount = 30, range = 0.3f..1f, step = 0.05f))
        assertEquals(
            6_000f,
            steppedValue(1_000f, towardsEnd = true, step = 1_000f, range = buffer, times = 5),
            0f,
        )
    }

    @Test
    fun `the default step is one of the slider's own steps`() {
        assertEquals(1f, defaultKeyStep(0f..10f, steps = 9), 0f)
        assertEquals(1f, defaultKeyStep(1f..8f, steps = 6), 0f)
        assertEquals(30f, defaultKeyStep(-720f..720f, steps = 1440 / 30 - 1), 0f)
        assertEquals(1f, defaultKeyStep(0f..20f, steps = 0), 0f)
    }
}
