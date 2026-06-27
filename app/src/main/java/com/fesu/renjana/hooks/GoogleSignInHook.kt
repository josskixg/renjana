package com.fesu.renjana.hooks

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fesu.renjana.gms.GoogleSignInVirtualizer
import com.fesu.renjana.utils.RenjanaLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.runBlocking

/**
 * Hook implementation for Google Sign-In virtualization.
 *
 * This class intercepts Google Sign-In API calls from guest apps and routes them
 * through GoogleSignInVirtualizer to provide virtualized authentication.
 *
 * Hooks installed:
 * 1. GoogleSignInClient.silentSignIn() - Return virtualized account if mapped
 * 2. GoogleSignInClient.getSignInIntent() - Return account picker intent
 * 3. GoogleSignInClient.signOut() - Clear instance mapping
 * 4. GoogleSignInClient.revokeAccess() - Clear instance mapping + tokens
 * 5. GoogleSignIn.getSignedInAccountFromIntent() - Extract virtualized account
 * 6. GoogleSignIn.getLastSignedInAccount() - Return virtualized account
 * 7. Activity.startActivityForResult() - Intercept sign-in intents
 * 8. GoogleSignInOptions.Builder.requestIdToken() - Capture server client ID
 * 9. CredentialManager.getCredential() - Android 14+ Credential Manager API
 *
 * Integration with CoreHooks:
 * - Uses CoreHooks.currentInstanceId to determine active instance
 * - Uses CoreHooks.virtualAccounts for quick account lookup
 * - Coordinates with GoogleSignInVirtualizer for account management
 *
 * Thread Safety:
 * - All hooks execute on the calling thread
 * - Uses runBlocking for suspend functions (necessary for Xposed hooks)
 * - GoogleSignInVirtualizer handles concurrency internally
 */
object GoogleSignInHook {
    private const val TAG = "GoogleSignInHook"

    /** Reference to the virtualizer (injected during initialization) */
    @Volatile
    private var virtualizer: GoogleSignInVirtualizer? = null

    /** Flag to prevent recursive hook calls */
    private val isInHook = ThreadLocal.withInitial { false }

    /**
     * Initialize the hook with a virtualizer instance.
     * Must be called before installing hooks.
     */
    fun initialize(virtualizer: GoogleSignInVirtualizer) {
        this.virtualizer = virtualizer
        RenjanaLog.i(TAG, "GoogleSignInHook initialized")
    }

    /**
     * Install all Google Sign-In hooks for a guest app.
     *
     * @param classLoader The guest app's ClassLoader
     * @param instanceId The container instance ID
     * @return true if at least one hook was installed successfully
     */
    fun installHooks(classLoader: ClassLoader, instanceId: String): Boolean {
        // Auto-initialize from the singleton if not already initialized explicitly.
        // This allows PineHookManager to call installHooks() without a prior
        // initialize(virtualizer) call, as long as GoogleSignInVirtualizer.init()
        // has been invoked during application startup.
        if (virtualizer == null) {
            if (GoogleSignInVirtualizer.isInitialized()) {
                virtualizer = GoogleSignInVirtualizer.get()
                RenjanaLog.i(TAG, "Auto-initialized virtualizer from singleton")
            } else {
                RenjanaLog.w(TAG, "Virtualizer not initialized, cannot install hooks")
                return false
            }
        }

        var hooksInstalled = 0

        // Hook 1: GoogleSignInClient.silentSignIn()
        if (hookSilentSignIn(classLoader, instanceId)) hooksInstalled++

        // Hook 2: GoogleSignInClient.getSignInIntent()
        if (hookGetSignInIntent(classLoader, instanceId)) hooksInstalled++

        // Hook 3: GoogleSignInClient.signOut()
        if (hookSignOut(classLoader, instanceId)) hooksInstalled++

        // Hook 4: GoogleSignInClient.revokeAccess()
        if (hookRevokeAccess(classLoader, instanceId)) hooksInstalled++

        // Hook 5: GoogleSignIn.getSignedInAccountFromIntent()
        if (hookGetSignedInAccountFromIntent(classLoader, instanceId)) hooksInstalled++

        // Hook 6: GoogleSignIn.getLastSignedInAccount()
        if (hookGetLastSignedInAccount(classLoader, instanceId)) hooksInstalled++

        // Hook 7: Activity.startActivityForResult() (for sign-in intents)
        if (hookStartActivityForResult(instanceId)) hooksInstalled++

        // Hook 8: GoogleSignInOptions.Builder.requestIdToken()
        if (hookRequestIdToken(classLoader, instanceId)) hooksInstalled++

        // Hook 9: CredentialManager.getCredential() (Android 14+)
        if (hookCredentialManager(classLoader, instanceId)) hooksInstalled++

        // Hook 10: AccountManager.getAccountsByType("com.google") (filter to assigned account)
        if (hookAccountManagerGetAccounts(classLoader, instanceId)) hooksInstalled++

        RenjanaLog.i(TAG, "Installed $hooksInstalled/10 Google Sign-In hooks for instance $instanceId")
        return hooksInstalled > 0
    }

    // ==================== Hook 1: silentSignIn ====================

    /**
     * Hook GoogleSignInClient.silentSignIn() to return virtualized account.
     *
     * Original signature: Task<GoogleSignInAccount> silentSignIn()
     */
    private fun hookSilentSignIn(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClientClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInClient",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClientClass,
                "silentSignIn",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            // Perform silent sign-in via virtualizer
                            val task = runBlocking {
                                virt.silentSignIn(resolvedInstanceId)
                            }

                            // Replace the original task result
                            param.result = task

                            RenjanaLog.d(TAG, "Intercepted silentSignIn() for instance $resolvedInstanceId")
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignInClient.silentSignIn()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook silentSignIn: ${e.message}")
            false
        }
    }

    // ==================== Hook 2: getSignInIntent ====================

    /**
     * Hook GoogleSignInClient.getSignInIntent() to return account picker intent.
     *
     * Original signature: Intent getSignInIntent()
     */
    private fun hookGetSignInIntent(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClientClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInClient",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClientClass,
                "getSignInIntent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            // Return account picker intent instead of Google's sign-in UI
                            val intent = virt.getSignInIntent(resolvedInstanceId)
                            param.result = intent

                            RenjanaLog.d(TAG, "Intercepted getSignInIntent() for instance $resolvedInstanceId")
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignInClient.getSignInIntent()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook getSignInIntent: ${e.message}")
            false
        }
    }

    // ==================== Hook 3: signOut ====================

    /**
     * Hook GoogleSignInClient.signOut() to clear instance mapping.
     *
     * Original signature: Task<Void> signOut()
     */
    private fun hookSignOut(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClientClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInClient",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClientClass,
                "signOut",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            // Clear account mapping
                            virt.removeAccountFromInstance(resolvedInstanceId)

                            // Also clear from CoreHooks
                            CoreHooks.virtualAccounts.remove(resolvedInstanceId)

                            RenjanaLog.d(TAG, "Intercepted signOut() for instance $resolvedInstanceId")
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignInClient.signOut()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook signOut: ${e.message}")
            false
        }
    }

    // ==================== Hook 4: revokeAccess ====================

    /**
     * Hook GoogleSignInClient.revokeAccess() to clear instance mapping and tokens.
     *
     * Original signature: Task<Void> revokeAccess()
     */
    private fun hookRevokeAccess(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClientClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInClient",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClientClass,
                "revokeAccess",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            // Clear account mapping and revoke tokens
                            virt.removeAccountFromInstance(resolvedInstanceId)
                            CoreHooks.virtualAccounts.remove(resolvedInstanceId)

                            RenjanaLog.d(TAG, "Intercepted revokeAccess() for instance $resolvedInstanceId")
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignInClient.revokeAccess()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook revokeAccess: ${e.message}")
            false
        }
    }

    // ==================== Hook 5: getSignedInAccountFromIntent ====================

    /**
     * Hook GoogleSignIn.getSignedInAccountFromIntent() to return virtualized account.
     *
     * Original signature: GoogleSignInAccount getSignedInAccountFromIntent(Intent)
     */
    private fun hookGetSignedInAccountFromIntent(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignIn",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClass,
                "getSignedInAccountFromIntent",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return
                        val intent = param.args[0] as? Intent ?: return

                        isInHook.set(true)
                        try {
                            // Extract selected account ID from intent extras
                            val selectedAccountId = intent.getStringExtra(
                                GoogleSignInVirtualizer.EXTRA_SELECTED_ACCOUNT_ID
                            )

                            if (selectedAccountId != null) {
                                // User selected an account in the picker
                                val task = runBlocking {
                                    virt.handleAccountPickerResult(
                                        resolvedInstanceId,
                                        Activity.RESULT_OK,
                                        intent
                                    )
                                }

                                // Get the account from the task
                                val taskIsSuccessful1 = runCatching { task?.javaClass?.getMethod("isSuccessful")?.invoke(task) as? Boolean }.getOrNull() ?: false
                                if (taskIsSuccessful1) {
                                    param.result = runCatching { task?.javaClass?.getMethod("getResult")?.invoke(task) }.getOrNull()
                                    RenjanaLog.d(TAG, "Intercepted getSignedInAccountFromIntent() for instance $resolvedInstanceId")
                                }
                            } else {
                                // No account selected, try silent sign-in
                                val account = runBlocking {
                                    virt.getInstanceAccount(resolvedInstanceId)
                                }

                                if (account != null) {
                                    // Return cached account
                                    val task = runBlocking {
                                        virt.silentSignIn(resolvedInstanceId)
                                    }
                                    val taskIsSuccessful2 = runCatching { task?.javaClass?.getMethod("isSuccessful")?.invoke(task) as? Boolean }.getOrNull() ?: false
                                    if (taskIsSuccessful2) {
                                        param.result = runCatching { task?.javaClass?.getMethod("getResult")?.invoke(task) }.getOrNull()
                                    }
                                }
                            }
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignIn.getSignedInAccountFromIntent()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook getSignedInAccountFromIntent: ${e.message}")
            false
        }
    }

    // ==================== Hook 6: getLastSignedInAccount ====================

    /**
     * Hook GoogleSignIn.getLastSignedInAccount() to return virtualized account.
     *
     * Original signature: GoogleSignInAccount getLastSignedInAccount(Context)
     */
    private fun hookGetLastSignedInAccount(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val googleSignInClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignIn",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                googleSignInClass,
                "getLastSignedInAccount",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            val task = runBlocking {
                                virt.silentSignIn(resolvedInstanceId)
                            }

                            val taskIsSuccessful3 = runCatching { task?.javaClass?.getMethod("isSuccessful")?.invoke(task) as? Boolean }.getOrNull() ?: false
                            if (taskIsSuccessful3) {
                                param.result = runCatching { task?.javaClass?.getMethod("getResult")?.invoke(task) }.getOrNull()
                                RenjanaLog.d(TAG, "Intercepted getLastSignedInAccount() for instance $resolvedInstanceId")
                            } else {
                                param.result = null
                            }
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignIn.getLastSignedInAccount()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook getLastSignedInAccount: ${e.message}")
            false
        }
    }

    // ==================== Hook 7: startActivityForResult ====================

    /**
     * Hook Activity.startActivityForResult() to intercept sign-in intents.
     *
     * This hook catches when guest apps launch the sign-in intent and shows
     * the account picker instead.
     */
    private fun hookStartActivityForResult(instanceId: String): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return
                        val intent = param.args[0] as? Intent ?: return
                        val requestCode = param.args[1] as Int

                        // Check if this is a Google Sign-In intent
                        if (isGoogleSignInIntent(intent)) {
                            isInHook.set(true)
                            try {
                                // Get account picker intent
                                val pickerIntent = virt.getSignInIntent(resolvedInstanceId).apply {
                                    putExtra(GoogleSignInVirtualizer.EXTRA_REQUEST_CODE, requestCode)
                                }

                                // Replace the intent
                                param.args[0] = pickerIntent

                                RenjanaLog.d(TAG, "Intercepted sign-in intent for instance $resolvedInstanceId")
                            } finally {
                                isInHook.set(false)
                            }
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked Activity.startActivityForResult()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook startActivityForResult: ${e.message}")
            false
        }
    }

    /**
     * Check if an Intent is a Google Sign-In intent.
     */
    private fun isGoogleSignInIntent(intent: Intent): Boolean {
        val component = intent.component ?: return false
        val className = component.className

        // Check for Google Sign-In activity
        return className.contains("GoogleSignInActivity", ignoreCase = true) ||
               className.contains("SignInHubActivity", ignoreCase = true) ||
               className.contains("AuthSignInActivity", ignoreCase = true)
    }

    // ==================== Hook 8: requestIdToken ====================

    /**
     * Hook GoogleSignInOptions.Builder.requestIdToken() to capture server client ID.
     *
     * Original signature: Builder requestIdToken(String serverClientId)
     */
    private fun hookRequestIdToken(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val builderClass = XposedHelpers.findClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                builderClass,
                "requestIdToken",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return
                        val serverClientId = param.args[0] as? String ?: return

                        isInHook.set(true)
                        try {
                            // Store server client ID for token validation
                            virt.setServerClientId(resolvedInstanceId, serverClientId)

                            RenjanaLog.d(TAG, "Captured server client ID for instance $resolvedInstanceId: $serverClientId")
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked GoogleSignInOptions.Builder.requestIdToken()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook requestIdToken: ${e.message}")
            false
        }
    }

    // ==================== Hook 9: CredentialManager (Android 14+) ====================

    /**
     * Hook CredentialManager.getCredential() for Android 14+ Credential Manager API.
     *
     * Original signature: GetCredentialResponse getCredential(Context, GetCredentialRequest)
     */
    private fun hookCredentialManager(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val credentialManagerClass = XposedHelpers.findClass(
                "androidx.credentials.CredentialManager",
                classLoader
            )

            val getCredentialRequestClass = XposedHelpers.findClass(
                "androidx.credentials.GetCredentialRequest",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                credentialManagerClass,
                "getCredential",
                Context::class.java,
                getCredentialRequestClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val virt = virtualizer ?: return
                        val resolvedInstanceId = CoreHooks.currentInstanceId.get() ?: return
                        val request = param.args[1] ?: return

                        isInHook.set(true)
                        try {
                            // Check if request contains Google credential options
                            if (hasGoogleCredentialOption(request, classLoader)) {
                                // Perform silent sign-in to get account
                                val task = runBlocking {
                                    virt.silentSignIn(resolvedInstanceId)
                                }

                                val isSuccessful = task?.let {
                                    try { it.javaClass.getMethod("isSuccessful").invoke(it) as? Boolean } catch (_: Exception) { null }
                                } ?: false
                                if (isSuccessful) {
                                    val account = task?.let {
                                        try { it.javaClass.getMethod("getResult").invoke(it) } catch (_: Exception) { null }
                                    }

                                    // Create synthetic GoogleIdTokenCredential
                                    val credential = account?.let { createVirtualCredential(it, classLoader) }
                                    if (credential != null) {
                                        // Wrap in GetCredentialResponse
                                        val response = wrapInGetCredentialResponse(credential, classLoader)
                                        if (response != null) {
                                            param.result = response
                                            RenjanaLog.d(TAG, "Intercepted CredentialManager.getCredential() for instance $resolvedInstanceId")
                                        }
                                    }
                                }
                            }
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked CredentialManager.getCredential()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook CredentialManager: ${e.message}")
            false
        }
    }

    /**
     * Check if a GetCredentialRequest contains Google credential options.
     */
    private fun hasGoogleCredentialOption(request: Any, classLoader: ClassLoader): Boolean {
        return try {
            val requestClass = request.javaClass
            val getCredentialOptionsMethod = requestClass.getMethod("getCredentialOptions")
            val options = getCredentialOptionsMethod.invoke(request) as? List<*> ?: return false

            // Check each option
            for (option in options) {
                val optionClass = option?.javaClass ?: continue
                val className = optionClass.name

                // Check for Google credential option types
                if (className.contains("GetGoogleIdOption", ignoreCase = true) ||
                    className.contains("GetSignInWithGoogleOption", ignoreCase = true)) {
                    return true
                }
            }

            false
        } catch (e: Exception) {
            RenjanaLog.d(TAG, "Failed to check credential options: ${e.message}")
            false
        }
    }

    /**
     * Create a virtual GoogleIdTokenCredential from a GoogleSignInAccount.
     */
    private fun createVirtualCredential(account: Any, classLoader: ClassLoader): Any? {
        return try {
            // Extract ID token from account
            val accountClass = account.javaClass
            val getIdTokenMethod = accountClass.getMethod("getIdToken")
            val idToken = getIdTokenMethod.invoke(account) as? String ?: return null

            // Create GoogleIdTokenCredential via reflection
            val credentialClass = XposedHelpers.findClass(
                "com.google.android.libraries.identity.googleid.GoogleIdTokenCredential",
                classLoader
            )

            // Use constructor or builder
            val constructor = credentialClass.getDeclaredConstructor(String::class.java)
            constructor.isAccessible = true
            constructor.newInstance(idToken)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to create virtual credential: ${e.message}")
            null
        }
    }

    /**
     * Wrap a credential in a GetCredentialResponse.
     */
    private fun wrapInGetCredentialResponse(credential: Any, classLoader: ClassLoader): Any? {
        return try {
            val responseClass = XposedHelpers.findClass(
                "androidx.credentials.GetCredentialResponse",
                classLoader
            )

            val credentialInterface = XposedHelpers.findClass(
                "androidx.credentials.Credential",
                classLoader
            )

            val constructor = responseClass.getDeclaredConstructor(credentialInterface)
            constructor.isAccessible = true
            constructor.newInstance(credential)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to wrap credential in response: ${e.message}")
            null
        }
    }

    // ==================== Hook 10: AccountManager.getAccountsByType ====================

    /**
     * Hook AccountManager.getAccountsByType() to filter Google accounts
     * down to only the account assigned to the current container instance.
     *
     * Original signature: Account[] getAccountsByType(String type)
     *
     * When a guest app queries for "com.google" accounts, it should only see
     * the account that was explicitly assigned to its instance — not every
     * Google account on the device. This prevents cross-instance account
     * leakage.
     */
    private fun hookAccountManagerGetAccounts(classLoader: ClassLoader, instanceId: String): Boolean {
        return try {
            val accountManagerClass = XposedHelpers.findClass(
                "android.accounts.AccountManager",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                accountManagerClass,
                "getAccountsByType",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInHook.get() ?: false) return

                        val type = param.args[0] as? String ?: return
                        // Only intercept Google account queries
                        if (type != "com.google") return

                        val virt = virtualizer ?: return
                        val currentInstanceId = CoreHooks.currentInstanceId.get() ?: return

                        isInHook.set(true)
                        try {
                            // Get the account assigned to this instance
                            val assignedAccount = runBlocking {
                                virt.getInstanceAccount(currentInstanceId)
                            }

                            if (assignedAccount == null) {
                                // No account assigned — guest sees zero Google accounts
                                param.result = emptyArray<Account>()
                                RenjanaLog.d(TAG, "Filtered getAccountsByType(\"com.google\") to empty (no account assigned to instance $currentInstanceId)")
                            } else {
                                // Filter the result to only include the assigned account's email
                                val originalAccounts = param.result as? Array<Account> ?: emptyArray()
                                val filtered = originalAccounts.filter { account ->
                                    account.name == assignedAccount.email
                                }.toTypedArray()

                                param.result = filtered
                                RenjanaLog.d(TAG, "Filtered getAccountsByType(\"com.google\") to ${filtered.size} account(s) for instance $currentInstanceId (assigned: ${assignedAccount.email})")
                            }
                        } finally {
                            isInHook.set(false)
                        }
                    }
                }
            )

            RenjanaLog.d(TAG, "Hooked AccountManager.getAccountsByType()")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook AccountManager.getAccountsByType: ${e.message}")
            false
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Uninstall all hooks (for cleanup).
     * Note: Xposed hooks cannot be uninstalled at runtime, this only resets state.
     */
    fun uninstallHooks() {
        virtualizer = null
        isInHook.remove()
        RenjanaLog.i(TAG, "GoogleSignInHook uninstalled (state reset)")
    }

    /**
     * Check if hooks are initialized.
     * Returns true if a virtualizer is available (either explicitly injected
     * or auto-initialized from the GoogleSignInVirtualizer singleton).
     */
    fun isInitialized(): Boolean = virtualizer != null || GoogleSignInVirtualizer.isInitialized()
}
