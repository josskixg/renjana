package com.fesu.renjana.gms

import com.fesu.renjana.database.GoogleAccountDao
import com.fesu.renjana.database.GoogleAccountEntity
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Manages Google accounts for container instances.
 * Handles account CRUD operations, token refresh, and per-instance assignment.
 */
class GoogleAccountManager(
    private val googleAccountDao: GoogleAccountDao
) {
    companion object {
        private const val TAG = "GoogleAccountManager"
    }

    /**
     * Get all Google accounts as a Flow
     */
    fun getAllAccounts(): Flow<List<GoogleAccount>> {
        return googleAccountDao.getAllAccounts().map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }
    }

    /**
     * Get a specific account by ID
     */
    suspend fun getAccountById(accountId: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            try {
                googleAccountDao.getAccountById(accountId)?.toDomainModel()
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get account by ID: $accountId", e)
                null
            }
        }
    }

    /**
     * Get a specific account by email
     */
    suspend fun getAccountByEmail(email: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            try {
                googleAccountDao.getAccountByEmail(email)?.toDomainModel()
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get account by email: $email", e)
                null
            }
        }
    }

    /**
     * Add a new Google account
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
                // Check for duplicate email
                val existingAccount = googleAccountDao.getAccountByEmail(email)
                if (existingAccount != null) {
                    return@withContext Result.failure(
                        IllegalStateException("Account with email $email already exists")
                    )
                }

                val accountId = Utils.generateId()
                val currentTime = System.currentTimeMillis()

                val entity = GoogleAccountEntity(
                    id = accountId,
                    email = email,
                    displayName = displayName,
                    photoUrl = photoUrl,
                    idToken = idToken,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenExpiryTime = tokenExpiryTime,
                    createdAt = currentTime
                )

                googleAccountDao.insertAccount(entity)
                RenjanaLog.i(TAG, "Added new Google account: $email")
                Result.success(entity.toDomainModel())
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to add account: $email", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Update an existing Google account
     */
    suspend fun updateAccount(account: GoogleAccount): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = account.toEntity()
                googleAccountDao.updateAccount(entity)
                RenjanaLog.i(TAG, "Updated Google account: ${account.email}")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update account: ${account.email}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Remove a Google account by ID
     */
    suspend fun removeAccount(accountId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                googleAccountDao.deleteAccountById(accountId)
                RenjanaLog.i(TAG, "Removed Google account: $accountId")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to remove account: $accountId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Refresh tokens for a Google account
     */
    suspend fun refreshTokens(
        accountId: String,
        idToken: String,
        accessToken: String?,
        tokenExpiryTime: Long
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val account = googleAccountDao.getAccountById(accountId)
                if (account == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Account not found: $accountId")
                    )
                }

                googleAccountDao.updateAccountTokens(
                    id = accountId,
                    idToken = idToken,
                    accessToken = accessToken,
                    expiryTime = tokenExpiryTime
                )

                RenjanaLog.i(TAG, "Refreshed tokens for account: ${account.email}")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to refresh tokens for account: $accountId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Check if account token is expired and needs refresh
     */
    suspend fun needsTokenRefresh(accountId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val account = googleAccountDao.getAccountById(accountId)
                account?.toDomainModel()?.isTokenExpired() ?: true
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to check token expiry for account: $accountId", e)
                true
            }
        }
    }

    /**
     * Convert entity to domain model
     */
    private fun GoogleAccountEntity.toDomainModel(): GoogleAccount {
        return GoogleAccount(
            id = id,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiryTime = tokenExpiryTime,
            createdAt = createdAt
        )
    }

    /**
     * Convert domain model to entity
     */
    private fun GoogleAccount.toEntity(): GoogleAccountEntity {
        return GoogleAccountEntity(
            id = id,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiryTime = tokenExpiryTime,
            createdAt = createdAt
        )
    }
}
