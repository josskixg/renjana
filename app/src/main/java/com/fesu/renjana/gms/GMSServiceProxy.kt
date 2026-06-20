package com.fesu.renjana.gms

import android.accounts.Account
import android.content.Context
import android.os.Bundle
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts and virtualizes Google Play Services API calls.
 * Routes requests to the correct account per instance.
 *
 * Uses reflection to access GMS classes at runtime (not compile-time dependencies).
 * This allows the app to work without bundling GMS libraries.
 */
class GMSServiceProxy(
    private val context: Context,
    private val googleAccountManager: GoogleAccountManager
) {
    companion object {
        private const val TAG = "GMSServiceProxy"
        private const val ACCOUNT_TYPE_GOOGLE = "com.google"
    }

    /** Map of instance ID to assigned Google account */
    private val instanceAccountMap = ConcurrentHashMap<String, GoogleAccount>()

    /** Map of instance ID to cached sign-in client (reflection-based) */
    private val signInClientCache = ConcurrentHashMap<String, Any>()

    // --- Instance-Account Mapping ---

    suspend fun assignAccountToInstance(instanceId: String, accountId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val account = googleAccountManager.getAccountById(accountId)
                if (account == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Account not found: $accountId")
                    )
                }

                instanceAccountMap[instanceId] = account
                signInClientCache.remove(instanceId)

                RenjanaLog.i(TAG, "Assigned account ${account.email} to instance $instanceId")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to assign account to instance: $instanceId")
                Result.failure(e)
            }
        }
    }

    fun removeAccountFromInstance(instanceId: String) {
        instanceAccountMap.remove(instanceId)
        signInClientCache.remove(instanceId)
        RenjanaLog.i(TAG, "Removed account from instance: $instanceId")
    }

    suspend fun getInstanceAccount(instanceId: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            instanceAccountMap[instanceId]
        }
    }

    // --- Google Sign-In Interception (Reflection-based) ---

    /**
     * Intercept GoogleSignInClient.silentSignIn().
     * Returns a virtualized GoogleSignInAccount for the instance.
     * Uses reflection to create the account object at runtime.
     */
    suspend fun silentSignIn(instanceId: String): Result<Any> {
        return withContext(Dispatchers.IO) {
            try {
                val account = instanceAccountMap[instanceId]
                if (account == null) {
                    return@withContext Result.failure(
                        IllegalStateException("No account assigned to instance: $instanceId")
                    )
                }

                if (googleAccountManager.needsTokenRefresh(account.id)) {
                    RenjanaLog.w(TAG, "Token expired for account ${account.email}, refresh recommended")
                }

                val signInAccount = createVirtualSignInAccount(account)
                if (signInAccount == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to create virtual GoogleSignInAccount")
                    )
                }

                RenjanaLog.d(TAG, "Silent sign-in OK for instance $instanceId (${account.email})")
                Result.success(signInAccount)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Silent sign-in failed for instance: $instanceId")
                Result.failure(e)
            }
        }
    }

    // --- AccountManager Interception ---

    suspend fun getAccounts(instanceId: String): Array<Account> {
        return withContext(Dispatchers.IO) {
            try {
                val account = instanceAccountMap[instanceId]
                    ?: return@withContext emptyArray<Account>()
                arrayOf(Account(account.email, ACCOUNT_TYPE_GOOGLE))
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get accounts for instance: $instanceId")
                emptyArray()
            }
        }
    }

    suspend fun getAccountsByType(instanceId: String, accountType: String): Array<Account> {
        return withContext(Dispatchers.IO) {
            if (accountType != ACCOUNT_TYPE_GOOGLE) {
                return@withContext emptyArray<Account>()
            }
            getAccounts(instanceId)
        }
    }

    suspend fun getAuthToken(
        instanceId: String,
        accountType: String,
        @Suppress("UNUSED_PARAMETER") authTokenType: String
    ): Bundle {
        return withContext(Dispatchers.IO) {
            val bundle = Bundle()

            try {
                if (accountType != ACCOUNT_TYPE_GOOGLE) {
                    bundle.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
                    return@withContext bundle
                }

                val account = instanceAccountMap[instanceId]
                if (account == null) {
                    bundle.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
                    return@withContext bundle
                }

                bundle.putString(android.accounts.AccountManager.KEY_AUTHTOKEN, account.accessToken)
                bundle.putString(android.accounts.AccountManager.KEY_ACCOUNT_NAME, account.email)
                bundle.putString(android.accounts.AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE_GOOGLE)
                bundle.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, true)

                RenjanaLog.d(TAG, "Auth token generated for instance $instanceId")
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to get auth token for instance: $instanceId")
                bundle.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
            }

            bundle
        }
    }

    // --- Virtual Account Creation (Reflection-based) ---

    /**
     * Create a virtualized GoogleSignInAccount from stored account data using reflection.
     * This avoids compile-time dependency on GMS libraries.
     */
    private fun createVirtualSignInAccount(account: GoogleAccount): Any? {
        return try {
            val accountClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount")
            
            // Try JSON-based construction
            val jsonResult = createFromJson(account, accountClass)
            if (jsonResult != null) return jsonResult

            // Try reflection on private constructor
            val reflectionResult = createViaReflection(account, accountClass)
            if (reflectionResult != null) return reflectionResult

            // Fallback: createDefault()
            createDefault(account, accountClass)
        } catch (e: ClassNotFoundException) {
            RenjanaLog.w(TAG, "GoogleSignInAccount class not available (GMS not in guest app)")
            null
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "All GoogleSignInAccount creation strategies failed")
            null
        }
    }

    private fun createFromJson(account: GoogleAccount, accountClass: Class<*>): Any? {
        return try {
            val json = org.json.JSONObject().apply {
                put("id", account.id)
                put("tokenId", account.idToken)
                put("email", account.email)
                put("displayName", account.displayName)
                if (account.photoUrl != null) put("photoUrl", account.photoUrl)
                put("serverAuthCode", "")
                put("expirationTime", account.tokenExpiryTime.toString())
                put("obfuscatedIdentifier", account.id)
            }

            val method = accountClass.getDeclaredMethod("fromJson", org.json.JSONObject::class.java)
            method.isAccessible = true
            method.invoke(null, json)
        } catch (e: Exception) {
            RenjanaLog.d(TAG, "JSON-based account creation unavailable")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createViaReflection(account: GoogleAccount, accountClass: Class<*>): Any? {
        return try {
            val constructors = accountClass.declaredConstructors
            val targetConstructor = constructors.maxByOrNull { it.parameterCount } ?: return null
            targetConstructor.isAccessible = true

            val params = targetConstructor.parameterTypes
            val args = Array<Any?>(params.size) { index ->
                when (params[index]) {
                    String::class.java -> when (index) {
                        0 -> account.id
                        1 -> account.idToken
                        2 -> account.email
                        3 -> account.displayName
                        5 -> ""
                        else -> account.id
                    }
                    android.net.Uri::class.java -> account.photoUrl?.let { android.net.Uri.parse(it) }
                    Long::class.javaPrimitiveType -> account.tokenExpiryTime
                    else -> null
                }
            }

            targetConstructor.newInstance(*args)
        } catch (e: Exception) {
            RenjanaLog.d(TAG, "Reflection-based account creation failed")
            null
        }
    }

    private fun createDefault(account: GoogleAccount, accountClass: Class<*>): Any? {
        return try {
            val systemAccount = Account(account.email, ACCOUNT_TYPE_GOOGLE)
            val createDefaultMethod = accountClass.getMethod(
                "createDefault",
                Account::class.java,
                Set::class.java
            )
            createDefaultMethod.invoke(null, systemAccount, emptySet<Any>())
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "createDefault fallback failed")
            null
        }
    }

    // --- Cleanup ---

    fun clearAll() {
        instanceAccountMap.clear()
        signInClientCache.clear()
        RenjanaLog.i(TAG, "Cleared all GMS service proxy data")
    }

    fun getActiveMappingCount(): Int = instanceAccountMap.size
}
