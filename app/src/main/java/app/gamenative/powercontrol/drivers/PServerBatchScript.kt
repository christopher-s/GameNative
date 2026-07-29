package app.gamenative.powercontrol.drivers

internal fun buildFailFastPServerScript(
    writablePaths: Collection<String>,
    commands: Collection<String>,
): String = buildString {
    appendLine("#!/system/bin/sh")
    appendLine("set -e")
    writablePaths.forEach { path -> appendLine("chmod 644 '$path'") }
    commands.forEach(::appendLine)
}

internal fun wrapPServerCommandWithExitStatus(command: String): String {
    val marker = "__GAMENATIVE_EXIT_STATUS:"
    return "( $command ); status=\$?; printf '\\n${marker}%s\\n' \"\$status\"; exit \$status"
}

internal data class PServerRollbackEntry(
    val path: String,
    val value: String,
    val originalMode: String,
)

internal enum class PServerBound {
    MIN,
    MAX,
}

internal fun buildPServerRollbackEntries(
    mutationPaths: List<String>,
    snapshots: Map<String, String>,
    modes: Map<String, String>,
): List<PServerRollbackEntry>? {
    return mutationPaths.distinct().map { path ->
        val value = snapshots[path] ?: return null
        val originalMode = modes[path] ?: return null
        PServerRollbackEntry(path, value, originalMode)
    }.asReversed()
}

internal fun buildPServerRollbackScript(entries: List<PServerRollbackEntry>): String = buildString {
    appendLine("set -e")
    entries.forEach { entry ->
        appendLine("chmod 644 '${entry.path}' && printf '%s\\n' '${entry.value}' > '${entry.path}' && chmod ${entry.originalMode} '${entry.path}'")
    }
}

internal fun orderedPServerBoundWrites(
    currentMin: Long,
    currentMax: Long,
    targetMin: Long,
    targetMax: Long,
    minMustNotExceedMax: Boolean,
): List<PServerBound> {
    val order = if (minMustNotExceedMax) {
        when {
            targetMin > currentMax -> listOf(PServerBound.MAX, PServerBound.MIN)
            targetMax < currentMin -> listOf(PServerBound.MIN, PServerBound.MAX)
            else -> listOf(PServerBound.MAX, PServerBound.MIN)
        }
    } else {
        when {
            targetMin < currentMax -> listOf(PServerBound.MAX, PServerBound.MIN)
            targetMax > currentMin -> listOf(PServerBound.MIN, PServerBound.MAX)
            else -> listOf(PServerBound.MAX, PServerBound.MIN)
        }
    }
    return order.filter { bound ->
        if (bound == PServerBound.MIN) currentMin != targetMin else currentMax != targetMax
    }
}

internal fun buildPServerBoundWriteCommands(
    minPath: String,
    maxPath: String,
    currentMin: Long,
    currentMax: Long,
    targetMin: Long,
    targetMax: Long,
    minMustNotExceedMax: Boolean,
): List<String> {
    return orderedPServerBoundWrites(
        currentMin = currentMin,
        currentMax = currentMax,
        targetMin = targetMin,
        targetMax = targetMax,
        minMustNotExceedMax = minMustNotExceedMax,
    ).map { bound ->
        if (bound == PServerBound.MIN) {
            "echo '$targetMin' > '$minPath'"
        } else {
            "echo '$targetMax' > '$maxPath'"
        }
    }
}
