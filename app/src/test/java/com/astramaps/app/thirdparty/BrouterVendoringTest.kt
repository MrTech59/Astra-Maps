package com.astramaps.app.thirdparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Guards the vendored BRouter runtime.
 *
 * BRouter is vendored into `third_party/brouter` as a flat source tree rather
 * than consumed as a Maven artifact, so nothing but this test would notice if
 * a re-sync silently dropped a class, changed a signature, or pulled in a
 * dependency that does not exist on Android.
 *
 * What this test asserts:
 *  - the classes the routing layer depends on are on the runtime classpath;
 *  - the public API shapes we call have not changed;
 *  - the vendored tree contains no AWT / `javax.imageio` references, which
 *    would fail at runtime on Android;
 *  - the offline-delta machinery is present and has the expected signature.
 */
class BrouterVendoringTest {

    @Test
    fun `core routing classes are available`() {
        listOf(
            // Routing
            "btools.router.RoutingContext",
            "btools.router.RoutingEngine",
            "btools.router.StdModel",
            "btools.router.KinematicModel",
            "btools.router.OsmPathModel",
            "btools.router.VoiceHint",
            "btools.router.VoiceHintList",
            "btools.router.VoiceHintProcessor",
            "btools.router.MessageData",
            "btools.router.OsmNodeNamed",
            "btools.router.OsmTrack",
            "btools.router.AreaInfo",
            "btools.router.RoutingHelper",
            "btools.router.ProfileCache",
            // Waypoint snapping
            "btools.mapaccess.WaypointMatcherImpl",
            "btools.codec.WaypointMatcher",
            // Storage / updates
            "btools.mapaccess.Rd5DiffTool",
            "btools.mapaccess.Rd5DiffManager",
            "btools.mapaccess.Rd5DiffValidator",
            "btools.mapaccess.StorageConfigHelper",
            "btools.util.ProgressListener",
            "btools.util.CheapRuler",
            "btools.codec.NoisyDiffCoder",
        ).forEach { name ->
            assertTrue(
                "$name must be on the classpath",
                runCatching { Class.forName(name) }.isSuccess,
            )
        }
    }

    @Test
    fun `offline delta machinery is present with the expected signature`() {
        // Rd5DiffTool.recoverFromDelta(File installed, File delta, File out, ProgressListener)
        // The listener type is the top-level btools.util.ProgressListener.
        val method: Method = Class.forName("btools.mapaccess.Rd5DiffTool").getMethod(
            "recoverFromDelta",
            File::class.java,
            File::class.java,
            File::class.java,
            Class.forName("btools.util.ProgressListener"),
        )
        assertEquals(Void.TYPE, method.returnType)
        assertTrue(Modifier.isStatic(method.modifiers))
    }

    @Test
    fun `routing context exposes the model selection hook we plan to patch`() {
        // `RoutingContext.setModel(String)` is private upstream and is reached
        // from `expctxGlobal._modelClass`. The Phase 4 plan is to make it
        // selectable programmatically; this test pins the current shape so
        // the patch cannot silently break against a future re-sync.
        val method = Class.forName("btools.router.RoutingContext")
            .getDeclaredMethod("setModel", String::class.java)
        assertTrue(
            "expected setModel to be private upstream",
            Modifier.isPrivate(method.modifiers),
        )
    }

    @Test
    fun `routing entry points keep their expected shapes`() {
        // These are the exact calls the Phase 4 RouteProvider will make.
        val context = Class.forName("btools.router.RoutingContext")

        val setWaypoint = context.getMethod(
            "setWaypoint",
            Class.forName("btools.router.OsmNodeNamed"),
            Boolean::class.javaPrimitiveType,
        )
        assertEquals(Void.TYPE, setWaypoint.returnType)

        val calcDistance = context.getMethod(
            "calcDistance",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        assertEquals(Int::class.javaPrimitiveType, calcDistance.returnType)

        val engine = Class.forName("btools.router.RoutingEngine")
        assertEquals(String::class.java, engine.getMethod("getErrorMessage").returnType)
        assertEquals(
            Class.forName("btools.router.OsmTrack"),
            engine.getMethod("getFoundTrack").returnType,
        )
    }

    @Test
    fun `routing context memory class defaults to 64 megabytes`() {
        // `memoryclass` is a public *instance* field on RoutingContext: the
        // peak heap BRouter may use for the routing graph, in megabytes. It is
        // the first number to tune for a mid-range phone.
        val field = Class.forName("btools.router.RoutingContext").getField("memoryclass")
        assertEquals(Int::class.javaPrimitiveType, field.type)
        assertTrue(
            "memoryclass must remain a public instance field",
            !Modifier.isStatic(field.modifiers),
        )

        // The upstream default cannot be read without an instance, so it is
        // read from the vendored source instead.
        val source = File(vendoredSourceRoot(), "btools/router/RoutingContext.java").readText()
        val default = Regex("""public\s+int\s+memoryclass\s*=\s*(\d+)""")
            .find(source)?.groupValues?.get(1)?.toInt()
        assertEquals("upstream memoryclass default must stay 64 MB", 64, default)
    }

    @Test
    fun `voice hint command codes are stable`() {
        // VoiceHint's constants are package-private, so read them reflectively.
        val voiceHint = Class.forName("btools.router.VoiceHint")
        val codes = voiceHint.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name to field.getInt(null)
            }

        assertEquals(
            "the 18 navigation voice-hint codes must not change; a change " +
                "breaks the Phase 5 voice pipeline",
            18,
            codes.size - 1, // minus END
        )
        assertEquals(1, codes["C"])
        assertEquals(2, codes["TL"])
        assertEquals(5, codes["TR"])
        assertEquals(10, codes["TLU"])
        assertEquals(11, codes["TRU"])
        assertEquals(15, codes["TU"])
        assertEquals(18, codes["ER"])
        assertEquals(100, codes["END"])
    }

    @Test
    fun `vendored source has no AWT or javax imageio references`() {
        val root = vendoredSourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { file ->
                val text = file.readText()
                text.contains("java.awt") ||
                    text.contains("javax.imageio") ||
                    text.contains("javax.swing")
            }
            .map { it.name }
            .toList()

        assertTrue(
            "these vendored files reference desktop-only APIs and would fail " +
                "on Android: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun vendoredSourceRoot(): File {
        // The test runs with the module directory as the working directory in
        // Gradle; walk upwards until the vendored tree is found.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "third_party/brouter/src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate third_party/brouter/src/main/java")
    }
}
