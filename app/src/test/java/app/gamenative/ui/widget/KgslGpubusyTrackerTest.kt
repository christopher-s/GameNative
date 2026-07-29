package app.gamenative.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KgslGpubusyTrackerTest {
    @Test
    fun sample_usesCumulativeCounterDeltasInsteadOfLifetimeRatio() {
        val tracker = KgslGpubusyTracker()

        assertNull(tracker.sample(busy = 900L, total = 1_000L))

        assertEquals(10, tracker.sample(busy = 910L, total = 1_100L))
    }

    @Test
    fun sample_returnsUnavailableForNonpositiveTotalDelta() {
        val tracker = KgslGpubusyTracker()

        assertNull(tracker.sample(busy = 100L, total = 200L))
        assertNull(tracker.sample(busy = 110L, total = 200L))
        assertEquals(50, tracker.sample(busy = 120L, total = 220L))
    }

    @Test
    fun sample_returnsUnavailableForNonpositiveInitialTotal() {
        val tracker = KgslGpubusyTracker()

        assertNull(tracker.sample(busy = 0L, total = 0L))
    }

    @Test
    fun sample_rebaselinesAfterCounterReset() {
        val tracker = KgslGpubusyTracker()

        assertNull(tracker.sample(busy = 900L, total = 1_000L))
        assertEquals(10, tracker.sample(busy = 910L, total = 1_100L))
        assertNull(tracker.sample(busy = 3L, total = 1_200L))
        assertEquals(50, tracker.sample(busy = 8L, total = 1_210L))
    }

    @Test
    fun sample_clampsUsageToValidPercentRange() {
        val tracker = KgslGpubusyTracker()

        assertNull(tracker.sample(busy = 1L, total = 100L))

        assertEquals(100, tracker.sample(busy = 201L, total = 200L))
    }
}
