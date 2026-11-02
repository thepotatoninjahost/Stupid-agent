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
     *   at host.vorv.tools.NotifyTool.run(NotifyTool.kt:66)
     * → returns "host.vorv.tools.NotifyTool"
     *
     * v0.6.9 fix: the regex used to capture the METHOD too
     * ("…NotifyTool.run") because [A-Za-z0-9_.]+ eats both — the trailing
     * lowercase method segment is now stripped (inner-class '$' suffixes
     * were already handled).
     */
    fun extractCrashedClass(stackTrace: String): String? {
        val lines = stackTrace.lines()
        for (line in lines) {
            // Match: "at host.vorv.<rest>(<file>:<line>)"
            val match = Regex("at\\s+(host\\.vorv\\.[A-Za-z0-9_.]+)").find(line)
            if (match != null) {
                var className = match.groupValues[1]
                // Strip inner-class suffixes — the outer class is what we want
                // to patch (inner classes are compiled into the outer).
                className = className.substringBefore("$")
                // Strip a trailing METHOD segment: stack frames end with
                // .methodName, where the method starts lowercase. Class
                // segments start uppercase by Kotlin/Java convention.
                val segments = className.split('.')
                val classSegments = segments.dropLastWhile { it.isNotEmpty() && !it[0].isUpperCase() }
                if (classSegments.isEmpty()) continue
                return classSegments.joinToString(".")
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
