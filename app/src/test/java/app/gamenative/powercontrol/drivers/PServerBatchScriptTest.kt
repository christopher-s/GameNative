package app.gamenative.powercontrol.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PServerBatchScriptTest {
    @Test
    fun buildFailFastPServerScript_stopsBeforeLaterWritesWhenACommandFails() {
        val script = buildFailFastPServerScript(
            writablePaths = listOf("/sys/cpu/min", "/sys/gpu/max"),
            commands = listOf(
                "echo '1000' > '/sys/cpu/min'",
                "echo '1' > '/sys/gpu/max'",
            ),
        )

        val lines = script.lines()

        assertEquals("set -e", lines[1])
        assertTrue(lines.indexOf("chmod 644 '/sys/cpu/min'") < lines.indexOf("echo '1000' > '/sys/cpu/min'"))
        assertTrue(lines.indexOf("chmod 644 '/sys/gpu/max'") < lines.indexOf("echo '1' > '/sys/gpu/max'"))
    }

    @Test
    fun wrapPServerCommandWithExitStatus_propagatesTheShellExitCode() {
        val command = wrapPServerCommandWithExitStatus("false")

        assertTrue(command.startsWith("( false ); status=\$?"))
        assertTrue(command.contains("__GAMENATIVE_EXIT_STATUS:%s"))
        assertTrue(command.endsWith("exit \$status"))
    }

    @Test
    fun buildPServerRollbackEntries_reversesFirstMutationOrderWithoutDuplicatingPaths() {
        val entries = buildPServerRollbackEntries(
            mutationPaths = listOf("/cpu/max", "/cpu/min", "/cpu/max"),
            snapshots = mapOf("/cpu/max" to "2000", "/cpu/min" to "1000"),
            modes = mapOf("/cpu/max" to "444", "/cpu/min" to "640"),
        )

        assertEquals(
            listOf(
                PServerRollbackEntry("/cpu/min", "1000", "640"),
                PServerRollbackEntry("/cpu/max", "2000", "444"),
            ),
            entries,
        )
    }

    @Test
    fun buildPServerRollbackEntries_rejectsIncompleteSnapshotsBeforeGeneratingCommands() {
        assertNull(
            buildPServerRollbackEntries(
                mutationPaths = listOf("/cpu/max"),
                snapshots = emptyMap(),
                modes = mapOf("/cpu/max" to "444"),
            ),
        )
    }

    @Test
    fun orderedPServerBoundWrites_restoresCpuAndGpuBoundsWithoutViolatingTheirInvariants() {
        assertEquals(
            listOf(PServerBound.MIN, PServerBound.MAX),
            orderedPServerBoundWrites(2_000, 3_000, 1_000, 1_500, minMustNotExceedMax = true),
        )
        assertEquals(
            listOf(PServerBound.MAX, PServerBound.MIN),
            orderedPServerBoundWrites(5, 4, 2, 1, minMustNotExceedMax = false),
        )
    }

    @Test
    fun buildPServerBoundWriteCommands_stagesCpuBoundsUpwardByRaisingMaxBeforeMin() {
        val commands = buildPServerBoundWriteCommands(
            minPath = "/cpu/min",
            maxPath = "/cpu/max",
            currentMin = 1_000,
            currentMax = 2_000,
            targetMin = 3_000,
            targetMax = 4_000,
            minMustNotExceedMax = true,
        )

        assertEquals(
            listOf(
                "echo '4000' > '/cpu/max'",
                "echo '3000' > '/cpu/min'",
            ),
            commands,
        )
    }

    @Test
    fun buildPServerBoundWriteCommands_stagesCpuBoundsDownwardByLoweringMinBeforeMax() {
        val commands = buildPServerBoundWriteCommands(
            minPath = "/cpu/min",
            maxPath = "/cpu/max",
            currentMin = 3_000,
            currentMax = 4_000,
            targetMin = 1_000,
            targetMax = 2_000,
            minMustNotExceedMax = true,
        )

        assertEquals(
            listOf(
                "echo '1000' > '/cpu/min'",
                "echo '2000' > '/cpu/max'",
            ),
            commands,
        )
    }

    @Test
    fun orderedPServerBoundWrites_widensOnlyTheOppositeCpuBoundWhenAStandaloneTargetNeedsIt() {
        assertEquals(
            listOf(PServerBound.MAX),
            orderedPServerBoundWrites(1_000, 2_000, 1_000, 3_000, minMustNotExceedMax = true),
        )
        assertEquals(
            listOf(PServerBound.MIN),
            orderedPServerBoundWrites(3_000, 4_000, 2_000, 4_000, minMustNotExceedMax = true),
        )
    }
}
