package com.ushi.sennaar

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SyncCredentials(private val preferences: SharedPreferences) {
    val endpoint: String get() = preferences.getString("endpoint", "").orEmpty()

    fun readToken(): String = runCatching {
        val stored = preferences.getString("encrypted_token", null) ?: return ""
        val (iv, ciphertext) = stored.split('.', limit = 2)
        val key = keyStore().getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(endpoint.toByteArray())
        cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrDefault("")

    fun save(endpoint: String, token: String) {
        if (endpoint == this.endpoint && token == readToken()) return
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(endpoint.toByteArray())
        val ciphertext = Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        check(preferences.edit()
            .putString("endpoint", endpoint)
            .putString("encrypted_token", "$iv.$ciphertext")
            .commit()) { "无法保存同步设置" }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "sennaar_sync_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
