package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.launch.AndroidRuntimeEnv
import pro.cslauncher.core.launch.ArgumentBuilder
import pro.cslauncher.core.launch.IllegalLaunchTransition
import pro.cslauncher.core.launch.JvmOptionNormalizer
import pro.cslauncher.core.launch.LaunchPlan
import pro.cslauncher.core.launch.LaunchState
import pro.cslauncher.core.launch.LaunchStateMachine
import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.launch.Milestone
import pro.cslauncher.core.launch.StartupMonitor
import pro.cslauncher.core.meta.ArgumentEntry
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.meta.Rule

class LaunchTest {

    @Test
    fun stateMachineRejectsIllegalTransitions() {
        val sm = LaunchStateMachine()
        assertEquals(LaunchState.IDLE, sm.state)
        sm.advance(LaunchState.VALIDATING)
        var threw = false
        try {
            sm.advance(LaunchState.IDLE)
        } catch (_: IllegalLaunchTransition) {
            threw = true
        }
        assertTrue("Going backwards must be refused", threw)
        sm.advance(LaunchState.PREPARING_RUNTIME)
        sm.advance(LaunchState.PREPARING_RENDERER)
        sm.advance(LaunchState.INITIALIZING_GAME)
        sm.advance(LaunchState.RUNNING)
        sm.advance(LaunchState.EXITED)
        // A finished launch can start again; that is the only way back from EXITED.
        sm.advance(LaunchState.VALIDATING)
        assertEquals(LaunchState.VALIDATING, sm.state)
    }

    @Test
    fun failureRecordsTheStage() {
        val sm = LaunchStateMachine()
        sm.advance(LaunchState.VALIDATING)
        sm.advance(LaunchState.PREPARING_RUNTIME)
        sm.fail("no java")
        assertEquals(LaunchState.FAILED, sm.state)
        assertEquals(LaunchState.PREPARING_RUNTIME, sm.status.value.failedAt)
        assertEquals("no java", sm.status.value.message)
        sm.reset()
        assertEquals(LaunchState.IDLE, sm.state)
    }

    @Test
    fun placeholderSubstitutionRecordsUnresolvedNames() {
        val unresolved = mutableSetOf<String>()
        val out = ArgumentBuilder.substitute(
            "--username ${'$'}{auth_player_name} --x ${'$'}{nope}",
            mapOf("auth_player_name" to "Steve"),
            unresolved
        )
        assertEquals("--username Steve --x ${'$'}{nope}", out)
        assertEquals(setOf("nope"), unresolved)
    }

    @Test
    fun rulesFilterArguments() {
        val android = TestWorld.platform(android = true)
        val entries = listOf(
            ArgumentEntry(listOf("--demo"), listOf(Rule(allow = true, osName = null, osVersion = null, osArch = null, features = mapOf("is_demo_user" to true)))),
            ArgumentEntry(listOf("--always"), emptyList())
        )
        assertEquals(listOf("--always"), ArgumentBuilder.expand(entries, android, LaunchFeatures()))
        assertEquals(listOf("--demo", "--always"), ArgumentBuilder.expand(entries, android, LaunchFeatures(isDemoUser = true)))
    }

    @Test
    fun jvmOptionsAreJoinedForJni() {
        val result = JvmOptionNormalizer.normalize(
            listOf("-cp", "a.jar:b.jar", "--add-opens", "java.base/java.io=ALL-UNNAMED", "-Xmx2048M", "-XstartOnFirstThread")
        )
        assertTrue(result.options.contains("-Djava.class.path=a.jar:b.jar"))
        assertTrue(result.options.contains("--add-opens=java.base/java.io=ALL-UNNAMED"))
        assertTrue(result.options.contains("-Xmx2048M"))
        assertEquals(listOf("-XstartOnFirstThread"), result.dropped)
        // Anything with a space would be split again by the JVM launcher.
        assertFalse(result.options.any { it.contains(" ") })
    }

    @Test
    fun planRedactsTheAccessToken() {
        val plan = LaunchPlan(
            profileId = "p", profileName = "P", versionId = "1.20.1",
            mainClass = "net.minecraft.client.main.Main",
            javaHome = "/java", javaMajor = 17, javaExecutable = "/java/bin/java", libjvm = null,
            classpath = listOf("/a.jar"), nativeDirs = listOf("/n"),
            jvmArgs = listOf("-Xmx2G"),
            gameArgs = listOf("--username", "Steve", "--accessToken", "SuperSecretToken123"),
            workingDir = "/game", env = emptyMap(), rendererId = "gl4es", rendererContext = "EGL_GLES2",
            rendererLibraries = emptyMap(), controlLayoutId = "default-touch",
            sessionDir = "/s", logFile = "/s/game.log", width = 800, height = 600
        )
        val text = plan.redactedCommandLine()
        assertFalse("The token must never appear in a rendered command line", text.contains("SuperSecretToken123"))
        assertTrue(text.contains("--username"))
    }

    @Test
    fun planValidationCatchesRelativePaths() {
        val plan = LaunchPlan(
            profileId = "p", profileName = "P", versionId = "1.20.1",
            mainClass = "Main", javaHome = "/java", javaMajor = 17, javaExecutable = "/java/bin/java", libjvm = null,
            classpath = listOf("relative.jar"), nativeDirs = listOf("natives"),
            jvmArgs = emptyList(), gameArgs = emptyList(), workingDir = "/game", env = emptyMap(),
            rendererId = "gl4es", rendererContext = "EGL_GLES2", rendererLibraries = emptyMap(),
            controlLayoutId = "l", sessionDir = "/s", logFile = "/s/log", width = 0, height = 600
        )
        val problems = plan.validate()
        assertTrue(problems.any { it.contains("Classpath") })
        assertTrue(problems.any { it.contains("Native directory") })
        assertTrue(problems.any { it.contains("window size") })
    }

    @Test
    fun androidRuntimeEnvComputesJavaHomeForModernAndLegacyLayouts() {
        val modern = AndroidRuntimeEnv.build(
            libjvm = "/rt/lib/server/libjvm.so", javaMajor = 17, nativeDirs = listOf("/n"),
            rendererLibraries = mapOf("gl" to "/r/libgl.so"), sessionTmp = "/s/tmp", gameDir = "/game",
            resolvConf = "/s/resolv.conf", timeZoneId = "Asia/Kolkata", androidRelease = "14",
            existingJvmArgs = emptyList()
        )
        assertEquals("/rt", modern.env["JAVA_HOME"])
        assertTrue(modern.linkerPath.contains("/rt/lib/server"))
        assertTrue(modern.linkerPath.contains("/n"))
        assertTrue(modern.jvmOptions.any { it.startsWith("-Duser.timezone=") })
        assertTrue(modern.jvmOptions.any { it.startsWith("-Dext.net.resolvPath=") })

        val legacy = AndroidRuntimeEnv.build(
            libjvm = "/jre/lib/aarch64/server/libjvm.so", javaMajor = 8, nativeDirs = emptyList(),
            rendererLibraries = emptyMap(), sessionTmp = "/s", gameDir = "/g", resolvConf = "/r",
            timeZoneId = "UTC", androidRelease = "13", existingJvmArgs = listOf("-Djava.home=/custom")
        )
        assertEquals("/jre", legacy.env["JAVA_HOME"])
        assertFalse("An existing -Djava.home must not be overridden", legacy.jvmOptions.any { it.startsWith("-Djava.home=") })
    }

    @Test
    fun resolvConfSkipsUnusableAddresses() {
        val text = AndroidRuntimeEnv.resolvConf(listOf("192.168.1.1", "fe80::1%wlan0", ""))
        assertTrue(text.contains("192.168.1.1"))
        assertFalse(text.contains("fe80"))
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", AndroidRuntimeEnv.resolvConf(emptyList()))
    }

    @Test
    fun startupMonitorReachesTitleScreen() {
        val seen = mutableListOf<Milestone>()
        val monitor = StartupMonitor { m, _ -> seen += m }
        monitor.markJvmStarted()
        monitor.onLine("[12:00:00] [main/INFO]: Setting user: Steve")
        monitor.onLine("[12:00:00] [Render thread/INFO]: Backend library: LWJGL version 3.3.1")
        monitor.onLine("[12:00:00] [Render thread/INFO]: Sound engine started")
        assertTrue(monitor.reachedTitleScreen)
        assertEquals(LaunchState.RUNNING, monitor.stateFor())
        assertFalse(monitor.crashed)
        assertEquals(Milestone.MAIN_ENTERED, seen[1])
    }

    @Test
    fun benignErrorsAreNotCrashes() {
        val monitor = StartupMonitor()
        monitor.markJvmStarted()
        monitor.onLine("Failed to open OpenAL device")
        monitor.onLine("Narrator library not found")
        monitor.onLine("Realms server unavailable")
        assertFalse(monitor.crashed)
    }

    @Test
    fun fatalMarkerIsACrash() {
        val monitor = StartupMonitor()
        monitor.markJvmStarted()
        monitor.onLine("---- Minecraft Crash Report ----")
        assertTrue(monitor.crashed)
        assertTrue(monitor.summary().startsWith("Crashed"))
    }

    @Test
    fun log4jXmlIsFlattened() {
        val converter = Log4jXml()
        assertTrue(converter.feed("<log4j:Event logger=\"x\" level=\"WARN\" timestamp=\"1700000000000\" thread=\"main\">").isEmpty())
        val lines = converter.feed("<log4j:Message><![CDATA[Something happened]]></log4j:Message>")
        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("[main/WARN]"))
        assertTrue(lines.first().contains("Something happened"))
        // Plain output passes through untouched.
        assertEquals(listOf("plain stdout"), converter.feed("plain stdout"))
    }
}
