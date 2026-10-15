package com.tivicopy.player.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio equaliser's arithmetic (ADR-031): the transform and the reduction to bars.
 *
 * The audio path itself needs a device; what it computes from a buffer does not.
 */
class SpectrumMathTest {

    private val n = AudioSpectrum.WINDOW
    private val rate = 44_100

    private fun tone(hz: Double, amplitude: Double = 1.0) =
        FloatArray(n) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun bandsOf(samples: FloatArray) =
        SpectrumMath.bands(samples, FloatArray(n), FloatArray(n), rate, AudioSpectrum.BANDS)

    @Test
    fun `silence is a row of flat bars`() {
        val bands = bandsOf(FloatArray(n))
        assertTrue(bands.all { it == 0f })
    }

    @Test
    fun `a tone lights the band it falls in, and the far bands stay low`() {
        val hz = 1_000.0
        val bands = bandsOf(tone(hz))
        val expected = (AudioSpectrum.BANDS * ln(hz / SpectrumMath.LOW_HZ) / ln(SpectrumMath.HIGH_HZ / SpectrumMath.LOW_HZ.toDouble())).toInt()
        val loudest = bands.indices.maxByOrNull { bands[it] }!!
        assertTrue("loudest band $loudest, expected about $expected", abs(loudest - expected) <= 1)
        assertTrue("a full-scale tone reaches the top: ${bands[loudest]}", bands[loudest] > 0.85f)
        assertTrue("the lowest band is quiet: ${bands[0]}", bands[0] < 0.5f)
        assertTrue("the highest band is quiet: ${bands.last()}", bands.last() < 0.5f)
    }

    @Test
    fun `a quieter tone draws a lower bar`() {
        val loud = bandsOf(tone(500.0, 1.0)).maxOrNull()!!
        val quiet = bandsOf(tone(500.0, 0.05)).maxOrNull()!!
        assertTrue("quiet $quiet should be below loud $loud", quiet < loud)
    }

    @Test
    fun `the transform of an impulse is flat`() {
        val re = FloatArray(16).also { it[0] = 1f }
        val im = FloatArray(16)
        SpectrumMath.fft(re, im)
        re.forEach { assertEquals(1f, it, 1e-5f) }
        im.forEach { assertEquals(0f, it, 1e-5f) }
    }
}
