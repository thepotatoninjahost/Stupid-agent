package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.BuildConfig
import host.vorv.data.SecretsStore
import host.vorv.nativeapp.AppInstaller
import host.vorv.selfupdate.GitHubRepo
import host.vorv.selfupdate.SelfUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * self_update — change Vorv's own code and get the changed Vorv onto the phone.
 *
 * THE LOOP THIS COMPLETES:
 *
 *   read_source (the exact code this build shipped)
 *     → the model writes the complete changed file
 *     → push (the file becomes a commit on main; the build regenerates its copy)
 *     → GitHub's build machine compiles, tests and publishes the APK
 *     → check (has the build for that commit appeared?)
 *     → install (download it, prove it is Vorv, hand it to the system installer)
 *
 * WHY IT IS NOT `self_fix`: `self_fix` swaps ONE class at runtime and reaches
 * only the classes with a dispatch point — the agent tools, the kernel and the
 * healer. Everything else (screens, storage, the builder itself) has no
 * dispatch point, so it cannot be patched in place. This tool changes the
 * source and lets the real build happen where the whole app can be built.
 * `self_fix` is faster and works offline; this is the permanent route.
 *
 * WHAT NEEDS THE OWNER: a GitHub token with Contents read and write on this
 * repository, pasted once in Setup → Self-modification. Nothing here writes
 * without it, and every push and every install asks in the consent dialog
 * first.
 */
class SelfUpdateTool(
    private val appContext: Context,
    private val secrets: SecretsStore,
) : Tool {

    override val name = "self_update"

    override val description =
        "Change Vorv's OWN code permanently: read the shipped source of a file, write the " +
            "complete changed file, push it to the repository, wait for the build machine, then " +
            "download and install the new Vorv over the old one. Use this for changes self_fix " +
            "cannot make (screens, storage, the build pipeline) or when a change must survive " +
            "a reinstall. Requires the owner's GitHub key (Setup → Self-modification). " +
            "Actions: 'status' (versions, key, latest build), 'read_source' (path, and 'from' " +
            "to choose the copy in this build or the one on GitHub main), 'push' (files + " +
            "message), 'check' (commit — also reports whether the build is running, succeeded " +
            "or failed, and which step failed), 'install' (commit). Pushing asks the owner " +
            "first; installing asks again."

    /** A download of a ~125 MB APK plus a build can legitimately take this long. */
    override val timeoutMs: Long get() = 25 * 60_000L

    /** One change per task: this is the expensive, whole-app route. */
    override val maxCallsPerTask: Int get() = 3

    override fun schema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply {
                    add("status"); add("read_source"); add("push"); add("check"); add("install")
                })
                addProperty("description", "What to do. Defaults to 'status'.")
            })
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "read_source: the file, e.g. " +
                    "app/src/main/java/host/vorv/tools/NotifyTool.kt")
            })
            add("files", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "push: the files to change. Each entry has the FULL " +
                    "new contents of one Kotlin file — never a fragment or a diff.")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("path", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "Repository path under app/src/main/java, " +
                                "ending in .kt")
                        })
                        add("content", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "The complete new file contents.")
                        })
                    })
                    add("required", JsonArray().apply { add("path"); add("content") })
                })
            })
            add("from", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { add("shipped"); add("github") })
                addProperty("description", "read_source: 'shipped' (default) reads the copy inside " +
                    "this installed build — the code actually running. 'github' reads main on " +
                    "GitHub. Use 'github' when the change has to build on the newest main, and " +
                    "'shipped' when you must see what is running right now.")
            })
            add("message", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "push: one line saying what changed and why. It becomes " +
                    "the commit message, so it has to be true.")
            })
            add("commit", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "check/install: the commit the build must come from " +
                    "(the id push returned). Blank means 'whatever the newest build is'.")
            })
        })
        add("required", JsonArray().apply { add("action") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            fun fail(message: String) = ToolResult(
                ok = false, output = "", durationMs = System.currentTimeMillis() - t0, error = message,
            )
            fun done(message: String, citation: Citation? = null) = ToolResult(
                ok = true, output = message, durationMs = System.currentTimeMillis() - t0,
                citation = citation,
            )

            val action = input.get("action")?.takeIf { !it.isJsonNull }?.asString
                ?.trim()?.lowercase().orEmpty().ifBlank { "status" }
            val token = secrets.keyFor(SelfUpdate.KEY_ID)
            val repo = GitHubRepo(token)

            try {
                when (action) {
                    "status" -> status(repo, token.isEmpty())
                    "read_source" -> readSource(repo, input)
                    "push" -> push(repo, token.isEmpty(), input, ctx)
                    "check" -> check(repo, input)
                    "install" -> install(repo, input, ctx)
                    else -> fail("Unknown action '$action'. Use status, read_source, push, check or install.")
                }
            } catch (e: GitHubRepo.GitHubError) {
                fail(e.message ?: "GitHub request failed.")
            } catch (e: Exception) {
                fail("self_update failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    // ------------------------------------------------------------ status

    private fun status(repo: GitHubRepo, noKey: Boolean): ToolResult {
        val lines = StringBuilder()
        lines.appendLine("Vorv running: ${BuildConfig.VERSION_NAME} (version code ${BuildConfig.VERSION_CODE})")
        lines.appendLine(
            if (noKey) {
                "GitHub key: not saved — reading works, pushing needs it (Setup → Self-modification)."
            } else {
                "GitHub key: saved."
            },
        )
        val head = Attempt.of("reading the repository head") { repo.headSha() }
        lines.appendLine("Repository main: " + (head?.take(12) ?: "unreachable (see the log)"))
        var releaseError: String? = null
        val rel = try {
            repo.release()
        } catch (e: Exception) {
            releaseError = e.message ?: e.javaClass.simpleName
            null
        }
        if (releaseError != null) {
            lines.appendLine("Latest build: could not be read ($releaseError)")
        } else if (rel == null) {
            lines.appendLine("Latest build: none has been published yet (the ci-latest release is absent).")
        } else {
            val apk = SelfUpdate.apkAsset(rel)
            lines.appendLine("Latest build: ${rel.title}")
            lines.appendLine("  built from: ${rel.commitSha() ?: "a commit its notes do not name"}")
            lines.appendLine(
                "  installable APK: " + (apk?.let { "${it.name} (${it.size / 1_000_000} MB)" }
                    ?: "none — the release has no debug APK"),
            )
        }
        return ToolResult(true, lines.toString().trimEnd(), 0)
    }

    // ------------------------------------------------------------ read_source

    /**
     * Read one file, from either copy, and never let one stand in for the other.
     *
     * 'shipped' is the default because it is the code that is RUNNING: a patch
     * or a change reasoned from `main` alone can be reasoned from code this APK
     * does not contain yet. 'github' is there for the other real job — seeing
     * what `main` says now, including a file that arrived after this build.
     */
    private fun readSource(repo: GitHubRepo, input: JsonObject): ToolResult {
        val path = input.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val mirror = SelfUpdate.mirrorPathFor(path)
            ?: return failure(
                "Only Vorv's own Kotlin files can be read: a path under ${SelfUpdate.SOURCE_ROOT} " +
                    "ending in .kt, e.g. app/src/main/java/host/vorv/tools/NotifyTool.kt.",
            )
        val from = input.get("from")?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?.lowercase().orEmpty().ifBlank { "shipped" }
        if (from != "shipped" && from != "github") {
            return failure("'from' is 'shipped' (the copy inside this build) or 'github' (main on " +
                "GitHub), not '$from'.")
        }

        val shipped = Attempt.of("reading the shipped copy of $path") {
            appContext.assets.open(mirror.removePrefix(SelfUpdate.MIRROR_ROOT))
                .bufferedReader().use { it.readText() }
        }
        // GitHub is read when it was asked for, and also when this build carries
        // no copy — a file that arrived after the APK was built is still real.
        val remote = if (from == "github" || shipped == null) repo.getFile(path) else null

        val text = if (from == "github") remote else shipped ?: remote
        if (text == null) {
            return failure(
                if (from == "github") "GitHub main has no '$path'. Check the path."
                else "This build ships no '$path' and GitHub main has no such file. Check the path.",
            )
        }
        val header = buildString {
            appendLine(
                if (from == "github") "From GitHub main: $path"
                else "The copy this build shipped: $path",
            )
            // Only a comparison that actually happened may be reported.
            if (remote != null) SelfUpdate.driftNote(shipped, remote)?.let { appendLine(it) }
            appendLine("Write the COMPLETE file back with action 'push' — never a fragment.")
        }
        return ToolResult(
            ok = true,
            output = header + text,
            durationMs = 0,
            citation = Citation("file", path, if (from == "github") "GitHub main" else "shipped source"),
        )
    }

    // ------------------------------------------------------------ push

    private suspend fun push(
        repo: GitHubRepo,
        noKey: Boolean,
        input: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        if (noKey) {
            return failure(
                "No GitHub key is saved, so Vorv cannot push. The owner pastes a fine-grained " +
                    "GitHub token (Contents: read and write on this one repository) in Setup → " +
                    "Self-modification. Nothing was sent.",
            )
        }
        val confirm = ctx.confirm ?: return failure(
            "user-consent bridge unavailable — a push changes Vorv's own code, so it refuses " +
                "to run without the owner's approval (fail closed).",
        )
        val message = input.get("message")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (message.length < 8) {
            return failure("'message' must say in one line what changed and why — it becomes the commit message.")
        }
        val array = input.getAsJsonArray("files")
            ?: return failure("'files' is required: one entry per file, each with path and content.")
        if (array.size() == 0 || array.size() > SelfUpdate.MAX_FILES) {
            return failure("One change may touch 1 to ${SelfUpdate.MAX_FILES} files; this one lists ${array.size()}.")
        }

        val files = mutableListOf<Pair<String, String>>()
        for ((i, el) in array.withIndex()) {
            if (!el.isJsonObject) return failure("file ${i + 1} is not an object with path and content.")
            val o = el.asJsonObject
            val path = o.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val content = o.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (SelfUpdate.mirrorPathFor(path) == null) return failure(
                "file ${i + 1}: '$path' is not Vorv's own Kotlin source. Only paths under " +
                    "${SelfUpdate.SOURCE_ROOT} ending in .kt can be changed.",
            )
            if (content.isBlank()) return failure("file ${i + 1} ($path) is empty — refusing to erase a file.")
            if (content.length > SelfUpdate.MAX_FILE_CHARS) {
                return failure(
                    "file ${i + 1} ($path) is ${content.length} chars, over the " +
                        "${SelfUpdate.MAX_FILE_CHARS}-char limit — split the class.",
                )
            }
            files += path to content
            // The shipped copy is NOT written to the repository any more: the
            // build makes it from app/src/main/java, so a second copy here
            // would be a file that can drift from the code it claims to mirror.
        }
        val duplicates = files.groupBy { it.first }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return failure("the same file is listed twice: ${duplicates.joinToString()}")
        }

        val listed = files.joinToString("\n") { "  • ${it.first}" }
        val approved = confirm(
            "Change Vorv's own code on GitHub?\\n\\n$message\\n\\nFiles:\\n$listed\\n\\n" +
                "This becomes a commit on main, the build machine rebuilds Vorv, and the new " +
                "build replaces this app only after you approve installing it.",
        )
        if (!approved) {
            return failure("The user DENIED the push — nothing was sent to GitHub.")
        }

        val commitMessage = "Self-change: $message\\n\\nRequested by the owner in Vorv and written by Vorv.\\n"
        val written = mutableListOf<String>()
        var lastSha = ""
        for ((path, content) in files) {
            try {
                lastSha = repo.putFile(path, content, commitMessage)
                written += path
            } catch (e: GitHubRepo.GitHubError) {
                val done = if (written.isEmpty()) "nothing" else written.joinToString()
                return failure(
                    "Stopped at $path: ${e.message} Already written: $done. The repository is " +
                        "now half-changed — run the same push again to finish it.",
                )
            }
        }
        return ToolResult(
            ok = true,
            output = "Pushed ${written.size} file(s).\\n" +
                "Commit: $lastSha\\n" +
                "GitHub is building Vorv now (tests, then the APK). That takes several minutes. " +
                "Then run self_update with action 'check' and commit '$lastSha', and when that " +
                "says the build exists, run action 'install' with the same commit.",
            durationMs = 0,
            citation = Citation("exec", "github:${lastSha.take(12)}", "self-change pushed"),
        )
    }

    // ------------------------------------------------------------ check

    /**
     * Has this change been built — and if not, why not.
     *
     * The build's own progress is read BEFORE the release, because a failed
     * build publishes nothing. Without that, all the phone could ever say is
     * "no build yet", and finding out which step failed would mean opening
     * GitHub on a computer. A key without Actions: Read still gets the release
     * check: the missing permission is reported, never raised.
     */
    private fun check(repo: GitHubRepo, input: JsonObject): ToolResult {
        val commit = input.get("commit")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()

        var run: GitHubRepo.WorkflowRun? = null
        var runProblem: String? = null
        try {
            run = repo.latestRun()
        } catch (e: GitHubRepo.GitHubError) {
            runProblem = e.message ?: "the build's progress could not be read."
        }
        val newestRun = run
        val runLine = if (newestRun == null) {
            "Build progress: " + (runProblem ?: "no build has run for main yet.")
        } else {
            "Build progress: ${newestRun.name} for ${newestRun.headSha.take(12)} — " +
                runVerdict(newestRun) + if (newestRun.url.isNotBlank()) " ${newestRun.url}" else ""
        }
        val runIsThisCommit = newestRun != null && commit.isNotBlank() &&
            newestRun.headSha.startsWith(commit, ignoreCase = true)

        val release = repo.release()
        if (release == null) {
            val detail = StringBuilder("No ci-latest build has been published yet.\n")
            detail.appendLine(runLine)
            val steps = if (newestRun != null && newestRun.failed) failedDetails(repo, newestRun) else emptyList()
            when {
                steps == null -> detail.appendLine(
                    "The build reported a failure, but its steps could not be listed — the saved " +
                        "key needs Actions: Read.",
                )
                steps.isNotEmpty() -> {
                    detail.appendLine("It failed at:")
                    steps.forEach { detail.appendLine("  • $it") }
                    detail.appendLine(
                        "Nothing was installed, and nothing on the phone needs undoing. " +
                            "Fix that step and push again.",
                    )
                }
                else -> detail.appendLine(
                    "If a change was just pushed, the build is still running (it runs the full " +
                        "test suite first). Check again in a few minutes.",
                )
            }
            return ToolResult(
                ok = false,
                output = detail.toString().trimEnd(),
                durationMs = 0,
                error = "no installable build yet — the release has not published this change",
            )
        }

        val built = release.commitSha()
        val matches = commit.isEmpty() || SelfUpdate.buildMatches(release, commit)
        val apk = SelfUpdate.apkAsset(release)
        val steps = if (newestRun != null && newestRun.failed && (!matches || runIsThisCommit)) {
            failedDetails(repo, newestRun)
        } else {
            emptyList()
        }

        return ToolResult(
            ok = true,
            output = buildString {
                appendLine(
                    when {
                        commit.isEmpty() ->
                            "Newest build: ${release.title}, from ${built ?: "an unnamed commit"}."
                        matches -> "YES — the newest build is from ${built?.take(12)}, the commit you named."
                        else -> "NOT YET — the newest build is from ${built ?: "an unnamed commit"}, " +
                            "not from ${commit.take(12)}."
                    },
                )
                appendLine(runLine)
                if (steps == null) {
                    appendLine(
                        "The newest build failed, but its steps could not be listed — the saved " +
                            "key needs Actions: Read.",
                    )
                } else if (steps.isNotEmpty()) {
                    appendLine("The newest build FAILED at:")
                    steps.forEach { appendLine("  • $it") }
                    appendLine(
                        "A failed build publishes nothing, so the release above is still the " +
                            "previous build. Fix that step and push again.",
                    )
                }
                appendLine("Release: ${release.tag} — ${release.title}")
                appendLine(
                    if (apk != null) "APK: ${apk.name} (${apk.size / 1_000_000} MB)"
                    else "APK: this release carries no debug APK, so it cannot be installed.",
                )
                when {
                    matches && apk != null -> appendLine(
                        "Next: action 'install'" +
                            if (commit.isNotBlank()) " with commit '$commit'." else ".",
                    )
                    !matches -> appendLine("Wait for the build of ${commit.take(12)}, then check again.")
                }
            }.trimEnd(),
            durationMs = 0,
        )
    }

    /**
     * One sentence for a run's state, from GitHub's own status and conclusion
     * words, so nobody has to guess what "completed / action_required" means.
     */
    private fun runVerdict(run: GitHubRepo.WorkflowRun): String = when {
        !run.finished -> "still running (${run.status.replace('_', ' ')})"
        run.failed -> "FAILED (${run.conclusion})"
        run.conclusion == "success" -> "succeeded"
        run.conclusion.isBlank() -> "finished without saying how"
        else -> "finished: ${run.conclusion}"
    }

    /**
     * The failing steps, or null when they cannot be read. Null is not empty:
     * "no step failed" and "this key may not read the steps" are different facts.
     */
    private fun failedDetails(repo: GitHubRepo, run: GitHubRepo.WorkflowRun): List<String>? =
        try {
            repo.failedSteps(run.id)
        } catch (e: GitHubRepo.GitHubError) {
            null
        }

    // ------------------------------------------------------------ install

    private suspend fun install(
        repo: GitHubRepo,
        input: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val confirm = ctx.confirm ?: return failure(
            "user-consent bridge unavailable — installing an update needs the owner's approval (fail closed).",
        )
        val commit = input.get("commit")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val release = repo.release() ?: return failure(
            "There is no ci-latest build to install yet. Push a change first, then check again.",
        )
        if (commit.isNotBlank() && !SelfUpdate.buildMatches(release, commit)) {
            return failure(
                "The newest build is from ${release.commitSha() ?: "an unnamed commit"}, not from " +
                    "${commit.take(12)} — refusing to install a build that is not the one asked for. " +
                    "Wait for the build, then check again.",
            )
        }
        val asset = SelfUpdate.apkAsset(release) ?: return failure(
            "The release carries no debug APK, so there is nothing safe to install.",
        )

        val dir = File(appContext.cacheDir, "selfupdate").apply { mkdirs() }
        dir.listFiles()?.forEach { stale ->
            if (stale.isFile && stale.name.endsWith(".apk")) stale.delete()
        }
        val apk = File(dir, "vorv-${(release.commitSha() ?: "latest").take(12)}.apk")
        repo.download(asset, apk)

        val problem = SelfUpdate.verifyApk(appContext, apk)
        if (problem != null) {
            apk.delete()
            return failure("Refusing this APK: $problem")
        }

        val installed = AppInstaller(appContext).installedVersion(appContext.packageName)
        val approved = confirm(
            "Install the new Vorv over this one?\\n\\nBuild: ${release.title}\\n" +
                "Version code: ${installed ?: 0} → ${readVersionCode(apk) ?: "?"}\\n\\n" +
                "Android will show its own install prompt. Vorv closes and reopens on the new build.",
        )
        if (!approved) {
            apk.delete()
            return failure("The user DENIED the install — the downloaded build was deleted.")
        }

        val result = AppInstaller(appContext).install(apk)
        return if (result.ok) {
            ToolResult(
                ok = true,
                output = "Installing ${result.packageName}. Android may ask for confirmation; after " +
                    "that Vorv restarts on the new build. This task ends here.",
                durationMs = 0,
                citation = Citation("exec", "selfinstall:${apk.name}", "new Vorv build"),
            )
        } else {
            apk.delete()
            failure("The installer refused it: ${result.detail}")
        }
    }

    private fun readVersionCode(apk: File): Long? = Attempt.of("reading the version code of ${apk.name}") {
        appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode
    }

    private fun failure(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message,
    )
}
