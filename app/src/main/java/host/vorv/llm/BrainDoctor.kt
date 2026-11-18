package host.vorv.llm

/**
 * Why a brain cannot answer, in the product, in plain words.
 *
 * One pure function over [BrainFacts] answers the owner's question the app used
 * to dodge: not "no brain to think with yet", but *which* piece is missing and
 * which card fixes it. Every check has a remedy that names a real place in Setup.
 */
object BrainDoctor {

    enum class State { OK, WARN, FAIL }

    data class Check(
        val name: String,
        val state: State,
        val detail: String,
        val remedy: String? = null,
    ) {
        fun line(): String = buildString {
            append(
                when (state) {
                    State.OK -> "✓ "
                    State.WARN -> "! "
                    State.FAIL -> "✗ "
                },
            )
            append(name).append(": ").append(detail)
            if (state != State.OK && remedy != null) append(" → ").append(remedy)
        }
    }

    /** Size within this fraction of the expected download is "complete". */
    private const val SIZE_TOLERANCE = 0.05

    /** Below this, the download cannot even start comfortably. */
    private const val STORAGE_FLOOR_BYTES = 1_000_000_000L

    fun diagnose(f: BrainFacts): List<Check> = buildList {
        addAll(cloudChecks(f))
        addAll(localChecks(f))
        add(generalCheck(f))
    }

    private fun cloudChecks(f: BrainFacts): List<Check> = buildList {
        val provider = f.providerLabel.ifBlank { f.providerId.ifBlank { "cloud provider" } }

        if (f.keyReadError != null) {
            add(
                Check(
                    "key",
                    State.FAIL,
                    "a key is stored but the encrypted store could not be read (${f.keyReadError})",
                    "enter the key once more in Setup → $provider",
                ),
            )
        } else if (!f.keyPresent) {
            add(
                Check(
                    "key",
                    if (f.llmMode == "local") State.WARN else State.FAIL,
                    "no key saved for $provider",
                    "Setup → $provider key → Save & Test",
                ),
            )
        } else {
            add(Check("key", State.OK, "saved for $provider"))
        }

        when {
            f.baseUrl.isBlank() -> add(
                Check("address", State.FAIL, "no provider address", "Setup → pick a provider"),
            )
            !(f.baseUrl.startsWith("https://") || f.baseUrl.startsWith("http://")) -> add(
                Check(
                    "address",
                    State.FAIL,
                    "the address does not start with https:// (${f.baseUrl})",
                    "Setup → fix the address",
                ),
            )
            else -> add(Check("address", State.OK, f.baseUrl.trimEnd('/')))
        }

        if (f.modelId.isBlank()) {
            add(
                Check("model", State.WARN, "no model chosen", "Setup → model → Refresh list, then pick one"),
            )
        } else {
            add(Check("model", State.OK, f.modelId))
        }

        f.lastProbe?.let { p ->
            when {
                p.answered -> add(Check("last test", State.OK, "$provider answered \"${p.replyText.take(60)}\""))
                p.code == 0 -> add(
                    Check("last test", State.FAIL, "no reply at all (${p.body.take(160)})", retryCard(provider)),
                )
                else -> add(
                    Check(
                        "last test",
                        State.FAIL,
                        "HTTP ${p.code} from ${p.model}: ${p.body.take(160)}",
                        remedyFor(p.code, provider),
                    ),
                )
            }
        } ?: add(Check("last test", State.WARN, "not tested yet", retryCard(provider)))

        f.lastExchange?.let { e ->
            if (!e.authorizationSent) {
                add(
                    Check(
                        "last request",
                        State.FAIL,
                        "sent to ${e.url} WITHOUT the key header — the server's 401 would be its own fault, not the key's",
                        "this is a bug; the key is always attached",
                    ),
                )
            } else if (e.code == 401 || e.code == 403) {
                add(
                    Check(
                        "last request",
                        State.FAIL,
                        "HTTP ${e.code} with the key attached: ${e.body.take(160)}",
                        "Setup → $provider → Save & Test with a fresh key",
                    ),
                )
            } else if (e.code == 200 && !e.answered) {
                add(
                    Check(
                        "last request",
                        State.FAIL,
                        "HTTP 200 but the reply carried no text — model \"${e.model}\" is probably retired",
                        "Setup → model → Refresh list and pick one the provider still serves",
                    ),
                )
            }
        }
    }

    private fun retryCard(provider: String) = "Setup → $provider → Save & Test"

    private fun remedyFor(code: Int, provider: String): String = when (code) {
        401, 403 -> "Setup → $provider → Save & Test with a fresh key"
        404 -> "Setup → model → Refresh list; the model id is not served there"
        429 -> "the provider is busy — wait, or add a second provider in Setup"
        in 500..599 -> "the provider is failing — add a second provider in Setup"
        else -> "Setup → $provider → Save & Test"
    }

    private fun localChecks(f: BrainFacts): List<Check> = buildList {
        val name = f.localModelName.ifBlank { f.localModelId.ifBlank { "the on-phone coder" } }

        if (!f.localDownloaded) {
            add(
                Check(
                    "on-phone coder",
                    if (f.llmMode == "local") State.FAIL else State.WARN,
                    "$name is not downloaded",
                    "Setup → $name → Download",
                ),
            )
            return@buildList
        }

        val expected = f.localExpectedBytes
        if (expected > 0) {
            val off = kotlin.math.abs(f.localBytes - expected).toDouble() / expected
            if (off > SIZE_TOLERANCE) {
                add(
                    Check(
                        "on-phone coder",
                        State.FAIL,
                        "$name is on disk but wrong size: ${mb(f.localBytes)} of ${mb(expected)}",
                        "Setup → $name → Download again (the last one did not finish)",
                    ),
                )
            } else {
                add(Check("on-phone coder", State.OK, "$name ${mb(f.localBytes)}, size as expected"))
            }
        } else {
            add(Check("on-phone coder", State.OK, "$name ${mb(f.localBytes)} on disk"))
        }

        f.localInitError?.let {
            add(Check("model engine", State.FAIL, "the model engine did not start: ${it.take(200)}", "reopen the app; if it repeats, re-download"))
        }
        f.localEngineError?.let {
            add(Check("model engine", State.FAIL, "the engine failed to build: ${it.take(200)}", "Setup → $name → Download again"))
        }
        f.localComputeUnit?.let {
            add(Check("compute unit", State.OK, "running on $it"))
        }
        if (f.abis.isNotEmpty() && f.abis.none { it == "arm64-v8a" }) {
            add(
                Check(
                    "device",
                    State.FAIL,
                    "this device is ${f.abis.joinToString()} — the on-phone coder needs arm64-v8a",
                    "use a cloud provider on this device",
                ),
            )
        }
    }

    private fun generalCheck(f: BrainFacts): Check {
        f.lastRuntimeError?.let {
            return Check("last chat error", State.FAIL, it.take(240), "see the rows above for the cause")
        }
        if (f.freeStorageBytes in 0 until STORAGE_FLOOR_BYTES) {
            return Check("storage", State.WARN, "only ${mb(f.freeStorageBytes)} free", "free space before a download")
        }
        return Check("last chat", State.OK, "no error recorded")
    }

    private fun mb(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6)
        else -> "$bytes bytes"
    }

    /** One block of text for the Copy button, and for the episode log. */
    fun report(f: BrainFacts): String = buildString {
        appendLine("Vorv brain health")
        appendLine("mode chosen: ${f.llmMode}")
        diagnose(f).forEach { appendLine(it.line()) }
    }.trimEnd()
}
