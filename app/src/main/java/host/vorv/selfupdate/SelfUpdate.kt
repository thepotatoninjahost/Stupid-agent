package host.vorv.selfupdate

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/**
 * The rules a self-change must obey, kept apart from the network so they can be
 * tested without one.
 *
 * THREE JOBS:
 *
 * 1. Know where the shipped copy of a source file lives. Vorv carries its own
 *    source in `assets/source` (that is what `self_fix` reads before it writes
 *    a patch). The build generates that copy from `app/src/main/java` and
 *    git-ignores it since 0.9.37, so it cannot drift and nothing else writes
 *    it. This file owns the one path rule that turns
 *    `app/src/main/java/host/vorv/…` into `app/src/main/assets/source/host/vorv/…`.
 *
 * 2. An update APK must be provably Vorv before Android is asked to install
 *    it. Android's own conditions for accepting an update are the same three
 *    checked here — same application id, same signing certificate, version
 *    code not lower than the installed one
 *    (<https://developer.android.com/google/play/app-updates>: "The application
 *    ID of the update must be the same as the installed app. The signing
 *    certificate of the update must be the same as the signing certificate of
 *    the installed app … The version code of the update must be higher or equal
 *    to the version code of the installed app."). Checking them here turns a
 *    silent installer rejection into a sentence that says which rule failed.
 *
 * 3. When the shipped copy and GitHub's copy of a file disagree, say so. Vorv
 *    can read GitHub (that is what [driftNote] is for); the shipped copy is not
 *    a substitute for it, it is the code this installed APK was built from.
 */
object SelfUpdate {

    /** Where Vorv's own Kotlin lives in the repository. */
    const val SOURCE_ROOT = "app/src/main/java/"

    /** Where the APK's read-only copy of that source lives. */
    const val MIRROR_ROOT = "app/src/main/assets/source/"

    /** The debug APK the workflow publishes on the rolling release. */
    const val APK_ASSET = "app-debug.apk"

    /** The provider id the GitHub key is stored under (SecretsStore, encrypted). */
    const val KEY_ID = "github"

    /** The biggest file a self-change may write. Bigger means it is not a source file. */
    const val MAX_FILE_CHARS = 200_000

    /** How many files one self-change may touch. */
    const val MAX_FILES = 6

    /** True for a Kotlin file inside Vorv's own source tree, and nothing else. */
    fun isSelfSourcePath(path: String): Boolean {
        val p = path.trim()
        if (!p.startsWith(SOURCE_ROOT) || !p.endsWith(".kt")) return false
        if (p.contains("..") || p.contains("//") || p.contains('\\')) return false
        val relative = p.removePrefix(SOURCE_ROOT)
        return relative.length > ".kt".length && relative.contains('/')
    }

    /**
     * The shipped copy of [sourcePath], or null when the path is not Vorv's own
     * source. The build writes that copy from the source tree, so this is the
     * path of the code the installed APK was compiled from.
     */
    fun mirrorPathFor(sourcePath: String): String? {
        if (!isSelfSourcePath(sourcePath)) return null
        return MIRROR_ROOT + sourcePath.trim().removePrefix(SOURCE_ROOT)
    }

    /**
     * The honest sentence when the copy this build ships and the copy on GitHub
     * main are not the same file, or null when they agree (or when nothing was
     * compared).
     *
     * WHY THIS EXISTS: "Vorv cannot read GitHub" was wrong. It can, and
     * `read_source` does. The shipped copy exists because `self_fix` must patch
     * the code that is RUNNING — the code this APK was built from — and `main`
     * can already be ahead of it (a change pushed but not yet built, tested and
     * installed). So both are readable, and when they differ this says which one
     * the phone actually runs instead of letting one stand in for the other.
     */
    fun driftNote(shipped: String?, remote: String?): String? = when {
        shipped == null && remote == null -> null
        remote == null -> "GitHub has no copy of this file on main — only the shipped copy exists. " +
            "If it was deleted on purpose, do not push it back blindly."
        shipped == null -> "This build ships no copy of this file, so it was read from GitHub main. " +
            "A file this build does not carry may have arrived after it was built."
        shipped.normaliseForCompare() == remote.normaliseForCompare() -> null
        else -> "The shipped copy and GitHub main DIFFER. The phone RUNS the shipped copy; a patch " +
            "built from GitHub's copy may not match what is running. Read 'from: github' to change " +
            "main, and remember the running app stays as it is until a new build is installed."
    }

    /** Line endings are not a code difference; a file written on Windows must not look changed. */
    private fun String.normaliseForCompare() = replace("\r\n", "\n")

    /** The APK to install from a release: the debug build, never the unsigned one. */
    fun apkAsset(release: GitHubRepo.Release): GitHubRepo.Asset? =
        release.assets.firstOrNull { it.name == APK_ASSET }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") && it.name.contains("debug") }

    /**
     * Whether [release] was built from [commit]. A prefix in either direction is
     * enough (the workflow writes the short SHA). A release that names no commit
     * is never a match: "probably the right build" is not a reason to install.
     */
    fun buildMatches(release: GitHubRepo.Release?, commit: String): Boolean {
        val wanted = commit.trim().lowercase()
        if (wanted.isEmpty() || release == null) return false
        val built = release.commitSha()?.lowercase() ?: return false
        return built.startsWith(wanted) || wanted.startsWith(built)
    }

    /**
     * Null when [apk] may replace the running Vorv; otherwise the sentence that
     * says why it may not. Fails closed: anything unreadable is a refusal.
     */
    fun verifyApk(context: Context, apk: File): String? {
        if (!apk.isFile || apk.length() == 0L) {
            return "the downloaded file is missing or empty — nothing to install."
        }
        val pm = context.packageManager
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "Android could not read an app from that file — it is not a valid APK."
        if (incoming.packageName != context.packageName) {
            return "that APK is ${incoming.packageName}, not ${context.packageName} — refusing to install another app over Vorv."
        }
        val installed = try {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return "Android does not report Vorv as installed, so there is nothing to update."

        if (incoming.longVersionCode < installed.longVersionCode) {
            return "that build is version ${incoming.longVersionCode}, older than the installed " +
                "${installed.longVersionCode} — Android refuses downgrades."
        }
        val have = signerDigests(installed.signingInfo)
        val want = signerDigests(incoming.signingInfo)
        if (have.isEmpty() || want.isEmpty()) {
            return "the signing certificates could not be read — refusing to install a build that cannot be proven to be Vorv."
        }
        if (have != want) {
            return "that APK is signed with a different key than the installed Vorv — Android would reject it " +
                "(and nothing may replace Vorv with a build that is not really Vorv)."
        }
        return null
    }

    /** SHA-256 of every signer certificate, so two APKs can be compared without keeping the certs. */
    private fun signerDigests(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val signers = if (info.hasMultipleSigners()) {
            info.apkContentsSigners
        } else {
            info.signingCertificateHistory
        }
        return signers?.map { cert ->
            MessageDigest.getInstance("SHA-256")
                .digest(cert.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }?.toSet().orEmpty()
    }
}
