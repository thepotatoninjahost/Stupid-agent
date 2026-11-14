package host.vorv.nativeapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Installs an APK the agent built, through the ordinary Android installer.
 *
 * No root, no system app, no `pm install` from a shell: a normal
 * `PackageInstaller` session, which means Android shows the same consent it
 * shows for any sideloaded app. That is the honest boundary — an app that
 * installed APKs silently would be malware-shaped.
 */
class AppInstaller(private val context: Context) {

    data class Result(val ok: Boolean, val packageName: String?, val detail: String)

    init {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "PackageInstaller sessions require Android 8+"
        }
    }

    /** Reads the package name from the APK itself — never guessed from a filename. */
    fun packageNameOf(apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    fun installedVersion(pkg: String): Long? = try {
        context.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    suspend fun install(apk: File, replaceExisting: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            if (!apk.isFile || apk.length() == 0L) {
                return@withContext Result(false, null, "APK not found or empty: ${apk.absolutePath}")
            }
            val pkg = packageNameOf(apk)
                ?: return@withContext Result(false, null,
                    "not a valid APK — Android could not read a package name from ${apk.name}")

            val existing = installedVersion(pkg)
            if (existing != null && !replaceExisting) {
                return@withContext Result(false, pkg, "$pkg is already installed (version $existing)")
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(pkg) // required when replacing
                }
            val sessionId = try {
                installer.createSession(params)
            } catch (e: Exception) {
                return@withContext Result(false, pkg, "cannot open an install session: ${e.message}")
            }

            try {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("base.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
            } catch (e: Exception) {
                installer.abandonSession(sessionId)
                return@withContext Result(false, pkg, "could not write the APK: ${e.message}")
            }

            // Commit, then wait for the real verdict from the system.
            val result = suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                val action = "$ACTION_PREFIX${System.nanoTime()}"
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                        val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                        val ok = status == PackageInstaller.STATUS_SUCCESS
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) {
                            cont.resume(ok to if (ok) "" else "installer said: $message (status $status)")
                        }
                    }
                }
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                try {
                    context.packageManager.packageInstaller
                        .openSession(sessionId).use { session -> session.commit(pending.intentSender!!) }
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false to "commit failed: ${e.message}")
                }
            }

            if (result.first) {
                Result(true, pkg, "installed $pkg (version ${installedVersion(pkg) ?: "?"})")
            } else {
                Result(false, pkg, result.second.ifBlank { "the installer rejected the APK" })
            }
        }

    /** Uninstalls a package the agent installed. Never touches anything else. */
    suspend fun uninstall(pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // The platform API wants a non-null result sender; this one is
            // fire-and-forget, so the intent goes nowhere and is not exported.
            val done = PendingIntent.getBroadcast(
                context,
                pkg.hashCode(),
                Intent(UNINSTALL_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender
            context.packageManager.packageInstaller.uninstall(pkg, done)
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val ACTION_PREFIX = "host.vorv.INSTALL_RESULT."
        const val UNINSTALL_ACTION = "host.vorv.UNINSTALL_RESULT"
    }
}
