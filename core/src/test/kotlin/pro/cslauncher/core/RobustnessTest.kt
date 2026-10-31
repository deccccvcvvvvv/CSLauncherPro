package pro.cslauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.account.AccountStore
import pro.cslauncher.core.account.InMemorySecretStore
import pro.cslauncher.core.account.OfflineIdentity
import pro.cslauncher.core.activity.ActivityItem
import pro.cslauncher.core.activity.ActivityKind
import pro.cslauncher.core.activity.ActivityLog
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.diagnostics.CrashAnalyzer
import pro.cslauncher.core.diagnostics.FailureKind
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.settings.PerformancePreset
import pro.cslauncher.core.renderer.RendererEnvironment
import pro.cslauncher.core.renderer.RendererRegistry
import pro.cslauncher.core.storage.StorageAnalyzer
import pro.cslauncher.core.storage.StorageCategory
import pro.cslauncher.core.util.Redactor
import java.nio.file.Files

class RobustnessTest {

    @Test
    fun archivePathsAreRejectedEverywhere() {
        val dir = Files.createTempDirectory("t")
        for (bad in listOf("C:\\Windows\\x.dll", "c:/x", "\\\\server\\share\\x", "../x", "/abs/x", "")) {
            assertTrue("Must refuse '$bad'", runCatching { pro.cslauncher.core.install.SafeZip.safeTarget(dir, bad) }.isFailure)
        }
        assertTrue(runCatching { pro.cslauncher.core.install.SafeZip.safeTarget(dir, "mods\\a/b.jar") }.isSuccess)
    }

    @Test
    fun offlineIdentityIsStableAndValidated() {
        // The UUID must be a pure function of the name: worlds and server whitelists key off it.
        assertEquals(OfflineIdentity.uuidString("Steve"), OfflineIdentity.uuidString("Steve"))
        assertNotEquals(OfflineIdentity.uuidString("Steve"), OfflineIdentity.uuidString("Alex"))
        // The exact value is not important; that it is the *same* value as other launchers is.
        assertEquals(32, OfflineIdentity.uuidString("Steve").length)
        assertEquals(OfflineIdentity.uuidString("Steve"), OfflineIdentity.uuidString("Steve"))
        assertNull(OfflineIdentity.validate("Steve"))
        assertTrue(OfflineIdentity.validate("") != null)
        assertTrue(OfflineIdentity.validate("bad name!") != null)
        assertTrue(OfflineIdentity.validate("x".repeat(17)) != null)
    }

    @Test
    fun accountStoreKeepsOfflineNamesUniqueAndCleansSecrets() {
        val dirs = TestWorld.dirs()
        val store = AccountStore(dirs.config.resolve("accounts.json"), InMemorySecretStore())
        val account = store.addOffline("Steve")
        assertTrue(account.isOffline)
        assertEquals(account.id, store.active()!!.id)
        assertTrue("A second account with the same name desyncs servers", runCatching { store.addOffline("steve") }.isFailure)
        assertEquals(1, store.accounts().size)
        store.remove(account.id)
        assertNull(store.active())
        assertEquals(0, store.accounts().size)
    }

    @Test
    fun accountsFileSurvivesCorruption() {
        val dirs = TestWorld.dirs()
        val file = dirs.config.resolve("accounts.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "}{ not json")
        val store = AccountStore(file, InMemorySecretStore())
        assertEquals(0, store.accounts().size)
        val backup = Files.list(file.parent).use { s -> s.toList().filter { it.fileName.toString().startsWith("accounts.corrupt-") } }
        assertEquals(1, backup.size)
    }

    @Test
    fun crashAnalyzerNamesTheFailureAndTheFix() {
        val log = """
            [12:00:00] [main/ERROR]: java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 61.0), this version of the Java Runtime only recognizes class file versions up to 52.0
            [12:00:01] [main/ERROR]: Could not find or load main class net.minecraft.client.main.Main
            [12:00:02] [Render thread/ERROR]: java.lang.NoClassDefFoundError: org/lwjgl/glfw/GLFW
        """.trimIndent()
        val diagnoses = CrashAnalyzer.analyze(log, exitCode = 1)
        assertTrue(diagnoses.isNotEmpty())
        assertTrue(diagnoses.any { it.kind == FailureKind.WRONG_JAVA_VERSION })
        assertTrue(diagnoses.any { it.kind == FailureKind.MISSING_LIBRARY || it.kind == FailureKind.MISSING_NATIVE })
        assertTrue(diagnoses.all { it.confidence in 0.0..1.0 })
        assertTrue(diagnoses.all { d -> d.repairs.isNotEmpty() || d.kind == FailureKind.UNKNOWN })
    }

    @Test
    fun healthyLogProducesNoDiagnosis() {
        val diagnoses = CrashAnalyzer.analyze("[12:00:00] [main/INFO]: Stopping!", exitCode = 0)
        assertTrue(diagnoses.isEmpty())
    }

    @Test
    fun redactorHidesEveryCredentialShape() {
        val samples = listOf(
            "Authorization: Bearer abc123def456",
            "access_token=SuperSecretValue123",
            "{\"accessToken\":\"AnotherSecret456\"}",
            "--accessToken YetAnother789"
        )
        for (s in samples) {
            val out = Redactor.redact(s)
            assertFalse("Leaked: $out", Regex("abc123def456|SuperSecretValue123|AnotherSecret456|YetAnother789").containsMatchIn(out))
        }
    }

    @Test
    fun heapRecommendationRespectsTheDevice() {
        val lowEnd = DeviceFacts(totalRamMb = 2048, is64Bit = true, androidApi = 30, cpuCores = 4, lowRamDevice = true)
        val heap = PerformanceAdvisor.recommendedHeapMb(lowEnd)
        assertTrue("Low-RAM devices must get a small heap, got $heap", heap <= PerformanceAdvisor.maxHeapMb(lowEnd))
        assertTrue(heap >= 512)
        val big = DeviceFacts(totalRamMb = 12288, is64Bit = true, androidApi = 34, cpuCores = 8, lowRamDevice = false)
        assertTrue(PerformanceAdvisor.recommendedHeapMb(big) > heap)
        assertTrue(PerformanceAdvisor.recommendedHeapMb(big, modCount = 100) <= PerformanceAdvisor.maxHeapMb(big))
        val bits32 = DeviceFacts(totalRamMb = 8192, is64Bit = false, androidApi = 25, cpuCores = 4, lowRamDevice = false)
        assertEquals(1024, PerformanceAdvisor.maxHeapMb(bits32))
    }

    @Test
    fun oversizedHeapIsFlagged() {
        val device = DeviceFacts(totalRamMb = 3072, is64Bit = true, androidApi = 30, cpuCores = 4, lowRamDevice = false)
        val advice = PerformanceAdvisor.recommendations(device, heapMb = 4096, recentFailures = 0, modCount = 0)
        assertTrue(advice.any { it.title.contains("Memory") })
        assertTrue(PerformanceAdvisor.recommendations(device, 1024, 0, 0).isEmpty())
        assertEquals(30, PerformanceAdvisor.presetFrameCap(PerformancePreset.BATTERY_SAVER))
        assertEquals(0.7f, PerformanceAdvisor.presetRenderScale(PerformancePreset.BATTERY_SAVER), 0.001f)
    }

    @Test
    fun storageReportsAndProtectsUserContent() {
        val dirs = TestWorld.dirs()
        val gameDir = Files.createTempDirectory("gamedir")
        Files.createDirectories(gameDir.resolve("mods"))
        Files.write(gameDir.resolve("mods/a.jar"), ByteArray(10_000))
        Files.createDirectories(gameDir.resolve("saves/w"))
        Files.write(gameDir.resolve("saves/w/level.dat"), ByteArray(40_000))
        val analyzer = StorageAnalyzer(dirs)
        val entries = analyzer.analyze(listOf(gameDir))
        assertTrue(entries.any { it.category == StorageCategory.MODS && it.bytes == 10_000L })
        assertTrue(entries.any { it.category == StorageCategory.WORLDS && it.bytes == 40_000L })
        // Worlds are not regenerable, so clearing them must be refused rather than silently delete.
        assertTrue(runCatching { analyzer.clear(StorageCategory.WORLDS) }.isFailure)
        assertTrue(Files.isRegularFile(gameDir.resolve("saves/w/level.dat")))
    }

    @Test
    fun noComponentsInstalledIsNotAnError() {
        val dirs = TestWorld.dirs()
        assertEquals(0, ComponentManager(dirs).list().size)
        assertNull(ComponentManager(dirs).byId("lwjgl3"))
    }

    @Test
    fun rendererStatusesExplainThemselves() {
        val env = RendererEnvironment(
            platform = TestWorld.platform(android = true),
            androidApi = 30,
            glesVersion = 0,
            vulkanApi = null,
            libraries = emptyMap(),
            gameVersionId = "1.20.1",
            gameUsesLwjgl3 = true,
            gameNeedsGl32Core = true
        )
        val registry = RendererRegistry()
        val statuses = registry.statuses(env)
        assertEquals(registry.backends.size, statuses.size)
        assertTrue(statuses.values.all { it.reason.isNotBlank() })
        // An unknown GLES level must never be treated as unusable.
        assertTrue(env.hasGles(3))
        assertNull(registry.byId("nope"))
    }

    @Test
    fun activityLogIsTrimmedAndOrdered() {
        val dirs = TestWorld.dirs()
        val log = ActivityLog(dirs.config.resolve("activity.jsonl"), maxItems = 5)
        repeat(12) { i -> log.launched("1.20.1", "p$i") }
        val items = log.items(limit = 3)
        assertEquals(3, items.size)
        assertEquals(ActivityKind.LAUNCHED, items.first().kind)
        assertTrue("The newest entry must survive trimming: ${items.first().profileId}", log.items(limit = 100).size <= 6)
        assertTrue(log.items(limit = 100).size >= 4)
        log.record(ActivityItem(kind = ActivityKind.CRASHED, title = "Crash", detail = "token abc123"))
        log.clear()
        assertEquals(0, log.items().size)
    }
}
