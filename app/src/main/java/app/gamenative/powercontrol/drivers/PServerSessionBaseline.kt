package app.gamenative.powercontrol.drivers

internal data class PServerSessionBaselineState(
    val cpuCaptured: Boolean,
    val gpuRequired: Boolean,
    val gpuCaptured: Boolean,
) {
    val isValid: Boolean
        get() = cpuCaptured && (!gpuRequired || gpuCaptured)

    companion object {
        val INVALID = PServerSessionBaselineState(
            cpuCaptured = false,
            gpuRequired = false,
            gpuCaptured = false,
        )
    }
}
