package app.gamenative.powercontrol

internal data class PowerTunerToken(
    val sessionGeneration: Long,
    val intentGeneration: Long,
)

internal class PowerSessionState {
    private var generation = 0L
    private var intentGeneration = 0L
    private var activeGeneration: Long? = null

    fun start(): Long {
        generation += 1
        intentGeneration += 1
        activeGeneration = generation
        return generation
    }

    fun invalidate(): Long {
        generation += 1
        intentGeneration += 1
        activeGeneration = null
        return generation
    }

    fun invalidateTunerRequests() {
        intentGeneration += 1
    }

    fun newTunerToken(): PowerTunerToken? {
        return activeGeneration?.let { PowerTunerToken(it, intentGeneration) }
    }

    fun isActive(token: PowerTunerToken): Boolean {
        return activeGeneration == token.sessionGeneration && intentGeneration == token.intentGeneration
    }
}
