package pro.cslauncher.core.perf

import pro.cslauncher.core.settings.PerformancePreset

/**
 * What the launcher knows about the device without asking Android. Filled in by the app layer and
 * kept as a value so heap decisions are testable on the host.
 */
data class DeviceFacts(
    val totalRamMb: Int,
    val is64Bit: Boolean,
    val androidApi: Int,
    val cpuCores: Int,
    val lowRamDevice: Boolean
) {
    companion object {
        /** Desktop fallback for the desktop launch path. */
        fun desktop(): DeviceFacts {
            val rt = Runtime.getRuntime()
            val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt()
            return DeviceFacts(
                totalRamMb = maxMb,
                is64Bit = System.getProperty("os.arch").orEmpty().contains("64"),
                androidApi = 0,
                cpuCores = rt.availableProcessors(),
                lowRamDevice = maxMb < 2048
            )
        }
    }
}

data class PresetEffect(val setting: String, val value: String)

data class Recommendation(val title: String, val detail: String)

/**
 * Evidence-based defaults. The golden rule: **never allocate all the RAM**. Android, the native heap,
 * the GPU driver and the JVM's own non-heap memory all need room, and a heap that is too large is a
 * silent killer — Android kills the process with no Java exception at all, which is the single most
 * common "random crash" on phones.
 */
object PerformanceAdvisor {

    fun recommendedHeapMb(d: DeviceFacts, modCount: Int = 0, javaMajor: Int = 17): Int {
        val ceiling = maxHeapMb(d)
        var base = when {
            d.totalRamMb <= 3072 -> 768
            d.totalRamMb <= 4096 -> 1024
            d.totalRamMb <= 6144 -> 1536
            d.totalRamMb <= 8192 -> 2048
            else -> 3072
        }
        if (modCount > 0) base += minOf(1024, modCount * 24)
        // Modern JVMs are unhappy below ~1 GB on a device with room to spare.
        if (javaMajor >= 17 && base < 1024 && d.totalRamMb > 3072) base = 1024
        return base.coerceIn(512, ceiling)
    }

    /**
     * Hard ceiling: a 32-bit address space limits the heap to about 1 GB; otherwise at most ~45 % of
     * RAM, minus a 1.5 GB reserve for the system.
     */
    fun maxHeapMb(d: DeviceFacts): Int {
        if (!d.is64Bit) return 1024
        if (d.totalRamMb <= 0) return 1024
        val byFraction = (d.totalRamMb * 0.45).toInt()
        val byReserve = d.totalRamMb - 1536
        return maxOf(512, minOf(byFraction, byReserve, 8192))
    }

    fun presetEffects(p: PerformancePreset): List<PresetEffect> = when (p) {
        PerformancePreset.BATTERY_SAVER -> listOf(
            PresetEffect("Frame-rate cap", "30 FPS"),
            PresetEffect("Render scale", "70%"),
            PresetEffect("Recording preset", "Battery saver")
        )
        PerformancePreset.BALANCED -> listOf(
            PresetEffect("Frame-rate cap", "60 FPS"),
            PresetEffect("Render scale", "85%"),
            PresetEffect("Recording preset", "Balanced")
        )
        PerformancePreset.PERFORMANCE -> listOf(
            PresetEffect("Frame-rate cap", "Unlimited"),
            PresetEffect("Render scale", "100%"),
            PresetEffect("Recording preset", "High quality")
        )
        PerformancePreset.CUSTOM -> emptyList()
    }

    fun presetFrameCap(p: PerformancePreset): Int = when (p) {
        PerformancePreset.BATTERY_SAVER -> 30
        PerformancePreset.BALANCED -> 60
        else -> 0
    }

    fun presetRenderScale(p: PerformancePreset): Float = when (p) {
        PerformancePreset.BATTERY_SAVER -> 0.7f
        PerformancePreset.BALANCED -> 0.85f
        else -> 1f
    }

    fun recommendations(d: DeviceFacts, heapMb: Int, recentFailures: Int, modCount: Int): List<Recommendation> = buildList {
        if (heapMb > maxHeapMb(d)) {
            add(
                Recommendation(
                    "Memory allocation too high",
                    "$heapMb MB exceeds the safe limit of ${maxHeapMb(d)} MB for this device; Android may kill the game."
                )
            )
        }
        if (!d.is64Bit) {
            add(Recommendation("32-bit device", "Heap is limited to about 1 GB. Prefer older versions and few mods."))
        }
        if (d.lowRamDevice) {
            add(Recommendation("Low-RAM device", "Use the Battery Saver preset and a render scale of 70% or less."))
        }
        if (modCount > 40 && heapMb < 2048) {
            add(Recommendation("Many mods", "$modCount mods are installed; consider at least 2048 MB if the device allows it."))
        }
        if (recentFailures >= 2) {
            add(Recommendation("Repeated launch failures", "Open Diagnostics to see the classified cause before retrying."))
        }
    }

    /**
     * JVM flags chosen for a device. Conservative on purpose: each of these has a reason on Android
     * and none of them trades a crash for a small speed-up.
     */
    fun jvmTuning(d: DeviceFacts, heapMb: Int, javaMajor: Int): List<String> = buildList {
        add("-Xmx${heapMb}M")
        add("-Xms${minOf(heapMb, 512)}M")
        if (javaMajor >= 9) {
            add("-XX:+UseG1GC")
            add("-XX:MaxGCPauseMillis=50")
            // On a phone the JIT compiler competes with the render thread for the same cores.
            if (d.cpuCores <= 4) add("-XX:TieredStopAtLevel=1")
        } else {
            add("-XX:+UseConcMarkSweepGC")
        }
        add("-XX:+UnlockExperimentalVMOptions")
        add("-XX:G1NewSizePercent=20")
        add("-XX:G1ReservePercent=20")
        // A crash that does not produce an hs_err file is a crash nobody can diagnose.
        add("-XX:+HeapDumpOnOutOfMemoryError")
    }
}
