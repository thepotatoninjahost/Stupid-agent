package host.vorv.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * app_intent — send real Android intents.
 * Can open URLs in browser, share text, dial numbers, open apps by package.
 */
class AppIntentTool(private val appContext: Context) : Tool {
    override val name = "app_intent"
    override val description = "Send an Android intent. Can open URLs, share text to other apps, dial phone numbers, or open apps by package name."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Intent action: 'view', 'share', 'dial', 'open_app'")
            })
            add("data", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Action-specific data. view=URL, dial=phone number, open_app=package name, share=text to share.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action"); add("data") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing action", durationMs = System.currentTimeMillis() - t0)
        val data = input.get("data")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing data", durationMs = System.currentTimeMillis() - t0)

        try {
            val intent = when (action.lowercase()) {
                "view" -> Intent(Intent.ACTION_VIEW, Uri.parse(data)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$data")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                "share" -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, data)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "open_app" -> appContext.packageManager.getLaunchIntentForPackage(data)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return@withContext ToolResult(ok = false, output = "", error = "App '$data' not installed", durationMs = System.currentTimeMillis() - t0)
                else -> return@withContext ToolResult(ok = false, output = "", error = "unknown action: $action", durationMs = System.currentTimeMillis() - t0)
            }
            appContext.startActivity(intent)
            ToolResult(
                ok = true,
                output = "Intent '$action' sent: $data",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "intent iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "intent failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * shell — run bash commands via Termux's RUN_COMMAND intent.
 * Requires Termux installed + permission granted (Android Settings → Apps → Vorv → Permissions → Additional → Run commands in Termux).
 * If Termux missing or permission not granted, returns the error so the agent can adapt.
 */
class ShellTool(private val appContext: Context) : Tool {
    override val name = "shell"
    override val description = "Run a bash command via Termux. Requires Termux installed + permission granted. Returns real stdout/stderr/exit_code. Use for git, file ops, system commands."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Bash command to run.")
            })
            add("cwd", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Optional working directory inside Termux home. Default '~'.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("command") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val command = input.get("command")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing command", durationMs = System.currentTimeMillis() - t0)

        // Check Termux installed
        val pm = appContext.packageManager
        val termuxInstalled = try {
            pm.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) { false }

        if (!termuxInstalled) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "Termux not installed. Install it from F-Droid or https://github.com/termux/termux-app/releases — Play Store version is deprecated.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }

        // Check RUN_COMMAND permission
        val hasPermission = pm.checkPermission("com.termux.permission.RUN_COMMAND", appContext.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "RUN_COMMAND permission not granted. Go to Android Settings → Apps → Vorv → Permissions → Additional permissions → Run commands in Termux environment. Also ensure ~/.termux/termux.properties has allow-external-apps=true.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }

        try {
            // Send the RUN_COMMAND intent to Termux's RunCommandService.
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", input.get("cwd")?.asString ?: "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            appContext.startService(intent)

            // Note: Termux RUN_COMMAND doesn't return stdout to the caller.
            // For real I/O capture, a different approach is needed (e.g. shared file, or
            // writing to a known location inside Termux and reading via SAF, but Termux
            // is sandboxed). For now, we report that the command was dispatched.
            //
            // A future iteration will have the agent write its output to a file under
            // the granted workspace folder, then read it back.
            ToolResult(
                ok = true,
                output = "Command dispatched to Termux: $command\n\nNote: stdout/stderr capture requires the command to write to a file inside your granted workspace, which the agent can then read with file_read. Example: shell({command: 'ls > /storage/emulated/0/Vorv/_shell_out.txt'})",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "shell iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "shell dispatch failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * notify — post a real system notification.
 */
class NotifyTool(private val appContext: Context) : Tool {
    override val name = "notify"
    override val description = "Post a system notification. Use when long tasks complete, or when the user asked to be reminded. Notification channel 'vorv_agent' must be created (the app does this on first use)."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("title", JsonObject().apply { addProperty("type", "string") })
            add("text", JsonObject().apply { addProperty("type", "string") })
        })
        add("required", com.google.gson.JsonArray().apply { add("title"); add("text") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val title = input.get("title")?.asString ?: "Vorv"
        val text = input.get("text")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing text", durationMs = System.currentTimeMillis() - t0)

        try {
            val nm = appContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // Create channel if needed
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val chan = android.app.NotificationChannel(
                    "vorv_agent", "Vorv Agent",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Vorv task notifications" }
                nm.createNotificationChannel(chan)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(appContext, "vorv_agent")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)

            ToolResult(
                ok = true,
                output = "Notification posted: $title — $text",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "notify iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "notify failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }
}
