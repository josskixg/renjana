package com.fesu.renjana.core

import com.fesu.renjana.database.GoogleAccountDao
import com.fesu.renjana.database.GoogleAccountEntity
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class GoogleAccountManager(private val accountDao: GoogleAccountDao) {
    companion object {
        private const val TAG = "GoogleAccountManager"
    }

    fun getAllAccounts(): Flow<List<GoogleAccount>> {
        return accountDao.getAllAccounts().map { entities ->
            entities.map { entityToAccount(it) }
        }
    }

    suspend fun getAccountById(accountId: String): GoogleAccount? {
        return accountDao.getAccountById(accountId)?.let { entityToAccount(it) }
    }

    suspend fun addAccount(
        email: String,
        displayName: String,
        photoUrl: String?,
        idToken: String,
        accessToken: String?,
        refreshToken: String?,
        tokenExpiryTime: Long
    ): GoogleAccount {
        val account = GoogleAccount(
            id = UUID.randomUUID().toString(),
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiryTime = tokenExpiryTime,
            createdAt = System.currentTimeMillis()
        )
        accountDao.insertAccount(accountToEntity(account))
        RenjanaLog.i(TAG, "Added account: $email")
        return account
    }

    suspend fun deleteAccount(accountId: String) {
        val entity = accountDao.getAccountById(accountId)
        if (entity != null) {
            accountDao.deleteAccount(entity)
            RenjanaLog.i(TAG, "Deleted account: ${entity.email}")
        }
    }

    suspend fun updateAccount(accountId: String, displayName: String, photoUrl: String?) {
        val entity = accountDao.getAccountById(accountId)
        if (entity != null) {
            val updated = entity.copy(displayName = displayName, photoUrl = photoUrl)
            accountDao.updateAccount(updated)
            RenjanaLog.i(TAG, "Updated account: ${entity.email}")
        }
    }

    private fun entityToAccount(entity: GoogleAccountEntity): GoogleAccount {
        return GoogleAccount(
            id = entity.id, email = entity.email, displayName = entity.displayName,
            photoUrl = entity.photoUrl, idToken = entity.idToken, accessToken = entity.accessToken,
            refreshToken = entity.refreshToken, tokenExpiryTime = entity.tokenExpiryTime, createdAt = entity.createdAt
        )
    }

    private fun accountToEntity(account: GoogleAccount): GoogleAccountEntity {
        return GoogleAccountEntity(
            id = account.id, email = account.email, displayName = account.displayName,
            photoUrl = account.photoUrl, idToken = account.idToken, accessToken = account.accessToken,
            refreshToken = account.refreshToken, tokenExpiryTime = account.tokenExpiryTime, createdAt = account.createdAt
        )
    }
}
