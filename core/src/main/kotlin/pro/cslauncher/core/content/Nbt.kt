package pro.cslauncher.core.content

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A parsed NBT tag.
 *
 * The set of types is exactly Minecraft's, because `level.dat` and `servers.dat` are written back by
 * the game: anything this type cannot round-trip would be silently destroyed on save. That is also
 * why arrays are preserved as raw primitives rather than boxed lists.
 */
sealed class Tag {

    data class TByte(val v: Byte) : Tag()
    data class TShort(val v: Short) : Tag()
    data class TInt(val v: Int) : Tag()
    data class TLong(val v: Long) : Tag()
    data class TFloat(val v: Float) : Tag()
    data class TDouble(val v: Double) : Tag()
    class TByteArray(val v: ByteArray) : Tag()
    data class TString(val v: String) : Tag()
    data class TList(val elementType: Int, val v: List<Tag>) : Tag()
    data class TCompound(val v: LinkedHashMap<String, Tag>) : Tag()
    class TIntArray(val v: IntArray) : Tag()
    class TLongArray(val v: LongArray) : Tag()

    val id: Int
        get() = when (this) {
            is TByte -> 1
            is TShort -> 2
            is TInt -> 3
            is TLong -> 4
            is TFloat -> 5
            is TDouble -> 6
            is TByteArray -> 7
            is TString -> 8
            is TList -> 9
            is TCompound -> 10
            is TIntArray -> 11
            is TLongArray -> 12
        }

    /** Typed accessors, so callers never have to pattern-match for the common cases. */
    fun compound(key: String): TCompound? = (this as? TCompound)?.v?.get(key) as? TCompound

    fun string(key: String): String? = ((this as? TCompound)?.v?.get(key) as? TString)?.v

    fun int(key: String): Int? = when (val t = (this as? TCompound)?.v?.get(key)) {
        is TInt -> t.v
        is TByte -> t.v.toInt()
        is TShort -> t.v.toInt()
        else -> null
    }

    fun long(key: String): Long? = when (val t = (this as? TCompound)?.v?.get(key)) {
        is TLong -> t.v
        is TInt -> t.v.toLong()
        else -> null
    }

    fun list(key: String): TList? = (this as? TCompound)?.v?.get(key) as? TList
}

/**
 * NBT reader/writer.
 *
 * Hardening matters here because these files come from the internet (downloaded worlds, server lists
 * from other launchers): every length prefix is bounded, nesting depth is capped, and a corrupted file
 * fails with a clear message instead of allocating a 2 GB array or overflowing the stack.
 */
object Nbt {

    private const val MAX_DEPTH = 512
    private const val MAX_BYTE_ARRAY = 64 shl 20          // 64 MB
    private const val MAX_INT_ARRAY = 16 shl 20
    private const val MAX_LONG_ARRAY = 8 shl 20
    private const val MAX_LIST = 10_000_000

    fun readFile(f: File): Pair<String, Tag.TCompound> = readPath(f.toPath())

    fun readPath(p: Path): Pair<String, Tag.TCompound> {
        BufferedInputStream(Files.newInputStream(p)).use { raw ->
            raw.mark(2)
            val b0 = raw.read()
            val b1 = raw.read()
            raw.reset()
            val stream: InputStream = if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(raw) else raw
            return DataInputStream(stream).use { read(it) }
        }
    }

    fun read(d: DataInput): Pair<String, Tag.TCompound> {
        val type = d.readByte().toInt()
        require(type == 10) { "The root tag is not a compound (type $type)" }
        val name = d.readUTF()
        return name to (readPayload(d, 10, 0) as Tag.TCompound)
    }

    private fun readPayload(d: DataInput, type: Int, depth: Int): Tag {
        require(depth < MAX_DEPTH) { "NBT nesting is too deep" }
        return when (type) {
            1 -> Tag.TByte(d.readByte())
            2 -> Tag.TShort(d.readShort())
            3 -> Tag.TInt(d.readInt())
            4 -> Tag.TLong(d.readLong())
            5 -> Tag.TFloat(d.readFloat())
            6 -> Tag.TDouble(d.readDouble())
            7 -> {
                val n = d.readInt()
                require(n in 0..MAX_BYTE_ARRAY) { "Byte array length $n is out of range" }
                Tag.TByteArray(ByteArray(n).also { d.readFully(it) })
            }
            8 -> Tag.TString(d.readUTF())
            9 -> {
                val elementType = d.readByte().toInt()
                val n = d.readInt()
                require(n in 0..MAX_LIST) { "List length $n is out of range" }
                Tag.TList(elementType, List(n) { readPayload(d, elementType, depth + 1) })
            }
            10 -> {
                val map = LinkedHashMap<String, Tag>()
                while (true) {
                    val t = d.readByte().toInt()
                    if (t == 0) break
                    val key = d.readUTF()
                    map[key] = readPayload(d, t, depth + 1)
                }
                Tag.TCompound(map)
            }
            11 -> {
                val n = d.readInt()
                require(n in 0..MAX_INT_ARRAY) { "Int array length $n is out of range" }
                Tag.TIntArray(IntArray(n) { d.readInt() })
            }
            12 -> {
                val n = d.readInt()
                require(n in 0..MAX_LONG_ARRAY) { "Long array length $n is out of range" }
                Tag.TLongArray(LongArray(n) { d.readLong() })
            }
            else -> throw IOException("Unknown NBT tag type $type")
        }
    }

    fun write(d: DataOutput, name: String, root: Tag.TCompound) {
        d.writeByte(10)
        d.writeUTF(name)
        writePayload(d, root)
    }

    private fun writePayload(d: DataOutput, t: Tag) {
        when (t) {
            is Tag.TByte -> d.writeByte(t.v.toInt())
            is Tag.TShort -> d.writeShort(t.v.toInt())
            is Tag.TInt -> d.writeInt(t.v)
            is Tag.TLong -> d.writeLong(t.v)
            is Tag.TFloat -> d.writeFloat(t.v)
            is Tag.TDouble -> d.writeDouble(t.v)
            is Tag.TByteArray -> {
                d.writeInt(t.v.size)
                d.write(t.v)
            }
            is Tag.TString -> d.writeUTF(t.v)
            is Tag.TList -> {
                d.writeByte(if (t.v.isEmpty()) t.elementType else t.v[0].id)
                d.writeInt(t.v.size)
                t.v.forEach { writePayload(d, it) }
            }
            is Tag.TCompound -> {
                for ((k, v) in t.v) {
                    d.writeByte(v.id)
                    d.writeUTF(k)
                    writePayload(d, v)
                }
                d.writeByte(0)
            }
            is Tag.TIntArray -> {
                d.writeInt(t.v.size)
                t.v.forEach { d.writeInt(it) }
            }
            is Tag.TLongArray -> {
                d.writeInt(t.v.size)
                t.v.forEach { d.writeLong(it) }
            }
        }
    }

    /**
     * Writes through a temporary file and an atomic move. A `level.dat` truncated by a crash is a
     * destroyed world, so the file on disk is always either the old or the new complete version.
     */
    fun writeFile(f: File, name: String, root: Tag.TCompound, gzip: Boolean) = writePath(f.toPath(), name, root, gzip)

    fun writePath(p: Path, name: String, root: Tag.TCompound, gzip: Boolean) {
        val parent = p.parent ?: throw IOException("No parent directory for $p")
        Files.createDirectories(parent)
        val tmp = p.resolveSibling(p.fileName.toString() + ".cs-tmp")
        val base = BufferedOutputStream(Files.newOutputStream(tmp), 64 * 1024)
        val stream = if (gzip) GZIPOutputStream(base) else base
        DataOutputStream(stream).use { write(it, name, root) }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Sets a value in the `Data` compound of a `level.dat`-style root, creating it when absent. */
    fun setInData(root: Tag.TCompound, key: String, value: Tag) {
        val data = root.compound("Data") ?: return
        data.v[key] = value
    }
}

/** Reads an NBT file only if it is a readable compound, otherwise null. Never throws. */
fun readNbtOrNull(p: Path): Tag.TCompound? = runCatching { Nbt.readPath(p).second }.getOrNull()
