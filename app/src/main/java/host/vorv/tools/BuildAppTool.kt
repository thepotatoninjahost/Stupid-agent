package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.computer.ComputerProbe
import host.vorv.forge.ForgeRuntime
import host.vorv.forge.PhoneBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * build_app — turn a project on the app's computer into an installable APK.
 *
 * This is the step that makes an app real: source in, signed APK out, both on
 * the phone. The APK it produces is what native_test then installs and drives.
 * A build that does not end with a verified signature is a failure, never a
 * "likely fine".
 */
class BuildAppTool(private val appContext: Context) : Tool {

    override val name = "build_app"
    override val description =
        "Compile a project on the app's computer into an installable, signed APK, without Termux " +
            "and without root. The project must contain .java files and a Java file named Main.java " +
            "that extends android.app.Activity — that is the app's entry point. Screen layout is " +
            "built in code, not XML. Returns the APK path; pass that to native_test to install and " +
            "actually exercise it."

    override val timeoutMs: Long get() = 600_000L
    override val maxCallsPerTask: Int get() = 8

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("project", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Project folder on the app's computer, e.g. 'todo'.")
            })
        })
        add("required", JsonArray().apply { add("project") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val project = input.get("project")?.asString?.trim()?.trim('/')
                ?: return@withContext fail("project is required")

            val home = ComputerProbe.home(appContext)
            val root = File(home.projectsDir, project).canonicalFile
            if (!root.path.startsWith(home.projectsDir.canonicalFile.path + File.separator)) {
                return@withContext fail("'$project' is not a project on the app's computer")
            }
            if (!root.isDirectory) return@withContext fail("no project folder '$project'")

            val builder = PhoneBuilder(appContext, ForgeRuntime(appContext))
            val build = builder.build(root, outputName = "app")
            val log = build.steps.joinToString("\n")

            if (!build.ok || build.apk == null) {
                return@withContext ToolResult(
                    ok = false,
                    output = "BUILD FAILED\n$log",
                    durationMs = System.currentTimeMillis() - started,
                    error = build.error ?: "build failed",
                    stderr = log,
                )
            }

            val apk = build.apk
            val inside = apk.path.startsWith(home.root.canonicalFile.path + File.separator)
            ToolResult(
                ok = true,
                output = "BUILD OK\n$log\napk: ${apk.absolutePath} (${apk.length()} bytes)" +
                    if (inside) "" else "\nwarning: apk landed outside the computer's tree",
                durationMs = System.currentTimeMillis() - started,
                citation = Citation("exec", "build_app:$project", "source → signed apk on the phone"),
            )
        }

    private fun fail(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message, stderr = message,
    )
}
