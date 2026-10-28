package com.marzvpn.app.api

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device identity: a non-exportable P-256 key in Android Keystore. The App API binds every session
 * to its public key and requires signatures for refresh, logout and login completion, so a copied
 * refresh token is useless on another device.
 */
object DeviceKey {
    private const val ALIAS = "marz_device_key_v1"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensure() {
        if (keyStore().containsAlias(ALIAS)) return
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBox = true))
            gen.generateKeyPair()
        } catch (_: Exception) {
            // No StrongBox on this device (StrongBoxUnavailableException or vendor quirks): use the TEE keystore.
            gen.initialize(spec(strongBox = false))
            gen.generateKeyPair()
        }
    }

    /** X.509 SubjectPublicKeyInfo (DER), base64 — the format the App API expects. */
    fun publicKey(): String {
        ensure()
        return Base64.encodeToString(keyStore().getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    fun sign(data: ByteArray): String {
        ensure()
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key)
            update(data)
        }.sign()
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    fun reset() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}

/** Small encrypted key-value store (AES-GCM key in Android Keystore) for tokens and login state. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("marz_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: JSONObject?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toString().toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /**
     * Several records changed in one durable step: written with commit() (true only once on disk),
     * never apply(). A null value removes the record. Call off the main thread.
     */
    fun commit(values: Map<String, JSONObject?>): Boolean = runCatching {
        val edit = prefs.edit()
        for ((name, value) in values) {
            if (value == null) edit.remove(name) else edit.putString(name, seal(value))
        }
        edit.commit()
    }.getOrDefault(false)

    private fun seal(value: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
    }

    fun get(name: String): JSONObject? {
        val sealed = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            JSONObject(String(cipher.doFinal(sealed, 12, sealed.size - 12)))
        }.getOrNull()
    }

    /** Stable random install id (not a hardware identifier). */
    fun deviceId(): String {
        prefs.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    fun clearSession() {
        prefs.edit().remove(TOKENS).remove(PENDING_TELEGRAM).remove(CACHE_ACCOUNT).remove(CACHE_SUBSCRIPTION).apply()
    }

    companion object {
        private const val ALIAS = "marz_tokens_v1"
        /** Access + refresh pair, and the unfinished rotation (one record: changed atomically). */
        const val TOKENS = "tokens"
        /** Session revocations still to deliver after a logout (never used for sign-in or refresh). */
        const val REFRESH_CANCELS = "refresh_cancels"
        const val PENDING_TELEGRAM = "pending_telegram"
        const val CACHE_ACCOUNT = "cache_account"
        const val CACHE_SUBSCRIPTION = "cache_subscription"
    }
}
