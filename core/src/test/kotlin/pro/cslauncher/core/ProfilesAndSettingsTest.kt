package pro.cslauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.profile.GameDirMode
import pro.cslauncher.core.profile.JvmArgs
import pro.cslauncher.core.profile.LoaderSpec
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.profile.ProfileStore
import pro.cslauncher.core.profile.ProfileValidator
import pro.cslauncher.core.settings.LauncherSettings
import pro.cslauncher.core.settings.SettingsStore
import java.nio.file.Files

class ProfilesAndSettingsTest {

    private fun store(): Pair<ProfileStore, pro.cslauncher.core.install.LauncherDirs> {
        val dirs = TestWorld.dirs()
        return ProfileStore(dirs.config.resolve("profiles.json"), dirs) to dirs
    }

    private fun profile(name: String = "Test") = Profile(
        name = name,
        versionId = "1.20.1",
        loader = LoaderSpec(LoaderType.FABRIC, "0.15.7"),
        launchVersionId = "fabric-loader-0.15.7-1.20.1"
    )

    @Test
    fun saveAndReloadRoundTrips() {
        val (store, _) = store()
        val saved = store.upsert(profile())
        val reloaded = store.load()
        assertEquals(1, reloaded.profiles.size)
        assertEquals(saved.profiles.first().id, reloaded.profiles.first().id)
        assertEquals(saved.profiles.first().id, reloaded.selectedId)
    }

    @Test
    fun invalidProfileIsRejected() {
        val (store, _) = store()
        val bad = profile().copy(name = "", renderScale = 2f)
        val problems = ProfileValidator.validate(bad)
        assertTrue(problems.any { it.field == "name" })
        assertTrue(problems.any { it.field == "renderScale" })
        val threw = try {
            store.upsert(bad)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
        assertEquals(0, store.load().profiles.size)
    }

    @Test
    fun duplicateAndDeleteKeepsOthers() {
        val (store, _) = store()
        val original = store.upsert(profile("A")).profiles.first()
        val copy = store.duplicate(original.id, "A copy")
        assertEquals(2, store.load().profiles.size)
        assertFalse(copy.id == original.id)
        assertNull(copy.lastPlayedAtMs)
        store.delete(original.id, deleteGameDir = false)
        val remaining = store.load().profiles
        assertEquals(1, remaining.size)
        assertEquals("A copy", remaining.first().name)
        assertEquals(copy.id, store.load().selectedId)
    }

    @Test
    fun deletionPlanWarnsAboutWorlds() {
        val (store, dirs) = store()
        val p = store.upsert(profile("Worlds")).profiles.first()
        val gameDir = dirs.instances.resolve(p.id)
        Files.createDirectories(gameDir.resolve("saves/world/region"))
        Files.writeString(gameDir.resolve("saves/world/region/r.0.0.mca"), "x".repeat(2048))
        val plan = store.planDeletion(p.id)
        assertTrue(plan.gameDirBytes > 0)
        assertFalse(plan.sharedWithOthers)
        assertTrue(plan.describe().contains("delete its game directory"))
    }

    @Test
    fun sharedGameDirectoryIsNeverDeleted() {
        val (store, dirs) = store()
        val a = store.upsert(profile("A").copy(gameDirMode = GameDirMode.SHARED)).profiles.first()
        val b = store.upsert(profile("B").copy(gameDirMode = GameDirMode.SHARED)).profiles.last()
        val plan = store.planDeletion(a.id)
        assertTrue("Two profiles share the directory", plan.sharedWithOthers)
        assertTrue(plan.describe().contains("no files are deleted"))

        Files.createDirectories(dirs.sharedGameDir.resolve("saves"))
        Files.writeString(dirs.sharedGameDir.resolve("saves/keep.dat"), "important")
        store.delete(a.id, deleteGameDir = true)
        assertTrue("The shared game directory must survive", Files.isRegularFile(dirs.sharedGameDir.resolve("saves/keep.dat")))
        assertEquals(1, store.load().profiles.size)
        assertEquals(b.id, store.load().profiles.first().id)
    }

    @Test
    fun exportImportStripsRuntimeState() {
        val (store, _) = store()
        val p = store.upsert(profile("Share").copy(lastPlayedAtMs = 123L)).profiles.first()
        val json = store.export(p.id)
        assertFalse("Play history must not be exported", json.contains("lastPlayedAtMs\": 123"))
        val imported = store.import(json)
        assertFalse(imported.id == p.id)
        assertEquals(p.name, imported.name)
        assertNull("An imported profile must not point at a loader build the device may not have", imported.launchVersionId)
        assertEquals(2, store.load().profiles.size)
    }

    @Test
    fun migrationFromSchemaOne() {
        val dirs = TestWorld.dirs()
        val file = dirs.config.resolve("profiles.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            { "profiles": [ { "id": "abc", "name": "Old", "mcVersion": "1.16.5", "memory": "2G", "loader": "fabric" } ] }
            """.trimIndent()
        )
        val store = ProfileStore(file, dirs)
        val loaded = store.load()
        assertEquals(1, loaded.profiles.size)
        val migrated = loaded.profiles.first()
        assertEquals("1.16.5", migrated.versionId)
        assertEquals(2048, migrated.memoryMb)
        assertEquals(LoaderType.FABRIC, migrated.loader?.type)
    }

    @Test
    fun corruptFileIsBackedUpNotLost() {
        val dirs = TestWorld.dirs()
        val file = dirs.config.resolve("profiles.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ this is not json")
        val store = ProfileStore(file, dirs)
        assertEquals(0, store.load().profiles.size)
        val backups = Files.list(file.parent).use { s -> s.toList().filter { it.fileName.toString().startsWith("profiles.corrupt-") } }
        assertEquals(1, backups.size)
    }

    @Test
    fun jvmArgsTokenizerHandlesQuotes() {
        assertEquals(listOf("-Xmx2G", "-Dfoo=a b", "-Dbar="), JvmArgs.tokenize("-Xmx2G -Dfoo=\"a b\" -Dbar=''"))
        val stripped = JvmArgs.stripLauncherOwned(listOf("-cp", "x.jar", "-Xmx2G", "-Djava.library.path=/l", "-XX:+UseG1GC"))
        assertEquals(listOf("-XX:+UseG1GC"), stripped)
        val findings = JvmArgs.validate(listOf("-jar", "foo.jar"))
        assertTrue(findings.any { it.severity == JvmArgs.Severity.ERROR })
    }

    @Test
    fun settingsPersistAndSanitize() {
        val dirs = TestWorld.dirs()
        val store = SettingsStore(dirs.config.resolve("settings.json"))
        assertFalse(store.exists())
        store.update { it.copy(downloadConcurrency = 99, theme = pro.cslauncher.core.settings.ThemeChoice.DARK_GRAPHITE) }
        assertEquals(16, store.current.downloadConcurrency)
        val reopened = SettingsStore(dirs.config.resolve("settings.json"))
        assertEquals(pro.cslauncher.core.settings.ThemeChoice.DARK_GRAPHITE, reopened.current.theme)
    }

    @Test
    fun settingsMigrationFromVersionOne() {
        val dirs = TestWorld.dirs()
        val file = dirs.config.resolve("settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{ "schemaVersion": 1, "theme": "PIXEL", "downloadConcurrency": 99 }""")
        val store = SettingsStore(file)
        assertEquals(LauncherSettings.SCHEMA, store.current.schemaVersion)
        assertEquals(pro.cslauncher.core.settings.ThemeChoice.MINECRAFT_LAUNCHER, store.current.theme)
        assertEquals(16, store.current.downloadConcurrency)
    }

    @Test
    fun corruptSettingsFallBackToDefaultsWithBackup() {
        val dirs = TestWorld.dirs()
        val file = dirs.config.resolve("settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "not json")
        val store = SettingsStore(file)
        assertNotNull(store.current)
        assertEquals(pro.cslauncher.core.settings.ThemeChoice.MINECRAFT_LAUNCHER, store.current.theme)
        val backups = Files.list(file.parent).use { s -> s.toList().filter { it.fileName.toString().contains("corrupt") } }
        assertEquals(1, backups.size)
    }
}
