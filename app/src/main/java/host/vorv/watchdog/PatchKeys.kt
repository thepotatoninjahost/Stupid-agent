package host.vorv.watchdog

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * PatchKeys — the device-local patch signing keypair (audit C-2).
 *
 * WHY THIS EXISTS: the audited HotPatcher accepted any .dex file that was
 * listed in a manifest whose hash the manifest itself carried — a
 * self-attested file loading into the app process with zero cryptographic
 * proof of origin. A patch must be SIGNED: the healer signs every dex it
 * compiles with this keypair, and HotPatcher refuses (not skips) any dex
 * whose signature does not verify against the device public key.
 *
 * KEY STORAGE:
 *  - Primary: Android Keystore ("vorv-patch-key"), hardware-backed where
 *    available — Ed25519 on API 33+ (the S25), ECDSA P-256 otherwise.
 *    The private key never leaves the Keystore.
 *  - Fallback (no Keystore provider — e.g. Robolectric in unit tests, or
 *    an unusual device): a software keypair persisted under filesDir with
 *    0700 permissions. For this single-user, never-published threat model
 *    that is still a real boundary against every actor except the user
 *    themselves, and it keeps the sign/verify path honest everywhere.
 *
 * SIGNATURE FORMAT: "ALG:base64(sig)" over the exact dex bytes (Ed25519:
 * PureEdDSA; ECDSA: SHA256withECDSA).
 */
object PatchKeys {

    private const val TAG = "Vorv/PatchKeys"
    private const val KS_ALIAS = "vorv-patch-key"
    private const val FALLBACK_DIR = "patchkeys"
    private const val FALLBACK_PRIV = "patch.key"
    private const val FALLBACK_PUB = "patch.pub"

    /** Algorithm actually in use on this device, e.g. "Ed25519". */
    @Volatile
    private var algorithm: String? = null

    /** Cached public key (verification is on the boot path — keep it hot). */
    @Volatile
    private var cachedPublic: PublicKey? = null

    /**
     * Ensure a keypair exists. Safe to call repeatedly; returns the signing
     * algorithm name, or null when no key material could be produced (in
     * which case signing AND verification both fail closed).
     */
    @Synchronized
    fun ensureKeys(context: Context): String? {
        algorithm?.let { return it }

        // --- Primary: Android Keystore ---
        if (tryKeystore()) {
            algorithm = keystoreAlgorithm
            return algorithm
        }

        // --- Fallback: software keypair under filesDir ---
        if (trySoftwareKeys(context)) {
            algorithm = softwareAlgorithm
            return algorithm
        }

        Log.e(TAG, "No signing backend available — patch sign/verify fail closed")
        return null
    }

    /** The public key in X.509/SPKI form (for consent records and logs). */
    fun publicKeyBytes(context: Context): ByteArray? {
        ensureKeys(context)
        return cachedPublic?.encoded
    }

    /**
     * Sign [data] with the device patch key. Returns "ALG:base64(sig)" or
     * null when signing is unavailable (fail closed — caller must refuse
     * to register the patch).
     */
    fun sign(context: Context, data: ByteArray): String? {
        val alg = ensureKeys(context) ?: return null
        return runCatching {
            val sig = Signature.getInstance(jcaAlgorithm(alg))
            sig.initSign(privateKey(context, alg))
            sig.update(data)
            encodeSignature(alg, sig.sign())
        }.onFailure {
            Log.e(TAG, "sign failed: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * Verify [data] against [encodedSignature] ("ALG:base64(sig)") using the
     * device public key. Any mismatch, unknown algorithm, or missing key
     * material returns false — callers REFUSE, never skip.
     */
    fun verify(context: Context, data: ByteArray, encodedSignature: String): Boolean {
        val alg = ensureKeys(context) ?: return false
        val pub = cachedPublic ?: return false
        val (sigAlg, sigBytes) = decodeSignature(encodedSignature) ?: return false
        if (sigAlg != alg) return false
        return runCatching {
            val verifier = Signature.getInstance(jcaAlgorithm(alg))
            verifier.initVerify(pub)
            verifier.update(data)
            verifier.verify(sigBytes)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Android Keystore backend
    // ------------------------------------------------------------------
    /** Algorithm used with the Keystore backend (Ed25519 on API 33+). */
    private val keystoreAlgorithm =
        if (Build.VERSION.SDK_INT >= 33) "Ed25519" else "EC"

    private fun tryKeystore(): Boolean {
        return runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getCertificate(KS_ALIAS)?.publicKey
            if (existing != null) {
                cachedPublic = existing
                return true
            }
            val kpg = java.security.KeyPairGenerator.getInstance(
                if (keystoreAlgorithm == "Ed25519") "Ed25519" else "EC",
                "AndroidKeyStore",
            )
            val spec = if (keystoreAlgorithm == "Ed25519") {
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY,
                ).build()
            } else {
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .build()
            }
            kpg.initialize(spec)
            val kp = kpg.generateKeyPair()
            // Keystore-backed private key is not exportable — record only the
            // public half; signing goes through the Keystore entry.
            cachedPublic = ks.getCertificate(KS_ALIAS)?.publicKey ?: kp.public
            true
        }.onFailure {
            Log.w(TAG, "Keystore backend unavailable (${it.message}) — falling back to software keys")
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Software fallback backend
    // ------------------------------------------------------------------
    /** Algorithm used with the software fallback backend. */
    private val softwareAlgorithm =
        if (Build.VERSION.SDK_INT >= 33) "Ed25519" else "EC"

    @Volatile private var softwarePrivate: PrivateKey? = null

    private fun trySoftwareKeys(context: Context): Boolean {
        return runCatching {
            val dir = File(context.filesDir, FALLBACK_DIR)
            dir.mkdirs()
            // filesDir is app-private (0700) on Android — the key material
            // never leaves it. (Explicit setReadable(false, false) calls
            // would strip the OWNER's permissions too and break the store.)
            val privFile = File(dir, FALLBACK_PRIV)
            val pubFile = File(dir, FALLBACK_PUB)

            if (privFile.exists() && pubFile.exists()) {
                val privBytes = privFile.readBytes()
                val pubBytes = pubFile.readBytes()
                val alg = if (softwareAlgorithm == "Ed25519") "Ed25519" else "EC"
                val kf = KeyFactory.getInstance(alg)
                softwarePrivate = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                cachedPublic = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                return true
            }

            val alg = if (softwareAlgorithm == "Ed25519") "Ed25519" else "EC"
            val kpg = KeyPairGenerator.getInstance(alg)
            if (alg == "EC") {
                kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
            val kp: KeyPair = kpg.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            softwarePrivate = kp.private
            cachedPublic = kp.public
            true
        }.onFailure {
            Log.e(TAG, "Software key backend failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    private fun privateKey(context: Context, alg: String): PrivateKey? {
        if (softwarePrivate != null) return softwarePrivate
        // Keystore path: fetch the entry (the private key object is usable
        // for signing even though it cannot be exported).
        return runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(KS_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
            entry?.privateKey
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Encoding helpers
    // ------------------------------------------------------------------
    private fun jcaAlgorithm(alg: String): String = when (alg) {
        "Ed25519" -> "Ed25519"
        "EC" -> "SHA256withECDSA"
        else -> alg
    }

    private fun encodeSignature(alg: String, sig: ByteArray): String =
        "$alg:" + android.util.Base64.encodeToString(
            sig, android.util.Base64.NO_WRAP)

    private fun decodeSignature(encoded: String): Pair<String, ByteArray>? {
        val idx = encoded.indexOf(':')
        if (idx <= 0) return null
        val alg = encoded.substring(0, idx)
        val bytes = runCatching {
            android.util.Base64.decode(encoded.substring(idx + 1), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return alg to bytes
    }
}
