package host.vorv.healer

import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * PatchVerifier — the COMPLETENESS gate (v0.6.8; user requirement: "when it
 * self-fixes, the fix must be complete and correct — no partial fixes").
 *
 * A patch that compiles is NOT a complete fix: the model may have dropped a
 * method, renamed a parameter default, or forgotten the dispatch interface.
 * Before a patch is signed or shown to the user, it is loaded in an
 * ISOLATED classloader (its own dex, the app classes on the parent path)
 * and compared against the ORIGINAL running class:
 *
 *   1. every PUBLIC constructor of the original must exist with a
 *      compatible parameter list in the patch;
 *   2. every PUBLIC method (including statics, and interface methods the
 *      original inherits from its dispatch base) must exist;
 *   3. every PUBLIC field must survive;
 *   4. the class must implement its PATCH-DISPATCH interface
 *      ([host.vorv.watchdog.PatchRegistry.dispatchBaseFor]) — the exact
 *      requirement for the patch to ever RUN;
 *   5. the class must be concrete and instantiable (not abstract).
 *
 * Failures return the exact list of omissions, which the heal loop feeds
 * back into the regeneration prompt. Nothing partial ever reaches the
 * signing step or the user.
 *
 * ON JVM vs ART: this verifier runs identically on-device (ART) and in
 * Robolectric tests (JVM) — it only needs Class objects and a classloader
 * that can see the patch dex plus the app classes.
 */
object PatchVerifier {

    private const val TAG = "Vorv/PatchVerifier"

    data class Verification(
        val ok: Boolean,
        val missingConstructors: List<String>,
        val missingMethods: List<String>,
        val missingFields: List<String>,
        val problems: List<String>,
    ) {
        fun asFeedback(): String = buildString {
            if (ok) return@buildString
            appendLine("Your patch is INCOMPLETE — it was rejected by the completeness check.")
            if (missingConstructors.isNotEmpty()) {
                appendLine("Missing constructors:")
                missingConstructors.forEach { appendLine("  - $it") }
            }
            if (missingMethods.isNotEmpty()) {
                appendLine("Missing methods:")
                missingMethods.forEach { appendLine("  - $it") }
            }
            if (missingFields.isNotEmpty()) {
                appendLine("Missing public fields:")
                missingFields.forEach { appendLine("  - $it") }
            }
            problems.forEach { appendLine("Problem: $it") }
            appendLine("Regenerate the COMPLETE class, keeping every public member of the original.")
        }
    }

    /**
     * Verify [patchDex] against [originalClass]. The patch class must be the
     * same FQCN as [originalClass].
     *
     * @param appClasspath the app's own classes (jar/dex dirs) so the patch
     *   can link against the types it references — the healer classpath
     *   extracted from the APK (audit H-4: implemented, not stubbed).
     */
    fun verify(
        patchDex: File,
        originalClass: Class<*>,
        appClasspath: List<File>,
    ): Verification {
        val fqcn = originalClass.name
        val missingCtors = mutableListOf<String>()
        val missingMethods = mutableListOf<String>()
        val missingFields = mutableListOf<String>()
        val problems = mutableListOf<String>()

        // --- isolated loader: patch dex FIRST, app classes on the parent ---
        val loader = try {
            PathClassLoader(
                patchDex.absolutePath,
                appClasspath.joinToString(":") { it.absolutePath },
                originalClass.classLoader,
            )
        } catch (e: Exception) {
            return Verification(false, emptyList(), emptyList(), emptyList(),
                listOf("patch dex could not be loaded: ${e.message}"))
        }

        val patched: Class<*> = try {
            loader.loadClass(fqcn)
        } catch (e: Exception) {
            return Verification(false, emptyList(), emptyList(), emptyList(),
                listOf("patch dex does not contain $fqcn: ${e.message}"))
        }

        // 5. concrete + instantiable
        if (Modifier.isAbstract(patched.modifiers) || patched.isInterface) {
            problems.add("patch class is abstract or an interface — it must be concrete")
        }

        // 4. dispatch interface must be implemented by the PATCH class.
        val dispatchBase = host.vorv.watchdog.PatchRegistry.dispatchBaseFor(fqcn)
        if (dispatchBase != null && !dispatchBase.isAssignableFrom(patched)) {
            problems.add(
                "patch does not implement the dispatch interface ${dispatchBase.name} " +
                    "— it would never run; add it to the class declaration")
        }

        // 1. public constructors
        val originalCtors = originalClass.constructors
        val patchedCtorSigs = patched.constructors.map { it.signature() }.toSet()
        for (c in originalCtors) {
            if (c.signature() !in patchedCtorSigs) {
                missingCtors.add("constructor ${c.signature()}")
            }
        }

        // 2. public methods (declared on the original; dispatch-interface
        //    methods are checked via requirement 4 anyway, but declared ones
        //    must all survive)
        val patchedMethodSigs = patched.methods.map { it.signature() }.toSet()
        for (m in originalClass.methods) {
            // Skip Object's own methods; every class has them.
            if (m.declaringClass == java.lang.Object::class.java) continue
            if (m.signature() !in patchedMethodSigs) {
                missingMethods.add("method ${m.signature()}")
            }
        }

        // 3. public fields
        // L-4 (code review, 2026-11): fields are compared by NAME **AND
        // TYPE**. The old name-only set let a patch change a public field's
        // type (Long → String, Citation → String, …) and still pass — every
        // caller then broke at runtime (NoSuchFieldError / verify error on
        // first access) with the patch already signed and applied. A
        // type-changed field is now reported as an omission with the exact
        // expected and actual types, feeding the regeneration prompt.
        val patchedFields = patched.fields.associate { it.name to it.type }
        for (f in originalClass.fields) {
            val patchedType = patchedFields[f.name]
            if (patchedType == null) {
                missingFields.add("field ${f.type.simpleName} ${f.name}")
            } else if (patchedType != f.type) {
                missingFields.add(
                    "field ${f.name} changed type: ${f.type.simpleName} → " +
                        "${patchedType.simpleName} — keep the original type ${f.type.simpleName}")
            }
        }

        val ok = missingCtors.isEmpty() && missingMethods.isEmpty() &&
            missingFields.isEmpty() && problems.isEmpty()
        if (!ok) {
            Log.w(TAG, "Patch for $fqcn INCOMPLETE: " +
                "+${missingCtors.size} ctors, +${missingMethods.size} methods, " +
                "+${missingFields.size} fields, +${problems.size} problems")
        }
        return Verification(ok, missingCtors, missingMethods, missingFields, problems)
    }

    // ------------------------------------------------------------------
    private fun Constructor<*>.signature(): String {
        val params = parameterTypes.joinToString(", ") { it.simpleName }
        return "$parameterCount params ($params)"
    }

    private fun Method.signature(): String {
        val params = parameterTypes.joinToString(", ") { it.simpleName }
        val static = if (Modifier.isStatic(modifiers)) "static " else ""
        return "$static${returnType.simpleName} $name($params)"
    }
}
