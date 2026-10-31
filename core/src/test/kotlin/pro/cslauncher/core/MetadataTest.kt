package pro.cslauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.launch.ArgumentBuilder
import pro.cslauncher.core.meta.AssetIndex
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.meta.NativeClassifier
import pro.cslauncher.core.meta.Rule
import pro.cslauncher.core.meta.VersionManifest
import pro.cslauncher.core.meta.VersionMetadata
import java.time.Instant
import pro.cslauncher.core.util.MetadataException
import pro.cslauncher.core.util.parseJsonArray

class MetadataTest {

    private val modern = """
    {
      "id": "1.20.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
      "javaVersion": { "component": "java", "majorVersion": 17 },
      "assets": "5", "assetIndex": { "id": "5", "url": "https://example.invalid/5.json", "sha1": "abc", "size": 10 },
      "downloads": { "client": { "url": "https://example.invalid/client.jar", "sha1": "cc", "size": 100 } },
      "arguments": {
        "game": [
          { "rules": [{ "action": "allow", "features": { "has_custom_resolution": true } }],
            "value": ["--width", "${'$'}{resolution_width}", "--height", "${'$'}{resolution_height}"] },
          "--username", "${'$'}{auth_player_name}"
        ],
        "jvm": ["-Djava.library.path=${'$'}{natives_directory}", "-Djava.class.path=${'$'}{classpath}"]
      },
      "libraries": [
        { "name": "com.example:plain:1.0", "downloads": { "artifact": { "url": "https://example.invalid/plain.jar", "path": "com/example/plain/1.0/plain-1.0.jar" } } },
        { "name": "org.lwjgl:lwjgl:3.3.1",
          "natives": { "linux": "natives-linux-${'$'}{arch}" },
          "downloads": {
            "artifact": { "url": "https://example.invalid/lwjgl.jar", "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar" },
            "classifiers": { "natives-linux": { "url": "https://example.invalid/lwjgl-natives-arm64.jar", "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux-arm64.jar" } } } },
        { "name": "com.windows:only:1.0",
          "rules": [{ "action": "allow", "os": { "name": "windows" } }],
          "downloads": { "artifact": { "url": "https://example.invalid/win.jar", "path": "win.jar" } } }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesModernMetadata() {
        val meta = VersionMetadata.parse(modern)
        assertEquals("1.20.1", meta.id)
        assertEquals(17, meta.javaMajor)
        assertTrue(meta.usesModernArguments)
        assertTrue(meta.usesLwjgl3)
        assertFalse(meta.usesSdl)
        assertEquals("5", meta.assetIndex?.id)
        assertEquals(3, meta.libraries.size)
        assertEquals("net.minecraft.client.main.Main", meta.mainClass)
    }

    @Test
    fun rulesExcludeWindowsOnlyLibraryOnAndroid() {
        val meta = VersionMetadata.parse(modern)
        val android = TestWorld.platform(android = true)
        val windowsOnly = meta.libraries.first { it.name.startsWith("com.windows") }
        assertFalse(Rule.allows(windowsOnly.rules, android, LaunchFeatures()))
    }

    @Test
    fun ruleAllowLaterWins() {
        val rules = Rule.parseList(
            parseJsonArray(
                """
                [{ "action": "allow" },
                 { "action": "disallow", "os": { "name": "osx" } }]
                """.trimIndent(), "rules"
            )
        )
        assertTrue(Rule.allows(rules, TestWorld.platform(android = true), LaunchFeatures()))
    }

    @Test
    fun featureRulesOnlyApplyWhenFeatureEnabled() {
        val meta = VersionMetadata.parse(modern)
        val android = TestWorld.platform(android = true)
        val without = ArgumentBuilder.expand(meta.gameArguments, android, LaunchFeatures())
        assertFalse(without.contains("--width"))
        val with = ArgumentBuilder.expand(meta.gameArguments, android, LaunchFeatures(hasCustomResolution = true))
        assertTrue(with.contains("--width"))
    }

    @Test
    fun nativeClassifierTriesPlatformVariants() {
        val meta = VersionMetadata.parse(modern)
        val lwjgl = meta.libraries.first { it.name.startsWith("org.lwjgl") }
        val candidates = NativeClassifier.candidates(lwjgl.natives.getValue("linux"), TestWorld.platform(android = true))
        assertTrue(candidates.contains("natives-linux-arm64"))
        assertTrue(candidates.first().startsWith("natives-linux"))
    }

    @Test
    fun mergeChildOverridesParentButKeepsLibraries() {
        val child = VersionMetadata.parse(
            """
            { "id": "fabric-loader-0.15.7-1.20.1", "inheritsFrom": "1.20.1",
              "libraries": [ { "name": "net.fabricmc:fabric-loader:0.15.7",
                "downloads": { "artifact": { "url": "https://example.invalid/fl.jar", "path": "fl.jar" } } } ] }
            """.trimIndent()
        )
        val parent = VersionMetadata.parse(modern)
        val merged = VersionMetadata.merge(child, parent)
        assertEquals("fabric-loader-0.15.7-1.20.1", merged.id)
        // The loader profile has no main class of its own: the parent's must survive the merge.
        assertEquals("net.minecraft.client.main.Main", merged.mainClass)
        assertTrue(merged.libraries.any { it.name.startsWith("net.fabricmc") })
        assertTrue(merged.libraries.any { it.name.startsWith("org.lwjgl") })
    }

    @Test
    fun legacyArgumentsAreSupported() {
        val legacy = """
        { "id": "1.8.9", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${'$'}{auth_player_name} --version ${'$'}{version_name}",
          "libraries": [] }
        """.trimIndent()
        val meta = VersionMetadata.parse(legacy)
        assertFalse(meta.usesModernArguments)
        assertEquals("--username ${'$'}{auth_player_name} --version ${'$'}{version_name}", meta.legacyArguments)
        assertFalse(meta.usesLwjgl3)
    }

    @Test
    fun rejectsMalformedMetadata() {
        val bad = listOf(
            "{",
            """{ "id": "", "mainClass": "x" }""",
            """{ "id": "x", "libraries": "nope" }"""
        )
        for (text in bad) {
            try {
                VersionMetadata.parse(text)
                throw AssertionError("Should have rejected: $text")
            } catch (_: MetadataException) {
            }
        }
    }

    @Test
    fun assetIndexIsDeduplicated() {
        val json = """
        { "objects": {
            "a/one": { "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1 },
            "b/same": { "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1 },
            "c/two": { "hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 2 }
        } }
        """.trimIndent()
        val index = AssetIndex.parse(json)
        assertEquals(3, index.objects.size)
        assertEquals(2, index.uniqueObjects().size)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", index.objects.first { it.name == "b/same" }.hash)
    }

    @Test
    fun manifestFindsVersion() {
        val manifest = VersionManifest.parse(
            """
            { "latest": { "release": "1.20.1", "snapshot": "24w01a" },
              "versions": [ { "id": "1.20.1", "type": "release", "url": "https://example.invalid/1.20.1.json", "sha1": "x", "releaseTime": "2023-06-12T00:00:00Z" } ] }
            """.trimIndent(),
            Instant.now(), fromCache = false
        )
        assertNotNull(manifest.find("1.20.1"))
        assertEquals(null, manifest.find("1.21"))
        assertEquals("1.20.1", manifest.latestRelease)
    }
}
