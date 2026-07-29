package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.PowerManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Automatic performance tuner that uses PID controllers to adjust CPU, GPU, and RAM bus
 * performance based on target FPS and current utilization metrics.
 *
 * @param availableCpuFreqs List of available CPU frequencies
 * @param numGpuLevels Number of GPU power levels
 * @param numBusLevels Number of RAM bus levels
 * @param onCpuFrequencyChange Callback when CPU frequency changes
 * @param onGpuLevelChange Callback when GPU level changes
 * @param onBusLevelChange Callback when RAM bus level changes
 * @param enableLogging Enable verbose logging of tuning operations
 */
class PerformanceAutoTuner(
    availableCpuFreqs: List<Long>,
    private val numGpuLevels: Int,
    private val numBusLevels: Int,
    private val onCpuFrequencyChange: (Long) -> Boolean,
    private val onGpuLevelChange: (Int) -> Boolean,
    private val onBusLevelChange: (Int) -> Boolean,
    private val getTuningStrategy: () -> AutoTuningStrategy,
    private val enableLogging: Boolean = false,
    private val warmupCycles: Int = WARMUP_CYCLES,
    private val cycleDelayMillis: Long = 2_000L,
) {
    private val availableCpuFreqs = normalizeCpuFrequencies(availableCpuFreqs)

    enum class BottleneckType {
        CPU_BOUND,
        GPU_BOUND,
        BOTH_BOUND,
        MEMORY_BOUND,
        NONE
    }

    companion object {
        private const val TAG = "PerformanceAutoTuner"

        private const val WARMUP_CYCLES = 10 // Around 20 seconds

        // Tuning thresholds
        private const val FPS_ERROR_THRESHOLD = 2.0
        private const val FPS_ERROR_LARGE = 5.0
        private const val USAGE_LOW_THRESHOLD = 70.0
        private const val USAGE_HIGH_THRESHOLD = 85.0
        private const val MIN_PERFORMANCE = 20.0
        private const val MAX_PERFORMANCE = 100.0
        private const val PERFORMANCE_REDUCTION_STEP = 2.0
        private const val ADJUSTMENT_DECAY_FACTOR = 0.3
    }

    /**
     * Get adjustment aggressiveness based on tuning strategy and bottleneck status
     */
    private fun getAdjustmentFactor(isBottleneck: Boolean): Double {
        return when (getTuningStrategy()) {
            AutoTuningStrategy.POWER_EFFICIENT -> if (isBottleneck) 0.5 else ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.BALANCED -> ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.AGGRESSIVE -> if (isBottleneck) 0.7 else ADJUSTMENT_DECAY_FACTOR
            AutoTuningStrategy.CONSERVATIVE -> if (isBottleneck) 0.2 else 0.1
        }
    }

    /**
     * Check if we should reduce non-bottleneck components
     */
    private fun shouldReduceNonBottleneck(): Boolean {
        return when (getTuningStrategy()) {
            AutoTuningStrategy.POWER_EFFICIENT -> true
            AutoTuningStrategy.BALANCED -> false
            AutoTuningStrategy.AGGRESSIVE -> false
            AutoTuningStrategy.CONSERVATIVE -> false
        }
    }

    private var cpuPidController: PidController? = null
    private var gpuPidController: PidController? = null
    private var busPidController: PidController? = null
    private var currentCpuPerformance: Double = 50.0
    private var currentGpuPerformance: Double = 50.0
    private var currentBusPerformance: Double = 50.0
    private val warmupCycleCounter = WarmupCycleCounter(warmupCycles)
    private val lastAppliedCpuFrequency = LastAppliedValue<Long>()
    private val lastAppliedGpuLevel = LastAppliedValue<Int>()
    private val lastAppliedBusLevel = LastAppliedValue<Int>()
    @Volatile private var isRunning: Boolean = false
    private val cycleLock = ReentrantLock()
    @Volatile
    private var tuningThread: Thread? = null
    private var currentBottleneck: BottleneckType = BottleneckType.NONE

    /**
     * Start the auto-tuning process
     */
    @Synchronized
    fun start() {
        if (isRunning) {
            Timber.tag(TAG).w("Auto-tuning already running")
            return
        }

        if (availableCpuFreqs.isEmpty()) {
            Timber.tag(TAG).e("No CPU frequencies available for auto-tuning")
            return
        }

        cycleLock.withLock {
            val minCpuFreq = availableCpuFreqs.first().toDouble()
            val maxCpuFreq = availableCpuFreqs.last().toDouble()

            Timber.tag(TAG).i("Starting auto-tuning (CPU: $minCpuFreq-$maxCpuFreq kHz, GPU levels: $numGpuLevels, Bus levels: $numBusLevels)")

            cpuPidController = PidController(
                kp = 0.5,
                ki = 0.2,
                kd = 0.1,
                outputMin = -100.0,
                outputMax = 100.0,
                integralLimit = 50.0,
                tag = "CpuPidController",
                enableLogging = false
            )

            if (numGpuLevels > 0) {
                gpuPidController = PidController(
                    kp = 0.5,
                    ki = 0.2,
                    kd = 0.1,
                    outputMin = -100.0,
                    outputMax = 100.0,
                    integralLimit = 50.0,
                    tag = "GpuPidController",
                    enableLogging = false
                )
            }

            if (numBusLevels > 0) {
                busPidController = PidController(
                    kp = 0.5,
                    ki = 0.2,
                    kd = 0.1,
                    outputMin = -100.0,
                    outputMax = 100.0,
                    integralLimit = 50.0,
                    tag = "BusPidController",
                    enableLogging = false
                )
            }

            resetSessionState()
            isRunning = true

            val worker = Thread {
                try {
                    while (canRunCycle()) {
                        cycleLock.withLock {
                            if (canRunCycle()) performTuningCycle()
                        }
                        Thread.sleep(cycleDelayMillis)
                    }
                } catch (e: InterruptedException) {
                    if (enableLogging) {
                        Timber.tag(TAG).i("Auto-tuning thread interrupted")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Auto-tuning error")
                } finally {
                    isRunning = false
                    if (tuningThread === Thread.currentThread()) {
                        tuningThread = null
                        resetControllers()
                    }
                    Timber.tag(TAG).i("Auto-tuning stopped")
                }
            }
            worker.name = "PerformanceAutoTuner"
            worker.priority = Thread.NORM_PRIORITY
            tuningThread = worker
            worker.start()
        }
    }

    /**
     * Stop the auto-tuning process
     */
    @Synchronized
    fun stop() {
        val worker = tuningThread ?: return
        isRunning = false
        worker.interrupt()
        if (worker !== Thread.currentThread()) {
            joinUninterruptibly(worker)
            tuningThread = null
        }

        if (enableLogging) {
            Timber.tag(TAG).i("Auto-tuning stopped and reset")
        }
    }

    /**
     * Perform one tuning cycle
     */
    private fun performTuningCycle() {
        if (!canRunCycle()) return
        if (warmupCycleCounter.shouldSkipCycle()) return

        val targetFps = PowerManager.targetFps.toDouble()
        val currentFps = PowerManager.currentFps.toDouble()

        // Skip tuning when targetFps is 0 (FPS limiter disabled) or currentFps is 0
        if (targetFps == 0.0 || currentFps == 0.0) {
            return
        }

        val cpuUsage = PowerManager.currentCpuUsage.toDouble()
        val gpuUsage = PowerManager.currentGpuUsage.toDouble()
        val fpsError = abs(targetFps - currentFps)

        currentBottleneck = detectBottleneck(cpuUsage, gpuUsage, fpsError)

        if (!canRunCycle()) return
        tuneCpu(targetFps, currentFps)
        if (!canRunCycle()) return
        tuneGpu(targetFps, currentFps)
        if (!canRunCycle()) return
        tuneBus(targetFps, currentFps)
    }

    /**
     * Tune CPU frequency based on FPS and CPU utilization
     */
    private fun tuneCpu(targetFps: Double, currentFps: Double) {
        if (!canRunCycle()) return
        cpuPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)
            val cpuUsage = PowerManager.currentCpuUsage.toDouble()

            // Check if CPU is the bottleneck
            val isCpuBottleneck = currentBottleneck == BottleneckType.CPU_BOUND ||
                                  currentBottleneck == BottleneckType.BOTH_BOUND
            val isNotCpuBottleneck = currentBottleneck == BottleneckType.GPU_BOUND ||
                                     currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS with low CPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && cpuUsage < USAGE_LOW_THRESHOLD && currentCpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentCpuPerformance = (currentCpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If CPU is clearly not the bottleneck, reduce it to save power (only for POWER_EFFICIENT)
            else if (shouldReduceNonBottleneck() && isNotCpuBottleneck && fpsError > FPS_ERROR_THRESHOLD && currentCpuPerformance > MIN_PERFORMANCE + 10.0) {
                currentCpuPerformance = (currentCpuPerformance - PERFORMANCE_REDUCTION_STEP * 0.5).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If CPU is the bottleneck, increase performance based on strategy
            else if (isCpuBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentCpuPerformance = (currentCpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If CPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || cpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentCpuPerformance = (currentCpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to actual CPU frequency
            val closestFreq = mapCpuPerformanceToFrequency(availableCpuFreqs, currentCpuPerformance)

            if (!canRunCycle()) return
            val didApply = lastAppliedCpuFrequency.applyIfChanged(closestFreq, onCpuFrequencyChange)
            if (enableLogging && didApply) {
                Timber.tag(TAG).d(
                    "CPU: FPS=%.1f/%.1f, usage=%.1f%%, perf=%.1f%%, freq=%d kHz",
                    currentFps, targetFps, cpuUsage, currentCpuPerformance, closestFreq
                )
            }
        }
    }

    /**
     * Tune GPU power level based on FPS and GPU utilization
     */
    private fun tuneGpu(targetFps: Double, currentFps: Double) {
        if (!canRunCycle()) return
        if (numGpuLevels <= 0) return

        gpuPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)
            val gpuUsage = PowerManager.currentGpuUsage.toDouble()

            // Check if GPU is the bottleneck
            val isGpuBottleneck = currentBottleneck == BottleneckType.GPU_BOUND ||
                                  currentBottleneck == BottleneckType.BOTH_BOUND
            val isNotGpuBottleneck = currentBottleneck == BottleneckType.CPU_BOUND ||
                                     currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS with low GPU usage, reduce performance
            if (fpsError < FPS_ERROR_THRESHOLD && gpuUsage < USAGE_LOW_THRESHOLD && currentGpuPerformance > MIN_PERFORMANCE + 5.0) {
                currentGpuPerformance = (currentGpuPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If GPU is clearly not the bottleneck, reduce it to save power (only for POWER_EFFICIENT)
            else if (shouldReduceNonBottleneck() && isNotGpuBottleneck && fpsError > FPS_ERROR_THRESHOLD && currentGpuPerformance > MIN_PERFORMANCE + 10.0) {
                currentGpuPerformance = (currentGpuPerformance - PERFORMANCE_REDUCTION_STEP * 0.5).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If GPU is the bottleneck, increase performance based on strategy
            else if (isGpuBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentGpuPerformance = (currentGpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If GPU usage is high but not hitting target, increase performance
            else if (fpsError > FPS_ERROR_LARGE || gpuUsage > USAGE_HIGH_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentGpuPerformance = (currentGpuPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to UI-friendly GPU power level (higher = better performance)
            val targetLevel = (currentGpuPerformance * (numGpuLevels - 1) / 100.0).toInt()
            val gpuLevel = targetLevel.coerceIn(0, numGpuLevels - 1)

            if (!canRunCycle()) return
            val didApply = lastAppliedGpuLevel.applyIfChanged(gpuLevel, onGpuLevelChange)
            if (enableLogging && didApply) {
                Timber.tag(TAG).d(
                    "GPU: FPS=%.1f/%.1f, usage=%.1f%%, perf=%.1f%%, level=%d",
                    currentFps, targetFps, gpuUsage, currentGpuPerformance, gpuLevel
                )
            }
        }
    }

    /**
     * Tune RAM bus level based on FPS
     */
    private fun tuneBus(targetFps: Double, currentFps: Double) {
        if (!canRunCycle()) return
        if (numBusLevels <= 0) return

        busPidController?.let { controller ->
            val fpsError = abs(targetFps - currentFps)

            // Check if memory/bus is the bottleneck
            val isMemoryBottleneck = currentBottleneck == BottleneckType.MEMORY_BOUND

            // If we're hitting target FPS, reduce bus performance to save power
            if (fpsError < FPS_ERROR_THRESHOLD && currentBusPerformance > MIN_PERFORMANCE + 5.0) {
                currentBusPerformance = (currentBusPerformance - PERFORMANCE_REDUCTION_STEP).coerceAtLeast(MIN_PERFORMANCE)
                controller.reset()
            }
            // If memory is the bottleneck, increase bus performance based on strategy
            else if (isMemoryBottleneck && fpsError > FPS_ERROR_THRESHOLD) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = true)
                currentBusPerformance = (currentBusPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // If we're missing target FPS, increase bus performance
            else if (fpsError > FPS_ERROR_LARGE) {
                val adjustment = controller.calculate(targetFps, currentFps)
                val aggressiveness = getAdjustmentFactor(isBottleneck = false)
                currentBusPerformance = (currentBusPerformance + adjustment * aggressiveness).coerceIn(MIN_PERFORMANCE, MAX_PERFORMANCE)
            }
            // Otherwise maintain current performance
            else {
                controller.reset()
            }

            // Map percentage to UI-friendly bus level (higher = better performance)
            val targetLevel = (currentBusPerformance * (numBusLevels - 1) / 100.0).toInt()
            val busLevel = targetLevel.coerceIn(0, numBusLevels - 1)

            if (!canRunCycle()) return
            val didApply = lastAppliedBusLevel.applyIfChanged(busLevel, onBusLevelChange)
            if (enableLogging && didApply) {
                Timber.tag(TAG).d(
                    "Bus: FPS=%.1f/%.1f, perf=%.1f%%, level=%d",
                    currentFps, targetFps, currentBusPerformance, busLevel
                )
            }
        }
    }

    /**
     * Detect performance bottleneck based on CPU/GPU usage and FPS error
     * Takes into account which components are supported by the driver
     */
    private fun detectBottleneck(cpuUsage: Double, gpuUsage: Double, fpsError: Double): BottleneckType {
        val isMissingTarget = fpsError > FPS_ERROR_THRESHOLD

        if (!isMissingTarget) return BottleneckType.NONE

        val hasGpuSupport = numGpuLevels > 0
        val hasBusSupport = numBusLevels > 0

        val cpuHigh = cpuUsage > USAGE_HIGH_THRESHOLD
        val gpuHigh = gpuUsage > USAGE_HIGH_THRESHOLD
        val cpuLow = cpuUsage < USAGE_LOW_THRESHOLD
        val gpuLow = gpuUsage < USAGE_LOW_THRESHOLD

        return when {
            // Both CPU and GPU are bottlenecks (only if GPU is supported)
            hasGpuSupport && cpuHigh && gpuHigh -> BottleneckType.BOTH_BOUND

            // CPU is the bottleneck
            cpuHigh && (!hasGpuSupport || gpuLow) -> BottleneckType.CPU_BOUND

            // GPU is the bottleneck (only if GPU is supported)
            hasGpuSupport && gpuHigh && cpuLow -> BottleneckType.GPU_BOUND

            // Memory/Bus bottleneck - both CPU and GPU have headroom (only if bus is supported)
            hasBusSupport && cpuLow && (!hasGpuSupport || gpuLow) -> BottleneckType.MEMORY_BOUND

            // Unknown bottleneck or no clear pattern
            else -> BottleneckType.NONE
        }
    }

    private fun resetSessionState() {
        currentCpuPerformance = 50.0
        currentGpuPerformance = 50.0
        currentBusPerformance = 50.0
        currentBottleneck = BottleneckType.NONE
        warmupCycleCounter.reset()
        lastAppliedCpuFrequency.reset()
        lastAppliedGpuLevel.reset()
        lastAppliedBusLevel.reset()
    }

    private fun canRunCycle(): Boolean =
        isRunning && tuningThread === Thread.currentThread() && !Thread.currentThread().isInterrupted

    private fun joinUninterruptibly(worker: Thread) {
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (exception: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun resetControllers() {
        cpuPidController?.reset()
        gpuPidController?.reset()
        busPidController?.reset()
        cpuPidController = null
        gpuPidController = null
        busPidController = null
        resetSessionState()
    }

    /**
     * Check if auto-tuning is currently running
     */
    fun isRunning(): Boolean = isRunning
}

internal fun normalizeCpuFrequencies(frequencies: List<Long>): List<Long> = frequencies.distinct().sorted()

internal fun mapCpuPerformanceToFrequency(frequencies: List<Long>, performance: Double): Long {
    val minFrequency = frequencies.first()
    val maxFrequency = frequencies.last()
    val targetFrequency = minFrequency + ((maxFrequency - minFrequency) * performance / 100.0)
    return frequencies.minByOrNull { abs(it - targetFrequency.toLong()) } ?: targetFrequency.toLong()
}

internal class WarmupCycleCounter(private val cyclesToSkip: Int) {
    private var completedCycles = 0

    fun shouldSkipCycle(): Boolean {
        if (completedCycles >= cyclesToSkip) return false
        completedCycles += 1
        return true
    }

    fun reset() {
        completedCycles = 0
    }
}

internal class LastAppliedValue<T> {
    private var value: T? = null

    fun applyIfChanged(nextValue: T, apply: (T) -> Boolean): Boolean {
        if (value == nextValue) return false
        if (!apply(nextValue)) return false
        value = nextValue
        return true
    }

    fun reset() {
        value = null
    }
}
