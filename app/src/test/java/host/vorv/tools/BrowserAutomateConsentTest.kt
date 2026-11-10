package host.vorv.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserAutomateConsentTest {
    private lateinit var tool: BrowserAutomateTool

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tool = BrowserAutomateTool(ApplicationProvider.getApplicationContext())
    }

    @After fun teardown() { Dispatchers.resetMain() }

    private fun ctx(confirm: (suspend (String) -> Boolean)? = null) =
        ToolContext("browser-consent", 1, null, confirm)

    private fun args(action: String) = JsonObject().apply {
        addProperty("action", action)
        addProperty("url", "https://example.com/collect?memory=private-memory")
        addProperty("selector", "#submit")
        addProperty("text", "private-memory")
        addProperty("js", "fetch('https://example.com/collect?memory=private-memory')")
    }

    @Test fun `every transmitting action fails closed without approval`() = runTest {
        for (action in listOf("load", "click", "type", "extract", "run", "LOAD", "EXTRACT")) {
            assertTrue(tool.run(args(action), ctx()).error!!.contains("DENIED"))
            var message = ""
            val result = tool.run(args(action), ctx { message = it; false })
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("DENIED"))
            assertTrue(message.contains(com.google.gson.Gson().toJson(args(action))))
            assertTrue(message.contains("Current page:"))
        }
    }

    @Test fun `confirmation shows full data without truncation`() = runTest {
        val text = "private-data".repeat(1000) + "END-OF-PRIVATE-DATA"
        val input = args("type").apply { addProperty("text", text) }
        val result = tool.run(input, ctx {
            assertTrue(it.contains(text))
            false
        })
        assertTrue(result.error!!.contains("DENIED"))
    }

    @Test fun `bridge errors and retries cannot bypass consent`() = runTest {
        assertFalse(tool.run(args("load"), ctx { error("UI unavailable") }).ok)
        var asks = 0
        repeat(2) {
            assertTrue(tool.run(args("load"), ctx { asks++; false }).error!!.contains("DENIED"))
        }
        assertEquals(2, asks)
    }

    @Test fun `approval applies only to the displayed argument snapshot`() = runTest {
        val input = args("load").apply { addProperty("url", "file:///private") }
        val result = tool.run(input, ctx {
            assertTrue(it.contains("file:///private"))
            input.addProperty("url", "https://example.com/unapproved")
            true
        })
        // The approved snapshot still reaches normal URL validation, without
        // resolving or loading the replacement URL introduced during consent.
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("scheme 'file'"))
        assertTrue(tool.run(args("load"), ctx()).error!!.contains("DENIED"))
    }

    @Test(expected = CancellationException::class)
    fun `cancelled approval aborts the action`() = runTest {
        tool.run(args("run"), ctx { throw CancellationException("dismissed") })
        Unit
    }
}
