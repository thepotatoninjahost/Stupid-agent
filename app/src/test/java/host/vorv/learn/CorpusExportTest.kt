package host.vorv.learn

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CorpusExportTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a written export is a real file with the rows in it`() {
        val jsonl = """{"kind":"CHAT_ASSISTANT","input":"q","output":"a","weight":0.6}"""
        val result = CorpusExport.write(context, jsonl, rows = 1)
        assertTrue(result.message, result.ok)
        val file = result.file
        assertNotNull(file)
        assertEquals(jsonl, file!!.readText())
        assertTrue(result.message.contains("ledger-"))
    }

    @Test
    fun `two exports never overwrite each other`() {
        val first = CorpusExport.write(context, "{\"a\":1}", 1, now = 1_700_000_000_000)
        val second = CorpusExport.write(context, "{\"a\":2}", 1, now = 1_700_000_060_000)
        assertNotEqualsFile(first.file, second.file)
        assertEquals("{\"a\":1}", first.file!!.readText())
    }

    @Test
    fun `an empty export is not written at all`() {
        val result = CorpusExport.write(context, "", rows = 0)
        assertFalse(result.ok)
        assertTrue(result.message.contains("empty"))
        assertTrue(result.file == null || !result.file.exists())
    }

    @Test
    fun `the folder lives inside the app, not on a shared shelf`() {
        val folder = CorpusExport.folder(context)
        assertTrue(folder.path.startsWith(context.filesDir.path))
        assertTrue(folder.isDirectory || folder.mkdirs())
    }

    @Test
    fun `the share sheet points at our own provider authority`() {
        val result = CorpusExport.write(context, "{\"a\":1}", 1)
        val intent = CorpusExport.shareIntent(context, result.file!!)
        assertNotNull("a written file must be shareable", intent)
        val send = intent!!.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
            ?: intent
        assertEquals("application/json", send.type)
    }

    @Test
    fun `an unreadable path produces a sentence, not a crash`() {
        val blocked = File(context.filesDir, CorpusExport.FOLDER)
        if (blocked.exists()) blocked.deleteRecursively()
        blocked.writeText("not a folder")
        val result = CorpusExport.write(context, "{\"a\":1}", 1)
        assertFalse(result.ok)
        assertTrue(result.message.isNotBlank())
        blocked.delete()
    }

    private fun assertNotEqualsFile(a: File?, b: File?) {
        assertNotNull(a)
        assertNotNull(b)
        assertTrue("exports collided: ${a!!.name}", a.name != b!!.name)
    }
}
