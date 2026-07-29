package app.gamenative.powercontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSessionStateTest {
    @Test
    fun invalidate_rejectsAStaleTunerGenerationAfterStopBegins() {
        val state = PowerSessionState()
        state.start()
        val token = state.newTunerToken()!!

        state.invalidate()

        assertFalse(state.isActive(token))
    }

    @Test
    fun start_acceptsOnlyTheLatestSessionGeneration() {
        val state = PowerSessionState()
        state.start()
        val firstToken = state.newTunerToken()!!
        state.start()
        val secondToken = state.newTunerToken()!!

        assertFalse(state.isActive(firstToken))
        assertTrue(state.isActive(secondToken))
    }

    @Test
    fun invalidateTunerRequests_rejectsAStaleRequestWithinTheSameSession() {
        val state = PowerSessionState()
        state.start()
        val staleToken = state.newTunerToken()!!

        state.invalidateTunerRequests()
        val currentToken = state.newTunerToken()!!

        assertFalse(state.isActive(staleToken))
        assertTrue(state.isActive(currentToken))
    }

    @Test
    fun invalidate_rejectsTheSessionGenerationOwnedByAFailedStart() {
        val state = PowerSessionState()
        val startingGeneration = state.start()

        state.invalidate()
        state.start()

        assertFalse(state.newTunerToken()?.sessionGeneration == startingGeneration)
    }

}
