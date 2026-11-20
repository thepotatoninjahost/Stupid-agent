package host.vorv.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review 2026-11-20: the on-device engine emits ~2048 tokens per reply, so a
 * whole file rarely fits one call. file_write grew an `append` flag to make
 * chunked writes possible — the model must be able to discover it from the
 * schema alone, and it must map to the appending stream mode.
 *
 * The SAF write itself is not exercised here (a tree Uri needs a real
 * provider); this pins the two surfaces the model and the resolver see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FileWriteAppendTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `append selects the appending stream mode, overwrite truncates`() {
        assertEquals("wa", fileWriteStreamMode(append = true))
        assertEquals("wt", fileWriteStreamMode(append = false))
    }

    @Test
    fun `schema advertises append and keeps path and content required`() {
        val schema = FileWriteTool(ctx).schema()
        val props = schema.getAsJsonObject("properties")
        assertTrue("append must be discoverable", props.has("append"))
        assertEquals("boolean", props.getAsJsonObject("append").get("type").asString)

        val required = schema.getAsJsonArray("required").map { it.asString }
        assertEquals(listOf("path", "content"), required)
    }

    @Test
    fun `description teaches the chunk size and the append protocol`() {
        val description = FileWriteTool(ctx).description
        assertTrue(description.contains(RECOMMENDED_WRITE_CHUNK_CHARS.toString()))
        assertTrue(description.contains("append=true"))
        assertTrue(description.contains("append=false"))
    }
}
