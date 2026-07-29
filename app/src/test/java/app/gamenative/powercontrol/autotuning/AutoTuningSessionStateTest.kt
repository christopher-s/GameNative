package app.gamenative.powercontrol.autotuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTuningSessionStateTest {
    @Test
    fun warmupCounter_skipsExactlyConfiguredCyclesAfterEachReset() {
        val counter = WarmupCycleCounter(cyclesToSkip = 10)

        repeat(10) {
            assertTrue(counter.shouldSkipCycle())
        }
        assertFalse(counter.shouldSkipCycle())

        counter.reset()

        assertTrue(counter.shouldSkipCycle())
    }

    @Test
    fun lastAppliedValue_notifiesOnlyOnChangedValuesAndAgainAfterReset() {
        val value = LastAppliedValue<Int>()
        val appliedValues = mutableListOf<Int>()

        assertTrue(value.applyIfChanged(2, appliedValues::add))
        assertFalse(value.applyIfChanged(2, appliedValues::add))

        value.reset()

        assertTrue(value.applyIfChanged(2, appliedValues::add))
        assertEquals(listOf(2, 2), appliedValues)
    }

    @Test
    fun lastAppliedValue_retriesWhenCallbackReturnsFalse() {
        val value = LastAppliedValue<Int>()
        val appliedValues = mutableListOf<Int>()

        assertFalse(value.applyIfChanged(2) {
            appliedValues.add(it)
            false
        })
        assertTrue(value.applyIfChanged(2) {
            appliedValues.add(it)
            true
        })
        assertFalse(value.applyIfChanged(2) { error("duplicate callback") })

        assertEquals(listOf(2, 2), appliedValues)
    }

    @Test
    fun lastAppliedValue_allowsRetryWhenCallbackThrows() {
        val value = LastAppliedValue<Int>()

        assertTrue(runCatching { value.applyIfChanged(2) { error("failure") } }.isFailure)

        assertTrue(value.applyIfChanged(2) { true })
    }

    @Test
    fun normalizeCpuFrequencies_sortsAndDeduplicatesHardwareValues() {
        assertEquals(
            listOf(300_000L, 1_200_000L, 2_000_000L),
            normalizeCpuFrequencies(listOf(2_000_000L, 300_000L, 1_200_000L, 300_000L)),
        )
    }

    @Test
    fun mapCpuPerformanceToFrequency_returnsClosestNormalizedHardwareValue() {
        val frequency = mapCpuPerformanceToFrequency(
            frequencies = listOf(300_000L, 1_000_000L, 2_000_000L),
            performance = 50.0,
        )

        assertEquals(1_000_000L, frequency)
    }
}
