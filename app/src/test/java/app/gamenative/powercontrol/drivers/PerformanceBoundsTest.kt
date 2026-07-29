package app.gamenative.powercontrol.drivers

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceBoundsTest {
    @Test
    fun normalizeMinRequest_raisesTheMaximumWhenCpuMinimumExceedsIt() {
        assertEquals(
            PerformanceBounds(minimum = 4, maximum = 4),
            normalizeMinRequest(currentMinimum = 1, currentMaximum = 3, requestedMinimum = 4),
        )
    }

    @Test
    fun normalizeMaxRequest_lowersTheMinimumWhenGpuMaximumFallsBelowIt() {
        assertEquals(
            PerformanceBounds(minimum = 1, maximum = 1),
            normalizeMaxRequest(currentMinimum = 3, currentMaximum = 4, requestedMaximum = 1),
        )
    }

    @Test
    fun normalizeRequests_preservesAnExplicitValidBatchPair() {
        val afterMinimum = normalizeMinRequest(
            currentMinimum = 3,
            currentMaximum = 4,
            requestedMinimum = 1,
        )

        assertEquals(
            PerformanceBounds(minimum = 1, maximum = 2),
            normalizeMaxRequest(
                currentMinimum = afterMinimum.minimum,
                currentMaximum = afterMinimum.maximum,
                requestedMaximum = 2,
            ),
        )
    }
}
