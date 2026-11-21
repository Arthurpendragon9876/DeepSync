package org.koitharu.kotatsu.sync.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps sync credentials (OAuth tokens, a WebDAV password) encrypted with a non-exportable Android
 * Keystore key. The ciphertext lives in `noBackupFilesDir`: it is useless on another phone anyway
 * (the key doesn't travel), and an auto-backup restore must never make a device look signed in when
 * it can't decrypt anything. An unreadable value reads as absent, which means "sign in again".
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {

	private val dir = File(context.noBackupFilesDir, DIR_NAME)
	private val cache = HashMap<String, String>()

	@Synchronized
	fun get(name: String): String? {
		cache[name]?.let { return it }
		val file = fileOf(name)
		if (!file.isFile) return null
		return runCatching { decrypt(file.readText()) }
			.onFailure { Log.w(TAG, "secret $name is unreadable, treating it as absent", it) }
			.getOrNull()
			?.also { cache[name] = it }
	}

	/**
	 * Like [get], but a secret that can't be read *right now* is not mistaken for a missing one.
	 *
	 * Returns null when there is no secret, or when it can never be read again (its Keystore key is gone or
	 * changed, or the file is corrupt): that file is deleted so it isn't retried forever, and the caller
	 * should ask the user to sign in. Throws [SecretUnavailableException] for everything else (the Keystore
	 * service hiccuping in the background, right after boot, under memory pressure...): the secret is still
	 * there and a later attempt will read it, so that must not log the user out.
	 */
	@Synchronized
	fun getStrict(name: String): String? {
		cache[name]?.let { return it }
		val file = fileOf(name)
		if (!file.isFile) return null
		return try {
			decrypt(file.readText()).also { cache[name] = it }
		} catch (e: Exception) {
			if (e.isPermanent()) {
				Log.w(TAG, "secret $name can never be read again, dropping it", e)
				file.delete()
				null
			} else {
				Log.w(TAG, "secret $name is temporarily unreadable", e)
				throw SecretUnavailableException("The saved login can't be read right now", e)
			}
		}
	}

	private fun Throwable.isPermanent(): Boolean =
		this is AEADBadTagException || this is IllegalArgumentException ||
			this is InvalidKeyException || this is UnrecoverableKeyException

	@Synchronized
	fun set(name: String, value: String?) {
		val file = fileOf(name)
		if (value == null) {
			cache.remove(name)
			file.delete()
			return
		}
		dir.mkdirs()
		// Write-then-rename: a crash mid-write must never leave a half-written secret.
		val tmp = File(dir, "$name.tmp")
		tmp.writeText(encrypt(value))
		if (!tmp.renameTo(file)) {
			file.delete()
			check(tmp.renameTo(file)) { "Can't store secret $name" }
		}
		cache[name] = value
	}

	@Synchronized
	fun clear(prefix: String) {
		cache.keys.removeAll { it.startsWith(prefix) }
		dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
	}

	private fun fileOf(name: String): File {
		require(name.isNotEmpty() && name.all { it.isLowerCase() || it.isDigit() || it == '_' }) { "Bad secret name" }
		return File(dir, name)
	}

	private fun key(): SecretKey {
		val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
		val spec = KeyGenParameterSpec.Builder(
			ALIAS,
			KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
		)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setKeySize(256)
			.build()
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
			init(spec)
			generateKey()
		}
	}

	private fun encrypt(plain: String): String {
		val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
		val out = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
		return Base64.encodeToString(out, Base64.NO_WRAP)
	}

	private fun decrypt(encoded: String): String {
		val bytes = Base64.decode(encoded.trim(), Base64.NO_WRAP)
		val cipher = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
		}
		return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
	}

	class SecretUnavailableException(message: String, cause: Throwable?) : IOException(message, cause)

	private companion object {

		const val TAG = "SecretStore"
		const val DIR_NAME = "sync_secrets"
		const val KEYSTORE = "AndroidKeyStore"
		const val ALIAS = "deepsync_sync_secrets"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_SIZE = 12
		const val TAG_BITS = 128
	}
}
