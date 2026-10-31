package pro.cslauncher.core.controls

import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layouts on disk: one JSON file per layout, named after its id.
 *
 * The built-in default is **always** available even if its file is missing or broken — a launcher that
 * cannot render controls is unusable, so the fallback is the shipped default rather than an empty
 * layout. A corrupt user layout is preserved as `.corrupt-*` instead of being overwritten.
 */
class ControlLayoutStore(private val dir: Path) {

    companion object {
        const val DEFAULT_ID = "default-touch"
        const val INDEX_FILE = "layouts.json"
    }

    private val idPattern = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")

    fun list(): List<ControlLayout> {
        val saved = if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { stream ->
                stream.toList()
                    .filter { it.fileName.toString().endsWith(".json") && it.fileName.toString() != INDEX_FILE }
                    .mapNotNull { path -> load(path)?.takeIf { it.id == path.fileName.toString().removeSuffix(".json") } }
            }
        }
        val default = saved.firstOrNull { it.id == DEFAULT_ID } ?: DefaultLayouts.defaultTouch()
        return listOf(default) + saved.filter { it.id != DEFAULT_ID }.sortedBy { it.name.lowercase() }
    }

    private fun load(path: Path): ControlLayout? = runCatching {
        ControlLayoutCodec.decode(FsUtil.readText(path))
    }.onFailure { e ->
        CsLog.w("Controls", "Layout ${path.fileName} is unusable and will be ignored: ${e.message}")
        runCatching { Files.copy(path, path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")) }
    }.getOrNull()

    fun get(id: String): ControlLayout? = list().firstOrNull { it.id == id }

    /** The layout to use for a profile: the requested one, or the default when it is missing. */
    fun getOrDefinitive(id: String): ControlLayout = get(id) ?: list().first()

    fun save(layout: ControlLayout): ControlLayout {
        require(idPattern.matches(layout.id)) { "Invalid layout id '${layout.id}'" }
        val problems = ControlLayoutCodec.validate(layout)
        if (problems.isNotEmpty()) throw LayoutValidationException(problems)
        Files.createDirectories(dir)
        FsUtil.writeAtomic(dir.resolve("${layout.id}.json"), ControlLayoutCodec.encode(layout))
        return layout
    }

    fun delete(id: String): Boolean {
        require(id != DEFAULT_ID) { "The default layout cannot be deleted" }
        val file = dir.resolve("$id.json")
        return runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
    }

    fun duplicate(sourceId: String, newId: String, newName: String): ControlLayout {
        val source = get(sourceId) ?: throw IllegalArgumentException("No layout '$sourceId'")
        require(get(newId) == null) { "A layout called '$newId' already exists" }
        val copy = source.copy(id = newId, name = newName, formatVersion = ControlLayoutCodec.FORMAT)
        return save(copy)
    }

    /** Imports a layout file the user picked. Fails closed: a bad file is rejected, not half-applied. */
    fun import(path: Path): ControlLayout {
        val layout = ControlLayoutCodec.decode(FsUtil.readText(path))
        require(get(layout.id) == null) { "A layout called '${layout.name}' already exists" }
        return save(layout)
    }

    fun export(id: String, target: Path): Path {
        val layout = get(id) ?: throw IllegalArgumentException("No layout '$id'")
        FsUtil.writeAtomic(target, ControlLayoutCodec.encode(layout))
        return target
    }
}
