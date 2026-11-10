package host.vorv.tools

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrashLogsToolTest {
    @get:Rule val temp = TemporaryFolder()

    private fun tool(root: File): CrashLogsTool {
        val base: Context = ApplicationProvider.getApplicationContext()
        return CrashLogsTool(object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File = root
        })
    }

    private fun read(tool: CrashLogsTool, path: String): ToolResult = runBlocking {
        tool.run(JsonObject().apply {
            addProperty("action", "read")
            addProperty("path", path)
        }, ToolContext("test", 1, null))
    }

    private fun write(root: File, path: String, text: String = "private diagnostic"): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun `reads reports in both evidence directories`() {
        val root = temp.newFolder()
        val tool = tool(root)
        for (path in listOf("crash_logs/report.txt", "watchdog/crash_detected.txt")) {
            write(root, path, "crash evidence")
            val result = read(tool, path)
            assertTrue(result.ok)
            assertEquals("crash evidence", result.output)
            assertEquals(path, result.citation?.ref)
        }
    }

    @Test fun `virtual boot trace entries remain readable`() {
        val root = temp.newFolder()
        write(root, "boot_trace.txt", "previous boot\n${"=".repeat(60)}\ncurrent boot")
        val tool = tool(root)
        assertTrue(read(tool, "boot_trace_current").output.contains("current boot"))
        assertEquals("previous boot", read(tool, "boot_trace_previous").output)
        assertFalse(read(tool, "crash_logs/../boot_trace.txt").ok)
    }

    @Test fun `rejects traversal to sibling diagnostics and dot segments`() {
        val root = temp.newFolder()
        write(root, "crash_logs/report.txt")
        write(root, "watchdog/crash_detected.txt")
        write(root, "other-diagnostics.txt")
        write(root, "crash_logs_backup/report.txt")
        val tool = tool(root)
        for (path in listOf(
            "crash_logs/../other-diagnostics.txt", "watchdog/../other-diagnostics.txt",
            "crash_logs/../crash_logs_backup/report.txt", "crash_logs/./report.txt",
            "crash_logs/sub/../report.txt", "other-diagnostics.txt",
            "crash_logs_backup/report.txt", "crash_logs/missing.txt", "watchdog/",
        )) {
            val result = read(tool, path)
            assertFalse(path, result.ok)
            assertEquals(path, "", result.output)
        }
    }

    @Test fun `rejects symlink escapes from evidence directories`() {
        val root = temp.newFolder()
        val sibling = write(root, "crash_logs_backup/secret.txt")
        val outside = temp.newFile().apply { writeText("outside secret") }
        File(root, "crash_logs").mkdirs()
        Files.createSymbolicLink(File(root, "crash_logs/sibling.txt").toPath(), sibling.toPath())
        Files.createSymbolicLink(File(root, "crash_logs/outside.txt").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "crash_logs/nested").toPath(), sibling.parentFile!!.toPath())
        val tool = tool(root)
        for (path in listOf("crash_logs/sibling.txt", "crash_logs/outside.txt", "crash_logs/nested/secret.txt")) {
            assertFalse(path, read(tool, path).ok)
        }
    }

    @Test fun `evidence directory symlink cannot bypass external root boundary`() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        write(outside, "secret.txt")
        Files.createSymbolicLink(File(root, "crash_logs").toPath(), outside.toPath())
        assertFalse(read(tool(root), "crash_logs/secret.txt").ok)
    }
}
