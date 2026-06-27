package com.fesu.renjana.gms

import android.content.Context
import android.content.Intent
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.database.RenjanaDatabase
import com.fesu.renjana.hooks.CoreHooks
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

        // ==================== Singleton ====================

        @Volatile
        private var instance: GoogleSignInVirtualizer? = null

        /**
         * Initialize the singleton instance with the given [context].
         *
         * Prefers to reuse the instance managed by [RenjanaApplication] (which
         * constructs the full dependency chain: GoogleAccountStore + GoogleTokenManager)
         * so that the singleton and the application-managed property always point
         * to the same object. This is essential because the virtualizer holds
         * in-memory state (instance→account map, token cache) that must not
         * diverge across instances.
         *
         * If [RenjanaApplication] is not yet initialized (rare edge case during
         * early startup), falls back to constructing the dependency chain directly
         * from [RenjanaDatabase].
         *
         * Safe to call multiple times; only the first call creates the instance.
         *
         * @return the singleton [GoogleSignInVirtualizer]
         */
        fun init(context: Context): GoogleSignInVirtualizer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }

                // Prefer the RenjanaApplication-managed instance to avoid divergence
                try {
                    val app = RenjanaApplication.get()
                    val fromApp = app.googleSignInVirtualizer
                    instance = fromApp
                    RenjanaLog.i(TAG, "GoogleSignInVirtualizer singleton initialized from RenjanaApplication")
                    return fromApp
                } catch (e: IllegalStateException) {
                    // RenjanaApplication not yet initialized — fall back to direct construction
                    RenjanaLog.d(TAG, "RenjanaApplication not ready, constructing virtualizer directly")
                }

                val appContext = context.applicationContext
                val database = RenjanaDatabase.getInstance(appContext)
                val accountStore = GoogleAccountStore(appContext, database.googleAccountDao())
                val tokenManager = GoogleTokenManager(accountStore)
                val virtualizer = GoogleSignInVirtualizer(appContext, accountStore, tokenManager)
                instance = virtualizer
                RenjanaLog.i(TAG, "GoogleSignInVirtualizer singleton initialized (direct)")
                return virtualizer
            }
        }

        /**
         * Get the singleton instance.
         *
         * Falls back to the [RenjanaApplication.googleSignInVirtualizer] lazy
         * property if [init] has not been called yet. This ensures [get] always
         * returns a valid instance regardless of initialization order, avoiding
         * the init-order race between the singleton and the application-managed
         * property (M5 fix).
         */
        fun get(): GoogleSignInVirtualizer {
            instance?.let { return it }
            // Fallback: use the RenjanaApplication lazy property
            return RenjanaApplication.get().googleSignInVirtualizer
        }

        /**
         * Returns the singleton instance if initialized, or null otherwise.
         */
        fun getOrNull(): GoogleSignInVirtualizer? = instance

        /**
         * Returns true if the singleton has been initialized.
         */
        fun isInitialized(): Boolean = instance != null
    }
    
    /**
     * Maps instance IDs to their assigned Google accounts.
     * Thread-safe via ConcurrentHashMap.
     */
    private val instanceAccountMap = ConcurrentHashMap<String, GoogleAccount>()
    
    /**
     * Cache of virtualized GoogleSignInAccount objects per instance.
     * Cleared when tokens are refreshed or account is changed.
     *
     * Stored as [Any] because GMS classes are `compileOnly` and resolved via
     * reflection at runtime — direct type references would cause NoClassDefFoundError (C2 fix).
     */
    private val virtualAccountCache = ConcurrentHashMap<String, Any>()
    
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
                
                // C1 fix: sync to CoreHooks.virtualAccounts so the installed hook
                // (CoreHooks.createGoogleSignInHook) can find the account. The hook reads
                // CoreHooks.virtualAccounts[packageName], NOT instanceAccountMap[instanceId].
                // Without this sync, GMS virtualization silently no-ops.
                try {
                    val inst = RenjanaApplication.get().instanceManager.getInstanceById(instanceId)
                    if (inst != null) {
                        CoreHooks.virtualAccounts[inst.packageName] = account
                        RenjanaLog.d(TAG, "Synced account ${account.email} to CoreHooks for package ${inst.packageName}")
                    } else {
                        RenjanaLog.w(TAG, "Instance $instanceId not found — CoreHooks.virtualAccounts not synced")
                    }
                } catch (e: Exception) {
                    RenjanaLog.w(TAG, "Failed to sync account to CoreHooks.virtualAccounts: ${e.message}")
                }
                
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
        
        // C1 fix: also remove from CoreHooks.virtualAccounts (keyed by packageName).
        // The hook reads CoreHooks.virtualAccounts[packageName], so we must remove by
        // packageName, not instanceId. Use runBlocking since this is not a suspend function.
        try {
            val inst = runBlocking { RenjanaApplication.get().instanceManager.getInstanceById(instanceId) }
            if (inst != null) {
                CoreHooks.virtualAccounts.remove(inst.packageName)
                RenjanaLog.d(TAG, "Removed account from CoreHooks for package ${inst.packageName}")
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to sync account removal to CoreHooks.virtualAccounts: ${e.message}")
        }
        
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
     * @return Virtualized GoogleSignInAccount (as Any via reflection), or null on failure.
     *         Callers must handle null — it means no account is mapped or token validation failed.
     */
    suspend fun silentSignIn(instanceId: String): Any? {
        return withContext(Dispatchers.IO) {
            try {
                val account = instanceAccountMap[instanceId]
                
                if (account == null) {
                    RenjanaLog.d(TAG, "No account mapped for instance $instanceId, sign-in required")
                    return@withContext createFailedResult(
                        8, // CommonStatusCodes.SIGN_IN_REQUIRED
                        "No account mapped to this instance"
                    )
                }
                
                // Check if we have a cached virtual account with valid tokens
                val cached = virtualAccountCache[instanceId]
                if (cached != null && isCachedAccountValid(cached)) {
                    RenjanaLog.d(TAG, "Returning cached virtual account for instance $instanceId")
                    return@withContext cached
                }
                
                // Validate and refresh tokens if needed
                val validatedAccount = validateAndRefreshTokens(instanceId, account)
                
                if (validatedAccount == null) {
                    RenjanaLog.w(TAG, "Token validation failed for instance $instanceId")
                    return@withContext createFailedResult(
                        8, // CommonStatusCodes.SIGN_IN_REQUIRED
                        "Token validation failed, re-authentication required"
                    )
                }
                
                // Create virtualized GoogleSignInAccount
                val virtualAccount = createVirtualGoogleSignInAccount(validatedAccount, instanceId)
                
                if (virtualAccount == null) {
                    RenjanaLog.e(TAG, "Failed to create virtual account for instance $instanceId")
                    return@withContext createFailedResult(
                        13, // CommonStatusCodes.INTERNAL_ERROR
                        "Failed to create virtual account"
                    )
                }
                
                virtualAccountCache[instanceId] = virtualAccount
                RenjanaLog.i(TAG, "Silent sign-in successful for instance $instanceId")
                
                virtualAccount
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Silent sign-in failed for instance $instanceId", e)
                createFailedResult(13, e.message ?: "Unknown error")
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
     * @return Virtualized GoogleSignInAccount (as Any via reflection), or null on failure.
     */
    suspend fun handleAccountPickerResult(
        instanceId: String,
        resultCode: Int,
        data: Intent?
    ): Any? {
        pendingAccountPickers.remove(instanceId)
        
        return withContext(Dispatchers.IO) {
            try {
                if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                    RenjanaLog.d(TAG, "Account picker canceled for instance $instanceId")
                    return@withContext createFailedResult(
                        12501, // SIGN_IN_CANCELLED
                        "User canceled sign-in"
                    )
                }
                
                val selectedAccountId = data.getStringExtra(EXTRA_SELECTED_ACCOUNT_ID)
                    ?: return@withContext createFailedResult(
                        13, // INTERNAL_ERROR
                        "No account selected"
                    )
                
                // Assign selected account to instance
                val assignResult = assignAccountToInstance(instanceId, selectedAccountId)
                if (assignResult.isFailure) {
                    return@withContext createFailedResult(
                        13, // CommonStatusCodes.INTERNAL_ERROR
                        "Failed to assign account: ${assignResult.exceptionOrNull()?.message}"
                    )
                }
                
                // Perform silent sign-in with the newly assigned account
                silentSignIn(instanceId)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to handle account picker result", e)
                createFailedResult(13, e.message ?: "Unknown error")
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
     * @return Virtualized GoogleSignInAccount (as Any via reflection), or null if construction fails
     */
    private fun createVirtualGoogleSignInAccount(
        account: GoogleAccount,
        instanceId: String
    ): Any? {
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
     * Uses Class.forName to avoid direct GMS dependency (A3/A4 fix).
     */
    private fun createViaBuilder(
        account: GoogleAccount,
        serverClientId: String?
    ): Any? {
        return try {
            // Resolve GoogleSignInAccount via reflection — no direct GMS import
            val accountClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount")

            // Optionally build GoogleSignInOptions to validate server client ID path,
            // but we don't actually need the options object for constructor injection —
            // we use it only to log that requestIdToken would have been set.
            if (serverClientId != null) {
                RenjanaLog.d(TAG, "Server client ID available for instance: will embed idToken")
            }

            val constructors = accountClass.declaredConstructors

            // Find constructor that matches our needs (id, idToken, email, displayName, photoUrl, ...)
            val constructor = constructors.find { ctor ->
                val params = ctor.parameterTypes
                params.size >= 7 &&
                params[0] == String::class.java && // ID
                params[1] == String::class.java && // Token ID
                params[2] == String::class.java && // Email
                params[3] == String::class.java && // Display name
                params[4] == String::class.java    // Photo URL
            }

            if (constructor == null) {
                RenjanaLog.w(TAG, "Suitable constructor not found in GoogleSignInAccount")
                return null
            }

            constructor.isAccessible = true

            // Create account with available data
            val accountObj = constructor.newInstance(
                account.id,                        // ID
                account.idToken,                   // ID token
                account.email,                     // Email
                account.displayName ?: "",         // Display name
                account.photoUrl ?: "",            // Photo URL
                null,                              // Family name
                null,                              // Given name
                System.currentTimeMillis() / 1000, // Expiration time (seconds)
                "0",                               // Obfuscated identifier
                emptySet<Any>()                    // Granted scopes
            )

            accountObj
        } catch (e: ClassNotFoundException) {
            RenjanaLog.d(TAG, "GoogleSignInAccount class not found via reflection: ${e.message}")
            null
        } catch (e: Exception) {
            RenjanaLog.d(TAG, "Builder-based construction failed: ${e.message}")
            null
        }
    }
    
    /**
     * Create GoogleSignInAccount using full reflection.
     * Fallback when builder pattern fails. No direct GMS type refs (A4/A5 fix).
     */
    private fun createViaReflection(
        account: GoogleAccount,
        serverClientId: String?
    ): Any? {
        return try {
            val accountClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount")

            // Find the most flexible constructor
            val constructor = accountClass.declaredConstructors
                .filter { it.parameterCount >= 5 }
                .maxByOrNull { it.parameterCount }

            if (constructor == null) {
                RenjanaLog.e(TAG, "No suitable constructor found in GoogleSignInAccount")
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

            // Fill remaining args with type-appropriate defaults
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

            constructor.newInstance(*args)
        } catch (e: ClassNotFoundException) {
            RenjanaLog.e(TAG, "GoogleSignInAccount class not found via reflection")
            null
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Reflection-based construction failed: ${e.message}")
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
     * Accepts Any to avoid direct GMS type reference (A5 fix).
     * Extracts idToken via reflection on the opaque account object.
     */
    private fun isCachedAccountValid(account: Any): Boolean {
        return try {
            // Extract idToken via reflection — no direct GMS type ref
            val idToken: String? = try {
                val method = account.javaClass.getMethod("getIdToken")
                method.invoke(account) as? String
            } catch (e: Exception) {
                // Fallback: try field access
                try {
                    val field = account.javaClass.getDeclaredField("mIdToken")
                    field.isAccessible = true
                    field.get(account) as? String
                } catch (e2: Exception) {
                    null
                }
            }

            if (idToken == null) return false

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
            (exp - currentTimeSec) > bufferSec

        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Cached account validation failed: ${e.message}")
            false
        }
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Create a failed result object with the specified status code.
     * Uses reflection to avoid direct GMS dependency (A2 fix).
     * Returns an ApiException wrapping a Status, or null if GMS classes are unavailable.
     */
    private fun createFailedResult(statusCode: Int, message: String): Any? {
        return try {
            val statusClass = Class.forName("com.google.android.gms.common.api.Status")
            val status = statusClass.getConstructor(Int::class.java, String::class.java)
                .newInstance(statusCode, message)
            try {
                val apiExceptionClass = Class.forName("com.google.android.gms.common.api.ApiException")
                apiExceptionClass.getConstructor(statusClass).newInstance(status)
            } catch (e: ClassNotFoundException) {
                RenjanaLog.w(TAG, "ApiException class not found, returning Status only")
                status
            }
        } catch (e: ClassNotFoundException) {
            RenjanaLog.w(TAG, "GMS Status class not found, returning null for status $statusCode: $message")
            null
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to create failed result for status $statusCode: ${e.message}")
            null
        }
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
