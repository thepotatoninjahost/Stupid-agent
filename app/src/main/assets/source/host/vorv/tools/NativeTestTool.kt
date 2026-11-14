package host.vorv.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.computer.ComputerProbe
import host.vorv.nativeapp.AppInstaller
import host.vorv.nativeapp.UiAutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * native_test — install an APK the agent built, open it, and tap through it.
 *
 * Same principle as web_test: an app is not "done" because code compiles. It is
 * done when it installs, launches, and the screens the user will touch behave.
 * Every step reports a check; any failed check, missing screen or crash is a
 * FAIL, and no steps means the run proves nothing.
 */
class NativeTestTool(private val appContext: Context) : Tool {

    override val name = "native_test"
    override val description =
        "Install an APK you built and actually use it: opens the app on the phone, taps and types " +
            "through the steps you give, and reports each step as pass or fail. Fails if the app " +
            "does not open, disappears (crashed), or a step cannot be performed. Requires the user " +
            "to have enabled 'Vorv app testing' in Settings > Accessibility. Always run this before " +
            "telling the user a native app is finished."

    override val timeoutMs: Long get() = 180_000L
    override val maxCallsPerTask: Int get() = 6

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("project", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Project folder on the app's computer holding the built APK, e.g. 'todo'.")
            })
            add("apk", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "APK path inside the project, e.g. 'build/outputs/app-debug.apk'.")
            })
            add("steps", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "Ordered steps. Each is {op, text, into, timeout_ms} where op is " +
                    "assert_text | assert_not_text | click | type | wait_text | assert_screen.")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("op", JsonObject().apply { addProperty("type", "string") })
                        add("text", JsonObject().apply { addProperty("type", "string") })
                        add("into", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "For type: label or id of the field to fill.")
                        })
                        add("timeout_ms", JsonObject().apply { addProperty("type", "integer") })
                    })
                })
            })
            add("uninstall_after", JsonObject().apply { addProperty("type", "boolean") })
        })
        add("required", JsonArray().apply { add("project"); add("apk"); add("steps") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val project = input.get("project")?.asString?.trim()?.trim('/')
                ?: return@withContext fail("project is required")
            val apkRel = input.get("apk")?.asString ?: return@withContext fail("apk is required")
            val steps = input.getAsJsonArray("steps")
                ?: return@withContext fail("steps is required")

            if (!UiAutomationService.isEnabled) {
                return@withContext fail(
                    "The user has not enabled 'Vorv app testing' in Settings > Accessibility, so there is " +
                        "no way to see or touch the app. Ask them to open Settings > Accessibility and turn " +
                        "it on, then run this again. Do not report the app as tested until then.",
                )
            }

            val home = ComputerProbe.home(appContext)
            val root = File(home.projectsDir, project).canonicalFile
            if (!root.path.startsWith(home.projectsDir.canonicalFile.path + File.separator)) {
                return@withContext fail("'$project' is not a project on the app's computer")
            }
            val apk = File(root, apkRel).canonicalFile
            if (!apk.path.startsWith(root.path + File.separator)) return@withContext fail("apk must stay inside the project")
            if (!apk.isFile) return@withContext fail("no APK at $apkRel — build it first")

            val installer = AppInstaller(appContext)
            val install = installer.install(apk)
            if (!install.ok) return@withContext fail("install failed: ${install.detail}")
            val pkg = install.packageName!!

            val checks = mutableListOf<String>()
            val failures = mutableListOf<String>()

            fun record(name: String, pass: Boolean, detail: String = "") {
                if (pass) checks += "ok   $name${if (detail.isEmpty()) "" else "  ($detail)"}"
                else {
                    failures += "FAIL $name${if (detail.isEmpty()) "" else "  ($detail)"}"
                    checks += "FAIL $name${if (detail.isEmpty()) "" else "  ($detail)"}"
                }
            }

            val open = openAndWait(pkg, 20_000)
            record("app opens", open.ok, open.detail)
            if (!open.ok) {
                if (input.get("uninstall_after")?.asBoolean == true) installer.uninstall(pkg)
                return@withContext result(checks, failures, started, pkg)
            }

            delay(1_200) // let the first frame settle

            for ((index, raw) in steps.withIndex()) {
                val step = raw.asJsonObject
                val op = step.get("op")?.asString?.lowercase() ?: "?" 
                val text = step.get("text")?.asString.orEmpty()
                val budget = (step.get("timeout_ms")?.asLong ?: 6_000L).coerceIn(300L, 30_000L)
                val label = "#${index + 1} $op${if (text.isEmpty()) "" else " '$text'"}"
                val service = UiAutomationService.service()
                if (service == null) {
                    record(label, false, "testing was switched off mid-run")
                    break
                }
                when (op) {
                    "assert_text", "assert_screen" -> {
                        val found = waitForText(service, text, budget)
                        record(label, found, if (found) "" else "not on screen")
                    }
                    "assert_not_text" -> {
                        val found = waitForText(service, text, 800)
                        record(label, !found, if (found) "still on screen" else "")
                    }
                    "wait_text" -> {
                        val found = waitForText(service, text, budget)
                        record(label, found, if (found) "" else "never appeared")
                    }
                    "click" -> {
                        val clicked = UiAutomationService.service()
                            ?.click(UiAutomationService.Lookup(text, exact = false)) ?: false
                        record(label, clicked, if (clicked) "" else "nothing clickable matched")
                        if (clicked) delay(600)
                    }
                    "type" -> {
                        val into = step.get("into")?.asString?.takeIf { it.isNotBlank() } ?: text
                        val typed = UiAutomationService.service()?.type(
                            UiAutomationService.Lookup(into, exact = false), text,
                        ) ?: false
                        record(label, typed, if (typed) "" else "no editable field matched '$into'")
                    }
                    else -> record(label, false, "unknown op '$op'")
                }
                val stillThere = UiAutomationService.foregroundPackage() == pkg
                if (!stillThere) {
                    record("app still running after step ${index + 1}", false,
                        "foreground is ${UiAutomationService.foregroundPackage() ?: "unknown"} — it likely crashed")
                    break
                }
            }

            if (input.get("uninstall_after")?.asBoolean == true) installer.uninstall(pkg)
            result(checks, failures, started, pkg)
        }

    private data class Opened(val ok: Boolean, val detail: String)

    private suspend fun openAndWait(pkg: String, timeoutMs: Long): Opened {
        val launch = appContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Opened(false, "$pkg has no launcher activity — nothing to open")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(launch)
        } catch (e: Exception) {
            return Opened(false, "could not start it: ${e.message}")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (UiAutomationService.foregroundPackage() == pkg) {
                val nodes = UiAutomationService.awaitScreen(8_000)
                return Opened(true, "${nodes?.size ?: 0} on-screen elements")
            }
            delay(300)
        }
        val last = UiAutomationService.foregroundPackage()
        return Opened(false,
            if (last == null) "it never came to the screen (crashed on launch?)"
            else "it did not come to the front; foreground stayed $last")
    }

    private suspend fun waitForText(service: UiAutomationService, text: String, budget: Long): Boolean {
        val deadline = System.currentTimeMillis() + budget
        while (System.currentTimeMillis() < deadline) {
            val nodes = service.snapshot()
            if (nodes.any { it.text.contains(text) || it.description.contains(text) }) return true
            delay(300)
        }
        return false
    }

    private fun result(checks: List<String>, failures: List<String>, started: Long, pkg: String): ToolResult {
        val ranAnything = checks.isNotEmpty()
        val ok = failures.isEmpty() && ranAnything
        val header = if (ok) "PASS — app installed, opened and every step behaved ($pkg)"
        else if (!ranAnything) "FAIL — nothing was verified"
        else "FAIL — ${failures.size} of ${checks.size} checks failed ($pkg)"
        return ToolResult(
            ok = ok,
            output = "$header\n" + checks.joinToString("\n"),
            durationMs = System.currentTimeMillis() - started,
            error = when {
                ok -> null
                !ranAnything -> "no steps ran, so the app is not proven to work"
                else -> failures.first().removePrefix("FAIL ").trim()
            },
            stderr = failures.joinToString("\n"),
            citation = Citation("exec", "native_test:$pkg", "app opened + tapped through"),
        )
    }

    private fun fail(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message, stderr = message,
    )
}
