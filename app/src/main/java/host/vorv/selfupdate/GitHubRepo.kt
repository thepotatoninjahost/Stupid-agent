package host.vorv.selfupdate

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The owner's repository, spoken to over the ordinary GitHub REST API.
 *
 * WHY THIS EXISTS: Vorv can already rewrite one of its own classes and run the
 * rewrite on the next start (`self_fix`), but that reaches only the classes with
 * a dispatch point, and it lives inside one installed build. A change to
 * anything else — the screens, the database, the build pipeline itself — is
 * only real when the whole app is rebuilt. Vorv cannot rebuild itself on the
 * phone: a modern resource table needs aapt2, which Google publishes only for
 * host operating systems, and the whole-app build needs Compose, Kotlin and the
 * Gradle plugin set. The build machine in `.github/workflows/build.yml` can do
 * it. This class is the phone's half of that loop: read the repository, put a
 * new file in it, and read back the rolling release the build machine
 * publishes.
 *
 * HONEST LIMITS: a token is needed to write; without one every call here is
 * read-only. Nothing in this class decides anything — the tool asks the owner
 * first, and [SelfUpdate.verifyApk] checks a downloaded APK before it can be
 * installed.
 *
 * API shape, checked against GitHub's documentation on 2026-11-28:
 * `PUT /repos/{owner}/{repo}/contents/{path}` needs `message`, `content`
 * (base64) and, when the file already exists, its blob `sha`
 * (<https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents>).
 * Prereleases are excluded from `GET /releases/latest`, so the rolling build is
 * read by tag instead
 * (<https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name>).
 * Release assets download from the asset `url` with
 * `Accept: application/octet-stream`, which redirects to a signed storage URL.
 */
class GitHubRepo(
    private val token: String?,
    private val client: OkHttpClient = defaultClient(),
) {

    /** A failure with a sentence the owner can act on, never a bare status code. */
    class GitHubError(message: String) : Exception(message)

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val assets: List<Asset>,
    ) {
        /**
         * The commit this build came from, exactly as the workflow writes it:
         * "Automated CI output of commit 1a2b3c4 …" in the notes, or
         * "Vorv CI build 1a2b3c4 (debug + unsigned release)" in the title.
         * Null for a build that does not say — which is treated as "cannot
         * prove it is the build you asked for", never as a match.
         */
        fun commitSha(): String? {
            Regex("commit\\s+([0-9a-f]{7,40})", RegexOption.IGNORE_CASE)
                .find(notes)?.groupValues?.get(1)?.let { return it }
            Regex("build\\s+([0-9a-f]{7,40})", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.get(1)?.let { return it }
            return null
        }
    }

    // ------------------------------------------------------------ reads

    /** The commit `main` points at right now. */
    fun headSha(): String {
        val (code, body) = call("GET", "$API/repos/$OWNER/$REPO/commits/$BRANCH", null)
        if (code !in 200..299) throw GitHubError(explain(code, body))
        return JSONObject(body).optString("sha")
            .ifBlank { throw GitHubError("GitHub answered without a commit id — try again.") }
    }

    /** The blob id of a file on `main`, or null when the file does not exist there. */
    fun fileSha(path: String): String? {
        val (code, body) = call("GET", contentsUrl(path), null)
        if (code == 404) return null
        if (code !in 200..299) throw GitHubError(explain(code, body))
        return JSONObject(body).optString("sha").ifBlank { null }
    }

    /**
     * The text of one file on `main`, or null when that file is not there.
     *
     * The contents endpoint hands a file back base64-encoded inside JSON
     * (`"encoding": "base64"`), and that base64 is wrapped in newlines which
     * are not padding, so they are stripped before decoding —
     * <https://docs.github.com/en/rest/repos/contents#get-repository-content>.
     * A directory, a symlink to one, or a file too large for this endpoint has
     * no `content`; that is reported as a sentence, never as an empty file.
     */
    fun getFile(path: String): String? {
        val (code, body) = call("GET", contentsUrl(path), null)
        if (code == 404) return null
        if (code !in 200..299) throw GitHubError(explain(code, body))
        val o = JSONObject(body)
        val encoding = o.optString("encoding")
        val content = o.optString("content")
        if (encoding != "base64" || content.isBlank()) {
            throw GitHubError(
                "'$path' has no text this can read on GitHub (encoding '${encoding.ifBlank { "none" }}') " +
                    "— it may be a folder, a symlink to one, or larger than 1 MB.",
            )
        }
        return String(
            Base64.decode(content.replace("\n", "").replace("\r", ""), Base64.DEFAULT),
            Charsets.UTF_8,
        )
    }

    /** One run of the build workflow, as GitHub reports it. */
    data class WorkflowRun(
        val id: Long,
        val headSha: String,
        val name: String,
        val status: String,      // queued | in_progress | completed
        val conclusion: String,  // success | failure | cancelled | … (empty while running)
        val url: String,         // the page a person can open
    ) {
        val finished: Boolean get() = status == "completed"
        val failed: Boolean get() = conclusion in FAILED_CONCLUSIONS
    }

    /**
     * The newest run of the build workflow on `main`, or null when none exists.
     *
     * Reading runs needs the key's **Actions: Read** permission; a key without
     * it gets the sentence that says so instead of an empty answer
     * (<https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens>:
     * `GET /repos/{owner}/{repo}/actions/runs` needs the Actions permission at
     * read; <https://docs.github.com/en/rest/actions/workflow-runs>).
     */
    fun latestRun(): WorkflowRun? {
        val (code, body) = call("GET", "$API/repos/$OWNER/$REPO/actions/runs?branch=$BRANCH&per_page=1", null)
        if (code == 404) return null
        if (code !in 200..299) throw GitHubError(explainActions(code, body))
        val run = JSONObject(body).optJSONArray("workflow_runs")?.optJSONObject(0) ?: return null
        return WorkflowRun(
            id = run.optLong("id"),
            headSha = run.optString("head_sha"),
            name = run.optString("name").ifBlank { RUN_NAME },
            status = run.optString("status"),
            conclusion = run.optString("conclusion"),
            url = run.optString("html_url"),
        )
    }

    /**
     * Which steps of [runId] failed, as "job → step". Empty when nothing failed;
     * null when the steps cannot be read at all (a key without Actions: Read),
     * so the caller can say that instead of reporting a clean run.
     */
    fun failedSteps(runId: Long): List<String>? {
        val (code, body) = try {
            call("GET", "$API/repos/$OWNER/$REPO/actions/runs/$runId/jobs?per_page=20", null)
        } catch (e: GitHubError) {
            return null
        }
        if (code !in 200..299) return null
        val jobs = JSONObject(body).optJSONArray("jobs") ?: return emptyList()
        val bad = mutableListOf<String>()
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            val jobName = job.optString("name").ifBlank { "job ${i + 1}" }
            val before = bad.size
            val steps = job.optJSONArray("steps")
            if (steps != null) {
                for (s in 0 until steps.length()) {
                    val step = steps.optJSONObject(s) ?: continue
                    if (step.optString("conclusion") in FAILED_CONCLUSIONS) {
                        bad += "$jobName → ${step.optString("name").ifBlank { "step ${s + 1}" }}"
                    }
                }
            }
            // A job can also die before any step starts (runner lost, timeout).
            if (bad.size == before && job.optString("conclusion") in FAILED_CONCLUSIONS) {
                bad += "$jobName (no single step is to blame — the job itself failed)"
            }
        }
        return bad
    }

    /** The rolling build the workflow republishes on every push to `main`. */
    fun release(tag: String = CI_TAG): Release? {
        val (code, body) = call("GET", "$API/repos/$OWNER/$REPO/releases/tags/$tag", null)
        if (code == 404) return null
        if (code !in 200..299) throw GitHubError(explain(code, body))
        val o = JSONObject(body)
        val assetsJson: JSONArray = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsJson.length()).mapNotNull { i ->
            val a = assetsJson.optJSONObject(i) ?: return@mapNotNull null
            val url = a.optString("url").ifBlank { a.optString("browser_download_url") }
            if (url.isBlank()) return@mapNotNull null
            Asset(
                name = a.optString("name"),
                url = url,
                size = a.optLong("size", 0L),
            )
        }
        return Release(
            tag = o.optString("tag_name", tag),
            title = o.optString("name"),
            notes = o.optString("body"),
            assets = assets,
        )
    }

    // ------------------------------------------------------------ writes

    /**
     * Replace (or create) one file on `main` and return the new commit id.
     *
     * A 409 means someone else changed the file between the blob read and the
     * write; that is retried once with a fresh blob id, and a second 409 is
     * reported as what it is instead of silently overwriting another change.
     */
    fun putFile(path: String, content: String, message: String): String {
        repeat(2) { attempt ->
            val sha = fileSha(path)
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("branch", BRANCH)
                .apply { if (sha != null) put("sha", sha) }
            val (code, text) = call("PUT", contentsUrl(path), body.toString())
            if (code in 200..299) {
                return JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
            }
            if (code != 409 || attempt == 1) throw GitHubError(explain(code, text))
        }
        throw GitHubError("GitHub kept reporting a conflict (409) — nothing was overwritten.")
    }

    /** Stream a release asset into [dest]. Writes a `.part` file and renames it, so a half download never looks complete. */
    fun download(asset: Asset, dest: File): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder()
            .url(asset.url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw GitHubError(
                        "the build download failed: GitHub answered HTTP ${resp.code}" +
                            if (resp.code == 403 || resp.code == 404) " — the build may have been replaced by a newer one; run status again." else ".",
                    )
                }
                val body = resp.body ?: throw GitHubError("the build download returned no bytes.")
                val length = body.contentLength()
                if (length > MAX_APK_BYTES) {
                    throw GitHubError("that asset is ${length / 1_000_000} MB — far larger than an APK should be; refusing it.")
                }
                part.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (!part.renameTo(dest)) {
                dest.delete()
                if (!part.renameTo(dest)) {
                    throw GitHubError("the downloaded file could not be moved into place.")
                }
            }
            return dest
        } catch (e: GitHubError) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw GitHubError("the build download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ internals

    private fun contentsUrl(path: String): String =
        "$API/repos/$OWNER/$REPO/contents/${path.trim('/')}?ref=$BRANCH"

    private fun call(method: String, url: String, json: String?): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val body = when {
            json != null -> json.toRequestBody(JSON)
            method == "PUT" || method == "POST" -> "".toRequestBody(JSON)
            else -> null
        }
        val request = builder.method(method, body).build()
        return try {
            client.newCall(request).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            throw GitHubError(
                "GitHub could not be reached from the phone — check the network. " +
                    "(${e.message ?: e.javaClass.simpleName})",
            )
        }
    }

    /** Actions: Read is a separate permission from Contents, and its absence is explained, not guessed at. */
    private fun explainActions(code: Int, body: String): String = when (code) {
        403 -> "GitHub would not show the build's progress (403). The saved key needs one more " +
            "permission: Actions: Read. GitHub → Settings → Developer settings → Fine-grained " +
            "tokens → the token → Permissions → Repository permissions → Actions: Read and write, " +
            "with Actions set to Read. Everything else already works without it."
        404 -> "That build is no longer on GitHub (404) — a newer push may have replaced it."
        else -> explain(code, body)
    }

    private fun explain(code: Int, body: String): String = when {
        code == 401 -> "GitHub rejected the saved key (401) — it is wrong, expired, or not allowed to do this."
        code == 403 -> "GitHub refused this action for the saved key (403) — the token needs Contents: read and write on this repository."
        code == 404 -> "GitHub could not find that (404) — the file path or the build tag does not exist."
        code == 409 -> "the file changed on GitHub since it was read (409) — run the change again."
        code == 422 -> "GitHub refused the request (422): ${body.take(200)}"
        code >= 500 -> "GitHub is having an outage (HTTP $code) — try again later."
        else -> "GitHub answered HTTP $code: ${body.take(200)}"
    }

    companion object {
        const val OWNER = "thepotatoninjahost"
        const val REPO = "Stupid-agent"
        const val BRANCH = "main"
        const val CI_TAG = "ci-latest"

        /** The workflow's name, used only when GitHub does not name the run. */
        const val RUN_NAME = "Build Vorv APK"

        private val FAILED_CONCLUSIONS =
            setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure")

        private const val API = "https://api.github.com"
        // The version GitHub's documentation names today; the endpoints used here
        // (contents PUT, release by tag, asset download) are unchanged by it.
        private const val API_VERSION = "2026-03-10"
        private const val USER_AGENT = "Vorv-self-update"
        private const val MAX_APK_BYTES = 400L * 1024 * 1024

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // The APK is ~125 MB; a phone connection needs room. A stalled
            // download still fails on this read timeout instead of hanging.
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
