package pro.cslauncher.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.str
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ServerEntry(
    val name: String,
    val host: String,
    val port: Int = 25565,
    val favorite: Boolean = false,
    val acceptTextures: Int? = null,
    val icon: String? = null
) {
    val address: String get() = if (port == 25565) host else "$host:$port"
}

/**
 * Parses what users actually type. Accepts `host`, `host:port`, `[ipv6]:port`, bare IPv6 and
 * internationalised domain names (which must be punycoded before connecting, or the JVM resolves
 * nothing).
 */
object ServerAddress {

    private val HOST = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

    fun parse(input: String): Pair<String, Int>? {
        val s = input.trim()
        if (s.isEmpty() || s.length > 260) return null
        val (hostPart, portPart) = when {
            s.startsWith("[") -> {
                val end = s.indexOf(']')
                if (end < 0) return null
                s.substring(1, end) to s.substring(end + 1).removePrefix(":").ifEmpty { "25565" }
            }
            s.count { it == ':' } == 1 -> s.substringBefore(':') to s.substringAfter(':')
            else -> s to "25565"
        }
        val port = portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (hostPart.isBlank()) return null
        val host = runCatching { IDN.toASCII(hostPart) }.getOrNull() ?: return null
        val looksLikeIpv6 = hostPart.contains(':')
        if (!(HOST.matches(host) || IPV4.matches(host) || (looksLikeIpv6 && hostPart.startsWith("[").not() && looksLikeIpv6))) {
            // A bare IPv6 literal is valid even though it has no letters a hostname would allow.
            if (!looksLikeIpv6) return null
        }
        return host to port
    }

    fun isValid(input: String): Boolean = parse(input) != null

    /** Adds the port only when it is not the default — the form the game itself stores. */
    fun format(host: String, port: Int): String = if (port == 25565) host else "$host:$port"
}

/**
 * `servers.dat` plus a side-car file for the things vanilla does not store.
 *
 * Two decisions worth knowing:
 * * favourites live in `launcher-servers.json`, not inside the NBT: inventing fields in a file the
 *   game parses risks it rejecting the whole list;
 * * before every write the previous file is copied to `servers.dat_old` — the same courtesy the game
 *   itself offers, and the difference between "oops" and "my server list is gone".
 */
class ServerListStore(private val gameDir: Path) {

    private val file: Path get() = gameDir.resolve("servers.dat")
    private val meta: Path get() = gameDir.resolve("launcher-servers.json")

    fun load(): List<ServerEntry> {
        val favorites = favorites()
        if (!Files.isRegularFile(file)) return emptyList()
        val root = readNbtOrNull(file) ?: run {
            CsLog.w("Servers", "servers.dat is unreadable; the list will be shown as empty")
            return emptyList()
        }
        val list = root.list("servers")?.v ?: return emptyList()
        return list.mapNotNull { tag ->
            val c = tag as? Tag.TCompound ?: return@mapNotNull null
            val ip = c.string("ip") ?: return@mapNotNull null
            val (host, port) = ServerAddress.parse(ip) ?: (ip to 25565)
            ServerEntry(
                name = c.string("name") ?: ip,
                host = host,
                port = port,
                favorite = ip in favorites,
                acceptTextures = c.int("acceptTextures"),
                icon = c.string("icon")
            )
        }
    }

    fun save(list: List<ServerEntry>) {
        val tags = list.map { e ->
            val map = LinkedHashMap<String, Tag>()
            map["name"] = Tag.TString(e.name)
            map["ip"] = Tag.TString(e.address)
            e.icon?.let { map["icon"] = Tag.TString(it) }
            e.acceptTextures?.let { map["acceptTextures"] = Tag.TByte(it.toByte()) }
            Tag.TCompound(map)
        }
        val root = Tag.TCompound(linkedMapOf("servers" to Tag.TList(10, tags)))
        Files.createDirectories(gameDir)
        if (Files.isRegularFile(file)) {
            runCatching { Files.copy(file, gameDir.resolve("servers.dat_old"), StandardCopyOption.REPLACE_EXISTING) }
        }
        Nbt.writePath(file, "", root, gzip = false)
        FsUtil.writeAtomic(
            meta,
            CsJson.encodeToString(
                JsonArray.serializer(),
                JsonArray(list.filter { it.favorite }.map { JsonPrimitive(it.address) })
            )
        )
    }

    fun add(entry: ServerEntry): List<ServerEntry> {
        val list = load()
        if (list.any { it.address.equals(entry.address, ignoreCase = true) }) {
            throw IllegalArgumentException("${entry.address} is already in the list")
        }
        val next = list + entry
        save(next)
        return next
    }

    fun remove(address: String): List<ServerEntry> {
        val next = load().filterNot { it.address.equals(address, ignoreCase = true) }
        save(next)
        return next
    }

    fun setFavorite(address: String, favorite: Boolean): List<ServerEntry> {
        val next = load().map { if (it.address.equals(address, true)) it.copy(favorite = favorite) else it }
        save(next)
        return next
    }

    private fun favorites(): Set<String> = runCatching {
        CsJson.parseToJsonElement(FsUtil.readText(meta)).jsonArray.map { it.jsonPrimitive.content }.toSet()
    }.getOrDefault(emptySet())
}

data class ServerStatus(
    val reachable: Boolean,
    val latencyMs: Long?,
    val versionName: String?,
    val protocol: Int?,
    val online: Int?,
    val max: Int?,
    val motd: String?,
    val error: String?
) {
    val note: String
        get() = "A status ping only checks reachability. Joining can still fail on authentication, " +
            "whitelist or version."
}

/**
 * A server-list ping (the same handshake the game's multiplayer screen uses).
 *
 * Deliberately timeout-bound and silent on failure: this runs while the user browses a list, so it
 * must never block the UI or throw. The result is honest about what it is — reachability only.
 */
object ServerPinger {

    fun ping(host: String, port: Int, timeoutMs: Int = 3000): ServerStatus {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // Handshake: protocol 47 is old enough that every modern server replies to status.
                val handshake = java.io.ByteArrayOutputStream()
                DataOutputStream(handshake).use { h ->
                    writeVarInt(h, 0x00)
                    writeVarInt(h, 47)
                    writeString(h, host)
                    h.writeShort(port)
                    writeVarInt(h, 1)   // next state: status
                }
                writeVarInt(out, handshake.size())
                out.write(handshake.toByteArray())
                out.write(byteArrayOf(0x01, 0x00))  // status request
                out.flush()

                readVarInt(input)                                  // packet length
                val packetId = readVarInt(input)
                if (packetId != 0x00) {
                    return ServerStatus(true, null, null, null, null, null, null, "Unexpected reply from the server")
                }
                val json = readString(input, maxLength = 1 shl 20)
                val status = parseStatus(json)
                val latency = (System.nanoTime() - started) / 1_000_000
                status.copy(reachable = true, latencyMs = latency)
            }
        } catch (e: Exception) {
            ServerStatus(false, null, null, null, null, null, null, e.message ?: e::class.java.simpleName)
        }
    }

    fun ping(entry: ServerEntry): ServerStatus = ping(entry.host, entry.port)

    private fun parseStatus(json: String): ServerStatus = runCatching {
        val root = pro.cslauncher.core.util.parseJsonObject(json, "server status")
        val players = root.obj("players")
        val version = root.obj("version")
        val description = root.str("description")
            ?: root.obj("description")?.str("text")
        ServerStatus(
            reachable = true,
            latencyMs = null,
            versionName = version?.str("name"),
            protocol = version?.int("protocol") ?: version?.str("protocol")?.toIntOrNull(),
            online = players?.int("online") ?: players?.str("online")?.toIntOrNull(),
            max = players?.int("max") ?: players?.str("max")?.toIntOrNull(),
            motd = description,
            error = null
        )
    }.getOrElse {
        ServerStatus(true, null, null, null, null, null, null, "The server sent an unreadable status")
    }

    // ---------------------------------------------------------------- protocol helpers

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (true) {
            val b = input.readUnsignedByte()
            value = value or ((b and 0x7F) shl position)
            if (b and 0x80 == 0) return value
            position += 7
            if (position >= 32) throw java.io.IOException("VarInt is too long")
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, maxLength: Int): String {
        val length = readVarInt(input)
        if (length < 0 || length > maxLength) throw java.io.IOException("String length $length is out of range")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
