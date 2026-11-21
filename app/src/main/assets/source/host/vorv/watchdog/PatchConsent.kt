package host.vorv.watchdog

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * PatchConsent — the user's approval of a self-repair patch, signed by the
 * phone's own security hardware AFTER the phone has checked that the user is
 * really there (fingerprint, face, or the device PIN/pattern).
 *
 * WHY THIS EXISTS: the audit's consent step was an in-app dialog, and the
 * "approved" record was written by the same process that later trusted it.
 * Any code inside the app could write that record and claim the user had
 * agreed. A dialog drawn by the app can never prove a human answered it.
 *
 * The fix is to move the proof OUT of the app:
 *  - The approval key lives in the Android Keystore and is created with
 *    `setUserAuthenticationRequired(true)` / `setUserAuthenticationParameters(0, ...)`:
 *    the key refuses to sign unless the system has just authenticated the
 *    user. The system shows its own fingerprint/PIN screen; app code cannot
 *    draw it, dismiss it, or produce the signature without it.
 *  - What gets signed is a statement naming the exact patch: its id, its
 *    target class and the SHA-256 of the exact bytes. An approval cannot be
 *    copied onto a different patch.
 *  - Verification at boot needs only the public certificate, so it works
 *    without prompting anyone.
 *
 * FAIL CLOSED: no device lock (so no way to authenticate) means no consent
 * key, which means a patch is refused. The app says so instead of applying
 * something it cannot prove was approved.
 */
object PatchConsent {

    private const val TAG = "Vorv/PatchConsent"
    private const val ALIAS = "vorv-patch-consent-key"
    private const val JCA = "SHA256withECDSA"
    private const val PREFIX = "vorv-consent-v1"
    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * The exact text the user's phone signs. Pure and stable: changing it
     * invalidates every existing approval, which is the intended effect of
     * changing what "approved" means.
     */
    fun statement(patchId: String, targetClass: String, sha256: String): String =
        "$PREFIX|$patchId|$targetClass|$sha256"

    /** True when the phone can authenticate the user at all (PIN, pattern, biometric). */
    fun hasDeviceLock(context: Context): Boolean = try {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.isDeviceSecure == true
    } catch (t: Throwable) {
        Log.w(TAG, "could not ask for the lock screen state: ${t.message}", t)
        false
    }

    /**
     * Create the approval key if it does not exist. Safe to call repeatedly.
     * False means no approval can be signed on this device (no Keystore, or
     * no secure lock screen) — callers must refuse the patch.
     */
    @Synchronized
    fun ensureKey(context: Context): Boolean {
        if (!hasDeviceLock(context)) {
            Log.w(TAG, "no secure lock screen — self-repair approval is unavailable")
            return false
        }
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) return true

            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                // 0 = authenticate for every single use.
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or
                        KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                .apply { initialize(builder.build()) }
                .generateKeyPair()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "consent key unavailable: ${t.message}", t)
            false
        }
    }

    /**
     * A Signature ready for a BiometricPrompt CryptoObject. The system hands
     * it back already unlocked, only after the user authenticated.
     */
    fun newSignature(context: Context): Signature? {
        if (!ensureKey(context)) return null
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            Signature.getInstance(JCA).apply { initSign(entry.privateKey) }
        } catch (t: Throwable) {
            Log.e(TAG, "could not open the consent signature: ${t.message}", t)
            null
        }
    }

    /** Encode a signature the system returned after authenticating the user. */
    fun encode(signature: Signature): String? = try {
        "EC:" + android.util.Base64.encodeToString(
            signature.sign(), android.util.Base64.NO_WRAP)
    } catch (t: Throwable) {
        // The system returned a signature object it then refused to sign with.
        Log.e(TAG, "signing the approval failed: ${t.message}", t)
        null
    }

    /**
     * True when the key refuses to sign unless the system has just
     * authenticated the user. Read from the Keystore itself, never from a
     * value the app stored.
     */
    private fun isAuthBound(key: java.security.PrivateKey): Boolean {
        val info = try {
            java.security.KeyFactory.getInstance(key.algorithm, KEYSTORE)
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read the consent key's parameters: ${t.message}", t)
            return false
        }
        @Suppress("DEPRECATION")
        return info.isUserAuthenticationRequired
    }

    /**
     * Verify an approval token against the device's approval certificate.
     * Needs no authentication — the boot path calls this on every patch.
     * Anything unexpected (missing key, wrong algorithm, tampered statement,
     * token for another patch) is false, and callers REFUSE.
     */
    fun verify(context: Context, statement: String, token: String): Boolean {
        if (statement.isEmpty() || token.isEmpty()) return false
        val idx = token.indexOf(':')
        if (idx <= 0) return false
        if (token.substring(0, idx) != "EC") return false
        val sigBytes = try {
            android.util.Base64.decode(token.substring(idx + 1), android.util.Base64.NO_WRAP)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "approval token is not valid base64: ${t.message}")
            return false
        }
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return false
            // The key must STILL be tied to the user's authentication. Without
            // this, deleting the alias and re-creating it as a free-signing key
            // would make every forged approval verify, so a key that is not
            // auth-bound is treated as no approval at all.
            if (!isAuthBound(entry.privateKey)) {
                Log.e(TAG, "the consent key is not bound to user authentication — refusing")
                return false
            }
            val cert = entry.certificate
            Signature.getInstance(JCA).apply {
                initVerify(cert.publicKey)
                update(statement.toByteArray(Charsets.UTF_8))
            }.verify(sigBytes)
        } catch (t: Throwable) {
            Log.e(TAG, "approval check could not run: ${t.message}", t)
            false
        }
    }
}
