package com.fesu.renjana.gms

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * Core virtualization logic for Google Sign-In.
 * 
 * This class manages the mapping between container instances and Google accounts,
 * creates virtualized GoogleSignInAccount objects, and handles the sign-in flow
 * interception.
 * 
 * Architecture:
 * - Each instance ID maps to exactly one Google account
 * - Virtualized accounts are created on-demand using reflection
 * - Tokens are validated and refreshed via GoogleTokenManager
 * - Account selection UI is shown when no account is mapped
 * 
 * Thread Safety:
 * - All public methods are thread-safe
 * - Uses ConcurrentHashMap for instance-account mappings
 * - Token operations are synchronized per instance
 */
class GoogleSignInVirtualizer(
    private val context: Context,
    private val accountStore: GoogleAccountStore,
    private val tokenManager: GoogleTokenManager
) {
    companion object {
        private const val TAG = "GoogleSignInVirtualizer"
        
        // Intent extras for account picker
        const val EXTRA_INSTANCE_ID = "renjana_instance_id"
        const val EXTRA_SELECTED_ACCOUNT_ID = "renjana_selected_account_id"
        const val EXTRA_REQUEST_CODE = "renjana_request_code"
        
        // Request code for account picker activity
        const val REQUEST_CODE_ACCOUNT_PICKER = 9001
    }
    
    /**
     * Maps instance IDs to their assigned Google accounts.
     * Thread-safe via ConcurrentHashMap.
     */
    private val instanceAccountMap = ConcurrentHashMap<String, GoogleAccount>()
    
    /**
     * Cache of virtualized GoogleSignInAccount objects per instance.
     * Cleared when tokens are refreshed or account is changed.
     */
    private val virtualAccountCache = ConcurrentHashMap<String, GoogleSignInAccount>()
    
    /**
     * Maps instance IDs to their server client IDs (extracted from GoogleSignInOptions).
     * Used for ID token validation.
     */
    private val serverClientIds = ConcurrentHashMap<String, String>()
    
    /**
     * Tracks instances that are currently showing account picker to prevent duplicates.
     */
    private val pendingAccountPickers = ConcurrentHashMap.newKeySet<String>()
    
    // ==================== Account Management ====================
    
    /**
     * Assign a Google account to a container instance.
     * 
     * @param instanceId Unique identifier of the container instance
     * @param accountId ID of the Google account to assign
     * @return Result indicating success or failure
     */
    suspend fun assignAccountToInstance(instanceId: String, accountId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountStore.getAccountById(accountId)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Account not found: $accountId")
                    )
                
                instanceAccountMap[instanceId] = account
                virtualAccountCache.remove(instanceId)
                
                RenjanaLog.i(TAG, "Assigned account ${account.email} to instance $instanceId")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to assign account to instance: $instanceId", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Remove account assignment from an instance (sign-out).
     * 
     * @param instanceId Unique identifier of the container instance
     */
    fun removeAccountFromInstance(instanceId: String) {
        instanceAccountMap.remove(instanceId)
        virtualAccountCache.remove(instanceId)
        tokenManager.clearTokensForInstance(instanceId)
        
        RenjanaLog.i(TAG, "Removed account from instance: $instanceId")
    }
    
    /**
     * Get the assigned account for an instance.
     * 
     * @param instanceId Unique identifier of the container instance
     * @return The assigned GoogleAccount, or null if no account is assigned
     */
    suspend fun getInstanceAccount(instanceId: String): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            instanceAccountMap[instanceId]
        }
    }
    
    /**
     * Store the server client ID for an instance (extracted from GoogleSignInOptions).
     * 
     * @param instanceId Unique identifier of the container instance
     * @param serverClientId OAuth 2.0 client ID for the guest app's backend
     */
    fun setServerClientId(instanceId: String, serverClientId: String) {
        serverClientIds[instanceId] = serverClientId
        RenjanaLog.d(TAG, "Stored server client ID for instance $instanceId")
    }
    
    // ==================== Silent Sign-In ====================
    
    /**
     * Attempt silent sign-in for an instance.
     * 
     * This method is called when a guest app invokes GoogleSignInClient.silentSignIn().
     * It returns a Task<GoogleSignInAccount> that completes with the virtualized account
     * if one is mapped, or fails with SIGN_IN_REQUIRED if no account is mapped.
     * 
     * @param instanceId Unique identifier of the container instance
     * @return Task that resolves to virtualized GoogleSignInAccount or fails
     */
    suspend fun silentSignIn(instanceId: String): Task<GoogleSignInAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val account = instanceAccountMap[instanceId]
                
                if (account == null) {
                    RenjanaLog.d(TAG, "No account mapped for instance $instanceId, sign-in required")
                    return@withContext createFailedTask<GoogleSignInAccount>(
                        CommonStatusCodes.SIGN_IN_REQUIRED,
                        "No account mapped to this instance"
                    )
                }
                
                // Check if we have a cached virtual account with valid tokens
                val cached = virtualAccountCache[instanceId]
                if (cached != null && isCachedAccountValid(cached)) {
                    RenjanaLog.d(TAG, "Returning cached virtual account for instance $instanceId")
                    return@withContext Tasks.forResult(cached)
                }
                
                // Validate and refresh tokens if needed
                val validatedAccount = validateAndRefreshTokens(instanceId, account)
                
                if (validatedAccount == null) {
                    RenjanaLog.w(TAG, "Token validation failed for instance $instanceId")
                    return@withContext createFailedTask<GoogleSignInAccount>(
                        CommonStatusCodes.SIGN_IN_REQUIRED,
                        "Token validation failed, re-authentication required"
                    )
                }
                
                // Create virtualized GoogleSignInAccount
                val virtualAccount = createVirtualGoogleSignInAccount(validatedAccount, instanceId)
                
                if (virtualAccount == null) {
                    RenjanaLog.e(TAG, "Failed to create virtual account for instance $instanceId")
                    return@withContext createFailedTask<GoogleSignInAccount>(
                        CommonStatusCodes.INTERNAL_ERROR,
                        "Failed to create virtual account"
                    )
                }
                
                virtualAccountCache[instanceId] = virtualAccount
                RenjanaLog.i(TAG, "Silent sign-in successful for instance $instanceId")
                
                Tasks.forResult(virtualAccount)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Silent sign-in failed for instance $instanceId", e)
                createFailedTask(CommonStatusCodes.INTERNAL_ERROR, e.message ?: "Unknown error")
            }
        }
    }
    
    // ==================== Explicit Sign-In ====================
    
    /**
     * Create an Intent that launches the account picker UI.
     * 
     * This method is called when a guest app invokes GoogleSignInClient.getSignInIntent().
     * Instead of launching Google's official sign-in UI, we show Renjana's account picker.
     * 
     * @param instanceId Unique identifier of the container instance
     * @return Intent to launch account picker activity
     */
    fun getSignInIntent(instanceId: String): Intent {
        // Check if account picker is already showing
        if (pendingAccountPickers.contains(instanceId)) {
            RenjanaLog.w(TAG, "Account picker already showing for instance $instanceId")
            return createDummyIntent()
        }
        
        pendingAccountPickers.add(instanceId)
        
        val intent = Intent(context, AccountPickerActivity::class.java).apply {
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        RenjanaLog.i(TAG, "Created account picker intent for instance $instanceId")
        return intent
    }
    
    /**
     * Handle the result from the account picker activity.
     * 
     * This method is called when the account picker returns with the user's selection.
     * It maps the selected account to the instance and creates a virtualized account.
     * 
     * @param instanceId Unique identifier of the container instance
     * @param resultCode Activity result code (RESULT_OK or RESULT_CANCELED)
     * @param data Intent containing the selected account ID
     * @return Task that resolves to virtualized GoogleSignInAccount or fails
     */
    suspend fun handleAccountPickerResult(
        instanceId: String,
        resultCode: Int,
        data: Intent?
    ): Task<GoogleSignInAccount> {
        pendingAccountPickers.remove(instanceId)
        
        return withContext(Dispatchers.IO) {
            try {
                if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                    RenjanaLog.d(TAG, "Account picker canceled for instance $instanceId")
                    return@withContext createFailedTask<GoogleSignInAccount>(
                        12501, // SIGN_IN_CANCELLED
                        "User canceled sign-in"
                    )
                }
                
                val selectedAccountId = data.getStringExtra(EXTRA_SELECTED_ACCOUNT_ID)
                    ?: return@withContext createFailedTask<GoogleSignInAccount>(
                        13, // INTERNAL_ERROR
                        "No account selected"
                    )
                
                // Assign selected account to instance
                val assignResult = assignAccountToInstance(instanceId, selectedAccountId)
                if (assignResult.isFailure) {
                    return@withContext createFailedTask<GoogleSignInAccount>(
                        CommonStatusCodes.INTERNAL_ERROR,
                        "Failed to assign account: ${assignResult.exceptionOrNull()?.message}"
                    )
                }
                
                // Perform silent sign-in with the newly assigned account
                silentSignIn(instanceId)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to handle account picker result", e)
                createFailedTask(CommonStatusCodes.INTERNAL_ERROR, e.message ?: "Unknown error")
            }
        }
    }
    
    // ==================== Account Construction ====================
    
    /**
     * Create a virtualized GoogleSignInAccount using reflection.
     * 
     * GoogleSignInAccount is a final class with private constructors, so we use
     * reflection to instantiate it with the correct field values.
     * 
     * @param account The GoogleAccount from our store
     * @param instanceId The instance ID (used for server client ID lookup)
     * @return Virtualized GoogleSignInAccount, or null if construction fails
     */
    private fun createVirtualGoogleSignInAccount(
        account: GoogleAccount,
        instanceId: String
    ): GoogleSignInAccount? {
        return try {
            val serverClientId = serverClientIds[instanceId]
            
            // Try using the public builder pattern first
            val accountViaBuilder = createViaBuilder(account, serverClientId)
            if (accountViaBuilder != null) {
                return accountViaBuilder
            }
            
            // Fall back to reflection-based construction
            createViaReflection(account, serverClientId)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to create virtual GoogleSignInAccount", e)
            null
        }
    }
    
    /**
     * Attempt to create GoogleSignInAccount using the builder pattern.
     * This is the preferred method as it's more stable across GMS versions.
     */
    private fun createViaBuilder(
        account: GoogleAccount,
        serverClientId: String?
    ): GoogleSignInAccount? {
        return try {
            val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestId()
            
            if (serverClientId != null) {
                optionsBuilder.requestIdToken(serverClientId)
            }
            
            val options = optionsBuilder.build()
            
            // Use GoogleSignInAccount constructor via reflection
            val accountClass = GoogleSignInAccount::class.java
            val constructors = accountClass.declaredConstructors
            
            // Find constructor that matches our needs
            val constructor = constructors.find { ctor ->
                val params = ctor.parameterTypes
                params.size >= 7 && 
                params[0] == String::class.java && // ID
                params[1] == String::class.java && // Token ID
                params[2] == String::class.java && // Email
                params[3] == String::class.java && // Display name
                params[4] == String::class.java     // Photo URL
            }
            
            if (constructor == null) {
                RenjanaLog.w(TAG, "Suitable constructor not found")
                return null
            }
            
            constructor.isAccessible = true
            
            // Create account with available data
            val accountObj = constructor.newInstance(
                account.id,                    // ID
                account.idToken,               // ID token
                account.email,                 // Email
                account.displayName ?: "",     // Display name
                account.photoUrl ?: "",        // Photo URL
                null,                          // Family name
                null,                          // Given name
                System.currentTimeMillis() / 1000, // Expiration time (seconds)
                "0",                           // Obfuscated identifier
                emptySet<Any>()                // Granted scopes
            )
            
            accountObj as? GoogleSignInAccount
        } catch (e: Exception) {
            RenjanaLog.d(TAG, "Builder-based construction failed: ${e.message}")
            null
        }
    }
    
    /**
     * Create GoogleSignInAccount using full reflection.
     * This is a fallback method when builder pattern fails.
     */
    private fun createViaReflection(
        account: GoogleAccount,
        serverClientId: String?
    ): GoogleSignInAccount? {
        return try {
            val accountClass = GoogleSignInAccount::class.java
            
            // Find the most flexible constructor
            val constructor = accountClass.declaredConstructors
                .filter { it.parameterCount >= 5 }
                .maxByOrNull { it.parameterCount }
            
            if (constructor == null) {
                RenjanaLog.e(TAG, "No suitable constructor found")
                return null
            }
            
            constructor.isAccessible = true
            
            // Prepare constructor arguments based on parameter count
            val paramCount = constructor.parameterCount
            val args = arrayOfNulls<Any>(paramCount)
            
            // Fill in known fields
            if (paramCount > 0) args[0] = account.id
            if (paramCount > 1) args[1] = account.idToken
            if (paramCount > 2) args[2] = account.email
            if (paramCount > 3) args[3] = account.displayName ?: ""
            if (paramCount > 4) args[4] = account.photoUrl ?: ""
            
            // Fill remaining args with defaults
            for (i in 5 until paramCount) {
                val paramType = constructor.parameterTypes[i]
                args[i] = when {
                    paramType == String::class.java -> ""
                    paramType == Long::class.javaPrimitiveType -> 0L
                    paramType == Int::class.javaPrimitiveType -> 0
                    paramType == Boolean::class.javaPrimitiveType -> false
                    paramType == Set::class.java -> emptySet<Any>()
                    else -> null
                }
            }
            
            val accountObj = constructor.newInstance(*args)
            accountObj as? GoogleSignInAccount
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Reflection-based construction failed", e)
            null
        }
    }
    
    // ==================== Token Management ====================
    
    /**
     * Validate tokens and refresh if needed.
     * 
     * @param instanceId The instance ID
     * @param account The GoogleAccount to validate
     * @return Validated account with fresh tokens, or null if validation fails
     */
    private suspend fun validateAndRefreshTokens(
        instanceId: String,
        account: GoogleAccount
    ): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            try {
                val serverClientId = serverClientIds[instanceId]
                
                // Check if ID token is still valid
                if (serverClientId != null && tokenManager.isIDTokenValid(account.idToken, serverClientId)) {
                    RenjanaLog.d(TAG, "ID token is valid for instance $instanceId")
                    return@withContext account
                }
                
                // Try to refresh tokens
                val refreshedAccount = tokenManager.refreshTokens(account)
                
                if (refreshedAccount == null) {
                    RenjanaLog.w(TAG, "Token refresh failed for instance $instanceId")
                    return@withContext null
                }
                
                // Update stored account
                accountStore.updateAccount(refreshedAccount)
                
                RenjanaLog.i(TAG, "Tokens refreshed for instance $instanceId")
                refreshedAccount
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Token validation/refresh failed", e)
                null
            }
        }
    }
    
    /**
     * Check if a cached virtual account has valid (non-expired) tokens.
     */
    private fun isCachedAccountValid(account: GoogleSignInAccount): Boolean {
        return try {
            val idToken = account.idToken ?: return false
            
            // Parse JWT to check expiration
            val parts = idToken.split(".")
            if (parts.size != 3) return false
            
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
            val json = org.json.JSONObject(payload)
            val exp = json.optLong("exp", 0)
            
            if (exp == 0L) return false
            
            // Check if token expires within 5 minutes
            val currentTimeSec = System.currentTimeMillis() / 1000
            val bufferSec = 300L // 5 minutes
            return (exp - currentTimeSec) > bufferSec
            
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Cached account validation failed: ${e.message}")
            return false
        }
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Create a failed Task with the specified status code.
     */
    private fun <T> createFailedTask(statusCode: Int, message: String): Task<T> {
        val status = com.google.android.gms.common.api.Status(statusCode, message)
        return Tasks.forException(ApiException(status))
    }
    
    /**
     * Create a dummy Intent (used when account picker is already showing).
     */
    private fun createDummyIntent(): Intent {
        return Intent().apply {
            putExtra("dummy", true)
        }
    }
    
    /**
     * Get the number of active instance-account mappings.
     */
    fun getActiveMappingCount(): Int = instanceAccountMap.size
    
    /**
     * Clear all instance-account mappings and caches.
     */
    fun clearAll() {
        instanceAccountMap.clear()
        virtualAccountCache.clear()
        serverClientIds.clear()
        pendingAccountPickers.clear()
        
        RenjanaLog.i(TAG, "Cleared all virtualizer state")
    }
}
