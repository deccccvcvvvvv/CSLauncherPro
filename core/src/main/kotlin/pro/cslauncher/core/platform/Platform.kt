package pro.cslauncher.core.platform

import java.util.Locale

/**
 * The platform the core is running on, expressed the way Minecraft metadata asks about it.
 *
 * `osName` uses Mojang's names ("linux", "windows", "osx") — Android reports "linux" because that
 * is what the game's own rules engine expects, while [isAndroid] tells the launcher which native
 * layer to use. Getting this wrong silently removes every `linux` library from the classpath.
 */
data class PlatformInfo(
    val osName: String,
    val arch: Arch,
    val osVersion: String,
    val isAndroid: Boolean,
    /** OpenGL ES major*10+minor, e.g. 32 for GLES 3.2; 0 when the platform could not report. */
    val glesVersion: Int = 0,
    /** Vulkan API level string, e.g. "1.1.128"; null when Vulkan is unavailable. */
    val vulkanApi: String? = null,
    /** Android release (API level), 0 on a desktop host. */
    val androidApi: Int = 0
) {

    enum class Arch(val mojangName: String, val bits: Int, val androidAbi: String?) {
        X86("x86", 32, "x86"),
        X86_64("x86_64", 64, "x86_64"),
        ARM32("arm32", 32, "armeabi-v7a"),
        ARM64("arm64", 64, "arm64-v8a");

        val jvmArch: String
            get() = when (this) {
                X86 -> "i386"
                X86_64 -> "amd64"
                ARM32 -> "arm"
                ARM64 -> "aarch64"
            }

        /** Aliases used by native JAR classifiers: `-amd64`, `-aarch64`, `-arm64`, `-x64` … */
        val aliases: List<String>
            get() = when (this) {
                X86 -> listOf("x86", "i386", "i686")
                X86_64 -> listOf("x86_64", "amd64", "x64")
                ARM32 -> listOf("arm32", "arm", "armv7", "armv7l")
                ARM64 -> listOf("arm64", "aarch64", "armv8")
            }

        companion object {
            fun fromJvm(arch: String): Arch = when (arch.lowercase(Locale.ROOT)) {
                "aarch64", "arm64", "armv8", "armv8l" -> ARM64
                "arm", "armv7", "armv7l", "arm32" -> ARM32
                "x86_64", "amd64", "x64" -> X86_64
                "x86", "i386", "i486", "i586", "i686" -> X86
                else -> if (arch.contains("64")) X86_64 else X86
            }

            fun fromAndroidAbi(abi: String): Arch = when (abi.lowercase(Locale.ROOT)) {
                "arm64-v8a" -> ARM64
                "armeabi-v7a", "armeabi" -> ARM32
                "x86_64" -> X86_64
                "x86" -> X86
                else -> fromJvm(abi)
            }
        }
    }

    val is64Bit get() = arch.bits == 64

    /** The ABI directory name on Android; null on a desktop host. */
    val androidAbi: String? get() = if (isAndroid) arch.androidAbi else null

    /** The bit width token Mojang's `${arch}` placeholder turns into for native classifiers. */
    val archBitsToken: String get() = if (is64Bit) "64" else "32"

    companion object {
        fun host(): PlatformInfo {
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            val name = when {
                os.contains("win") -> "windows"
                os.contains("mac") || os.contains("darwin") -> "osx"
                else -> "linux"
            }
            return PlatformInfo(
                osName = name,
                arch = Arch.fromJvm(System.getProperty("os.arch", "x86_64")),
                osVersion = System.getProperty("os.version", ""),
                isAndroid = false
            )
        }

        /**
         * Android platform. `osName` stays "linux" on purpose: the game's rule engine looks for
         * `linux`, and a separate `android` name would silently drop every LWJGL native.
         */
        fun android(abi: String, release: String, api: Int = 0, glesVersion: Int = 0, vulkanApi: String? = null) =
            PlatformInfo(
                osName = "linux",
                arch = Arch.fromAndroidAbi(abi),
                osVersion = release,
                isAndroid = true,
                glesVersion = glesVersion,
                vulkanApi = vulkanApi,
                androidApi = api
            )

        /** Detects Android at runtime (some tests and tools run the core on a device without wiring). */
        fun autoDetect(): PlatformInfo {
            val vm = System.getProperty("java.vm.name", "")
            val runtime = System.getProperty("java.runtime.name", "")
            val runningOnAndroid = vm.contains("Dalvik", true) || runtime.contains("Android", true)
            if (!runningOnAndroid) return host()
            // The Android layer always injects the real values; this is only a fallback for tools.
            return android("arm64-v8a", System.getProperty("os.version", ""), 0)
        }
    }
}
