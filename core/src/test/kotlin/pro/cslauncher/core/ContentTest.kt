package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.content.ContentIndex
import pro.cslauncher.core.content.ModManager
import pro.cslauncher.core.content.Mrpack
import pro.cslauncher.core.content.ModpackException
import pro.cslauncher.core.content.Nbt
import pro.cslauncher.core.content.ServerAddress
import pro.cslauncher.core.content.Tag
import pro.cslauncher.core.content.WorldManager
import pro.cslauncher.core.profile.LoaderType
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentTest {

    private fun modJar(dir: java.nio.file.Path, name: String, fabricJson: String): java.nio.file.Path {
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(fabricJson.toByteArray())
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun nbtRoundTripsComplexData() {
        val root = Tag.TCompound(
            linkedMapOf(
                "Data" to Tag.TCompound(
                    linkedMapOf(
                        "LevelName" to Tag.TString("My World"),
                        "LastPlayed" to Tag.TLong(1699999999000L),
                        "GameType" to Tag.TInt(1),
                        "hardcore" to Tag.TByte(0),
                        "Seed" to Tag.TLong(-1234567890123L),
                        "list" to Tag.TList(3, listOf(Tag.TInt(1), Tag.TInt(2)))
                    )
                )
            )
        )
        val dir = Files.createTempDirectory("nbt")
        val file = dir.resolve("level.dat")
        Nbt.writePath(file, "", root, gzip = true)
        val (_, reread) = Nbt.readPath(file)
        val data = reread.compound("Data")!!
        assertEquals("My World", data.string("LevelName"))
        assertEquals(1699999999000L, data.long("LastPlayed"))
        assertEquals(1, data.int("GameType"))
        assertEquals(2, data.list("list")!!.v.size)
    }

    @Test
    fun nbtRejectsAbsurdLengths() {
        val dir = Files.createTempDirectory("nbt")
        val file = dir.resolve("bad.dat")
        // type=7 (byte array), name "", then a length of 2 GB.
        val bytes = byteArrayOf(
            10, 0, 0,
            7, 0, 1, 'x'.code.toByte(),
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
        Files.write(file, bytes)
        val failed = try {
            Nbt.readPath(file)
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("A huge declared length must be refused", failed)
    }

    @Test
    fun serverAddressParsing() {
        assertEquals("mc.example.com" to 25565, ServerAddress.parse("mc.example.com"))
        assertEquals("mc.example.com" to 25566, ServerAddress.parse("mc.example.com:25566"))
        assertEquals("::1" to 25565, ServerAddress.parse("[::1]"))
        assertEquals("::1" to 25570, ServerAddress.parse("[::1]:25570"))
        assertNull(ServerAddress.parse("host:99999"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("bad host"))
    }

    @Test
    fun modInspectionReadsFabricMetadata() {
        val gameDir = Files.createTempDirectory("game")
        modJar(
            gameDir.resolve("mods"), "sodium.jar",
            """
            { "schemaVersion": 1, "id": "sodium", "name": "Sodium", "version": "0.5.8",
              "description": "Faster rendering",
              "depends": { "minecraft": "1.20.1", "fabricloader": ">=0.15", "fabric-api": "*" } }
            """.trimIndent()
        )
        val manager = ModManager(gameDir)
        val mods = manager.list()
        assertEquals(1, mods.size)
        val mod = mods.first()
        assertEquals("sodium", mod.modId)
        assertEquals("Sodium", mod.name)
        assertEquals(LoaderType.FABRIC, mod.loaders.first())
        assertFalse(mod.hasNativeCode)
        assertNull(mod.metadataError)
    }

    @Test
    fun disablingRenamesAndKeepsFile() {
        val gameDir = Files.createTempDirectory("game")
        modJar(gameDir.resolve("mods"), "a.jar", """{ "id": "a", "name": "A", "version": "1" }""")
        val manager = ModManager(gameDir)
        val mod = manager.list().first()
        assertTrue(mod.enabled)
        val disabled = manager.setEnabled(mod, false)
        assertTrue(disabled.fileName.toString().endsWith(".disabled"))
        val reloaded = manager.list().first()
        assertFalse(reloaded.enabled)
        val enabledAgain = manager.setEnabled(reloaded, true)
        assertEquals("a.jar", enabledAgain.fileName.toString())
    }

    @Test
    fun compatibilityFlagsMissingDependencyAndWrongLoader() {
        val gameDir = Files.createTempDirectory("game")
        modJar(
            gameDir.resolve("mods"), "dependent.jar",
            """{ "id": "dependent", "name": "Dependent", "version": "1",
                 "depends": { "minecraft": "1.20.1", "fabric-api": "*" } }"""
        )
        val manager = ModManager(gameDir)
        val findings = manager.checkCompatibility("1.20.1", LoaderType.FABRIC, 17)
        assertTrue("A missing required dependency must be reported", findings.any { it.message.contains("fabric-api") })

        val forgeFindings = manager.checkCompatibility("1.20.1", LoaderType.FORGE, 17)
        assertTrue("A Fabric mod on Forge must be flagged", forgeFindings.any { it.message.contains("will not load") })
    }

    @Test
    fun versionRangeMatching() {
        val gameDir = Files.createTempDirectory("game")
        val manager = ModManager(gameDir)
        assertTrue(manager.matchesVersion("1.20.x", "1.20.1"))
        assertFalse(manager.matchesVersion("1.20.x", "1.21"))
        assertTrue(manager.matchesVersion(">=1.19 <1.21", "1.20.4"))
        assertFalse(manager.matchesVersion(">=1.19 <1.21", "1.21.1"))
        assertTrue(manager.matchesVersion("1.20 || 1.21", "1.21"))
    }

    @Test
    fun worldRenameAndBackup() {
        val gameDir = Files.createTempDirectory("game")
        val world = gameDir.resolve("saves/MyWorld")
        Files.createDirectories(world.resolve("region"))
        Files.writeString(world.resolve("region/r.0.0.mca"), "region data")
        val root = Tag.TCompound(linkedMapOf("Data" to Tag.TCompound(linkedMapOf("LevelName" to Tag.TString("Old")))))
        Nbt.writePath(world.resolve("level.dat"), "", root, gzip = true)

        val backups = Files.createTempDirectory("backups")
        val manager = WorldManager(gameDir, backups)
        val listed = manager.list(computeSize = true)
        assertEquals(1, listed.size)
        assertEquals("Old", listed.first().levelName)
        assertTrue(listed.first().sizeBytes!! > 0)

        manager.rename(world, "New Name")
        assertEquals("New Name", manager.list().first().levelName)

        val zip = manager.backup(world)
        assertTrue(Files.isRegularFile(zip))
        assertTrue(Files.size(zip) > 0)
        assertTrue("A backup must never contain session.lock", !java.util.zip.ZipFile(zip.toFile()).use { z -> z.entries().toList().any { it.name.contains("session.lock") } })
    }

    @Test
    fun worldsOutsideSavesAreRefused() {
        val gameDir = Files.createTempDirectory("game")
        val outside = Files.createTempDirectory("outside")
        val manager = WorldManager(gameDir, Files.createTempDirectory("b"))
        val failed = try {
            manager.backup(outside)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun mrpackPathsAreValidated() = runBlocking {
        val bad = listOf("../escape.jar", "/etc/x", "a/../../b", "")
        for (path in bad) {
            val failed = try {
                Mrpack.checkPath(path)
                false
            } catch (_: pro.cslauncher.core.content.ModpackException) {
                true
            }
            assertTrue("Must reject '$path'", failed)
        }
        assertEquals("mods/a.jar", Mrpack.checkPath("mods\\a.jar"))
    }

    @Test
    fun mrpackParsingReadsLoaderAndRejectsUnsignedFiles() {
        val index = """
        { "formatVersion": 1, "game": "minecraft", "name": "Test Pack", "versionId": "1.0.0",
          "dependencies": { "minecraft": "1.20.1", "fabric-loader": "0.15.7" },
          "files": [ { "path": "mods/a.jar", "hashes": {
              "sha1": "${"a".repeat(40)}", "sha512": "${"b".repeat(128)}" },
              "fileSize": 100, "downloads": [ "https://cdn.modrinth.com/a.jar" ] } ] }
        """.trimIndent()
        val parsed = Mrpack.parse(index)
        assertEquals("1.20.1", parsed.minecraftVersion)
        assertEquals(LoaderType.FABRIC, parsed.loader)
        assertEquals(1, parsed.files.size)
        assertEquals("https://cdn.modrinth.com/a.jar", Mrpack.pickUrl(parsed.files.first().downloads))

        val untrusted = try {
            Mrpack.parse(index.replace("cdn.modrinth.com", "evil.example.com"))
            true
        } catch (_: Exception) {
            false
        }
        // Parsing succeeds (the file is listed); the *URL* is what gets refused at plan time.
        assertTrue(untrusted)
        assertNull(Mrpack.pickUrl(listOf("https://evil.example.com/a.jar")))
    }

    @Test
    fun contentIndexForgetsDeletedFiles() {
        val gameDir = Files.createTempDirectory("game")
        val mods = gameDir.resolve("mods")
        Files.createDirectories(mods)
        Files.writeString(mods.resolve("kept.jar"), "x")
        val index = ContentIndex(gameDir)
        index.record(
            listOf(
                pro.cslauncher.core.content.IndexedContent("modrinth", "p1", "Kept", "kept.jar", "MOD", "1.0"),
                pro.cslauncher.core.content.IndexedContent("modrinth", "p2", "Gone", "gone.jar", "MOD", "1.0")
            )
        )
        val loaded = index.load()
        assertEquals(1, loaded.size)
        assertEquals("kept.jar", loaded.first().fileName)
        assertEquals("sodium", ContentIndex.baseName("sodium-0.5.8-fabric.jar"))
    }
}
