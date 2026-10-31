package pro.cslauncher.core.launch

import java.io.File

/**
 * Everything a **desktop** `java` binary would normally get from its launcher, the OS image or `/etc`,
 * assembled for the in-process OpenJDK on Android:
 *
 * * absolute-path preloading of the runtime's own libraries — the app's linker namespace does not
 *   search the runtime directory, and HotSpot later `dlopen`s `libjava`/`libnet`/… by bare name;
 * * a linker search path covering the runtime, the LWJGL natives and the renderer libraries;
 * * `java.home`, `user.home`, `java.io.tmpdir` and the time zone (Android has no TZ database where
 *   the JDK expects it);
 * * a resolver configuration file, because Android has no `/etc/resolv.conf` and the Android OpenJDK
 *   builds read `ext.net.resolvPath` instead.
 *
 * A pure function of its inputs, so it is unit-testable on the host without an Android device.
 */
data class AndroidRuntimeEnv(
    /** absolute path -> is it required (a missing required library is a fatal configuration error). */
    val preload: List<Pair<String, Boolean>>,
    val linkerPath: String,
    val env: Map<String, String>,
    val jvmOptions: List<String>
) {
    /** Missing required libraries, i.e. reasons this environment cannot work. */
    fun missingRequired(): List<String> = preload.filter { it.second && !File(it.first).exists() }.map { it.first }

    companion object {

        /**
         * Load order respects `DT_NEEDED`: libjvm ← libjava ← (libnet, libzip, libjimage) ← libnio;
         * libawt ← headless ← fontmanager.
         */
        val RUNTIME_LIBS = listOf(
            "libjvm.so" to true,
            "libverify.so" to false,
            "libjava.so" to true,
            "libjimage.so" to false,
            "libzip.so" to true,
            "libnet.so" to true,
            "libnio.so" to true,
            "libawt.so" to false,
            "libawt_headless.so" to false,
            "libfreetype.so" to false,
            "libfontmanager.so" to false
        )

        fun build(
            libjvm: String,
            javaMajor: Int,
            nativeDirs: List<String>,
            rendererLibraries: Map<String, String>,
            sessionTmp: String,
            gameDir: String,
            resolvConf: String,
            timeZoneId: String,
            androidRelease: String,
            existingJvmArgs: List<String>,
            /** The app's `libc++_shared.so`; runtimes built with c++_shared (Java 25) need it. */
            cxxShared: String? = null
        ): AndroidRuntimeEnv {
            val jvmFile = File(libjvm)
            // Java 8:  <home>/lib/<arch>/server/libjvm.so  -> libDir = <home>/lib/<arch>
            // Java 9+: <home>/lib/server/libjvm.so         -> libDir = <home>/lib
            val libDir = jvmFile.parentFile.parentFile
            val javaHome = if (javaMajor <= 8) libDir.parentFile.parentFile else libDir.parentFile

            val preload = listOfNotNull(cxxShared?.let { it to false }) + RUNTIME_LIBS.map { (name, required) ->
                (if (name == "libjvm.so") jvmFile else File(libDir, name)).absolutePath to required
            }

            val rendererDirs = rendererLibraries.values.mapNotNull { File(it).parent }
            val linker = (
                listOf(jvmFile.parent.orEmpty(), libDir.absolutePath, File(libDir, "jli").absolutePath) +
                    nativeDirs + rendererDirs
                ).distinct().filter { it.isNotBlank() }.joinToString(":")

            val env = linkedMapOf(
                "JAVA_HOME" to javaHome.absolutePath,
                "LD_LIBRARY_PATH" to linker,
                "TMPDIR" to sessionTmp,
                "HOME" to gameDir
            )

            fun has(prefix: String) = existingJvmArgs.any { it.startsWith(prefix) }
            val options = mutableListOf<String>()
            fun add(key: String, value: String) {
                if (!has("-D$key=")) options += "-D$key=$value"
            }
            add("java.home", javaHome.absolutePath)
            add("user.home", gameDir)
            add("user.timezone", timeZoneId)
            add("os.name", "Linux")
            add("os.version", "Android-$androidRelease")
            add("ext.net.resolvPath", resolvConf)
            // jemalloc is not shipped with the Android JDK builds.
            add("org.lwjgl.system.allocator", "system")
            add("org.lwjgl.vulkan.libname", "libvulkan.so")
            // Forge's early progress window needs a desktop GL context that does not exist here.
            add("fml.earlyprogresswindow", "false")
            // Fabric/Quilt must not fork a second GUI process on Android.
            add("loader.disable_forked_guis", "true")
            add("jna.boot.library.path", nativeDirs.firstOrNull() ?: libDir.absolutePath)
            add("java.awt.headless", "true")
            add("sun.jnu.encoding", "UTF-8")
            if (!has("-XX:ErrorFile")) options += "-XX:ErrorFile=$sessionTmp/../hs_err_pid%p.log"

            return AndroidRuntimeEnv(preload, linker, env, options)
        }

        /**
         * Android exposes DNS servers as `net.dns1…`, and `%`-suffixed IPv6 link-local addresses are
         * not usable in a resolv.conf. Falls back to public resolvers rather than writing an empty file,
         * which makes every hostname lookup fail with an unhelpful "UnknownHostException".
         */
        fun resolvConf(dnsServers: List<String>): String {
            val servers = dnsServers
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.contains('%') }
                .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
            return servers.distinct().joinToString("") { "nameserver $it\n" }
        }
    }
}
