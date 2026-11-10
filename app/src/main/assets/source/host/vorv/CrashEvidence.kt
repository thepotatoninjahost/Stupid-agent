package host.vorv

import java.io.File

internal fun newestCrashEvidence(externalDir: File): File? {
    val reports = File(externalDir, "crash_logs").listFiles().orEmpty().filter {
        it.isFile && it.name.endsWith(".txt") && !it.name.startsWith("handled_")
    }
    val detections = File(externalDir, "watchdog").listFiles().orEmpty().filter {
        it.isFile && it.name.startsWith("crash_detected") && it.name.endsWith(".txt")
    }
    return (reports + detections).maxByOrNull { it.lastModified() }
}
