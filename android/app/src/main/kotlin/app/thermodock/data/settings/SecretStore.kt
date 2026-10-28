/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Holds the engine API key (decision D4). */
interface SecretStore {
    val hasApiKey: Flow<Boolean>
    suspend fun apiKey(): String?
    suspend fun setApiKey(key: String)
    suspend fun clearApiKey()
}

/** Encrypts and decrypts small secrets. Split out so the store can be tested off-device. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. Blob = 12-byte IV ‖ ciphertext. */
// Kept from the Flowstead name (D66): the Android Keystore alias that encrypts the stored engine key.
class KeystoreCipher(private val alias: String = "flowstead.engine.key") : SecretCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

@Singleton
class EncryptedSecretStore @Inject constructor(
    @Named("secrets") private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SecretStore {

    override val hasApiKey: Flow<Boolean> = store.data.map { it[API_KEY] != null }

    /** Null when no key is stored, or when it can no longer be decrypted (e.g. restored on another device). */
    override suspend fun apiKey(): String? {
        val b64 = store.data.first()[API_KEY] ?: return null
        return runCatching { String(cipher.decrypt(Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "API key is empty" }
        val blob = Base64.encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        store.edit { it[API_KEY] = blob }
    }

    override suspend fun clearApiKey() {
        store.edit { it.remove(API_KEY) }
    }

    private companion object {
        val API_KEY = stringPreferencesKey("engine_api_key")
    }
}
