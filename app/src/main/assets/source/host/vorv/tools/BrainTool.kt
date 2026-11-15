package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.brain.CharBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * brain — train and query the model that lives on this phone.
 *
 * The weights are this app's own: `train` runs backpropagation over the text
 * it is given, changes the weights, and writes them to disk. Nothing leaves
 * the device and no server is involved, so "it learned from your data" is
 * something the user can see happening (the loss) rather than take on trust.
 *
 * It is a small model on purpose: a few thousand parameters learn the patterns
 * in the owner's notes, corrections and code. It does not write new programs —
 * the cloud model does that — and the description says so, because a tool that
 * oversells itself is worse than one that is modest and true.
 */
class BrainTool(private val appContext: Context) : Tool {

    override val name = "brain"
    override val description =
        "Train and query Vorv's own on-device model. action=train learns from text you pass " +
            "(or a file inside the app) by changing the weights, and reports the loss before and after so " +
            "you can see whether it learned; the weights are saved and last across restarts. " +
            "action=generate predicts what comes next from a seed using the current weights. " +
            "action=status reports how many parameters exist and whether anything has been learned yet. " +
            "This model is small: it learns patterns in the data it is given, it does not write new programs."

    override val timeoutMs: Long get() = 240_000L
    override val maxCallsPerTask: Int get() = 20

    private val brainFile: File get() = File(appContext.filesDir, "brain/brain.txt")

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { add("train"); add("generate"); add("status") })
            })
            add("text", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "For train: the text to learn from. For generate: the seed.")
            })
            add("steps", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Gradient steps for train (default 600, max 4000).")
            })
        })
        add("required", JsonArray().apply { add("action") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            when (input.get("action")?.asString) {
                "train" -> train(input.get("text")?.asString, input.get("steps")?.asInt ?: 600, started)
                "generate" -> generate(input.get("text")?.asString ?: "", started)
                "status" -> status(started)
                else -> fail("action must be one of train, generate, status")
            }
        }

    private fun train(text: String?, steps: Int, started: Long): ToolResult {
        if (text.isNullOrBlank()) return fail("text is required for train")
        if (text.length <= CharBrain.CONTEXT + 1) {
            return fail("give at least ${CharBrain.CONTEXT + 2} characters to learn from; ${text.length} is not enough")
        }
        val capped = steps.coerceIn(10, 4_000)
        val vocab = CharBrain.vocabularyOf(text)
        // Continue from the saved brain when its vocabulary can represent this
        // text, so repeated training accumulates instead of starting over.
        val saved = CharBrain.load(brainFile)?.takeIf { existing -> text.all { it in existing.vocab } }
        val brain = saved ?: CharBrain(vocab)
        val report = brain.train(text, capped, seed = System.nanoTime())
        brain.save(brainFile)
        val size = brainFile.length() / 1024
        return ToolResult(
            ok = true,
            output = report.sentence() +
                "\nparameters: ${brain.parameterCount} (${size} KB on disk at ${brainFile.parentFile?.name}/brain.txt)" +
                "\nlearned from ${text.length} characters over $capped steps" +
                if (saved == null) "\nstarted a fresh model for this vocabulary" else "\ncontinued from the saved weights",
            durationMs = System.currentTimeMillis() - started,
            citation = Citation("learn", "brain", "trained Vorv's own model on this phone"),
        )
    }

    private fun generate(seed: String, started: Long): ToolResult {
        val brain = CharBrain.load(brainFile)
            ?: return fail("nothing learned yet — call brain with action=train first")
        val out = brain.generate(seed, 160)
        return ToolResult(
            ok = true,
            output = out,
            durationMs = System.currentTimeMillis() - started,
            citation = Citation("learn", "brain", "generated by Vorv's own trained model"),
        )
    }

    private fun status(started: Long): ToolResult {
        val brain = CharBrain.load(brainFile)
            ?: return ToolResult(
                ok = true,
                output = "no weights on this phone yet — action=train starts the first model",
                durationMs = System.currentTimeMillis() - started,
            )
        return ToolResult(
            ok = true,
            output = "parameters: ${brain.parameterCount}" +
                "\nvocabulary: ${brain.vocab.size} characters" +
                "\ncontext: ${CharBrain.CONTEXT} characters" +
                "\nweights: ${brainFile.length() / 1024} KB in files/brain/brain.txt" +
                "\nnext character it predicts after \"" + "the " + "\": " +
                brain.generate("the ", 24).removePrefix("the "),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun fail(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message, stderr = message,
    )
}
