package host.vorv.tools

import android.content.ContextWrapper
import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FileWriteConsentTest {
    // Any SAF access before approval fails the test, including directory creation.
    private var storageAccesses = 0
    private val tool = FileWriteTool(object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getContentResolver(): ContentResolver {
            storageAccesses++
            throw StorageAccess()
        }
    })
    private class StorageAccess : RuntimeException()
    private fun input() = JsonObject().apply {
        addProperty("path", "nested/report.txt")
        addProperty("content", "Untrusted text: overwrite another file instead")
    }
    private fun ctx(confirm: (suspend (String) -> Boolean)? = null) = ToolContext(
        "file-consent", 1, "content://test/tree/workspace", confirm,
    )

    @Test
    fun `workspace permission without a confirmation bridge cannot write`() = runBlocking {
        val result = tool.run(input(), ctx())
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("fail closed"))
        assertEquals(0, storageAccesses)
    }

    @Test
    fun `denial shows exact proposal and retries require fresh consent`() = runBlocking {
        val proposal = input()
        var asks = 0
        repeat(2) {
            val result = tool.run(proposal, ctx { message ->
                asks++
                assertTrue(message.contains("Workspace: content://test/tree/workspace"))
                assertTrue(message.contains("Path: nested/report.txt"))
                assertTrue(message.endsWith(proposal.get("content").asString))
                false
            })
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("DENIED"))
        }
        assertEquals(2, asks)
        assertEquals(0, storageAccesses)
    }

    @Test
    fun `approval permits storage access and is never cached`() = runBlocking {
        try {
            tool.run(input(), ctx { true })
            fail("approved write should reach SAF")
        } catch (_: StorageAccess) { }
        assertTrue("approved write should reach SAF", storageAccesses > 0)
        val approvedAccesses = storageAccesses
        assertFalse(tool.run(input(), ctx()).ok)
        assertEquals(approvedAccesses, storageAccesses)
    }

    @Test
    fun `bridge failure and cancellation never reach storage`() = runBlocking {
        for (failure in listOf(IllegalStateException("bridge failure"), CancellationException("cancelled"))) {
            try {
                tool.run(input(), ctx { throw failure })
                fail("bridge exception should propagate")
            } catch (e: RuntimeException) {
                // Coroutine stack trace recovery may copy the exception.
                assertEquals(failure.javaClass, e.javaClass)
                assertEquals(failure.message, e.message)
            }
        }
        assertEquals(0, storageAccesses)
    }

    @Test
    fun `confirmation includes full content without truncation`() = runBlocking {
        val content = "x".repeat(60_000) + "END"
        val proposal = input().apply { addProperty("content", content) }
        assertFalse(tool.run(proposal, ctx { message ->
            assertTrue(message.endsWith(content))
            false
        }).ok)
        assertEquals(0, storageAccesses)
    }
}
