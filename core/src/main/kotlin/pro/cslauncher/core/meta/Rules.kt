package pro.cslauncher.core.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.str

/** Feature flags the launcher can present to Mojang's rule engine. */
data class LaunchFeatures(
    val isDemoUser: Boolean = false,
    val hasCustomResolution: Boolean = false,
    val hasQuickPlaysSupport: Boolean = false,
    val isQuickPlaySingleplayer: Boolean = false,
    val isQuickPlayMultiplayer: Boolean = false,
    val isQuickPlayRealms: Boolean = false
) {
    fun asMap(): Map<String, Boolean> = buildMap {
        put("is_demo_user", isDemoUser)
        put("has_custom_resolution", hasCustomResolution)
        put("has_quick_plays_support", hasQuickPlaysSupport)
        put("is_quick_play_singleplayer", isQuickPlaySingleplayer)
        put("is_quick_play_multiplayer", isQuickPlayMultiplayer)
        put("is_quick_play_realms", isQuickPlayRealms)
    }
}

/**
 * One entry of a `rules` array. Mojang's semantics: with no rules an entry is allowed; otherwise
 * the **last matching rule wins** and the default is deny. This is implemented once here because
 * every consumer (libraries, arguments, mods) must agree, or a version launches with a broken
 * classpath.
 */
data class Rule(
    val allow: Boolean,
    val osName: String?,
    val osVersion: String?,
    val osArch: String?,
    val features: Map<String, Boolean>
) {

    fun matches(p: PlatformInfo, f: LaunchFeatures): Boolean {
        if (osName != null && !osName.equals(p.osName, ignoreCase = true)) return false

        if (osArch != null) {
            val ok = when (osArch.lowercase()) {
                "x86" -> p.arch == PlatformInfo.Arch.X86
                "x86_64", "amd64", "x64" -> p.arch == PlatformInfo.Arch.X86_64
                "arm64", "aarch64", "armv8" -> p.arch == PlatformInfo.Arch.ARM64
                "arm", "arm32", "armv7" -> p.arch == PlatformInfo.Arch.ARM32
                else -> false
            }
            if (!ok) return false
        }

        if (osVersion != null) {
            // Mojang express macOS versions as a regex such as "^10\\.(\\d+)$".
            val actual = p.osVersion
            val pattern: Regex = try {
                Regex(osVersion)
            } catch (_: Exception) {
                return false
            }
            if (!pattern.containsMatchIn(actual)) return false
        }

        if (features.isNotEmpty()) {
            val flags = f.asMap()
            for ((key, required) in features) {
                if ((flags[key] ?: false) != required) return false
            }
        }
        return true
    }

    companion object {

        fun parseList(json: kotlinx.serialization.json.JsonArray?): List<Rule> =
            json?.mapNotNull { parse(it as? JsonObject ?: return@mapNotNull null) } ?: emptyList()

        fun parse(o: JsonObject): Rule? {
            val action = o.str("action") ?: return null
            val os = o.obj("os")
            val features = HashMap<String, Boolean>()
            val featureObj = o.obj("features")
            if (featureObj != null) {
                for ((k, v) in featureObj.entries) {
                    val flag = (v as? JsonPrimitive)?.booleanOrNull ?: continue
                    features[k] = flag
                }
            }
            return Rule(
                allow = action.equals("allow", ignoreCase = true),
                osName = os?.str("name"),
                osVersion = os?.str("version"),
                osArch = os?.str("arch"),
                features = features
            )
        }

        /** Mojang semantics, with an option to ignore feature rules when listing what a version needs. */
        fun allows(rules: List<Rule>, platform: PlatformInfo, features: LaunchFeatures, ignoreFeatures: Boolean = false): Boolean {
            if (rules.isEmpty()) return true
            var allowed = false
            for (rule in rules) {
                val effective = if (ignoreFeatures) rule.copy(features = emptyMap()) else rule
                if (!effective.matches(platform, features)) continue
                allowed = effective.allow
            }
            return allowed
        }
    }
}

/** OS / architecture aliases inside native classifier templates (`natives-linux-${arch}`). */
object NativeClassifier {

    /**
     * Candidate classifier names for a library's `natives` template, most specific first.
     *
     * Published metadata is inconsistent: some versions use a fixed classifier, newer ones use
     * `${arch}` with `64`/`32`, and third-party builds use `aarch64`/`amd64`/`arm64`. The installer
     * takes the first candidate that the metadata actually declares, which is why this returns a
     * list instead of one guess.
     */
    fun candidates(template: String, platform: PlatformInfo): List<String> {
        val arch = platform.arch
        val substitutions = LinkedHashSet<String>()
        substitutions += "-${platform.archBitsToken}"                     // Mojang's own form
        arch.aliases.forEach { substitutions += "-$it" }
        substitutions += platform.archBitsToken
        arch.aliases.forEach { substitutions += it }
        substitutions += ""                                              // no arch suffix
        val expanded = substitutions.map { template.replace("\${arch}", it) }.distinct().toMutableList()
        // A template without ${arch} must not be multiplied.
        if (!template.contains("\${arch}")) return listOf(template)
        // Some publishers template with ${arch} but only ship the arch-less classifier, so that form
        // is tried as a last resort instead of failing to resolve the natives at all.
        if (template.endsWith("-\${arch}")) expanded += template.removeSuffix("-\${arch}")
        return expanded.distinct()
    }
}
