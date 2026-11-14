package host.vorv.nativeapp

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The eyes and fingers the agent uses on a real app.
 *
 * An [AccessibilityService] is the only supported way for a non-rooted app to
 * read another app's screen and click its buttons. It is off by default and
 * the user turns it on in Settings — it can see what is on screen, so that
 * consent is not something this app can or should skip.
 *
 * Used for one purpose: exercising an app the agent just built and installed,
 * so "it works" means someone actually tapped through it.
 */
class UiAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        instance.set(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            e.packageName?.let { currentPackage.set(it.toString()) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    /** A flattened, readable picture of what is on screen right now. */
    data class Node(
        val text: String,
        val description: String,
        val viewId: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
    )

    /**
     * Snapshot of the active window. Bounded: an unreadable screen is better
     * than a 2 MB string that silently truncates mid-sentence.
     */
    fun snapshot(maxNodes: Int = 120): List<Node> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<Node>(maxNodes)
        walk(root, out, maxNodes)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Node>, max: Int) {
        if (node == null || out.size >= max) return
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        out += Node(
            text = text,
            description = desc,
            viewId = id,
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
        )
        for (i in 0 until node.childCount) {
            if (out.size >= max) return
            walk(node.getChild(i), out, max)
        }
    }

    /** Clicks the first node whose text, description or id matches. */
    fun click(lookup: Lookup): Boolean {
        val node = find(lookup) ?: return false
        var target: AccessibilityNodeInfo? = node
        // Many UI trees put the label on a child of the clickable container.
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Types into the matching editable field. */
    fun type(lookup: Lookup, text: String): Boolean {
        val node = find(lookup) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun exists(lookup: Lookup): Boolean = find(lookup) != null

    private fun find(lookup: Lookup): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Id lookups are precise and cheap: try them first.
        if (lookup.viewId != null) {
            root.findAccessibilityNodeInfosByViewId(lookup.viewId).firstOrNull()?.let { return it }
        }
        val candidates = buildList {
            if (lookup.text != null) addAll(root.findAccessibilityNodeInfosByText(lookup.text))
        }
        val exact = candidates.firstOrNull { node ->
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            when {
                lookup.text != null && lookup.exact -> t == lookup.text || d == lookup.text
                lookup.text != null -> (t?.contains(lookup.text) == true) || (d?.contains(lookup.text) == true)
                else -> false
            }
        }
        if (exact != null) return exact
        // Fall back to a full scan for descriptions the API does not index.
        return if (lookup.text == null) null else firstMatching(root, lookup)
    }

    private fun firstMatching(node: AccessibilityNodeInfo?, lookup: Lookup): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        val needle = lookup.text ?: ""
        val hit = if (lookup.exact) t == needle || d == needle else t.contains(needle) || d.contains(needle)
        if (hit) return node
        for (i in 0 until node.childCount) {
            firstMatching(node.getChild(i), lookup)?.let { return it }
        }
        return null
    }

    /** What to look for: any of text, description or view id. */
    data class Lookup(
        val text: String? = null,
        val viewId: String? = null,
        val exact: Boolean = false,
    )

    companion object {
        private val instance = AtomicReference<UiAutomationService?>(null)
        private val currentPackage = AtomicReference<String?>(null)

        val isEnabled: Boolean get() = instance.get() != null

        fun foregroundPackage(): String? = currentPackage.get()

        /** Null when the service is not enabled by the user. */
        fun service(): UiAutomationService? = instance.get()

        suspend fun awaitScreen(timeoutMs: Long = 5_000): List<Node>? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val nodes = service()?.snapshot()
                if (!nodes.isNullOrEmpty()) return nodes
                kotlinx.coroutines.delay(250)
            }
            return null
        }
    }
}
