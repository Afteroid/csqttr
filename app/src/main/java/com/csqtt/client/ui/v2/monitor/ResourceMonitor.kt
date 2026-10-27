package com.csqtt.client.ui.v2.monitor

import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.csqtt.client.TunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.RandomAccessFile

data class MemoryBreakdown(
    val javaHeapMb: Float = 0f,
    val nativeHeapMb: Float = 0f,
    val graphicsMb: Float = 0f,
    val codeMb: Float = 0f,
    val stackMb: Float = 0f,
    val privateOtherMb: Float = 0f,
    val systemMb: Float = 0f,
    val totalPssMb: Float = 0f,
)

data class ResourceSnapshot(
    val cpuPercent: Float = 0f,
    val ramMb: Float = 0f,
    val rxSpeedKbps: Float = 0f,
    val txSpeedKbps: Float = 0f,
    val totalRxMb: Float = 0f,
    val totalTxMb: Float = 0f,
    val activeWorkers: Int = 0,
    val isTunnelRunning: Boolean = false,
    val breakdown: MemoryBreakdown = MemoryBreakdown(),
)

data class MeasurementSession(
    val isActive: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val peakCpuPercent: Float = 0f,
    val avgCpuPercent: Float = 0f,
    val startRamMb: Float = 0f,
    val currentRamMb: Float = 0f,
    val peakRamMb: Float = 0f,
    val totalBytesTransferred: Long = 0L,
    val samplesCount: Int = 0,
)

object ResourceMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _snapshot = MutableStateFlow(ResourceSnapshot())
    val snapshot = _snapshot.asStateFlow()

    private val _session = MutableStateFlow(MeasurementSession())
    val session = _session.asStateFlow()

    private var lastCpuTime = -1L
    private var lastAppCpuTime = -1L
    private var lastTrafficSampleTime = -1L
    private var lastRxBytes = -1L
    private var lastTxBytes = -1L

    @Volatile
    private var detailedMemoryEnabled = false
    private val detailedMemoryRequests = java.util.concurrent.atomic.AtomicInteger(0)
    private var detailedSampleTick = 0
    private var lastBreakdown = MemoryBreakdown()

    /** Requests or releases the expensive PSS breakdown sampling (reading /proc/self/smaps). */
    fun setDetailedMemoryEnabled(enabled: Boolean) {
        val count = if (enabled) {
            detailedMemoryRequests.incrementAndGet()
        } else {
            detailedMemoryRequests.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        detailedMemoryEnabled = count > 0
    }

    // Benchmark session accumulators
    private var sessionStartTime = 0L
    private var sessionCpuSum = 0.0
    private var sessionCpuSamples = 0
    private var sessionPeakCpu = 0f
    private var sessionStartRam = 0f
    private var sessionPeakRam = 0f
    private var sessionStartRx = 0L
    private var sessionStartTx = 0L

    init {
        startMonitoring()
    }

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                sample()
                delay(800)
            }
        }
    }

    fun startMeasurement() {
        val currentSnap = _snapshot.value
        val myUid = Process.myUid()
        sessionStartTime = SystemClock.elapsedRealtime()
        sessionCpuSum = 0.0
        sessionCpuSamples = 0
        sessionPeakCpu = currentSnap.cpuPercent
        sessionStartRam = currentSnap.ramMb
        sessionPeakRam = currentSnap.ramMb
        sessionStartRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0)
        sessionStartTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0)

        _session.value = MeasurementSession(
            isActive = true,
            elapsedSeconds = 0,
            peakCpuPercent = sessionPeakCpu,
            avgCpuPercent = currentSnap.cpuPercent,
            startRamMb = sessionStartRam,
            currentRamMb = currentSnap.ramMb,
            peakRamMb = sessionPeakRam,
            totalBytesTransferred = 0,
            samplesCount = 1,
        )
    }

    fun stopMeasurement() {
        _session.value = _session.value.copy(isActive = false)
    }

    fun resetMeasurement() {
        _session.value = MeasurementSession()
        sessionStartTime = 0L
        sessionCpuSum = 0.0
        sessionCpuSamples = 0
        sessionPeakCpu = 0f
        sessionStartRam = 0f
        sessionPeakRam = 0f
        sessionStartRx = 0L
        sessionStartTx = 0L
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val cpu = readProcessCpuPercent()
        val ramMb = readProcessRamMb()

        val myUid = Process.myUid()
        val currentRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0)
        val currentTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0)

        var rxSpeedKbps = 0f
        var txSpeedKbps = 0f

        if (lastTrafficSampleTime > 0 && now > lastTrafficSampleTime) {
            val deltaSec = (now - lastTrafficSampleTime) / 1000f
            if (deltaSec > 0.1f && lastRxBytes >= 0 && lastTxBytes >= 0) {
                rxSpeedKbps = ((currentRx - lastRxBytes) / deltaSec) / 1024f
                txSpeedKbps = ((currentTx - lastTxBytes) / deltaSec) / 1024f
            }
        }

        lastTrafficSampleTime = now
        lastRxBytes = currentRx
        lastTxBytes = currentTx

        val activeWorkers = TunnelManager.activeWorkers.value
        val running = TunnelManager.running.value

        if (detailedMemoryEnabled && detailedSampleTick++ % 6 == 0) {
            lastBreakdown = readMemoryBreakdown()
        }

        val newSnapshot = ResourceSnapshot(
            cpuPercent = cpu,
            ramMb = ramMb,
            rxSpeedKbps = rxSpeedKbps.coerceAtLeast(0f),
            txSpeedKbps = txSpeedKbps.coerceAtLeast(0f),
            totalRxMb = currentRx / (1024f * 1024f),
            totalTxMb = currentTx / (1024f * 1024f),
            activeWorkers = activeWorkers,
            isTunnelRunning = running,
            breakdown = lastBreakdown,
        )
        _snapshot.value = newSnapshot

        // Update active benchmark session
        if (_session.value.isActive && sessionStartTime > 0) {
            val elapsedSec = (now - sessionStartTime) / 1000L
            sessionCpuSum += cpu
            sessionCpuSamples++
            if (cpu > sessionPeakCpu) sessionPeakCpu = cpu
            if (ramMb > sessionPeakRam) sessionPeakRam = ramMb

            val currentSessionRx = (currentRx - sessionStartRx).coerceAtLeast(0)
            val currentSessionTx = (currentTx - sessionStartTx).coerceAtLeast(0)
            val totalSessionBytes = currentSessionRx + currentSessionTx

            _session.value = MeasurementSession(
                isActive = true,
                elapsedSeconds = elapsedSec,
                peakCpuPercent = sessionPeakCpu,
                avgCpuPercent = (sessionCpuSum / sessionCpuSamples.coerceAtLeast(1)).toFloat(),
                startRamMb = sessionStartRam,
                currentRamMb = ramMb,
                peakRamMb = sessionPeakRam,
                totalBytesTransferred = totalSessionBytes,
                samplesCount = sessionCpuSamples,
            )
        }
    }

    private fun readProcessCpuPercent(): Float {
        return runCatching {
            val pid = Process.myPid()
            val reader = RandomAccessFile("/proc/$pid/stat", "r")
            val line = reader.readLine()
            reader.close()

            if (line.isNullOrBlank()) return 0f
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 17) return 0f

            val utime = parts[13].toLongOrNull() ?: 0L
            val stime = parts[14].toLongOrNull() ?: 0L
            val appTime = utime + stime

            val now = SystemClock.elapsedRealtime()

            var cpuPercent = 0f
            if (lastCpuTime > 0 && lastAppCpuTime >= 0 && now > lastCpuTime) {
                val timeDeltaMs = now - lastCpuTime
                val appTimeDeltaTicks = appTime - lastAppCpuTime
                // Typical Linux clock tick is 100 Hz (10ms per tick)
                val appTimeDeltaMs = appTimeDeltaTicks * 10L
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuPercent = ((appTimeDeltaMs.toFloat() / timeDeltaMs) * 100f).coerceIn(0f, 100f * cores)
            }

            lastCpuTime = now
            lastAppCpuTime = appTime
            cpuPercent
        }.getOrDefault(0f)
    }

    private fun readMemoryBreakdown(): MemoryBreakdown = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun kb(key: String): Float =
            (info.getMemoryStat(key)?.toLongOrNull() ?: 0L) / 1024f

        MemoryBreakdown(
            javaHeapMb = kb("summary.java-heap"),
            nativeHeapMb = kb("summary.native-heap"),
            graphicsMb = kb("summary.graphics"),
            codeMb = kb("summary.code"),
            stackMb = kb("summary.stack"),
            privateOtherMb = kb("summary.private-other"),
            systemMb = kb("summary.system"),
            totalPssMb = kb("summary.total-pss"),
        )
    }.getOrDefault(MemoryBreakdown())

    private fun readProcessRamMb(): Float {
        return runCatching {
            // Read from /proc/self/status for accurate VmRSS (physical RAM resident set size)
            val pid = Process.myPid()
            val reader = RandomAccessFile("/proc/$pid/status", "r")
            var line = reader.readLine()
            var rssKb = 0L
            while (line != null) {
                if (line.startsWith("VmRSS:")) {
                    val value = line.substringAfter("VmRSS:").trim().substringBefore(" ").trim()
                    rssKb = value.toLongOrNull() ?: 0L
                    break
                }
                line = reader.readLine()
            }
            reader.close()

            if (rssKb > 0) {
                rssKb / 1024f
            } else {
                // Fallback to Runtime + Native Heap
                val jvmAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val nativeAllocated = Debug.getNativeHeapAllocatedSize()
                (jvmAllocated + nativeAllocated) / (1024f * 1024f)
            }
        }.getOrElse {
            val jvmAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val nativeAllocated = Debug.getNativeHeapAllocatedSize()
            (jvmAllocated + nativeAllocated) / (1024f * 1024f)
        }
    }
}
