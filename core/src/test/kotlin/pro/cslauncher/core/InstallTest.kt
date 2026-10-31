package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.install.Component
import pro.cslauncher.core.install.InstallException
import pro.cslauncher.core.install.InstallStage
import pro.cslauncher.core.install.Installer
import pro.cslauncher.core.install.Verifier
import pro.cslauncher.core.util.Hashing
import java.nio.file.Files

class InstallTest {

    private val clientBytes = "fake client jar".toByteArray()
    private val libBytes = "fake library".toByteArray()

    /** A real zip containing one .so, so native extraction is exercised for real. */
    private val nativesBytes: ByteArray = java.io.ByteArrayOutputStream().also { buffer ->
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("libtest.so"))
            zip.write("native".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private val metadata = """
    {
      "id": "1.20.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
      "javaVersion": { "majorVersion": 17 },
      "downloads": { "client": { "url": "https://example.invalid/client.jar", "sha1": "${Hashing.sha1(clientBytes)}", "size": ${clientBytes.size} } },
      "arguments": { "game": ["--username", "${'$'}{auth_player_name}"], "jvm": [] },
      "libraries": [
        { "name": "org.example:lib:1.0", "downloads": { "artifact": {
            "url": "https://example.invalid/lib.jar", "sha1": "${Hashing.sha1(libBytes)}", "size": ${libBytes.size},
            "path": "org/example/lib/1.0/lib-1.0.jar" } } },
        { "name": "org.lwjgl:lwjgl:3.3.1", "natives": { "linux": "natives-linux-${'$'}{arch}" },
          "downloads": {
            "artifact": { "url": "https://example.invalid/lwjgl.jar", "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
                          "sha1": "${Hashing.sha1("lwjgl".toByteArray())}", "size": 5 },
            "classifiers": { "natives-linux": { "url": "https://example.invalid/n.jar",
                          "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar",
                          "sha1": "${Hashing.sha1(nativesBytes)}", "size": ${nativesBytes.size} } } } }
      ]
    }
    """.trimIndent()

    private fun setup(android: Boolean = false): Triple<InMemory, Installer, FakeTransport> {
        val dirs = TestWorld.dirs()
        val http = FakeTransport()
            .putBytes("https://example.invalid/client.jar", clientBytes)
            .putBytes("https://example.invalid/lib.jar", libBytes)
            .putBytes("https://example.invalid/n.jar", nativesBytes)
            .putBytes("https://example.invalid/lwjgl.jar", "lwjgl".toByteArray())
        val installer = Installer(dirs, http, http.downloads(), TestWorld.platform(android))
        return Triple(InMemory(dirs, installer, http), installer, http)
    }

    class InMemory(val dirs: pro.cslauncher.core.install.LauncherDirs, val installer: Installer, val http: FakeTransport) {
        fun writeMetadata(text: String) {
            Files.createDirectories(dirs.versionJson("1.20.1").parent)
            Files.writeString(dirs.versionJson("1.20.1"), text)
        }
    }

    @Test
    fun resolveMarksAndroidDesktopNativesAsReplaced() {
        val (env, installer, _) = setup(android = true)
        env.writeMetadata(metadata)
        val rv = runBlocking { installer.resolve("1.20.1", null) }
        assertTrue("glibc natives must be reported as replaced on Android", rv.desktopNativesReplaced.any { it.contains("lwjgl") })
        assertTrue("The glibc native jar must not be installed on Android", rv.nativeLibraries.isEmpty())
        assertEquals("1.20.1", rv.meta.id)
        assertTrue(rv.classpathFiles.isNotEmpty())
    }

    @Test
    fun resolveKeepsNativesOnDesktop() {
        val (env, installer, _) = setup(android = false)
        env.writeMetadata(metadata)
        val rv = runBlocking { installer.resolve("1.20.1", null) }
        assertTrue(rv.nativeLibraries.isNotEmpty())
        assertTrue(rv.desktopNativesReplaced.isEmpty())
    }

    @Test
    fun installDownloadsAndVerifies() = runBlocking {
        val (env, installer, http) = setup()
        env.writeMetadata(metadata)
        val report = installer.install("1.20.1", null)
        assertTrue("Install should verify clean: ${report.summary()}", report.complete)
        assertTrue(Files.isRegularFile(env.dirs.versionJar("1.20.1")))
        assertTrue(Files.isRegularFile(env.dirs.libraryFile("org/example/lib/1.0/lib-1.0.jar")))
        assertTrue(http.requestCount.get() > 0)
    }

    @Test
    fun reinstallDoesNotRedownload() = runBlocking {
        val (env, installer, http) = setup()
        env.writeMetadata(metadata)
        installer.install("1.20.1", null)
        val requestsAfterFirst = http.requestCount.get()
        val second = installer.install("1.20.1", null)
        assertTrue(second.complete)
        assertEquals("An intact install must not hit the network", requestsAfterFirst, http.requestCount.get())
    }

    @Test
    fun corruptFileIsFoundAndRepairable() = runBlocking {
        val (env, installer, _) = setup()
        env.writeMetadata(metadata)
        installer.install("1.20.1", null)
        // Corrupt a library on disk and re-verify: it must be reported with a repair task.
        Files.writeString(env.dirs.libraryFile("org/example/lib/1.0/lib-1.0.jar"), "tampered")
        val rv = installer.resolve("1.20.1", null)
        val report = Verifier(env.dirs, installer, TestWorld.platform()).verify(rv, fullHash = true)
        assertFalse(report.complete)
        assertTrue(report.issues.any { it.component == Component.LIBRARY })
        assertTrue(report.repairable)
        assertTrue(report.repairTasks.isNotEmpty())
    }

    @Test
    fun failureIsStageTagged() = runBlocking {
        val dirs = TestWorld.dirs()
        Files.createDirectories(dirs.versionJson("1.20.1").parent)
        Files.writeString(dirs.versionJson("1.20.1"), metadata)
        val http = FakeTransport()   // nothing resolvable
        val installer = Installer(dirs, http, http.downloads(), TestWorld.platform())
        val error = try {
            installer.install("1.20.1", null)
            null
        } catch (e: InstallException) {
            e
        }
        assertTrue(error != null)
        assertEquals(InstallStage.CLIENT, error!!.stage)
    }
}
