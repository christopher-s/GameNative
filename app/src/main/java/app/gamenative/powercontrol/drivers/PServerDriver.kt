package app.gamenative.powercontrol.drivers

import android.annotation.SuppressLint
import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import timber.log.Timber
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Performance driver implementation for devices with PServer support
 * (AYN Odin, Retroid Pocket, etc.)
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class PServerDriver(private val context: Context? = null) : PerformanceDriver() {

    companion object {
        private const val TAG = "PServerDriver"

        // CPU sysfs paths
        private const val CPU_BASE_PATH = "/sys/devices/system/cpu"
        private const val CPUFREQ_PATH = "/sys/devices/system/cpu/cpufreq"
        private const val POLICY0_PATH = "$CPUFREQ_PATH/policy0"

        // GPU sysfs paths (Adreno)
        private const val GPU_BASE_PATH = "/sys/class/kgsl/kgsl-3d0"
        private const val GPU_DEVFREQ_PATH = "$GPU_BASE_PATH/devfreq"
    }

    // CPU policy information for optimized control
    private data class CpuPolicy(
        val policyId: Int,
        val governorPath: String,
        val minFreqPath: String,
        val maxFreqPath: String,
        val cpuCores: List<Int>,
        val maxFrequency: Long = 0L
    )

    // CPU cluster types based on frequency
    enum class CpuCluster {
        EFFICIENCY,    // Lowest frequency cores
        PERFORMANCE,   // Mid-high frequency cores
        PRIME          // Highest frequency core(s)
    }

    private val binderLock = Any()
    @Volatile
    private var binder: IBinder? = null
    private var binderDeathRecipient: IBinder.DeathRecipient? = null
    @Volatile
    private var isPServerAvailable: Boolean = false
    private val isGpuAvailable: Boolean

    private val originalSysfsModes = mutableMapOf<String, String>()

    // Serializes batch sessions and all sysfs writes (auto-tuner thread vs UI threads)
    private val batchLock = ReentrantLock()

    // Batch update support - only touched under batchLock
    private val batchCommands = mutableListOf<String>()
    private val batchFilePaths = mutableSetOf<String>()
    private val batchSnapshots = mutableMapOf<String, String>()
    private val batchFirstMutationPaths = mutableListOf<String>()
    private var isBatchMode = false
    private var batchFailed = false

    // Values staged by batch-mode setters; applied to cached state only on commit success
    private var batchPendingMin: Long? = null
    private var batchPendingMax: Long? = null
    private var batchPendingGovernor: String? = null
    private var batchPendingGpuMin: Int? = null
    private var batchPendingGpuMax: Int? = null

    // Original per-policy state captured before first modification, restored in stop()
    private data class PolicySnapshot(val governor: String, val minFreq: Long, val maxFreq: Long)
    private val originalPolicyStates = mutableMapOf<String, PolicySnapshot>()
    private data class GpuSnapshot(val minPowerLevel: Int, val maxPowerLevel: Int)
    private var originalGpuState: GpuSnapshot? = null
    private var baselineState = PServerSessionBaselineState.INVALID

    // CPU policies discovered at initialization (reduces redundant IPC calls)
    private var cpuPolicies: List<CpuPolicy> = emptyList()

    // CPU cluster mapping for affinity control
    private var cpuClusters: Map<CpuCluster, List<Int>> = emptyMap()

    // Taskset mask format (cached after first detection)
    private var tasksetMaskFormat: TasksetMaskFormat? = null

    enum class TasksetMaskFormat {
        PLAIN_HEX,  // e.g., "f8"
        HEX_PREFIX  // e.g., "0xf8"
    }

    // Track current CPU settings (what was requested, not what policy0 has)
    private var currentMinCpuFreq: Long = 0L
    private var currentMaxCpuFreq: Long = 0L
    private var currentGovernor: String = ""
    private var currentMinGpuSysfsLevel: Int? = null
    private var currentMaxGpuSysfsLevel: Int? = null

    init {
        connectToPServer()

        // Check GPU support once during initialization
        isGpuAvailable = try {
            val maxPwrLevelFile = File("$GPU_BASE_PATH/max_pwrlevel")
            val availableFreqsFile = File("$GPU_DEVFREQ_PATH/available_frequencies")
            maxPwrLevelFile.exists() && availableFreqsFile.exists()
        } catch (e: Exception) {
            false
        }
    }

    // ========================================
    // General / Driver Support
    // ========================================

    override fun isBusSupported(): Boolean = false

    /**
     * Check if PServer driver is available on this device
     */
    override fun isDriverSupported(): Boolean {
        return connectToPServer() != null
    }

    /**
     * Check if CPU governor control is supported
     */
    override fun isGovernorSupported(): Boolean {
        return isDriverSupported()
    }

    /**
     * Check if GPU control is supported (Adreno GPUs)
     */
    override fun isGpuSupported(): Boolean {
        return isGpuAvailable
    }

    /**
     * Get display unit for frequency values
     * Returns HZ for formatted display (e.g., 2.4 GHz)
     */
    override fun getDisplayUnit(): DisplayUnit {
        return DisplayUnit.HZ
    }

    /**
     * Begin a batch update session.
     * Collects commands to execute in a single root call for better performance.
     */
    override fun beginUpdate() {
        batchLock.lock()
        try {
            check(!isBatchMode) { "A PServer update is already in progress" }
            clearBatchState()
            check(ensureRestorableBaseline()) { "PServer baseline is unavailable" }
            isBatchMode = true
        } catch (throwable: Throwable) {
            batchLock.unlock()
            throw throwable
        }
    }

    /**
     * Commit all pending updates from the batch session.
     * Writes commands to a temporary shell script and executes it to avoid Binder size limits.
     */
    override fun commit(): Boolean {
        val acquiredHere = !batchLock.isHeldByCurrentThread
        if (acquiredHere) batchLock.lock()
        try {
            if (!isBatchMode) {
                return true
            }
            if (!baselineState.isValid) return false
            if (batchFailed) return false
            if (batchCommands.isEmpty()) return true
            return commitLocked()
        } finally {
            if (isBatchMode) clearBatchState()
            if (batchLock.isHeldByCurrentThread) batchLock.unlock()
        }
    }

    override fun cancelUpdate() {
        val acquiredHere = !batchLock.isHeldByCurrentThread
        if (acquiredHere) batchLock.lock()
        try {
            clearBatchState()
        } finally {
            batchLock.unlock()
        }
    }

    private fun commitLocked(): Boolean {

        var scriptFile: File? = null
        return try {
            // Create temporary shell script in app cache directory (or fallback to /data/local/tmp)
            scriptFile = if (context != null) {
                File(context.cacheDir, "pserver_batch_${System.currentTimeMillis()}.sh")
            } else {
                File("/data/local/tmp/pserver_batch_${System.currentTimeMillis()}.sh")
            }

            val commands = buildBatchCommands() ?: return false
            val scriptContent = buildFailFastPServerScript(batchFilePaths, commands)

            try {
                scriptFile.writeText(scriptContent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to write batch script to ${scriptFile.absolutePath}")
                return false
            }

            // Make script executable and run it
            val chmodResult = executeCheckedAsRoot("chmod 755 '${scriptFile.absolutePath}'")
            if (chmodResult.isFailure) {
                Timber.tag(TAG).e("Failed to chmod batch script: ${chmodResult.exceptionOrNull()?.message}")
                return false
            }

            val execResult = executeCheckedAsRoot("/system/bin/sh '${scriptFile.absolutePath}'")
            val success = execResult.isSuccess

            if (execResult.isFailure) {
                Timber.tag(TAG).e("Failed to execute batch script: ${execResult.exceptionOrNull()?.message}")
            } else {
                // When using auto-tuning, this log can spam around, suppress it
                if (PowerManager.currentProfile?.enableAutoTuning == false) {
                    Timber.tag(TAG).d("Successfully executed ${commands.size} batched commands")
                }
            }

            if (success) {
                batchPendingMin?.let { targetMin ->
                    currentMinCpuFreq = targetMin
                    if (batchPendingMax == null && currentMaxCpuFreq < targetMin) {
                        currentMaxCpuFreq = targetMin
                    }
                }
                batchPendingMax?.let { targetMax ->
                    currentMaxCpuFreq = targetMax
                    if (batchPendingMin == null && currentMinCpuFreq > targetMax) {
                        currentMinCpuFreq = targetMax
                    }
                }
                batchPendingGovernor?.let { currentGovernor = it }
                batchPendingGpuMin?.let { currentMinGpuSysfsLevel = it }
                batchPendingGpuMax?.let { currentMaxGpuSysfsLevel = it }
            } else if (!rollbackBatchLocked()) {
                Timber.tag(TAG).e("Failed to restore PServer state after batch failure")
            }
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to commit batch update")
            if (!rollbackBatchLocked()) {
                Timber.tag(TAG).e("Failed to restore PServer state after batch exception")
            }
            false
        } finally {
            // Clean up script file
            try {
                scriptFile?.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to delete batch script")
            }
        }
    }

    private fun clearBatchState() {
        batchCommands.clear()
        batchFilePaths.clear()
        batchSnapshots.clear()
        batchFirstMutationPaths.clear()
        batchPendingMin = null
        batchPendingMax = null
        batchPendingGovernor = null
        batchPendingGpuMin = null
        batchPendingGpuMax = null
        batchFailed = false
        isBatchMode = false
    }

    private fun buildBatchCommands(): List<String>? {
        if (batchPendingMin == null && batchPendingMax == null) return batchCommands

        val cpuBoundPaths = if (cpuPolicies.isNotEmpty()) {
            cpuPolicies.flatMap { listOf(it.minFreqPath, it.maxFreqPath) }
        } else {
            (0 until getNumCpus()).flatMap { cpu ->
                listOf(
                    "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_min_freq",
                    "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_max_freq",
                )
            }
        }.toSet()
        val nonBoundCommands = batchCommands.filterNot { command ->
            cpuBoundPaths.any { path -> command.endsWith(" > '$path'") }
        }
        val boundCommands = mutableListOf<String>()
        val policyPaths = if (cpuPolicies.isNotEmpty()) {
            cpuPolicies.map { policy ->
                Triple(policy.minFreqPath, policy.maxFreqPath, policy.maxFrequency)
            }
        } else {
            (0 until getNumCpus()).map { cpu ->
                Triple(
                    "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_min_freq",
                    "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_max_freq",
                    0L,
                )
            }
        }
        for ((minPath, maxPath, policyMax) in policyPaths) {
            val currentMin = readSysfsFile(minPath)?.toLongOrNull() ?: return null
            val currentMax = readSysfsFile(maxPath)?.toLongOrNull() ?: return null
            val targetBounds = resolveCpuBounds(currentMin, currentMax, policyMax) ?: return null
            boundCommands += buildPServerBoundWriteCommands(
                minPath = minPath,
                maxPath = maxPath,
                currentMin = currentMin,
                currentMax = currentMax,
                targetMin = targetBounds.first,
                targetMax = targetBounds.second,
                minMustNotExceedMax = true,
            )
        }
        return nonBoundCommands + boundCommands
    }

    private fun resolveCpuBounds(
        currentMin: Long,
        currentMax: Long,
        policyMax: Long,
    ): Pair<Long, Long>? {
        fun capToPolicyMax(value: Long): Long = if (policyMax > 0) minOf(value, policyMax) else value

        var targetMin = batchPendingMin?.let(::capToPolicyMax) ?: currentMin
        var targetMax = batchPendingMax?.let(::capToPolicyMax) ?: currentMax
        if (batchPendingMin != null && batchPendingMax == null) targetMax = maxOf(targetMax, targetMin)
        if (batchPendingMax != null && batchPendingMin == null) targetMin = minOf(targetMin, targetMax)
        return if (targetMin <= targetMax) targetMin to targetMax else null
    }

    private fun runInSingleOperationTransaction(block: () -> Boolean): Boolean {
        if (isBatchMode) return block()

        beginUpdate()
        return try {
            if (block()) commit() else {
                cancelUpdate()
                false
            }
        } catch (throwable: Throwable) {
            cancelUpdate()
            throw throwable
        }
    }

    private fun rollbackBatchLocked(): Boolean {
        if (batchSnapshots.isEmpty()) return true

        val rollbackEntries = buildPServerRollbackEntries(
            mutationPaths = batchFirstMutationPaths,
            snapshots = batchSnapshots,
            modes = originalSysfsModes,
        ) ?: return false
        return executeCheckedAsRoot(buildPServerRollbackScript(rollbackEntries)).isSuccess
    }

    /**
     * Start the performance driver.
     * Validates CPU frequency scaling support and discovers CPU policies.
     */
    override fun start() {
        batchLock.withLock {
            if (cpuPolicies.isEmpty()) {
                validateCpuFreqSupport()
                cpuPolicies = discoverCpuPolicies()
                cpuClusters = identifyCpuClusters()
            }

            if (!captureSessionBaseline()) {
                Timber.tag(TAG).e("Unable to capture complete original PServer state")
            }
        }
    }

    /**
     * Stop the performance driver
     * Restores the original CPU policy state and modified sysfs permissions
     */
    override fun stop() {
        batchLock.withLock {
            clearBatchState()
            if (connectToPServer() == null) {
                Timber.tag(TAG).w("PServer not available to restore settings")
            } else {
                val cpuRestored = restoreCpuState()
                val gpuRestored = restoreGpuState()
                val modesRestored = restoreSysfsModes()
                if (!cpuRestored || !gpuRestored || !modesRestored) {
                    Timber.tag(TAG).e("PServer restoration failed; discarding session baseline")
                }
            }
            clearSessionBaseline()
            cpuPolicies = emptyList()
            cpuClusters = emptyMap()
        }
    }

    private fun restoreCpuState(): Boolean {
        if (!hasCompleteCpuPolicySnapshot()) return false

        var restored = true
        for (policy in cpuPolicies) {
            val snapshot = originalPolicyStates[policy.governorPath] ?: continue
            restored = restoreCpuBounds(policy, snapshot) && restored
            if (snapshot.governor.isNotEmpty()) {
                restored = writeSysfsFile(policy.governorPath, snapshot.governor) && restored
            }
        }
        return restored
    }

    private fun restoreCpuBounds(policy: CpuPolicy, snapshot: PolicySnapshot): Boolean {
        val currentMin = readSysfsFile(policy.minFreqPath)?.toLongOrNull() ?: return false
        val currentMax = readSysfsFile(policy.maxFreqPath)?.toLongOrNull() ?: return false
        return orderedPServerBoundWrites(
            currentMin = currentMin,
            currentMax = currentMax,
            targetMin = snapshot.minFreq,
            targetMax = snapshot.maxFreq,
            minMustNotExceedMax = true,
        ).all { bound ->
            if (bound == PServerBound.MIN) {
                writeSysfsFile(policy.minFreqPath, snapshot.minFreq.toString())
            } else {
                writeSysfsFile(policy.maxFreqPath, snapshot.maxFreq.toString())
            }
        }
    }

    // ========================================
    // CPU Control - Getters
    // ========================================

    /**
     * Get current minimum CPU frequency in KHz
     * Returns the last requested value, not policy0's value
     */
    override fun getCurrentMinCpuValue(): Long {
        // If we haven't set anything yet, read from policy0
        if (currentMinCpuFreq == 0L) {
            currentMinCpuFreq = readSysfsFile("$POLICY0_PATH/scaling_min_freq")?.toLongOrNull() ?: 0L
        }
        return currentMinCpuFreq
    }

    /**
     * Get current maximum CPU frequency in KHz
     * Returns the last requested value, not policy0's value
     */
    override fun getCurrentMaxCpuValue(): Long {
        // If we haven't set anything yet, read from policy0
        if (currentMaxCpuFreq == 0L) {
            currentMaxCpuFreq = readSysfsFile("$POLICY0_PATH/scaling_max_freq")?.toLongOrNull() ?: 0L
        }
        return currentMaxCpuFreq
    }

    /**
     * Get current CPU governor name
     * Returns the last set governor, not policy0's governor
     */
    override fun getCurrentGovernor(): String {
        // If we haven't set anything yet, read from policy0
        if (currentGovernor.isEmpty()) {
            currentGovernor = readSysfsFile("$POLICY0_PATH/scaling_governor")?.trim() ?: ""
        }
        return currentGovernor
    }

    /**
     * Get list of available CPU governors
     */
    override fun getAvailableGovernors(): List<String> {
        return try {
            val governors = readSysfsFile("$POLICY0_PATH/scaling_available_governors")
            governors?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available governors")
            emptyList()
        }
    }

    /**
     * Get list of available CPU frequencies in KHz (sorted)
     * Collects frequencies from all CPU policies to include all clusters
     */
    override fun getAvailableCpuFrequencies(): List<Long> {
        return try {
            val allFrequencies = mutableSetOf<Long>()

            // If policies are discovered, read from each policy
            if (cpuPolicies.isNotEmpty()) {
                for (policy in cpuPolicies) {
                    val policyDir = policy.governorPath.substringBeforeLast("/")
                    val freqs = readSysfsFile("$policyDir/scaling_available_frequencies")
                    freqs?.split("\\s+".toRegex())
                        ?.mapNotNull { it.toLongOrNull() }
                        ?.let { allFrequencies.addAll(it) }
                }
            } else {
                // Fallback: read from policy0 only
                val freqs = readSysfsFile("$POLICY0_PATH/scaling_available_frequencies")
                freqs?.split("\\s+".toRegex())
                    ?.mapNotNull { it.toLongOrNull() }
                    ?.let { allFrequencies.addAll(it) }
            }

            allFrequencies.sorted()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available frequencies")
            emptyList()
        }
    }

    // ========================================
    // CPU Control - Setters
    // ========================================

    /**
     * Set CPU governor for all CPU cores.
     * Uses policy-based approach to reduce IPC calls by 50-75%.
     */
    override fun setGovernor(governor: String): Boolean = batchLock.withLock {
        runInSingleOperationTransaction { stageGovernor(governor) }
    }

    private fun stageGovernor(governor: String): Boolean {
        return try {
            if (!ensureRestorableBaseline()) return false

            // Use policy-based approach if policies are discovered
            if (cpuPolicies.isNotEmpty()) {
                for (policy in cpuPolicies) {
                    if (!captureBatchSnapshot(policy.governorPath)) return false
                    batchFilePaths.add(policy.governorPath)
                    batchCommands.add("echo '$governor' > '${policy.governorPath}'")
                }
                batchPendingGovernor = governor
                return true
            }

            // Fallback: per-CPU approach (legacy behavior)
            val numCpus = getNumCpus()

            for (cpu in 0 until numCpus) {
                val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor"
                if (!captureBatchSnapshot(path)) return false
                batchFilePaths.add(path)
                batchCommands.add("echo '$governor' > '$path'")
            }
            batchPendingGovernor = governor
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set governor")
            false
        }
    }

    /**
     * Set minimum CPU frequency in KHz.
     */
    override fun setMinCpuValue(value: Long): Boolean = batchLock.withLock {
        runInSingleOperationTransaction { stageCpuFreq(isMin = true, value = value) }
    }

    /**
     * Set maximum CPU frequency in KHz.
     */
    override fun setMaxCpuValue(value: Long): Boolean = batchLock.withLock {
        runInSingleOperationTransaction { stageCpuFreq(isMin = false, value = value) }
    }

    /**
     * Shared implementation for the min/max CPU frequency setters.
     * Uses policy-based writes to reduce IPC calls by 50-75% and caps each
     * policy at its own maximum frequency. In batch mode the value is staged
     * and only committed to the cached state when commit() succeeds.
     */
    private fun stageCpuFreq(isMin: Boolean, value: Long): Boolean {
        val kind = if (isMin) "min" else "max"
        return try {
            if (!ensureRestorableBaseline()) return false

            // Use policy-based approach if policies are discovered
            if (cpuPolicies.isNotEmpty()) {
                for (policy in cpuPolicies) {
                    val cappedValue = if (policy.maxFrequency > 0) minOf(value, policy.maxFrequency) else value
                    if (!captureBatchSnapshot(policy.minFreqPath)) return false
                    if (!captureBatchSnapshot(policy.maxFreqPath)) return false
                    batchFilePaths.add(policy.minFreqPath)
                    batchFilePaths.add(policy.maxFreqPath)
                    val path = if (isMin) policy.minFreqPath else policy.maxFreqPath
                    batchCommands.add("echo '$cappedValue' > '$path'")
                }
                if (isMin) batchPendingMin = value else batchPendingMax = value
                return true
            }

            // Fallback: per-CPU approach (legacy behavior)
            val numCpus = getNumCpus()

            for (cpu in 0 until numCpus) {
                val minPath = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_min_freq"
                val maxPath = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_max_freq"
                if (!captureBatchSnapshot(minPath)) return false
                if (!captureBatchSnapshot(maxPath)) return false
                batchFilePaths.add(minPath)
                batchFilePaths.add(maxPath)
                val path = if (isMin) minPath else maxPath
                batchCommands.add("echo '$value' > '$path'")
            }
            if (isMin) batchPendingMin = value else batchPendingMax = value
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set $kind frequency")
            false
        }
    }

    // ========================================
    // GPU Control - Getters
    // ========================================

    /**
     * Get list of available GPU frequencies in KHz (sorted)
     */
    override fun getAvailableGpuFrequencies(): List<Long> {
        return try {
            val freqs = readSysfsFile("$GPU_DEVFREQ_PATH/available_frequencies")
            freqs?.split("\\s+".toRegex())
                ?.mapNotNull { it.toLongOrNull() }
                ?.map { it / 1000 }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available GPU frequencies")
            emptyList()
        }
    }

    /**
     * Get current GPU frequency in KHz
     */
    override fun getCurrentGpuValue(): Long {
        return try {
            val freqHz = readSysfsFile("$GPU_DEVFREQ_PATH/cur_freq")?.toLongOrNull() ?: 0L
            freqHz / 1000
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU frequency")
            0L
        }
    }

    /**
     * Get current GPU minimum power level
     * Returns UI-friendly value where higher = better performance
     * (Internally converts from Adreno's reversed sysfs semantics)
     */
    override fun getCurrentMinGpuPowerLevel(): Int {
        return try {
            val sysfsLevel = readSysfsFile("$GPU_BASE_PATH/min_pwrlevel")?.toIntOrNull() ?: 0
            val numLevels = getNumGpuPowerLevels()
            // Convert: sysfs min_pwrlevel (high index = low perf) to UI (high value = high perf)
            if (numLevels > 0) numLevels - 1 - sysfsLevel else 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU min power level")
            0
        }
    }

    /**
     * Get current GPU maximum power level
     * Returns UI-friendly value where higher = better performance
     * (Internally converts from Adreno's reversed sysfs semantics)
     */
    override fun getCurrentMaxGpuPowerLevel(): Int {
        return try {
            val sysfsLevel = readSysfsFile("$GPU_BASE_PATH/max_pwrlevel")?.toIntOrNull() ?: 0
            val numLevels = getNumGpuPowerLevels()
            // Convert: sysfs max_pwrlevel (low index = high perf) to UI (high value = high perf)
            if (numLevels > 0) numLevels - 1 - sysfsLevel else 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU max power level")
            0
        }
    }

    /**
     * Get total number of GPU power levels available
     */
    override fun getNumGpuPowerLevels(): Int {
        return try {
            readSysfsFile("$GPU_BASE_PATH/num_pwrlevels")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get number of GPU power levels")
            0
        }
    }

    // ========================================
    // GPU Control - Setters
    // ========================================

    /**
     * Set GPU minimum power level
     * Accepts UI-friendly value where higher = better performance
     * (Internally converts to Adreno's reversed sysfs semantics)
     */
    override fun setMinGpuPowerLevel(level: Int): Boolean = batchLock.withLock {
        runInSingleOperationTransaction { stageMinGpuPowerLevel(level) }
    }

    private fun stageMinGpuPowerLevel(level: Int): Boolean {
        if (!isGpuSupported()) {
            Timber.tag(TAG).w("GPU control not supported")
            return false
        }
        if (!ensureRestorableBaseline()) return false

        val numLevels = getNumGpuPowerLevels()
        // Convert: UI level (high = high perf) to sysfs min_pwrlevel (high index = low perf)
        val sysfsLevel = if (numLevels > 0) numLevels - 1 - level else level

        val currentMaxSysfs = batchPendingGpuMax
            ?: currentMaxGpuSysfsLevel
            ?: readSysfsFile("$GPU_BASE_PATH/max_pwrlevel")?.toIntOrNull()
            ?: 0
        if (sysfsLevel < currentMaxSysfs) {
            if (!stageGpuPowerLevel("$GPU_BASE_PATH/max_pwrlevel", sysfsLevel)) return false
            batchPendingGpuMax = sysfsLevel
        }
        if (!stageGpuPowerLevel("$GPU_BASE_PATH/min_pwrlevel", sysfsLevel)) return false
        batchPendingGpuMin = sysfsLevel
        return true
    }

    /**
     * Set GPU maximum power level
     * Accepts UI-friendly value where higher = better performance
     * (Internally converts to Adreno's reversed sysfs semantics)
     */
    override fun setMaxGpuPowerLevel(level: Int): Boolean = batchLock.withLock {
        runInSingleOperationTransaction { stageMaxGpuPowerLevel(level) }
    }

    private fun stageMaxGpuPowerLevel(level: Int): Boolean {
        if (!isGpuSupported()) {
            Timber.tag(TAG).w("GPU control not supported")
            return false
        }
        if (!ensureRestorableBaseline()) return false

        val numLevels = getNumGpuPowerLevels()
        // Convert: UI level (high = high perf) to sysfs max_pwrlevel (low index = high perf)
        val sysfsLevel = if (numLevels > 0) numLevels - 1 - level else level

        val currentMinSysfs = batchPendingGpuMin
            ?: currentMinGpuSysfsLevel
            ?: readSysfsFile("$GPU_BASE_PATH/min_pwrlevel")?.toIntOrNull()
            ?: (if (numLevels > 0) numLevels - 1 else 0)
        if (sysfsLevel > currentMinSysfs) {
            if (!stageGpuPowerLevel("$GPU_BASE_PATH/min_pwrlevel", sysfsLevel)) return false
            batchPendingGpuMin = sysfsLevel
        }
        if (!stageGpuPowerLevel("$GPU_BASE_PATH/max_pwrlevel", sysfsLevel)) return false
        batchPendingGpuMax = sysfsLevel
        return true
    }

    override fun getDefaultProfile(): PowerProfile {
        val availableFrequencies = getAvailableCpuFrequencies()
        val availableGovernors = getAvailableGovernors()

        if (availableFrequencies.isEmpty()) {
            // Fallback to a safe default
            return PowerProfile(
                name = PerformancePreset.BALANCED.displayName,
                governor = CpuGovernor.SCHEDUTIL,
                minCpuFreq = getCurrentMinCpuValue(),
                maxCpuFreq = getCurrentMaxCpuValue(),
                minGpuPowerLevel = 0,
                maxGpuPowerLevel = 0
            )
        }

        val midFreq = availableFrequencies[availableFrequencies.size / 2]
        val maxFreq = availableFrequencies.last()

        // GPU power levels
        val maxGpuPowerLevel = if (isGpuSupported()) {
            getNumGpuPowerLevels() - 1
        } else {
            0
        }
        val midGpuLevel = maxGpuPowerLevel / 2

        // Return Balanced profile (middle performance)
        val governor = when {
            availableGovernors.contains(CpuGovernor.SCHEDUTIL.governorName) -> CpuGovernor.SCHEDUTIL
            availableGovernors.contains(CpuGovernor.CONSERVATIVE.governorName) -> CpuGovernor.CONSERVATIVE
            availableGovernors.contains(CpuGovernor.INTERACTIVE.governorName) -> CpuGovernor.INTERACTIVE
            else -> CpuGovernor.SCHEDUTIL
        }

        return PowerProfile(
            name = PerformancePreset.BALANCED.displayName,
            governor = governor,
            minCpuFreq = midFreq,
            maxCpuFreq = maxFreq,
            minGpuPowerLevel = midGpuLevel,
            maxGpuPowerLevel = maxGpuPowerLevel
        )
    }

    /**
     * Write GPU power level to sysfs using PServer root access
     * @param path Sysfs path to write to
     * @param level Power level value in sysfs semantics (0 = fastest for Adreno)
     */
    private fun writeGpuPowerLevel(path: String, level: Int): Boolean {
        if (connectToPServer() == null) {
            Timber.tag(TAG).w("PServer not available to write GPU power level")
            return false
        }

        return try {
            if (!ensureRestorableBaseline()) return false
            if (captureSysfsMode(path) == null) return false
            val command = "chmod 644 '$path' && echo $level > '$path'"
            val result = executeCheckedAsRoot(command)

            if (result.isFailure) {
                Timber.tag(TAG).e("Failed to write GPU power level to $path: ${result.exceptionOrNull()?.message}")
                return false
            }

            if (result.isSuccess) {
                if (path.endsWith("min_pwrlevel")) currentMinGpuSysfsLevel = level
                if (path.endsWith("max_pwrlevel")) currentMaxGpuSysfsLevel = level
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write GPU power level to $path")
            false
        }
    }

    private fun stageGpuPowerLevel(path: String, level: Int): Boolean {
        if (!captureBatchSnapshot(path)) return false
        batchFilePaths.add(path)
        batchCommands.add("echo '$level' > '$path'")
        return true
    }

    // ========================================
    // Policy-Based CPU Control (GameMode-inspired)
    // ========================================

    /**
     * Discover CPU policies by resolving symlinks.
     * Inspired by GameMode's realpath() approach to eliminate redundant writes.
     *
     * Benefits:
     * - Reduces IPC calls by 50-75% on devices with shared policies
     * - Eliminates redundant writes to CPUs sharing the same policy
     * - More robust against race conditions
     */
    private fun discoverCpuPolicies(): List<CpuPolicy> {
        val policies = mutableMapOf<String, MutableList<Int>>()
        val numCpus = getNumCpus()

        Timber.tag(TAG).d("Discovering CPU policies for $numCpus cores")

        for (cpu in 0 until numCpus) {
            val governorSymlink = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor"

            try {
                // Resolve symlink to find the actual policy directory
                val governorRealPath = File(governorSymlink).canonicalPath

                // Extract policy directory from real path
                val policyDir = File(governorRealPath).parent ?: continue

                // Group CPUs by their policy directory
                if (!policies.containsKey(policyDir)) {
                    policies[policyDir] = mutableListOf()
                }
                policies[policyDir]?.add(cpu)

            } catch (e: Exception) {
                // Fallback: treat as individual policy
                val policyDir = "$CPU_BASE_PATH/cpu$cpu/cpufreq"
                if (!policies.containsKey(policyDir)) {
                    policies[policyDir] = mutableListOf()
                }
                policies[policyDir]?.add(cpu)
            }
        }

        // Convert to CpuPolicy objects and read max frequency for each
        val policyList = policies.entries.mapIndexed { index, (policyDir, cpuList) ->
            val maxFreq = try {
                readSysfsFile("$policyDir/cpuinfo_max_freq")?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }

            CpuPolicy(
                policyId = index,
                governorPath = "$policyDir/scaling_governor",
                minFreqPath = "$policyDir/scaling_min_freq",
                maxFreqPath = "$policyDir/scaling_max_freq",
                cpuCores = cpuList.sorted(),
                maxFrequency = maxFreq
            )
        }

        if (policyList.isNotEmpty()) {
            Timber.tag(TAG).i("Discovered ${policyList.size} CPU policies:")
            policyList.forEach { policy ->
                Timber.tag(TAG).i("  Policy ${policy.policyId}: CPUs ${policy.cpuCores.joinToString()} (max: ${policy.maxFrequency / 1000} MHz)")
            }
        }

        return policyList
    }

    /**
     * Validate CPU frequency scaling support.
     * Helps diagnose issues like disabled cpufreq in BIOS/kernel.
     */
    private fun validateCpuFreqSupport(): Boolean {
        val checks = mapOf(
            "CPU base directory" to CPU_BASE_PATH,
            "CPUFreq directory" to CPUFREQ_PATH,
            "Policy0 directory" to POLICY0_PATH,
            "Policy0 governor" to "$POLICY0_PATH/scaling_governor"
        )

        var allValid = true
        val results = mutableListOf<String>()

        for ((name, path) in checks) {
            val valid = File(path).exists()
            val status = if (valid) "✓" else "✗"
            results.add("  $status $name")

            if (!valid) {
                allValid = false
            }
        }

        if (!allValid) {
            Timber.tag(TAG).w("CPU frequency scaling validation:")
            results.forEach { Timber.tag(TAG).w(it) }
            Timber.tag(TAG).w(
                "CPU frequency scaling may be disabled. " +
                "Check kernel config or device settings."
            )
        } else {
            Timber.tag(TAG).d("CPU frequency scaling validation: All checks passed")
        }

        return allValid
    }

    /**
     * Identify CPU clusters based on max frequencies.
     * Categorizes CPUs into EFFICIENCY, PERFORMANCE, and PRIME clusters.
     */
    private fun identifyCpuClusters(): Map<CpuCluster, List<Int>> {
        if (cpuPolicies.isEmpty()) {
            Timber.tag(TAG).w("Cannot identify clusters: no policies discovered")
            return emptyMap()
        }

        // Read max frequencies for each policy
        val policiesWithFreq = cpuPolicies.map { policy ->
            val maxFreq = try {
                readSysfsFile("${policy.governorPath.substringBeforeLast("/")}/cpuinfo_max_freq")
                    ?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }
            policy.copy(maxFrequency = maxFreq)
        }.sortedBy { it.maxFrequency }

        val clusters = mutableMapOf<CpuCluster, MutableList<Int>>()

        when (policiesWithFreq.size) {
            1 -> {
                // Single cluster - all cores are same type
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[0].cpuCores.toMutableList()
            }
            2 -> {
                // Dual cluster (big.LITTLE)
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[1].cpuCores.toMutableList()
            }
            3 -> {
                // Tri-cluster (efficiency + performance + prime)
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[1].cpuCores.toMutableList()
                clusters[CpuCluster.PRIME] = policiesWithFreq[2].cpuCores.toMutableList()
            }
            else -> {
                // 4+ clusters - group by frequency ranges
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PRIME] = policiesWithFreq.last().cpuCores.toMutableList()

                val perfCores = mutableListOf<Int>()
                for (i in 1 until policiesWithFreq.size - 1) {
                    perfCores.addAll(policiesWithFreq[i].cpuCores)
                }
                clusters[CpuCluster.PERFORMANCE] = perfCores
            }
        }

        Timber.tag(TAG).i("Identified CPU clusters:")
        clusters.forEach { (cluster, cores) ->
            val freq = policiesWithFreq.find { cores.intersect(it.cpuCores.toSet()).isNotEmpty() }?.maxFrequency ?: 0
            Timber.tag(TAG).i("  $cluster: CPUs ${cores.joinToString()} @ ${freq / 1000} MHz")
        }

        return clusters
    }

    // ========================================
    // CPU Affinity / Process Pinning
    // ========================================

    /**
     * Get the list of CPU core numbers for a specific cluster.
     *
     * @param cluster The CPU cluster type
     * @return List of CPU core numbers, or empty list if cluster not found
     */
    fun getCpuCoresByCluster(cluster: CpuCluster): List<Int> {
        return cpuClusters[cluster] ?: emptyList()
    }

    /**
     * Get the number of CPU clusters identified.
     * Used to determine optimal pinning strategy.
     *
     * @return Number of clusters (1, 2, or 3+)
     */
    fun getCpuClusterCount(): Int {
        return cpuClusters.size
    }

    /**
     * Pin a process to specific CPU cores using taskset.
     *
     * @param pid Process ID to pin
     * @param cpuMask CPU affinity mask (e.g., "0xff" for CPUs 0-7, "0x80" for CPU 7 only)
     * @return true if successful
     */
    fun setCpuAffinity(pid: Int, cpuMask: String): Boolean {
        if (connectToPServer() == null) {
            Timber.tag(TAG).w("PServer not available for CPU affinity")
            return false
        }

        return try {
            // taskset -p only re-pins the main thread; iterate all TIDs so threads the
            // process already spawned (render/worker threads) are pinned too.
            val command = "for t in /proc/$pid/task/*; do taskset -p '$cpuMask' \"\${t##*/}\" 2>/dev/null; done"
            val result = executeAsRoot(command)

            if (result.isSuccess) {
                Timber.tag(TAG).i("Set CPU affinity for PID $pid to mask $cpuMask")
                true
            } else {
                Timber.tag(TAG).e("Failed to set CPU affinity: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set CPU affinity for PID $pid")
            false
        }
    }

    /**
     * Pin a process to specific CPU cores by core list.
     *
     * @param pid Process ID to pin
     * @param cpuList List of CPU core numbers (e.g., listOf(3, 4, 5, 6, 7))
     * @return true if successful
     */
    fun setCpuAffinityByCores(pid: Int, cpuList: List<Int>): Boolean {
        if (cpuList.isEmpty()) {
            Timber.tag(TAG).w("Empty CPU list provided")
            return false
        }

        // Convert CPU list to bitmask
        // e.g., [3,4,5,6,7] -> 0xf8 (binary: 11111000)
        val mask = cpuList.fold(0) { acc, cpu -> acc or (1 shl cpu) }
        val hexMask = getTasksetMask(mask)

        return setCpuAffinity(pid, hexMask)
    }

    /**
     * Get the correct taskset mask format for this system.
     * Detects once and caches the result.
     *
     * @param mask Bitmask value (e.g., 0xf8 = 248)
     * @return Formatted mask string (e.g., "f8" or "0xf8")
     */
    fun getTasksetMask(mask: Int): String {
        // Return cached format if already detected
        if (tasksetMaskFormat != null) {
            return when (tasksetMaskFormat) {
                TasksetMaskFormat.PLAIN_HEX -> mask.toString(16)
                TasksetMaskFormat.HEX_PREFIX -> "0x${mask.toString(16)}"
                else -> mask.toString(16)
            }
        }

        // Detect format by testing with a simple command
        try {
            val testMask = "0x1"  // Test with 0x prefix
            val command = arrayOf("sh", "-c", "taskset $testMask echo test 2>&1")
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            tasksetMaskFormat = if (process.exitValue() != 0) {
                // 0x prefix failed, use plain hex
                Timber.tag(TAG).d("Detected taskset format: plain hex (no 0x prefix)")
                TasksetMaskFormat.PLAIN_HEX
            } else {
                // 0x prefix works
                Timber.tag(TAG).d("Detected taskset format: hex with 0x prefix")
                TasksetMaskFormat.HEX_PREFIX
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect taskset format, defaulting to plain hex")
            tasksetMaskFormat = TasksetMaskFormat.PLAIN_HEX
        }

        // Return formatted mask
        return when (tasksetMaskFormat) {
            TasksetMaskFormat.PLAIN_HEX -> mask.toString(16)
            TasksetMaskFormat.HEX_PREFIX -> "0x${mask.toString(16)}"
            else -> mask.toString(16)
        }
    }

    /**
     * Get the process ID for a given package name or process name.
     *
     * @param packageName Package name (e.g., "app.gamenative") or process name
     * @return Process ID or null if not found
     */
    fun getProcessId(packageName: String): Int? {
        return try {
            val result = executeAsRoot("pidof '$packageName'")
            result.getOrNull()?.trim()?.toIntOrNull()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get PID for $packageName")
            null
        }
    }

    /**
     * Find Running processes searching command line.
     * This is more reliable for Wine processes than pidof.
     *
     * @return List of pairs containing process ID and command line
     */
    private fun findRunningProcesses(): List<Pair<Int, String>> {
        return try {
            val command = arrayOf("sh", "-c", "ps -eo pid=,args= | awk '{ pid=\$1; \$1=\"\"; sub(/^ /, \"\"); print pid \"|\" \$0 }'")
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

            if (output.isNullOrEmpty()) {
                return emptyList()
            }

            val processes = output.lines().mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) {
                    return@mapNotNull null
                }

                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val cmdline = parts[1]

                if (cmdline.contains("ps -eo", ignoreCase = true)) {
                    return@mapNotNull null
                }

                Pair(pid, cmdline)
            }

            processes
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to find Running processe")
            emptyList()
        }
    }

    /**
     * Find Running processes by searching for executable name.
     * This is more reliable for Wine processes than pidof.
     *
     * @param executableName x name (e.g., "YookaLaylee64.exe")
     * @return List of pairs containing process ID and command line
     */
    fun findRunningProcesses(executableName: String): List<Pair<Int, String>> {
        return try {
            val allProcesses = findRunningProcesses()
            val matchingProcesses = allProcesses.filter { (_, cmdline) ->
                cmdline.contains(executableName, ignoreCase = false)
            }

            if (matchingProcesses.isNotEmpty()) {
                Timber.tag(TAG).d("Found ${matchingProcesses.size} Wine process(es) for $executableName")
            }

            matchingProcesses
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to find Wine process for $executableName")
            emptyList()
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun getNumCpus(): Int {
        return try {
            val content = readSysfsFile("$CPU_BASE_PATH/present")
            // Format is comma-separated ranges, e.g. "0-7" or "0-3,6-7"
            val total = content?.trim()?.split(",")?.sumOf { range ->
                val bounds = range.split("-")
                if (bounds.size == 2) bounds[1].toInt() - bounds[0].toInt() + 1 else 1
            } ?: 0
            if (total > 0) total else Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    private fun executeAsRoot(cmd: String): Result<String?> {
        val activeBinder = connectToPServer()
            ?: return Result.failure(IllegalStateException("PServer not available"))
        return executeWithBinder(activeBinder, cmd).recoverCatching { failure ->
            if (failure is DeadObjectException) {
                disconnectBinder(activeBinder)
                val reconnectedBinder = connectToPServer()
                    ?: throw failure
                return@recoverCatching executeWithBinder(reconnectedBinder, cmd).getOrThrow()
            }
            throw failure
        }
    }

    private fun executeWithBinder(activeBinder: IBinder, cmd: String): Result<String?> {
        if (!activeBinder.isBinderAlive) {
            disconnectBinder(activeBinder)
            return Result.failure(IllegalStateException("PServer not available"))
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            if (!activeBinder.transact(0, data, reply, 0)) {
                return Result.failure(IllegalStateException("PServer rejected root command"))
            }
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Timber.tag(TAG).e(throwable, "Failed to execute command via PServer: $cmd")
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun executeCheckedAsRoot(cmd: String): Result<String?> {
        val marker = "__GAMENATIVE_EXIT_STATUS:"
        return executeAsRoot(wrapPServerCommandWithExitStatus(cmd)).fold(
            onSuccess = { output ->
                val reply = output ?: return@fold Result.failure(
                    IllegalStateException("PServer did not report root command status"),
                )
                val markerIndex = reply.lastIndexOf(marker)
                if (markerIndex < 0) {
                    Result.failure(IllegalStateException("PServer did not report root command status"))
                } else {
                    val status = reply.substring(markerIndex + marker.length).trim().toIntOrNull()
                    if (status == 0) {
                        Result.success(reply.substring(0, markerIndex).trim())
                    } else {
                        Result.failure(IllegalStateException("PServer root command exited with status $status"))
                    }
                }
            },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    private fun connectToPServer(): IBinder? = synchronized(binderLock) {
        binder?.takeIf { it.isBinderAlive }?.let {
            isPServerAvailable = true
            return it
        }
        disconnectBinder(binder)
        val connectedBinder = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            getService.invoke(serviceManager, "PServerBinder") as? IBinder
        } catch (exception: Exception) {
            Timber.tag(TAG).w("Root service not available: ${exception.message}")
            null
        } ?: return null

        val deathRecipient = IBinder.DeathRecipient {
            disconnectBinder(connectedBinder)
            Timber.tag(TAG).w("PServer binder died; reconnecting on the next operation")
        }
        try {
            connectedBinder.linkToDeath(deathRecipient, 0)
        } catch (exception: Exception) {
            Timber.tag(TAG).w(exception, "Unable to monitor PServer binder")
        }
        binder = connectedBinder
        binderDeathRecipient = deathRecipient
        isPServerAvailable = true
        Timber.tag(TAG).i("PServer service found and available")
        connectedBinder
    }

    private fun disconnectBinder(expectedBinder: IBinder?) = synchronized(binderLock) {
        if (expectedBinder == null || binder !== expectedBinder) return
        binderDeathRecipient?.let { recipient ->
            runCatching { expectedBinder.unlinkToDeath(recipient, 0) }
        }
        binder = null
        binderDeathRecipient = null
        isPServerAvailable = false
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }

    private fun readSysfsFile(path: String): String? {
        // Try using PServer cat command first (works with root permissions)
        if (connectToPServer() != null) {
            return try {
                val result = executeAsRoot("cat '$path'")
                if (result.isSuccess) {
                    result.getOrNull()?.trim()
                } else {
                    Timber.tag(TAG).e("Failed to read $path via PServer: ${result.exceptionOrNull()?.message}")
                    // Fallback: try direct file read
                    tryDirectFileRead(path)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read $path via PServer")
                // Fallback: try direct file read
                tryDirectFileRead(path)
            }
        }

        // Fallback: try direct file read if PServer not available
        return tryDirectFileRead(path)
    }

    private fun tryDirectFileRead(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read $path directly")
            null
        }
    }

    private fun writeSysfsFile(path: String, value: String): Boolean {
        if (connectToPServer() == null) {
            Timber.tag(TAG).w("PServer not available to write to $path")
            return false
        }

        return try {
            if (captureSysfsMode(path) == null) return false
            val command = "chmod 644 '$path' && echo '$value' > '$path'"
            val result = executeCheckedAsRoot(command)

            if (result.isFailure) {
                Timber.tag(TAG).e("Failed to write to $path: ${result.exceptionOrNull()?.message}")
                return false
            }

            result.isSuccess
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write to $path")
            false
        }
    }

    private fun captureBatchSnapshot(path: String): Boolean {
        if (captureSysfsMode(path) == null) {
            batchFailed = true
            return false
        }
        if (path !in batchSnapshots) {
            val originalValue = readSysfsFile(path)
            if (originalValue == null) {
                batchFailed = true
                return false
            } else {
                batchSnapshots[path] = originalValue
                batchFirstMutationPaths.add(path)
            }
        }
        return true
    }

    private fun captureSysfsMode(path: String): String? {
        originalSysfsModes[path]?.let { return it }
        val mode = executeCheckedAsRoot("stat -c %a '$path'").getOrNull()
            ?.trim()
            ?.takeIf { it.matches(Regex("[0-7]{3,4}")) }
            ?: return null
        originalSysfsModes[path] = mode
        return mode
    }

    private fun ensureRestorableBaseline(): Boolean {
        return baselineState.isValid || captureSessionBaseline()
    }

    private fun captureSessionBaseline(): Boolean {
        // Never re-baseline over a live tuned state; a valid baseline is only cleared by stop()
        if (baselineState.isValid) return true
        clearSessionBaseline()
        val cpuCaptured = captureCpuPolicySnapshot()
        val gpuRequired = isGpuSupported()
        val gpuCaptured = cpuCaptured && (!gpuRequired || snapshotGpuState())
        baselineState = PServerSessionBaselineState(cpuCaptured, gpuRequired, gpuCaptured)
        if (!baselineState.isValid) {
            clearSessionBaseline()
            return false
        }
        return true
    }

    private fun clearSessionBaseline() {
        baselineState = PServerSessionBaselineState.INVALID
        originalPolicyStates.clear()
        originalGpuState = null
        originalSysfsModes.clear()
        currentMinGpuSysfsLevel = null
        currentMaxGpuSysfsLevel = null
    }

    private fun captureCpuPolicySnapshot(): Boolean {
        if (hasCompleteCpuPolicySnapshot()) return true
        if (cpuPolicies.isEmpty()) return false

        val snapshots = mutableMapOf<String, PolicySnapshot>()
        for (policy in cpuPolicies) {
            val policyDir = policy.governorPath.substringBeforeLast("/")
            val governor = readSysfsFile("$policyDir/scaling_governor")?.trim()?.takeIf(String::isNotEmpty)
                ?: return false
            val minFrequency = readSysfsFile("$policyDir/scaling_min_freq")?.toLongOrNull()
                ?: return false
            val maxFrequency = readSysfsFile("$policyDir/scaling_max_freq")?.toLongOrNull()
                ?: return false
            if (minFrequency <= 0 || maxFrequency <= 0 || minFrequency > maxFrequency) return false
            snapshots[policy.governorPath] = PolicySnapshot(governor, minFrequency, maxFrequency)
        }
        originalPolicyStates.clear()
        originalPolicyStates.putAll(snapshots)
        return true
    }

    private fun hasCompleteCpuPolicySnapshot(): Boolean {
        return baselineState.isValid && cpuPolicies.isNotEmpty() &&
            originalPolicyStates.size == cpuPolicies.size &&
            cpuPolicies.all { it.governorPath in originalPolicyStates }
    }

    private fun snapshotGpuState(): Boolean {
        if (!isGpuSupported()) return true
        if (originalGpuState != null) return true
        val minPowerLevel = readSysfsFile("$GPU_BASE_PATH/min_pwrlevel")?.toIntOrNull() ?: return false
        val maxPowerLevel = readSysfsFile("$GPU_BASE_PATH/max_pwrlevel")?.toIntOrNull() ?: return false
        if (minPowerLevel < 0 || maxPowerLevel < 0 || minPowerLevel < maxPowerLevel) return false
        originalGpuState = GpuSnapshot(minPowerLevel, maxPowerLevel)
        currentMinGpuSysfsLevel = minPowerLevel
        currentMaxGpuSysfsLevel = maxPowerLevel
        return true
    }

    private fun restoreGpuState(): Boolean {
        if (!isGpuSupported()) return true
        val snapshot = originalGpuState ?: return false
        val currentMin = readSysfsFile("$GPU_BASE_PATH/min_pwrlevel")?.toIntOrNull() ?: return false
        val currentMax = readSysfsFile("$GPU_BASE_PATH/max_pwrlevel")?.toIntOrNull() ?: return false
        return orderedPServerBoundWrites(
            currentMin = currentMin.toLong(),
            currentMax = currentMax.toLong(),
            targetMin = snapshot.minPowerLevel.toLong(),
            targetMax = snapshot.maxPowerLevel.toLong(),
            minMustNotExceedMax = false,
        ).all { bound ->
            if (bound == PServerBound.MIN) {
                writeGpuPowerLevel("$GPU_BASE_PATH/min_pwrlevel", snapshot.minPowerLevel)
            } else {
                writeGpuPowerLevel("$GPU_BASE_PATH/max_pwrlevel", snapshot.maxPowerLevel)
            }
        }
    }

    private fun restoreSysfsModes(): Boolean {
        if (originalSysfsModes.isEmpty()) return true
        return originalSysfsModes.all { (path, mode) ->
            executeCheckedAsRoot("chmod $mode '$path'").isSuccess
        }
    }
}
