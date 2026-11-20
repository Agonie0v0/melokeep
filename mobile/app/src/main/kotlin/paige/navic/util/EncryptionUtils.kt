/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.russhwolf.settings.Settings
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CredentialEncryptor {
	private const val ANDROID_KEY_STORE = "AndroidKeyStore"
	private const val KEY_ALIAS = "navic_credentials_key_v1"
	private const val TRANSFORMATION = "AES/GCM/NoPadding"
	private const val PREFIX_V1 = "enc:v1:"
	private const val PREFIX_V2 = "enc:v2:"
	private const val FALLBACK_PREFS_NAME = "melokeep_credential_fallback_v2"
	private const val FALLBACK_KEY_PREF = "credential_aes256_key_v2"

	@Volatile
	private var appContext: Context? = null

	private val defaultFallbackSettings: Settings by lazy { Settings() }

	fun init(context: Context) {
		appContext = context.applicationContext
	}

	private fun fallbackPrefs(): SharedPreferences? {
		return appContext?.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
	}

	@Synchronized
	private fun getOrCreateKeyStoreKey(forceRecreate: Boolean = false): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
		if (forceRecreate) {
			runCatching {
				if (keyStore.containsAlias(KEY_ALIAS)) {
					keyStore.deleteEntry(KEY_ALIAS)
				}
			}
		} else {
			(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		}

		val keyGenerator =
			KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
		keyGenerator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setKeySize(256)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build()
		)
		return keyGenerator.generateKey()
	}

	@Synchronized
	private fun getOrCreateSoftwareFallbackKey(fallbackSettings: Settings?): SecretKey {
		val prefs = fallbackPrefs()
		val storedEncoded = prefs?.getString(FALLBACK_KEY_PREF, null)
			?: (fallbackSettings ?: defaultFallbackSettings).getString(FALLBACK_KEY_PREF, "")

		if (storedEncoded.isNotBlank()) {
			val decoded = runCatching { Base64.decode(storedEncoded, Base64.NO_WRAP) }.getOrNull()
			if (decoded != null && decoded.size == 32) {
				return SecretKeySpec(decoded, "AES")
			}
		}

		val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
		if (prefs != null) {
			prefs.edit().putString(FALLBACK_KEY_PREF, encoded).commit()
		} else {
			(fallbackSettings ?: defaultFallbackSettings).putString(FALLBACK_KEY_PREF, encoded)
		}
		return SecretKeySpec(keyBytes, "AES")
	}

	private fun encryptAesGcm(plainText: String, key: SecretKey, prefix: String): String {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key)
		val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
		val combined = cipher.iv + encrypted
		return prefix + Base64.encodeToString(combined, Base64.NO_WRAP)
	}

	private fun decryptAesGcm(payloadBase64: String, key: SecretKey): String {
		val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
		if (combined.size <= 12) return ""
		val iv = combined.copyOfRange(0, 12)
		val encrypted = combined.copyOfRange(12, combined.size)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
		return String(cipher.doFinal(encrypted), Charsets.UTF_8)
	}

	fun encrypt(plainText: String, fallbackSettings: Settings? = null): String {
		if (plainText.isEmpty()) return ""

		val firstAttempt = runCatching {
			encryptAesGcm(plainText, getOrCreateKeyStoreKey(forceRecreate = false), PREFIX_V1)
		}
		if (firstAttempt.isSuccess) return firstAttempt.getOrThrow()
		Logger.e("CredentialEncryptor", "KeyStore encrypt failed on first attempt, recreating alias", firstAttempt.exceptionOrNull())

		val retryAttempt = runCatching {
			encryptAesGcm(plainText, getOrCreateKeyStoreKey(forceRecreate = true), PREFIX_V1)
		}
		if (retryAttempt.isSuccess) return retryAttempt.getOrThrow()
		Logger.e("CredentialEncryptor", "KeyStore encrypt failed after recreate, falling back to software AES", retryAttempt.exceptionOrNull())

		return encryptAesGcm(plainText, getOrCreateSoftwareFallbackKey(fallbackSettings), PREFIX_V2)
	}

	fun decrypt(cipherText: String, fallbackSettings: Settings? = null): String {
		if (cipherText.isEmpty()) return ""

		return when {
			cipherText.startsWith(PREFIX_V1) -> runCatching {
				decryptAesGcm(cipherText.removePrefix(PREFIX_V1), getOrCreateKeyStoreKey())
			}.onFailure {
				Logger.e("CredentialEncryptor", "Failed to decrypt v1 credential", it)
			}.getOrDefault("")

			cipherText.startsWith(PREFIX_V2) -> runCatching {
				decryptAesGcm(cipherText.removePrefix(PREFIX_V2), getOrCreateSoftwareFallbackKey(fallbackSettings))
			}.onFailure {
				Logger.e("CredentialEncryptor", "Failed to decrypt v2 credential", it)
			}.getOrDefault("")

			else -> cipherText
		}
	}

	fun isEncrypted(value: String): Boolean {
		return value.startsWith(PREFIX_V1) || value.startsWith(PREFIX_V2)
	}
}

fun Settings.getEncryptedString(key: String, defaultValue: String = ""): String {
	val raw = getString(key, "")
	if (raw.isEmpty()) return defaultValue
	if (!CredentialEncryptor.isEncrypted(raw)) {
		runCatching { insertEncryptedString(key, raw) }
		return raw
	}
	val decrypted = CredentialEncryptor.decrypt(raw, fallbackSettings = this)
	return decrypted.ifEmpty { defaultValue }
}

fun Settings.getEncryptedString(key: String): String? {
	val value = this@getEncryptedString.getEncryptedString(key, "")
	return value.ifEmpty { null }
}

fun Settings.insertEncryptedString(key: String, value: String?) {
	if (value == null) {
		remove(key)
	} else {
		putString(key, CredentialEncryptor.encrypt(value, fallbackSettings = this))
	}
}
