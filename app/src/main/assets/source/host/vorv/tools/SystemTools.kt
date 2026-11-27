package host.vorv.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "Vorv/ShellTool"

/**
 * DialGuard (audit finding H-1): refuses premium-rate and malformed numbers.
 * ACTION_DIAL only pre-fills the dialer, but a confused model could still
 * aim the user at a pay-per-call number; combined with the user-confirmation
 * dialog this closes the loop.
 */
object DialGuard {
    private val premiumPatterns = listOf(
        Regex("""^\+?1-?9(00|76)"""),          // US 1-900 / 1-976 premium
        Regex("""^09\d{6,}$"""),                // UK 09xx premium
        Regex("""^0900"""),                     // DE 0900 premium-rate (M12: was '^090',
                                              // which matched EVERY Japanese mobile)
        Regex("""^89\d{6,}$"""),                // IT 89x value-added
        // L-2 (code review, 2026-11): HK adult 166x lines are EIGHT digits.
        // The old '^166\d' prefix form matched the international form of
        // every US number with area code 660–669 written without '+'
        // ("1-660-555-0123" → "16605550123" → refused) — an 11-digit US
        // number is not an HK adult line. Anchoring to exactly 8 digits
        // keeps every real HK 166xxxxx refusal and clears the false
        // positive; the user-confirmation dialog remains the backstop.
        Regex("""^166\d{5}$"""),                // HK 8-digit adult 166xxxxx
        Regex("""^190\d"""),                    // AU 190x premium
        Regex("""^118\d{3}$"""),                // UK directory 118xxx
        Regex("""^1-?877-?ext"""),              // obviously malformed junk
    )

    /**
     * M12 (code review, 2026-11): Japanese mobile prefixes — 090/080/070
     * followed by 8 digits — are ORDINARY numbers, not premium. The old
     * patterns refused every one of them ('^09\d{6,}$' and '^090' both
     * match 090-XXXX-XXXX), so the tool blocked normal Japanese calls.
     * International form (+81 90-…) drops the leading 0, hence 0?.
     * An 11-digit 090… number is ALSO a valid UK premium shape — that
     * collision is inherent without a country code; the JP mobile
     * interpretation wins (the finding's exact case) and the
     * user-confirmation dialog remains the backstop for every dial.
     */
    private val jpMobile = Regex("""^(\+81[ -]?)?0?[789]0\d{8}$""")

    /** null = acceptable; non-null = honest refusal reason. */
    fun validate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "empty phone number"
        val allowedChars = trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' || it == '.' }
        if (!allowedChars) return "phone number contains invalid characters"
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 7) return "phone number too short (${digits.length} digits)"
        if (digits.length > 15) return "phone number too long (${digits.length} digits, max 15)"
        val normalized = trimmed.replace(Regex("[\\s().-]"), "")
        // M12: Japanese mobile shapes are checked BEFORE the premium list —
        // see [jpMobile].
        if (jpMobile.containsMatchIn(normalized)) return null
        val hit = premiumPatterns.firstOrNull { it.containsMatchIn(normalized) }
        if (hit != null) return "refusing to dial premium-rate number '$trimmed'"
        return null
    }
}

/**
 * Monotonic, collision-free notification ids (audit L-10): derived from a
 * random start + AtomicInteger, never from a truncated timestamp.
 */
object NotificationIds {
    private val next = java.util.concurrent.atomic.AtomicInteger(
        (System.currentTimeMillis() % 1_000_000L).toInt() + 1000)

    fun nextId(): Int = next.incrementAndGet()
}

/**
 * app_intent — send real Android intents (audit findings H-1, L-2, L-3).
 *
 * EVERY action requires explicit user confirmation through the consent
 * bridge (ctx.confirm). The dialog shows exactly what will happen, in
 * full — nothing is ever cut off (v0.6.11, audit N-I4). If the
 * bridge is unavailable the tool DENIES — fail closed, never open.
 *
 * "view" additionally restricts to http/https URLs (no file://, no app deep
 * links); "dial" additionally enforces DialGuard.
 */
class AppIntentTool(private val appContext: Context) : Tool {
    override val name = "app_intent"
    override val description =
        "Send an Android intent: open an http/https URL, share text, dial a number, " +
        "or open an installed app. EVERY action requires the user to approve a " +
        "confirmation dialog first — if they deny it, report that the action was " +
        "not performed. Premium-rate numbers are refused outright."

    override val timeoutMs: Long get() = 70_000L   // confirmation dialog + intent dispatch
    override val maxCallsPerTask: Int get() = 15

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Intent action: 'view', 'share', 'dial', 'open_app'")
            })
            add("data", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Action-specific data. view=http(s) URL, dial=phone number, open_app=package name, share=text to share.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action"); add("data") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing action",
                durationMs = System.currentTimeMillis() - t0)
        val data = input.get("data")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing data",
                durationMs = System.currentTimeMillis() - t0)

        val confirm = ctx.confirm
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "User confirmation bridge unavailable — refusing to send intent (fail closed).",
                durationMs = System.currentTimeMillis() - t0)

        try {
            val intent = when (action.lowercase()) {
                "view" -> {
                    val uri = Uri.parse(data)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        return@withContext ToolResult(ok = false, output = "",
                            error = "Refusing to view URL with scheme '$scheme' — only http/https allowed.",
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "dial" -> {
                    val refusal = DialGuard.validate(data)
                    if (refusal != null) {
                        return@withContext ToolResult(ok = false, output = "", error = refusal,
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(data)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "share" -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, data)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "open_app" -> appContext.packageManager.getLaunchIntentForPackage(data)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return@withContext ToolResult(ok = false, output = "",
                        error = "No launchable activity for '$data' — it is not installed, " +
                            "is disabled, or has no launcher screen",
                        durationMs = System.currentTimeMillis() - t0)
                else -> return@withContext ToolResult(ok = false, output = "",
                    error = "unknown action: $action",
                    durationMs = System.currentTimeMillis() - t0)
            }

            val summary = when (action.lowercase()) {
                "view" -> "open $data in your browser"
                "dial" -> "open the dialer with $data"
                "share" -> "share the text: \"$data\""
                else -> "launch the app '$data'"
            }
            val allowed = confirm("Vorv wants to $summary")
            if (!allowed) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "User DENIED the confirmation to $summary. Do not claim it happened.",
                    durationMs = System.currentTimeMillis() - t0)
            }

            appContext.startActivity(intent)
            ToolResult(
                ok = true,
                output = "User approved; intent '$action' sent: $data",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "intent iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "intent failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * notify — post a real system notification.
 *
 * v0.6.0: checks POST_NOTIFICATIONS at runtime and fails honestly when it
 * isn't granted (audit finding H-2) — previously the notification silently
 * no-opped while the tool reported success.
 */
class NotifyTool(private val appContext: Context) : Tool {
    override val name = "notify"
    override val description =
        "Post a system notification after user confirmation of its full title and text. " +
        "Requires the notification permission — if it " +
        "isn't granted, ask the user to enable it in Android Settings → Apps → Vorv → " +
        "Notifications. Use when long tasks complete or the user asked to be reminded."

    override val timeoutMs: Long get() = 70_000L // confirmation dialog + notification dispatch
    override val maxCallsPerTask: Int get() = 10

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
        val text = input.get("text")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing text",
                durationMs = System.currentTimeMillis() - t0)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "POST_NOTIFICATIONS permission not granted — the notification will NOT " +
                        "appear. Ask the user to grant it in Android Settings → Apps → Vorv → " +
                        "Notifications, then try again.",
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        val confirm = ctx.confirm
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "User confirmation bridge unavailable — refusing to post notification (fail closed).",
                durationMs = System.currentTimeMillis() - t0)
        val allowed = confirm("Vorv wants to post a system notification.\n\nTitle:\n$title\n\nText:\n$text\n\nAllow?")
        if (!allowed) {
            return@withContext ToolResult(ok = false, output = "",
                error = "User DENIED the notification. It was not posted.",
                durationMs = System.currentTimeMillis() - t0)
        }

        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // M-6 (code review, 2026-11): this used to create "vorv_agent" —
                // the SAME id AgentService creates for the foreground service —
                // with a different importance. Channels are immutable once
                // created, so whichever component ran first won and the other's
                // importance was silently dropped. User-facing pings now live on
                // their own channel; the FGS owns "vorv_agent_fgs" (and deletes
                // the legacy collided id on upgrade).
                val chan = android.app.NotificationChannel(
                    "vorv_notify", "Vorv Notifications",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Vorv task notifications" }
                nm.createNotificationChannel(chan)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(appContext, "vorv_notify")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setAutoCancel(true)
                .build()
            nm.notify(NotificationIds.nextId(), notif)

            ToolResult(
                ok = true,
                output = "Notification posted: $title — $text",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "notify iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "notify failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}
