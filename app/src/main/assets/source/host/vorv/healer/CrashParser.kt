package host.vorv.healer

/**
 * Parses a Java/Kotlin stack trace to identify the crashed class.
 *
 * Looks for the first frame in the `host.vorv` package — that's the app's
 * own code, and the most likely location of the bug (vs. framework code).
 */
object CrashParser {

    /**
     * Extract the most likely buggy class from a stack trace.
     * Returns the fully-qualified class name, or null if none found.
     *
     * Example stack trace line:
     *   at host.vorv.llm.ToolCallParser.parse(ToolCallParser.kt:66)
     * → returns "host.vorv.llm.ToolCallParser"
     */
    fun extractCrashedClass(stackTrace: String): String? {
        val lines = stackTrace.lines()
        for (line in lines) {
            // Match: "at host.vorv.<rest>(<file>:<line>)"
            val match = Regex("at\\s+(host\\.vorv\\.[A-Za-z0-9_.]+)").find(line)
            if (match != null) {
                val className = match.groupValues[1]
                // Strip inner-class suffixes — the outer class is what we want
                // to patch (inner classes are compiled into the outer).
                val outerClass = className.substringBefore("$")
                return outerClass
            }
        }
        return null
    }

    /**
     * Extract the crashed class from a boot trace (breadcrumbs).
     * If the boot trace shows a step like "geniex_init_begin" as the last
     * step, we can infer the crash happened during GenieX init — the
     * candidate class is the one doing the init (MainActivity or VorvApplication).
     */
    fun inferCrashedClassFromBootTrace(bootTrace: String): String? {
        val lines = bootTrace.lines().filter { it.isNotBlank() }
        val lastStep = lines.lastOrNull() ?: return null

        return when {
            lastStep.contains("geniex_init") -> "host.vorv.MainActivity"
            lastStep.contains("init_room") -> "host.vorv.VorvApplication"
            lastStep.contains("hotpatch") -> "host.vorv.VorvApplication"
            lastStep.contains("init_prefs") -> "host.vorv.VorvApplication"
            lastStep.contains("setcontent") -> "host.vorv.MainActivity"
            else -> null
        }
    }
}
