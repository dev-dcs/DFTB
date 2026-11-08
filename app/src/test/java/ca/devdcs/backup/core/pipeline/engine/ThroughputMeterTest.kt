package ca.devdcs.backup.core.pipeline.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputMeterTest {

    @Test
    fun `first sample is a baseline then rate is bytes per second`() {
        val meter = ThroughputMeter(smoothing = 1.0)
        assertEquals(0L, meter.sample(0L, 0L))
        assertEquals(1_000L, meter.sample(1_000L, 1_000L))
        assertEquals(4_000L, meter.sample(1_500L, 3_000L))
    }

    @Test
    fun `smoothing damps spikes`() {
        val meter = ThroughputMeter(smoothing = 0.5)
        meter.sample(0L, 0L)
        assertEquals(1_000L, meter.sample(1_000L, 1_000L))
        // Instant 3000 B/s, smoothed halfway from 1000.
        assertEquals(2_000L, meter.sample(2_000L, 4_000L))
    }

    @Test
    fun `samples closer than the minimum interval keep the current rate`() {
        val meter = ThroughputMeter(smoothing = 1.0, minSampleMillis = 100L)
        meter.sample(0L, 0L)
        meter.sample(1_000L, 1_000L)
        assertEquals(1_000L, meter.sample(1_010L, 50_000L))
    }

    @Test
    fun `a rewind after a retry never reports negative speed`() {
        val meter = ThroughputMeter(smoothing = 1.0)
        meter.sample(0L, 0L)
        meter.sample(1_000L, 5_000L)
        assertEquals(0L, meter.sample(2_000L, 100L))
        assertTrue(meter.sample(3_000L, 1_100L) > 0L)
        meter.reset()
        assertEquals(0L, meter.current())
    }
}
