package app.gamenative.powercontrol

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.powercontrol.autotuning.PerformanceAutoTuner
import app.gamenative.powercontrol.drivers.NoOpPerformanceDriver
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.powercontrol.drivers.SamsungPerformanceDriver
import app.gamenative.powercontrol.profiles.CpuGovernor
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Volatile
    private var driver: PerformanceDriver? = null
    private var autoTuner: PerformanceAutoTuner? = null
    private val lifecycleLock = Object()
    private val powerOperationLock = ReentrantLock()
    private var hasActiveSession = false
    private var isSessionStarting = false
    private var isSessionStopping = false
    private var isAutoTunerStopping = false
    private var startingSessionGeneration: Long? = null
    private val sessionState = PowerSessionState()

    /**
     * The currently active power profile.
     * Updated when settings change, used for saving on stop.
     */
    var currentProfile: PowerProfile? = null
        private set

    var targetFps: Int = 0
        set(value) {
            // Enforce non-negative values and round/clamp if necessary
            field = value.coerceAtLeast(0)
        }

    var currentFps: Float = 0f
        set(value) {
            // Enforce non-negative values and round/clamp if necessary
            field = value.coerceAtLeast(0f)
        }

    var currentCpuUsage: Float = 0f
        set(value) {
            // Enforce 0-100% range
            field = value.coerceIn(0f, 100f)
        }

    var currentGpuUsage: Float = 0f
        set(value) {
            // Enforce 0-100% range
            field = value.coerceIn(0f, 100f)
        }

    /**
     * Initialize PowerManager with application context.
     * Should be called once during application startup.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (driver != null) return

        driver = when {
            SamsungPerformanceDriver.isSamsungDevice() -> {
                val samsungDriver = SamsungPerformanceDriver(context.applicationContext)
                if (samsungDriver.isDriverSupported()) {
                    Timber.tag("PowerManager").i("Using Samsung Performance Driver")
                    samsungDriver
                } else {
                    Timber.tag("PowerManager").w("Samsung device detected but Performance SDK not available")
                    NoOpPerformanceDriver()
                }
            }
            else -> {
                val pServerDriver = PServerDriver(context.applicationContext)
                if (pServerDriver.isDriverSupported()) {
                    Timber.tag("PowerManager").i("Using PServer Driver")
                    pServerDriver
                } else {
                    Timber.tag("PowerManager").w("No performance driver available")
                    NoOpPerformanceDriver()
                }
            }
        }
    }

    private fun getDriver(): PerformanceDriver {
        driver?.let { return it }
        return synchronized(this) {
            driver ?: NoOpPerformanceDriver().also {
                Timber.tag("PowerManager").w("PowerManager not initialized, using NoOpPerformanceDriver as fallback")
                driver = it
            }
        }
    }

    data class CpuInfo(
        val currentGovernor: String,
        val currentMinValue: Long,
        val currentMaxValue: Long
    )

    data class GpuInfo(
        val currentGpuValue: Long,
        val minGpuPowerLevel: Int,
        val maxGpuPowerLevel: Int,
        val numGpuPowerLevels: Int
    )

    data class BusInfo(
        val minBusLevel: Int,
        val maxBusLevel: Int,
        val numBusLevels: Int
    )

    // ========================================
    // General Settings
    // ========================================

    /**
     * Start the performance driver and restore saved profile if available
     */
    fun start() {
        val startGeneration: Long
        synchronized(lifecycleLock) {
            awaitSessionStopLocked()
            if (hasActiveSession) return
            hasActiveSession = true
            isSessionStarting = true
            startGeneration = sessionState.start()
            startingSessionGeneration = startGeneration
        }

        try {
            powerOperationLock.withLock {
                getDriver().start()
                restoreSavedProfile()
                // Pin PulseAudio to dedicated performance core if PServer is available
                pinPulseAudioToDedicatedCore()
            }
            synchronized(lifecycleLock) { finishSessionStartLocked(startGeneration) }
        } catch (throwable: Throwable) {
            val tuner: PerformanceAutoTuner?
            synchronized(lifecycleLock) {
                if (startingSessionGeneration != startGeneration) throw throwable
                sessionState.invalidate()
                tuner = detachAutoTunerLocked()
            }
            try {
                tuner?.stop()
                powerOperationLock.withLock {
                    getDriver().stop()
                }
            } catch (cleanupFailure: Throwable) {
                throwable.addSuppressed(cleanupFailure)
            } finally {
                synchronized(lifecycleLock) {
                    if (startingSessionGeneration == startGeneration) {
                        hasActiveSession = false
                        isSessionStopping = false
                        finishAutoTunerStopLocked()
                        finishSessionStartLocked(startGeneration)
                    }
                }
            }
            throw throwable
        }
    }

    /**
     * Stop the performance driver and save current profile
     */
    fun stop() {
        val tuner: PerformanceAutoTuner?
        val driverToStop: PerformanceDriver?
        synchronized(lifecycleLock) {
            awaitSessionStopLocked()
            awaitSessionStartLocked()
            awaitAutoTunerStopLocked()
            isSessionStopping = true
            sessionState.invalidate()
            tuner = detachAutoTunerLocked()
            driverToStop = getDriver().takeIf { hasActiveSession }
        }

        try {
            tuner?.stop()
            powerOperationLock.withLock {
                val profileToSave = synchronized(lifecycleLock) { currentProfile?.copy() }
                if (driverToStop != null) {
                    saveProfile(profileToSave)
                    driverToStop.stop()
                }
            }
        } finally {
            synchronized(lifecycleLock) {
                hasActiveSession = false
                finishAutoTunerStopLocked()
                isSessionStopping = false
                lifecycleLock.notifyAll()
            }
        }
    }

    /**
     * Start automatic performance tuning.
     * Uses PID controller to adjust CPU/GPU/Bus frequencies based on targetFps and utilization.
     * Works with any driver that supports CPU frequency and GPU power level control.
     */
    fun startAutoTuning() {
        val driver: PerformanceDriver
        val tunerToken: PowerTunerToken
        synchronized(lifecycleLock) {
            awaitSessionStopLocked()
            awaitAutoTunerStopLocked()
            if (!hasActiveSession) return
            if (autoTuner?.isRunning() == true) {
                Timber.tag("PowerManager").w("Auto-tuning already running")
                return
            }
            driver = getDriver()
            tunerToken = sessionState.newTunerToken() ?: return
        }

        // Check if driver supports required features
        val availableCpuFreqs = driver.getAvailableCpuFrequencies()
        if (availableCpuFreqs.isEmpty()) {
            Timber.tag("PowerManager").w("Auto-tuning requires CPU frequency control")
            return
        }

        val numGpuLevels = if (driver.isGpuSupported()) driver.getNumGpuPowerLevels() else 0
        val numBusLevels = if (driver.isBusSupported()) driver.getNumBusLevels() else 0

        val tuner = PerformanceAutoTuner(
            availableCpuFreqs = availableCpuFreqs,
            numGpuLevels = numGpuLevels,
            numBusLevels = numBusLevels,
            onCpuFrequencyChange = { freq ->
                applyTunerUpdate(tunerToken) {
                    val cpuInfo = getCpuInfo()
                    val currentMinCpuFreq = cpuInfo?.currentMinValue ?: currentProfile?.minCpuFreq ?: freq
                    val currentMaxCpuFreq = cpuInfo?.currentMaxValue ?: currentProfile?.maxCpuFreq ?: freq
                    when {
                        freq > currentMaxCpuFreq -> {
                            maxCpuValue(freq)
                            minCpuValue(freq)
                        }
                        freq < currentMinCpuFreq -> {
                            minCpuValue(freq)
                            maxCpuValue(freq)
                        }
                        else -> {
                            minCpuValue(freq)
                            maxCpuValue(freq)
                        }
                    }
                }
            },
            onGpuLevelChange = { level ->
                applyTunerUpdate(tunerToken) {
                    minGpuPowerLevel(level)
                    maxGpuPowerLevel(level)
                }
            },
            onBusLevelChange = { level ->
                applyTunerUpdate(tunerToken) {
                    minBusLevel(level)
                    maxBusLevel(level)
                }
            },
            getTuningStrategy = {
                synchronized(lifecycleLock) {
                    if (sessionState.isActive(tunerToken)) {
                        currentProfile?.tuningStrategy ?: AutoTuningStrategy.BALANCED
                    } else {
                        AutoTuningStrategy.BALANCED
                    }
                }
            },
            enableLogging = BuildConfig.DEBUG
        )

        synchronized(lifecycleLock) {
            if (
                isSessionStopping ||
                isAutoTunerStopping ||
                !sessionState.isActive(tunerToken) ||
                currentProfile?.enableAutoTuning != true ||
                autoTuner?.isRunning() == true
            ) return
            autoTuner = tuner
            tuner.start()
        }
        Timber.tag("PowerManager").i("Auto-tuning started (CPU freqs: ${availableCpuFreqs.size}, GPU levels: $numGpuLevels, Bus levels: $numBusLevels)")
    }

    /**
     * Stop automatic performance tuning.
     */
    fun stopAutoTuning() {
        val tuner: PerformanceAutoTuner?
        synchronized(lifecycleLock) {
            awaitAutoTunerStopLocked()
            sessionState.invalidateTunerRequests()
            tuner = detachAutoTunerLocked()
        }
        try {
            tuner?.stop()
        } finally {
            synchronized(lifecycleLock) {
                finishAutoTunerStopLocked()
                lifecycleLock.notifyAll()
            }
        }
    }

    /**
     * Update the current profile reference.
     * Should be called when the UI changes the active profile.
     */
    fun setCurrentProfile(profile: PowerProfile) {
        val tuner: PerformanceAutoTuner?
        synchronized(lifecycleLock) {
            if (isSessionStopping) return
            sessionState.invalidateTunerRequests()
            tuner = detachAutoTunerLocked()
        }

        try {
            tuner?.stop()
        } finally {
            synchronized(lifecycleLock) {
                finishAutoTunerStopLocked()
                lifecycleLock.notifyAll()
            }
        }

        val enableAutoTuning = powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (isSessionStopping) return@withLock null
                currentProfile = profile.copy()
                profile.enableAutoTuning
            }
        }
        if (enableAutoTuning == true) startAutoTuning()
    }

    fun applyProfile(profile: PowerProfile): Boolean {
        var enableAutoTuning = false
        val applied = powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (isSessionStopping) return@withLock false
            }
            val success = updateInternal {
                name(profile.name)
                governor(profile.governor.governorName)
                minCpuValue(profile.minCpuFreq)
                maxCpuValue(profile.maxCpuFreq)
                if (isGpuSupported()) {
                    minGpuPowerLevel(profile.minGpuPowerLevel)
                    maxGpuPowerLevel(profile.maxGpuPowerLevel)
                }
                if (isBusSupported()) {
                    minBusLevel(profile.minBusLevel)
                    maxBusLevel(profile.maxBusLevel)
                }
            }
            if (success) {
                synchronized(lifecycleLock) {
                    currentProfile = profile.copy()
                    sessionState.invalidateTunerRequests()
                    enableAutoTuning = profile.enableAutoTuning
                }
            } else {
                enableAutoTuning = false
            }
            success
        }

        if (applied) {
            if (enableAutoTuning) startAutoTuning() else stopAutoTuning()
        }
        return applied
    }

    private fun detachAutoTunerLocked(): PerformanceAutoTuner? {
        isAutoTunerStopping = true
        return autoTuner.also { autoTuner = null }
    }

    private fun applyTunerUpdate(
        tunerToken: PowerTunerToken,
        block: UpdateBuilder.() -> Unit,
    ): Boolean {
        synchronized(lifecycleLock) {
            if (isSessionStopping || !sessionState.isActive(tunerToken)) return false
        }
        return powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (isSessionStopping || !sessionState.isActive(tunerToken)) return@withLock false
            }
            updateInternal(block)
        }
    }

    private fun runProfileOperation(
        defaultValue: Boolean,
        operation: () -> Boolean,
    ): Boolean {
        synchronized(lifecycleLock) {
            if (isSessionStopping) return defaultValue
        }
        return powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (isSessionStopping) return@withLock defaultValue
            }
            operation()
        }
    }

    private fun finishAutoTunerStopLocked() {
        isAutoTunerStopping = false
    }

    private fun finishSessionStartLocked(startGeneration: Long) {
        if (startingSessionGeneration != startGeneration) return
        startingSessionGeneration = null
        isSessionStarting = false
        lifecycleLock.notifyAll()
    }

    private fun awaitSessionStopLocked() {
        waitForLifecycleStateLocked { isSessionStopping }
    }

    private fun awaitSessionStartLocked() {
        waitForLifecycleStateLocked { isSessionStarting }
    }

    private fun awaitAutoTunerStopLocked() {
        waitForLifecycleStateLocked { isAutoTunerStopping }
    }

    private fun waitForLifecycleStateLocked(isStopping: () -> Boolean) {
        var interrupted = false
        while (isStopping()) {
            try {
                lifecycleLock.wait()
            } catch (exception: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Check if the selected performance driver is available.
     */
    fun isDriverAvailable(): Boolean {
        return getDriver().isDriverSupported()
    }

    /**
     * Get display unit preference for frequency values
     */
    fun getDisplayUnit(): PerformanceDriver.DisplayUnit {
        return getDriver().getDisplayUnit()
    }

    /**
     * Begin a batch update session.
     * For PServerDriver, this starts collecting commands to execute in a single call.
     * For SamsungDriver, this is a no-op as CustomParams already handles batching.
     */
    fun beginUpdate() {
        getDriver().beginUpdate()
    }

    /**
     * Commit all pending updates from the batch session.
     * For PServerDriver, this executes all collected commands in a single root call.
     * For SamsungDriver, this is a no-op as each setter already calls start(params).
     */
    fun commit(): Boolean {
        return getDriver().commit()
    }

    fun cancelUpdate() {
        getDriver().cancelUpdate()
    }

    /**
     * Builder for batch updates. Provides a fluent API for setting multiple values.
     * Usage:
     * ```
     * PowerManager.update {
     *     governor(profile.governor.governorName)
     *     minCpuValue(profile.minFreq)
     *     maxCpuValue(profile.maxFreq)
     * }
     * ```
     */
    class UpdateBuilder {
        private var settersSucceeded = true

        fun name(name: String): UpdateBuilder {
            setProfileName(name)
            return this
        }
        fun governor(governor: String): UpdateBuilder {
            settersSucceeded = setGovernor(governor) && settersSucceeded
            return this
        }

        fun minCpuValue(value: Long): UpdateBuilder {
            settersSucceeded = setMinCpuValue(value) && settersSucceeded
            return this
        }

        fun maxCpuValue(value: Long): UpdateBuilder {
            settersSucceeded = setMaxCpuValue(value) && settersSucceeded
            return this
        }

        fun minGpuPowerLevel(level: Int): UpdateBuilder {
            settersSucceeded = setMinGpuPowerLevel(level) && settersSucceeded
            return this
        }

        fun maxGpuPowerLevel(level: Int): UpdateBuilder {
            settersSucceeded = setMaxGpuPowerLevel(level) && settersSucceeded
            return this
        }

        fun minBusLevel(level: Int): UpdateBuilder {
            settersSucceeded = setMinBusLevel(level) && settersSucceeded
            return this
        }

        fun maxBusLevel(level: Int): UpdateBuilder {
            settersSucceeded = setMaxBusLevel(level) && settersSucceeded
            return this
        }

        fun build(): Boolean {
            if (!settersSucceeded) {
                cancelUpdate()
                return false
            }
            return commit()
        }
    }

    /**
     * Execute a batch update using a builder pattern.
     * All updates are collected and executed in a single call for PServerDriver.
     */
    fun update(block: UpdateBuilder.() -> Unit): Boolean {
        return powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (isSessionStopping) return@withLock false
            }
            updateInternal(block)
        }
    }

    private fun updateInternal(block: UpdateBuilder.() -> Unit): Boolean {
        val profileSnapshot = synchronized(lifecycleLock) { currentProfile?.copy() }
        var updateStarted = false
        try {
            beginUpdate()
            updateStarted = true
            val builder = UpdateBuilder()
            builder.block()
            val succeeded = builder.build()
            if (!succeeded) {
                cancelUpdate()
                synchronized(lifecycleLock) { currentProfile = profileSnapshot }
            }
            return succeeded
        } catch (exception: Throwable) {
            if (updateStarted) {
                try {
                    cancelUpdate()
                } catch (cleanupException: Throwable) {
                    exception.addSuppressed(cleanupException)
                }
            }
            synchronized(lifecycleLock) { currentProfile = profileSnapshot }
            throw exception
        }
    }

    // ========================================
    // CPU Control
    // ========================================

    /**
     * Get current CPU information (governor, min/max frequencies)
     */
    fun getCpuInfo(): CpuInfo? {
        return try {
            CpuInfo(
                currentGovernor = getDriver().getCurrentGovernor(),
                currentMinValue = getDriver().getCurrentMinCpuValue(),
                currentMaxValue = getDriver().getCurrentMaxCpuValue()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get CPU info")
            null
        }
    }

    /**
     * Get list of available CPU governors
     */
    fun getAvailableGovernors(): List<String> {
        return getDriver().getAvailableGovernors()
    }

    /**
     * Get list of available CPU frequencies in KHz
     */
    fun getAvailableCpuFrequencies(): List<Long> {
        return getDriver().getAvailableCpuFrequencies()
    }

    fun setProfileName(name: String) {
        powerOperationLock.withLock {
            synchronized(lifecycleLock) {
                if (!isSessionStopping) currentProfile?.name = name
            }
        }
    }

    /**
     * Set CPU governor
     */
    fun setGovernor(governor: String): Boolean {
        return runProfileOperation(false) {
            val result = getDriver().setGovernor(governor)
            if (result) {
                val cpuGovernor = CpuGovernor.fromString(governor)
                if (cpuGovernor != null) {
                    synchronized(lifecycleLock) { currentProfile?.governor = cpuGovernor }
                }
            }
            result
        }
    }

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    fun setMinCpuValue(frequency: Long): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMinCpuValue(frequency)
            if (result) synchronizeProfileCpuBounds(driver)
            result
        }
    }

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    fun setMaxCpuValue(frequency: Long): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMaxCpuValue(frequency)
            if (result) synchronizeProfileCpuBounds(driver)
            result
        }
    }

    // ========================================
    // GPU Control
    // ========================================

    /**
     * Check if GPU control is supported
     */
    fun isGpuSupported(): Boolean {
        return getDriver().isGpuSupported()
    }

    /**
     * Get current GPU information (frequency, power levels)
     */
    fun getGpuInfo(): GpuInfo? {
        return try {
            if (!getDriver().isGpuSupported()) return null
            GpuInfo(
                currentGpuValue = getDriver().getCurrentGpuValue(),
                minGpuPowerLevel = getDriver().getCurrentMinGpuPowerLevel(),
                maxGpuPowerLevel = getDriver().getCurrentMaxGpuPowerLevel(),
                numGpuPowerLevels = getDriver().getNumGpuPowerLevels()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get GPU info")
            null
        }
    }

    /**
     * Get list of available GPU frequencies in KHz
     */
    fun getAvailableGpuFrequencies(): List<Long> {
        return getDriver().getAvailableGpuFrequencies()
    }

    /**
     * Set minimum GPU power level (0 = fastest, higher = slower)
     */
    fun setMinGpuPowerLevel(level: Int): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMinGpuPowerLevel(level)
            if (result) synchronizeProfileGpuBounds(driver)
            result
        }
    }

    /**
     * Set maximum GPU power level (0 = fastest, higher = slower)
     */
    fun setMaxGpuPowerLevel(level: Int): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMaxGpuPowerLevel(level)
            if (result) synchronizeProfileGpuBounds(driver)
            result
        }
    }

    // ========================================
    // RAM Bus Control
    // ========================================

    fun isBusSupported(): Boolean {
        return getDriver().isBusSupported()
    }

    fun getBusInfo(): BusInfo? {
        return try {
            if (!getDriver().isBusSupported()) return null

            BusInfo(
                minBusLevel = getDriver().getCurrentMinBusLevel(),
                maxBusLevel = getDriver().getCurrentMaxBusLevel(),
                numBusLevels = getDriver().getNumBusLevels()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get RAM bus info")
            null
        }
    }

    fun setMinBusLevel(level: Int): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMinBusLevel(level)
            if (result) synchronizeProfileBusBounds(driver)
            result
        }
    }

    fun setMaxBusLevel(level: Int): Boolean {
        return runProfileOperation(false) {
            val driver = getDriver()
            val result = driver.setMaxBusLevel(level)
            if (result) synchronizeProfileBusBounds(driver)
            result
        }
    }

    private fun synchronizeProfileCpuBounds(driver: PerformanceDriver) {
        val minimum = driver.getCurrentMinCpuValue()
        val maximum = driver.getCurrentMaxCpuValue()
        synchronized(lifecycleLock) {
            currentProfile?.let { profile ->
                profile.minCpuFreq = minimum
                profile.maxCpuFreq = maximum
            }
        }
    }

    private fun synchronizeProfileGpuBounds(driver: PerformanceDriver) {
        val minimum = driver.getCurrentMinGpuPowerLevel()
        val maximum = driver.getCurrentMaxGpuPowerLevel()
        synchronized(lifecycleLock) {
            currentProfile?.let { profile ->
                profile.minGpuPowerLevel = minimum
                profile.maxGpuPowerLevel = maximum
            }
        }
    }

    private fun synchronizeProfileBusBounds(driver: PerformanceDriver) {
        val minimum = driver.getCurrentMinBusLevel()
        val maximum = driver.getCurrentMaxBusLevel()
        synchronized(lifecycleLock) {
            currentProfile?.let { profile ->
                profile.minBusLevel = minimum
                profile.maxBusLevel = maximum
            }
        }
    }

    // ========================================
    // Profile Persistence
    // ========================================

    /**
     * Save a power profile to preferences
     */
    fun saveProfile() {
        powerOperationLock.withLock {
            val profile = synchronized(lifecycleLock) { currentProfile?.copy() }
            saveProfile(profile)
        }
    }

    private fun saveProfile(profile: PowerProfile?) {
        try {
            val jsonString = if (profile != null) {
                json.encodeToString(profile.copy(driverId = getDriverId()))
            } else ""
            PrefManager.powerControlProfile = jsonString
            Timber.tag("PowerManager").d("Saved power profile: $jsonString")
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to save power profile")
        }
    }

    // ========================================
    // CPU Affinity / Process Pinning
    // ========================================

    /**
     * Pin PulseAudio daemon to a dedicated core.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to first efficiency/lower-frequency core
     * - Tri-cluster: Pin to first efficiency core
     * - Single-cluster: Pin to first available core
     */
    private fun pinPulseAudioToDedicatedCore() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Give PulseAudio time to start if it wasn't already running
                Thread.sleep(500)

                val audioPid = driver.getProcessId("libpulseaudio.so")
                if (audioPid != null) {
                    val clusterCount = driver.getCpuClusterCount()
                    val effCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.EFFICIENCY)
                    val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)

                    // Choose cores based on cluster configuration
                    val audioCores = when {
                        effCores.isNotEmpty() -> listOf(effCores.first())
                        perfCores.isNotEmpty() -> listOf(perfCores.first())
                        else -> emptyList()
                    }

                    if (audioCores.isNotEmpty()) {
                        val success = driver.setCpuAffinityByCores(audioPid, audioCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned PulseAudio (PID: $audioPid) to CPU ${audioCores.first()} ($clusterCount clusters)")
                        }
                    }
                } else {
                    Timber.tag("PowerManager").d("PulseAudio not found, skipping audio pinning")
                }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin PulseAudio")
            }
        }.start()
    }

    /**
     * Pin Background processes for optimal game performance.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to efficiency/lower-frequency cores to free prime cores for game
     * - Tri-cluster: Pin to efficiency + performance cores, leave prime for game
     * - Single-cluster: Pin to all available cores
     */
    fun pinBackgroundProcesses() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Wait for Wine to fully initialize
                Thread.sleep(2000)

                val clusterCount = driver.getCpuClusterCount()
                val effCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.EFFICIENCY)
                val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)

                // Determine Wine infrastructure cores based on cluster configuration
                val wineCores = when (clusterCount) {
                    1 -> perfCores // Single cluster: use all cores
                    2 -> effCores  // Dual cluster: use lower-frequency cores, save prime for game
                    else -> effCores + perfCores // Tri+ cluster: use eff + perf, save prime for game
                }

                if (wineCores.isEmpty()) {
                    Timber.tag("PowerManager").w("No cores available for Wine pinning")
                    return@Thread
                }

                // Pin wineserver to Wine infrastructure cores (critical for Wine IPC)
                driver.findRunningProcesses("wineserver")
                    .firstOrNull { it.second.endsWith("wineserver") }?.let {
                    val pid = it.first
                    val success = driver.setCpuAffinityByCores(pid, wineCores)
                    if (success) {
                        Timber.tag("PowerManager").i("Pinned wineserver (PID: $pid) to CPUs ${wineCores.joinToString()}")
                    }
                }

                // Pin winhandler to Wine infrastructure cores
                driver.findRunningProcesses("winhandler.exe")
                    .firstOrNull { it.second.endsWith("winhandler.exe") }?.let {
                    val pid = it.first
                    val success = driver.setCpuAffinityByCores(pid, wineCores)
                    if (success) {
                        Timber.tag("PowerManager").i("Pinned winhandler.exe (PID: $pid) to CPUs ${wineCores.joinToString()}")
                    }
                }

                // Pin services.exe to first two Wine infrastructure cores
                driver.findRunningProcesses("services.exe")
                    .firstOrNull { it.second.endsWith("services.exe") }?.let {
                    val pid = it.first
                    val serviceCores = wineCores.take(2)
                    if (serviceCores.isNotEmpty()) {
                        val success = driver.setCpuAffinityByCores(pid, serviceCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned services.exe (PID: $pid) to CPUs ${serviceCores.joinToString()}")
                        }
                    }
                }

                // Pin libsteambootstrap.so to first two Wine infrastructure cores
                driver.findRunningProcesses("libsteambootstrap.so")
                    .firstOrNull { it.second.contains("libsteambootstrap.so") }?.let {
                        val pid = it.first
                        val serviceCores = wineCores.take(2)
                        if (serviceCores.isNotEmpty()) {
                            val success = driver.setCpuAffinityByCores(pid, serviceCores)
                            if (success) {
                                Timber.tag("PowerManager").i("Pinned libsteambootstrap.so (PID: $pid) to CPUs ${serviceCores.joinToString()}")
                            }
                        }
                    }

            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin Wine infrastructure")
            }
        }.start()
    }

    /**
     * Pin a game process with retry logic.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to prime cores only for maximum performance
     * - Tri-cluster: Pin to performance + prime cores
     * - Single-cluster: Pin to all available cores
     *
     * @param processName Process name or package name
     * @param maxRetries Maximum number of retry attempts (default: 10)
     * @param retryDelayMs Delay between retries in milliseconds (default: 1000)
     */
    fun pinGameWithRetry(
        processName: String,
        maxRetries: Int = 10,
        retryDelayMs: Long = 1000
    ) {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                var retries = maxRetries
                val isWineExecutable = processName.endsWith(".exe", ignoreCase = true)

                while (retries > 0) {
                    // Use Wine-specific search for .exe files, regular pidof for others
                    val pid = if (isWineExecutable) {
                        driver.findRunningProcesses(processName).find {
                            it.second.endsWith(processName, ignoreCase = true) &&
                            !it.second.contains("winhandler.exe")
                        }?.first
                    } else {
                        driver.getProcessId(processName)
                    }

                    if (pid != null) {
                        val clusterCount = driver.getCpuClusterCount()
                        val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)
                        val primeCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PRIME)

                        // Determine game cores based on cluster configuration
                        val gameCores = when (clusterCount) {
                            1 -> perfCores // Single cluster: use all cores
                            2 -> primeCores.ifEmpty { perfCores } // Dual: prime only (or perf if no prime)
                            else -> perfCores + primeCores // Tri+: perf + prime, leave efficiency for background
                        }

                        if (gameCores.isNotEmpty()) {
                            val success = driver.setCpuAffinityByCores(pid, gameCores)
                            if (success) {
                                Timber.tag("PowerManager").i(
                                    "Pinned $processName (PID: $pid) to CPUs ${gameCores.joinToString()} ($clusterCount clusters) after ${maxRetries - retries + 1} attempts"
                                )
                            }
                        }
                        return@Thread
                    }
                    Thread.sleep(retryDelayMs)
                    retries--
                }
                Timber.tag("PowerManager").w("Failed to find process after $maxRetries attempts: $processName")
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin game with retry: $processName")
            }
        }.start()
    }

    /**
     * Restore the saved power profile from preferences
     */
    private fun restoreSavedProfile() {
        try {
            val jsonString = PrefManager.powerControlProfile
            if (jsonString.isEmpty()) {
                synchronized(lifecycleLock) {
                    currentProfile = driver?.getDefaultProfile()
                }
                Timber.tag("PowerManager").d("No saved profile to restore")
                return
            }

            val restoredProfile = json.decodeFromString<PowerProfile>(jsonString)
            if (restoredProfile.driverId != null && restoredProfile.driverId != getDriverId()) {
                Timber.tag("PowerManager").w("Saved power profile belongs to a different driver")
                synchronized(lifecycleLock) {
                    currentProfile = getDriver().getDefaultProfile()
                }
                return
            }

            Timber.tag("PowerManager").d("Restoring power profile: $jsonString")

            val success = applyProfile(restoredProfile)

            if (success) {
                Timber.tag("PowerManager").i("Successfully restored power profile")
            } else {
                Timber.tag("PowerManager").w("Failed to restore power profile")
            }

            if (currentProfile?.enableAutoTuning == true) {
                startAutoTuning()
            }
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to restore power profile, falling back to default")
            synchronized(lifecycleLock) {
                currentProfile = getDriver().getDefaultProfile()
            }
        }
    }

    private fun getDriverId(): String = when (getDriver()) {
        is PServerDriver -> "pserver"
        is SamsungPerformanceDriver -> "samsung"
        else -> "noop"
    }
}
