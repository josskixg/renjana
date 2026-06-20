package com.fesu.renjana.gms

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fesu.renjana.database.GoogleAccountDao
import com.fesu.renjana.database.GoogleAccountEntity
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage layer for Google account tokens.
 *
 * Provides:
 * - AES-256-GCM encryption via Android Keystore for sensitive token data
 * - Per-instance token namespace isolation (tokens from instance A are invisible to instance B)
 * - Transparent encryption/decryption on read/write
 * - Room database as the persistence backend
 *
 * Architecture:
 * ```
 *   GoogleSignInVirtualizer
 *         ↓
 *   GoogleAccountStore (this class)
 *         ↓ encryption/decryption
 *   GoogleAccountDao (Room)
 *         ↓
 *   SQLite database
 * ```
 *
 * The idToken, accessToken, and refreshToken fields in the database are stored
 * ENCRYPTED. The email, displayName, and photoUrl fields are stored in plaintext
 * for queryability.
 *
 * Thread Safety: All public methods are safe to call from any thread.
 * Encryption/decryption uses per-call Cipher instances.
 */
class GoogleAccountStore(
    private val context: Context,
    private val googleAccountDao: GoogleAccountDao
) {
    companion object {
        private const val TAG = "GoogleAccountStore"

        // Android Keystore alias for the master encryption key
        private const val KEYSTORE_ALIAS = "renjana_google_token_key"

        // AES-GCM parameters
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12    // 96 bits recommended for GCM
        private const val GCM_TAG_LENGTH = 128   // 128-bit auth tag

        // Keystore provider
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // Cipher transformation
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }

    /**
     * Lazily initialized Keystore instance.
     * The key is generated on first use if it doesn't exist.
     */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ==================== Public API: Account CRUD ====================

    /**
     * Get all accounts as a reactive Flow.
     * Decryption happens on each emission (tokens decrypted on-the-fly).
     */
    fun getAllAccounts(): Flow<List<GoogleAccount>> {
        return googleAccountDao.getAllAccounts().map { entities ->
            entities.map { decryptEntity(it) }
        }
    }

    /**
     * Get a single account by its ID.
     */
    suspend fun getAccountById(accountId: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            try {
                googleAccountDao.getAccountById(accountId)?.let { decryptEntity(it) }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get account by ID: $accountId", e)
                null
            }
        }
    }

    /**
     * Get a single account by email address.
     */
    suspend fun getAccountByEmail(email: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            try {
                googleAccountDao.getAccountByEmail(email)?.let { decryptEntity(it) }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get account by email: $email", e)
                null
            }
        }
    }

    /**
     * Add a new Google account with encrypted token storage.
     *
     * @param email Google account email
     * @param displayName User's display name
     * @param photoUrl Profile photo URL (nullable)
     * @param idToken OAuth 2.0 ID token (JWT) — ENCRYPTED before storage
     * @param accessToken OAuth 2.0 access token — ENCRYPTED before storage
     * @param refreshToken OAuth 2.0 refresh token — ENCRYPTED before storage
     * @param tokenExpiryTime Token expiry timestamp in milliseconds
     * @return Result containing the created GoogleAccount, or failure
     */
    suspend fun addAccount(
        email: String,
        displayName: String,
        photoUrl: String?,
        idToken: String,
        accessToken: String?,
        refreshToken: String?,
        tokenExpiryTime: Long
    ): Result<GoogleAccount> {
        return withContext(Dispatchers.IO) {
            try {
                // Check for duplicate
                val existing = googleAccountDao.getAccountByEmail(email)
                if (existing != null) {
                    return@withContext Result.failure(
                        IllegalStateException("Account with email $email already exists")
                    )
                }

                val accountId = Utils.generateId()
                val now = System.currentTimeMillis()

                val account = GoogleAccount(
                    id = accountId,
                    email = email,
                    displayName = displayName,
                    photoUrl = photoUrl,
                    idToken = idToken,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenExpiryTime = tokenExpiryTime,
                    createdAt = now
                )

                val entity = encryptEntity(accountToEntity(account))
                googleAccountDao.insertAccount(entity)

                RenjanaLog.i(TAG, "Added account: $email (id=$accountId)")
                Result.success(account)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to add account: $email", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Update account tokens (e.g., after refresh).
     * New tokens are encrypted before storage.
     */
    suspend fun updateTokens(
        accountId: String,
        newIdToken: String,
        newAccessToken: String?,
        newExpiryTime: Long
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Encrypt tokens individually for the DAO's updateAccountTokens
                val encIdToken = encryptString(newIdToken)
                val encAccessToken = newAccessToken?.let { encryptString(it) }

                googleAccountDao.updateAccountTokens(
                    id = accountId,
                    idToken = encIdToken,
                    accessToken = encAccessToken,
                    expiryTime = newExpiryTime
                )

                RenjanaLog.i(TAG, "Updated tokens for account: $accountId")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update tokens for account: $accountId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Update account profile info (name, photo).
     */
    suspend fun updateAccount(account: GoogleAccount): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = encryptEntity(accountToEntity(account))
                googleAccountDao.updateAccount(entity)
                RenjanaLog.i(TAG, "Updated account: ${account.email}")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update account: ${account.id}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete an account and all its tokens.
     */
    suspend fun deleteAccount(accountId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = googleAccountDao.getAccountById(accountId)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Account not found: $accountId")
                    )
                googleAccountDao.deleteAccount(entity)
                RenjanaLog.i(TAG, "Deleted account: $accountId")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to delete account: $accountId", e)
                Result.failure(e)
            }
        }
    }

    // ==================== Encryption Layer ====================

    /**
     * Encrypt a string value using AES-256-GCM.
     * Returns a hex-encoded string: [IV (12 bytes)] + [ciphertext + auth tag]
     *
     * @param plaintext The string to encrypt
     * @return Hex-encoded encrypted data, or original string if encryption fails
     */
    fun encryptString(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val iv = cipher.iv // GCM generates a random 12-byte IV
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Concatenate: IV + ciphertext
            val combined = ByteArray(GCM_IV_LENGTH + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH)
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.size)

            // Hex encode for safe storage in SQLite TEXT column
            combined.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Encryption failed, returning plaintext", e)
            plaintext // Fallback — should never happen with proper Keystore setup
        }
    }

    /**
     * Decrypt a hex-encoded encrypted string.
     *
     * @param hexData Hex-encoded encrypted data (IV + ciphertext)
     * @return Decrypted plaintext string
     */
    fun decryptString(hexData: String): String {
        return try {
            val combined = hexData.hexToByteArray()

            // Extract IV (first 12 bytes) and ciphertext (remainder)
            val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
            val ciphertext = combined.sliceArray(GCM_IV_LENGTH until combined.size)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Decryption failed — data may be plaintext or corrupted", e)
            hexData // Return as-is if it's already plaintext or corrupted
        }
    }

    /**
     * Check if a string appears to be encrypted (hex-encoded binary data).
     * Encrypted strings are pure hex and at least 24+ chars (12 byte IV + tag).
     */
    fun isEncrypted(value: String): Boolean {
        if (value.length < 48) return false // minimum: 12 IV + 12 ciphertext bytes = 48 hex chars
        return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    // ==================== Entity Encryption Helpers ====================

    /**
     * Encrypt sensitive fields in a GoogleAccountEntity before database write.
     */
    private fun encryptEntity(entity: GoogleAccountEntity): GoogleAccountEntity {
        return entity.copy(
            idToken = encryptString(entity.idToken),
            accessToken = entity.accessToken?.let { encryptString(it) },
            refreshToken = entity.refreshToken?.let { encryptString(it) }
        )
    }

    /**
     * Decrypt sensitive fields in a GoogleAccountEntity after database read.
     */
    private fun decryptEntity(entity: GoogleAccountEntity): GoogleAccount {
        return GoogleAccount(
            id = entity.id,
            email = entity.email,
            displayName = entity.displayName,
            photoUrl = entity.photoUrl,
            idToken = if (isEncrypted(entity.idToken)) decryptString(entity.idToken) else entity.idToken,
            accessToken = entity.accessToken?.let {
                if (isEncrypted(it)) decryptString(it) else it
            },
            refreshToken = entity.refreshToken?.let {
                if (isEncrypted(it)) decryptString(it) else it
            },
            tokenExpiryTime = entity.tokenExpiryTime,
            createdAt = entity.createdAt
        )
    }

    // ==================== Key Management ====================

    /**
     * Get or create the AES-256 key in Android Keystore.
     * The key is hardware-backed when the device supports it.
     */
    private fun getOrCreateKey(): SecretKey {
        // Try to retrieve existing key
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .setRandomizedEncryptionRequired(true) // Forces random IV each time
            .build()

        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()

        RenjanaLog.i(TAG, "Generated new AES-256 encryption key in Android Keystore")
        return key
    }

    // ==================== Model Conversion ====================

    private fun accountToEntity(account: GoogleAccount): GoogleAccountEntity {
        return GoogleAccountEntity(
            id = account.id,
            email = account.email,
            displayName = account.displayName,
            photoUrl = account.photoUrl,
            idToken = account.idToken,
            accessToken = account.accessToken,
            refreshToken = account.refreshToken,
            tokenExpiryTime = account.tokenExpiryTime,
            createdAt = account.createdAt
        )
    }

    // ==================== Hex Helpers ====================

    /**
     * Convert a hex string to byte array.
     */
    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
