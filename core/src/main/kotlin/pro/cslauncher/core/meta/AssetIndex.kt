package pro.cslauncher.core.meta

import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.MetadataException
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str
import pro.cslauncher.core.util.long
import pro.cslauncher.core.util.numberish
import kotlinx.serialization.json.JsonObject

/**
 * One asset. The hash *is* the on-disk name, which makes the local check cheap: a file with the
 * right name and size is treated as present, and only a repair hashes it.
 */
data class AssetObject(val name: String, val hash: String, val size: Long) {
    val relativeObjectPath get() = "${hash.substring(0, 2)}/$hash"

    fun url(base: String = DEFAULT_BASE) = "${base.trimEnd('/')}/$relativeObjectPath"

    companion object {
        /**
         * Asset CDN. Overridable per installer instance: the CDN is frequently slow or blocked in
         * some regions and a mirror must not require patching the code.
         */
        const val DEFAULT_BASE = "https://resources.download.minecraft.net"
    }
}

/**
 * An asset index. Pre-1.7 versions additionally need a `virtual`/`map_to_resources` layout, where
 * each object is also materialised under its logical name.
 */
data class AssetIndex(val objects: List<AssetObject>, val virtual: Boolean, val mapToResources: Boolean) {
    val totalSize get() = objects.sumOf { it.size }

    /** Distinct blobs: several logical names can share one hash. */
    fun uniqueObjects(): List<AssetObject> = objects.distinctBy { it.hash }

    fun byName(): Map<String, AssetObject> = objects.associateBy { it.name }

    companion object {
        private val HASH = Regex("^[0-9a-fA-F]{40}$")

        fun parse(text: String): AssetIndex {
            val o = parseJsonObject(text, "Asset index")
            val objs = o.obj("objects") ?: throw MetadataException("Asset index has no 'objects'")
            val list = objs.map { (name, v) ->
                val e = v as? JsonObject ?: throw MetadataException("Asset '$name' is malformed")
                val hash = e.str("hash") ?: throw MetadataException("Asset '$name' has no hash")
                if (!HASH.matches(hash)) throw MetadataException("Asset '$name' has an invalid hash")
                // Asset names become paths on disk: a hostile index must not escape assets/objects.
                if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                    throw MetadataException("Asset '$name' has an unsafe path")
                }
                AssetObject(name, hash.lowercase(), e.long("size") ?: e.numberish("size") ?: -1)
            }
            if (list.isEmpty()) CsLog.w("AssetIndex", "Asset index contains no objects")
            return AssetIndex(
                objects = list,
                virtual = o["virtual"]?.toString()?.contains("true") == true,
                mapToResources = o["map_to_resources"]?.toString()?.contains("true") == true
            )
        }
    }
}
