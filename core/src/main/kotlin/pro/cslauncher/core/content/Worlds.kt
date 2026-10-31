package pro.cslauncher.core.content

import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WorldInfo(
    val dir: Path,
    val folderName: String,
    val levelName: String?,
    val lastPlayedMs: Long?,
    val gameMode: Int?,
    val hardcore: Boolean?,
    val versionName: String?,
    val hasIcon: Boolean,
    val sizeBytes: Long?,
    val readError: String?
) {
    val displayName: String get() = levelName?.takeIf { it.isNotBlank() } ?: folderName

    fun describe(): String = buildString {
        append(displayName)
        versionName?.let { append(" · $it") }
        sizeBytes?.let { append(" · ${FsUtil.human(it)}") }
    }
}

class WorldInUseException(name: String) : IllegalStateException("World '$name' is open in the game. Close it first.")

/**
 * World management for one game directory.
 *
 * The safety rules here are not optional:
 * * a world that is **open in the game** is detected through its `session.lock` and refused — writing
 *   to a live world corrupts it;
 * * only directories inside `saves/` are ever touched, so a crafted path cannot delete something else;
 * * `level.dat` is edited through a backup plus an atomic write, because it is the file that makes a
 *   world a world;
 * * backups are real zips of the whole folder, not a copy of the dat.
 */
class WorldManager(private val gameDir: Path, private val backupsRoot: Path) {

    val savesDir: Path get() = gameDir.resolve("saves")

    fun list(computeSize: Boolean = false): List<WorldInfo> {
        val dir = savesDir
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it.resolve("level.dat")) }
                .map { read(it, computeSize) }
        }.sortedByDescending { it.lastPlayedMs ?: 0 }
    }

    fun read(dir: Path, computeSize: Boolean = false): WorldInfo = try {
        val data = Nbt.readPath(dir.resolve("level.dat")).second.compound("Data")
            ?: error("level.dat has no Data compound")
        WorldInfo(
            dir = dir,
            folderName = dir.fileName.toString(),
            levelName = data.string("LevelName"),
            lastPlayedMs = data.long("LastPlayed"),
            gameMode = data.int("GameType"),
            hardcore = data.int("hardcore")?.let { it != 0 },
            versionName = data.compound("Version")?.string("Name"),
            hasIcon = Files.isRegularFile(dir.resolve("icon.png")),
            sizeBytes = if (computeSize) FsUtil.sizeOf(dir) else null,
            readError = null
        )
    } catch (e: Exception) {
        WorldInfo(
            dir, dir.fileName.toString(), null, null, null, null, null, false, null,
            "level.dat unreadable: ${e.message}"
        )
    }

    /**
     * True when the game holds the world open. `session.lock` is exactly what the game uses for this,
     * so the check is the same one the game performs.
     */
    fun isInUse(dir: Path): Boolean {
        val lock = dir.resolve("session.lock")
        if (!Files.exists(lock)) return false
        return try {
            FileChannel.open(lock, StandardOpenOption.WRITE).use { ch ->
                val acquired = ch.tryLock()
                if (acquired == null) true else {
                    acquired.release()
                    false
                }
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun guard(dir: Path) {
        require(FsUtil.isInside(savesDir, dir)) { "Not a world of this profile" }
        if (isInUse(dir)) throw WorldInUseException(dir.fileName.toString())
    }

    fun rename(dir: Path, newName: String) {
        guard(dir)
        require(newName.isNotBlank() && newName.length <= 64) { "World names must be 1-64 characters" }
        val levelDat = dir.resolve("level.dat")
        val (rootName, root) = Nbt.readPath(levelDat)
        val data = root.compound("Data") ?: error("level.dat has no Data compound")
        Files.copy(levelDat, dir.resolve("level.dat_cs_backup"), StandardCopyOption.REPLACE_EXISTING)
        data.v["LevelName"] = Tag.TString(newName)
        Nbt.writePath(levelDat, rootName, root, gzip = true)
    }

    fun backup(dir: Path): Path {
        guard(dir)
        val out = backupsRoot.resolve("worlds")
            .resolve("${FsUtil.safeFileName(dir.fileName.toString())}-${System.currentTimeMillis()}.zip")
        Files.createDirectories(out.parent)
        val tmp = out.resolveSibling(out.fileName.toString() + ".part")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zip ->
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        // session.lock is meaningless in a backup and can confuse the game on restore.
                        if (file.fileName.toString() == "session.lock") return@forEach
                        val name = dir.relativize(file).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(name))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        CsLog.i("Worlds", "Backed up ${dir.fileName} to ${out.fileName} (${FsUtil.human(Files.size(out))})")
        return out
    }

    fun backups(): List<Path> {
        val dir = backupsRoot.resolve("worlds")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".zip") }.toList() }
            .sortedByDescending { it.fileName.toString() }
    }

    /**
     * Deletes a world after moving it into the backup folder first. Deleting a world without a backup
     * is never offered, because a mis-tap that erases 300 hours of building is unforgivable.
     */
    fun delete(dir: Path, makeBackup: Boolean = true): Path? {
        guard(dir)
        val backup = if (makeBackup) backup(dir) else null
        FsUtil.deleteTreeWithin(savesDir, dir)
        return backup
    }

    fun duplicate(dir: Path, newFolderName: String): Path {
        guard(dir)
        val cleaned = FsUtil.safeFileName(newFolderName)
        val target = savesDir.resolve(cleaned)
        require(!Files.exists(target)) { "'$cleaned' already exists" }
        Files.createDirectories(target)
        Files.walk(dir).use { stream ->
            stream.forEach { src ->
                val rel = dir.relativize(src)
                val dst = target.resolve(rel.toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst)
                else if (src.fileName.toString() != "session.lock") Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return target
    }

    /** Whatever the game last wrote, for the "continue playing" card. */
    fun mostRecent(): WorldInfo? = list().firstOrNull { it.readError == null }

    fun totalBytes(): Long = list(computeSize = true).sumOf { it.sizeBytes ?: 0 }
}
