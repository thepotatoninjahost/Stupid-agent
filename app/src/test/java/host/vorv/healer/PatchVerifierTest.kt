package host.vorv.healer

import host.vorv.healer.fixture.FixtureTarget
import host.vorv.testutil.JavacFixture
import host.vorv.watchdog.UrlShadowLoader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-3 pinned: PatchVerifier.verify had no test at all, and the audit found
 * that its parent-first loader compared the ORIGINAL class with itself, so
 * every patch "passed". These tests run the real comparison logic
 * (verifyWithLoader) against compiled fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PatchVerifierTest {

    private val fqcn = FixtureTarget::class.java.name

    private fun patchLoader(source: String): ClassLoader = UrlShadowLoader(
        JavacFixture.compile(fqcn, source).urls,
        javaClass.classLoader,
        setOf(fqcn),
    )

    @Test
    fun `a complete patch passes and is marked as shadowing`() {
        val v = PatchVerifier.verifyWithLoader(
            patchLoader(
                """
                package host.vorv.healer.fixture;
                public class FixtureTarget {
                    // The Kotlin/Compose compiler emits this on the original.
                    public static final int ${'$'}stable = 0;
                    public FixtureTarget() {}
                    public String alpha() { return "patched-a"; }
                    public String beta() { return "patched-b"; }
                }
                """.trimIndent(),
            ),
            FixtureTarget::class.java,
        )
        assertTrue(
            "complete patch must pass: ctors=${v.missingConstructors} " +
                "methods=${v.missingMethods} fields=${v.missingFields} problems=${v.problems}",
            v.ok,
        )
        assertTrue("it must be the patch's class", v.shadowsOriginal)
        assertTrue(v.missingMethods.isEmpty())
        assertTrue(v.problems.isEmpty())
    }

    @Test
    fun `a patch missing one public method is rejected and the method is named`() {
        val v = PatchVerifier.verifyWithLoader(
            patchLoader(
                """
                package host.vorv.healer.fixture;
                public class FixtureTarget {
                    // The Kotlin/Compose compiler emits this on the original.
                    public static final int ${'$'}stable = 0;
                    public FixtureTarget() {}
                    public String alpha() { return "patched-a"; }
                }
                """.trimIndent(),
            ),
            FixtureTarget::class.java,
        )
        assertFalse("a partial patch must be rejected", v.ok)
        assertTrue(
            "the missing method must be named: ${v.missingMethods}",
            v.missingMethods.any { it.contains("beta") },
        )
        assertTrue("the feedback must explain the rejection", v.asFeedback().contains("INCOMPLETE"))
    }

    @Test
    fun `a loader that returns the running original is rejected`() {
        val v = PatchVerifier.verifyWithLoader(
            FixtureTarget::class.java.classLoader!!,
            FixtureTarget::class.java,
        )
        assertFalse("comparing the original with itself must never pass", v.ok)
        assertFalse(v.shadowsOriginal)
        assertTrue(
            "the reason must say the patch dex was not used: ${v.problems}",
            v.problems.any { it.contains("running original") },
        )
    }

    @Test
    fun `a dex without the class is rejected`() {
        val other = JavacFixture.compile(
            "host.vorv.healer.fixture.Unrelated",
            """
            package host.vorv.healer.fixture;
            public class Unrelated { public Unrelated() {} }
            """.trimIndent(),
        )
        val v = PatchVerifier.verifyWithLoader(
            UrlShadowLoader(other.urls, javaClass.classLoader!!, setOf(fqcn)),
            FixtureTarget::class.java,
        )
        assertFalse(v.ok)
        assertTrue(
            "the reason must name the missing class: ${v.problems}",
            v.problems.any { it.contains(fqcn) },
        )
    }
}
