package app.gamenative.powercontrol.drivers

internal data class PerformanceBounds(
    val minimum: Int,
    val maximum: Int,
)

internal fun normalizeMinRequest(
    currentMinimum: Int,
    currentMaximum: Int,
    requestedMinimum: Int,
): PerformanceBounds {
    return PerformanceBounds(
        minimum = requestedMinimum,
        maximum = maxOf(currentMaximum, requestedMinimum),
    )
}

internal fun normalizeMaxRequest(
    currentMinimum: Int,
    currentMaximum: Int,
    requestedMaximum: Int,
): PerformanceBounds {
    return PerformanceBounds(
        minimum = minOf(currentMinimum, requestedMaximum),
        maximum = requestedMaximum,
    )
}
