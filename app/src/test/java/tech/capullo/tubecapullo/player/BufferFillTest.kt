package tech.capullo.tubecapullo.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferFillTest {
    @Test
    fun `an unknown duration leaves the threshold uncapped`() {
        assertEquals(0.5f, bufferFill(bufferedMs = 1_500, positionMs = 1_000, thresholdMs = 1_000, remainingMs = C.TIME_UNSET), 0f)
    }

    @Test
    fun `a negative remaining time counts as unknown`() {
        assertEquals(0.5f, bufferFill(bufferedMs = 1_500, positionMs = 1_000, thresholdMs = 1_000, remainingMs = -200), 0f)
    }

    @Test
    fun `the remaining time caps the threshold so the end of a track can fill the ring`() {
        assertEquals(0.5f, bufferFill(bufferedMs = 9_200, positionMs = 9_000, thresholdMs = 2_000, remainingMs = 400), 0f)
        assertEquals(1f, bufferFill(bufferedMs = 9_400, positionMs = 9_000, thresholdMs = 2_000, remainingMs = 400), 0f)
    }

    @Test
    fun `nothing to wait for is a full buffer`() {
        assertEquals(1f, bufferFill(bufferedMs = 0, positionMs = 0, thresholdMs = 0, remainingMs = C.TIME_UNSET), 0f)
        assertEquals(1f, bufferFill(bufferedMs = 0, positionMs = 0, thresholdMs = -1, remainingMs = C.TIME_UNSET), 0f)
        assertEquals(1f, bufferFill(bufferedMs = 5_000, positionMs = 5_000, thresholdMs = 2_000, remainingMs = 0), 0f)
    }

    @Test
    fun `a buffered position behind the playback position is empty, not negative`() {
        assertEquals(0f, bufferFill(bufferedMs = 800, positionMs = 1_000, thresholdMs = 1_000, remainingMs = 10_000), 0f)
    }
}
