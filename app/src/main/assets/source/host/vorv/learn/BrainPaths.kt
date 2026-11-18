package host.vorv.learn

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Where the on-phone brain lives, and how much memory it is allowed to ask for.
 *
 * The budget is not a guess dressed up as a number: it is a fixed share of the
 * heap Android grants this process, and the rung chooser prints the number it
 * used, so a demotion always names its measurement.
 */
object BrainPaths {

    /** Share of the process heap training may use; the rest belongs to the UI. */
    private const val HEAP_SHARE = 0.55

    fun dir(context: Context): File = File(context.filesDir, "brain").apply {
        if (!isDirectory && !mkdirs()) throw java.io.IOException("cannot create ${path}")
    }

    fun budgetBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heap = am.memoryClass.toLong() * 1024L * 1024L
        return (heap * HEAP_SHARE).toLong()
    }

    fun describeBudget(context: Context): String {
        val mb = budgetBytes(context) / (1024L * 1024L)
        return "${mb} MB of training memory (55% of this app's heap)"
    }

    fun tokenizerFile(context: Context): File = File(dir(context), "tokenizer.txt")
}
