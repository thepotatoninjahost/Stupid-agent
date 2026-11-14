package host.vorv.computer

import android.content.Context
import com.chaquo.python.Python
import host.vorv.forge.ForgeProbe
import java.io.File

/**
 * Wires the computer to this device and answers the only question the rest of
 * the app should ask: what can I actually run right now?
 *
 * This replaces `TermuxProbe`. There is nothing to install, no permission to
 * grant and no second app involved: the JavaScript engine is in the APK, the
 * Python interpreter is in the APK, and the compiler payload — when bundled —
 * is verified by sha256 before it is ever used.
 */
object ComputerProbe {

    data class Capability(val id: String, val ready: Boolean, val detail: String)

    fun home(context: Context): ComputerHome = ComputerHome(File(context.filesDir, "computer"))

    fun capabilities(context: Context): List<Capability> = listOf(
        Capability("js", true, "built-in interpreter"),
        Capability(
            "python",
            pythonStarted(context),
            if (pythonStarted(context)) "CPython in the APK" else "interpreter failed to start",
        ),
        Capability(
            "kotlin-patch",
            ForgeProbe.readinessError(context) == null,
            ForgeProbe.readinessError(context) ?: "toolchain verified",
        ),
    )

    private fun pythonStarted(context: Context): Boolean = runCatching {
        if (Python.isStarted()) return@runCatching true
        Python.start(com.chaquo.python.android.AndroidPlatform(context))
        Python.isStarted()
    }.getOrDefault(false)

    /** Null when at least one engine can run — the honest gate for tools. */
    fun readinessError(context: Context): String? {
        val ready = capabilities(context).filter { it.ready }
        if (ready.isNotEmpty()) return null
        return "The app's computer has no runnable engine: " +
            capabilities(context).joinToString("; ") { "${it.id} — ${it.detail}" }
    }
}
