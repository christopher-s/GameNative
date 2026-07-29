package app.gamenative.powercontrol.drivers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PServerSessionBaselineTest {
    @Test
    fun isValid_requiresCpuAndGpuSnapshotsWhenGpuIsSupported() {
        assertFalse(
            PServerSessionBaselineState(
                cpuCaptured = true,
                gpuRequired = true,
                gpuCaptured = false,
            ).isValid,
        )
        assertTrue(
            PServerSessionBaselineState(
                cpuCaptured = true,
                gpuRequired = true,
                gpuCaptured = true,
            ).isValid,
        )
    }

    @Test
    fun isValid_allowsCpuOnlySessions() {
        assertTrue(
            PServerSessionBaselineState(
                cpuCaptured = true,
                gpuRequired = false,
                gpuCaptured = false,
            ).isValid,
        )
    }

    @Test
    fun invalid_discardsAllPriorSnapshotValidity() {
        assertFalse(PServerSessionBaselineState.INVALID.isValid)
    }
}
