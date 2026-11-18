package host.vorv.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner's rule: nothing fails silently. `runCatching { … }` whose result is
 * dropped is the usual way a failure disappears without a word.
 *
 * A real rule (detekt) would need a rules jar and a plugin download in every
 * build; this is the cheap version with most of the value: the number of such
 * sites may go down, never up. Use [Attempt.of] instead of a discarded
 * `runCatching`: it logs one line naming what failed and why.
 */
class SilentFailureGuardTest {

    private val baseline = 153

    @Test
    fun `no new silently swallowed failures`() {
        val sources = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot find the Java sources from ${File(".").absolutePath}")

        val sites = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { f -> Regex("""runCatching\s*\{""").findAll(f.readText()).count() }

        assertTrue(
            "Silent-failure sites grew from $baseline to $sites. " +
                "A new failure is being thrown away without a word. Use Attempt.of(\"what \"+\"was being done\") { … } " +
                "so the failure is logged, or, if this one really must stay quiet, raise the baseline in this test " +
                "and say why in the commit message.",
            sites <= baseline,
        )
    }
}
