package host.vorv

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newestUnhandledEvidenceWinsAcrossDirectories() {
        val root = temporary.newFolder()
        val reports = File(root, "crash_logs").apply { mkdirs() }
        val watchdog = File(root, "watchdog").apply { mkdirs() }
        File(reports, "crash_old.txt").apply { writeText("old"); setLastModified(1_000) }
        val latest = File(watchdog, "crash_detected_new.txt").apply { writeText("new"); setLastModified(2_000) }
        File(reports, "handled_crash_latest.txt").apply { writeText("handled"); setLastModified(3_000) }
        assertEquals(latest, newestCrashEvidence(root))
    }
}
