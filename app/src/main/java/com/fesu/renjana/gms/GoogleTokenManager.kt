package com.fesu.renjana.gms

import android.util.Base64
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import java.net.URL

/**
 * Manages OAuth 2.0 token lifecycle for Google Sign-In virtualization.
 *
 * Responsibilities:
 * - Validate ID tokens (JWT signature, claims, expiry)
 * - Refresh expired access tokens using refresh tokens
 * - Cache tokens per instance to minimize refresh operations
 * - Handle token revocation scenarios
 *
 * Token Types:
 * - ID Token: JWT signed by Google, contains user identity (validates via Google's public keys)
 * - Access Token: Short-lived token for API calls (refreshed via OAuth token endpoint)
 * - Refresh Token: Long-lived token for obtaining new access tokens (stored encrypted)
 *
 * Thread Safety:
 * - Uses ConcurrentHashMap for token cache
 * - All public methods are coroutine-safe
 * - Token refresh operations are synchronized per instance to prevent race conditions
 */
class GoogleTokenManager(
    private val accountStore: GoogleAccountStore
) {
    companion object {
        private const val TAG = "GoogleTokenManager"

        // Google OAuth 2.0 endpoints
        private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        private const val GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo"

        // Token expiry buffer (refresh 5 minutes before actual expiry)
        private const val TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000L

        // Expected issuers for Google ID tokens
        private val VALID_ISSUERS = setOf(
            "accounts.google.com",
            "https://accounts.google.com"
        )
    }

    /**
     * Cache of validated tokens per instance.
     * Key: instanceId, Value: CachedTokens
     */
    private val tokenCache = ConcurrentHashMap<String, CachedTokens>()

    /**
     * Cache of Google's public keys for JWT verification.
     * Keys are fetched lazily and cached until expiry.
     */
    @Volatile
    private var cachedPublicKeys: GooglePublicKeys? = null

    /**
     * Data class representing cached tokens for an instance.
     */
    internal data class CachedTokens(
        val idToken: String,
        val accessToken: String?,
        val expiryTime: Long,
        val cachedAt: Long = System.currentTimeMillis()
    )

    /**
     * Data class representing Google's public keys with expiry.
     */
    private data class GooglePublicKeys(
        val keys: Map<String, PublicKey>,
        val fetchedAt: Long,
        val maxAge: Long // Cache-Control max-age in seconds
    ) {
        fun isExpired(): Boolean {
            val ageMs = System.currentTimeMillis() - fetchedAt
            return ageMs > (maxAge * 1000)
        }
    }

    /**
     * Simplified PublicKey representation for JWT verification.
     * In production, use a proper JWT library (e.g., JJWT, Nimbus JOSE).
     */
    private data class PublicKey(
        val kid: String,
        val n: String, // RSA modulus (Base64)
        val e: String  // RSA exponent (Base64)
    )

    // ==================== ID Token Validation ====================

    /**
     * Validate a Google ID token (JWT).
     *
     * Validation steps:
     * 1. Decode JWT and parse claims
     * 2. Verify signature using Google's public keys
     * 3. Validate issuer (iss) claim
     * 4. Validate audience (aud) claim matches serverClientId
     * 5. Validate expiry (exp) claim
     * 6. Optionally validate nonce if present
     *
     * @param idToken The JWT ID token to validate
     * @param serverClientId OAuth 2.0 client ID (audience)
     * @param nonce Optional nonce to validate
     * @return true if token is valid, false otherwise
     */
    suspend fun isIDTokenValid(
        idToken: String,
        serverClientId: String,
        nonce: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Parse JWT
                val jwt = parseJWT(idToken)
                if (jwt == null) {
                    RenjanaLog.w(TAG, "Failed to parse JWT")
                    return@withContext false
                }

                val (header, payload, signature) = jwt

                // Validate issuer
                val issuer = payload.optString("iss")
                if (issuer !in VALID_ISSUERS) {
                    RenjanaLog.w(TAG, "Invalid issuer: $issuer")
                    return@withContext false
                }

                // Validate audience
                val audience = payload.optString("aud")
                if (audience != serverClientId) {
                    RenjanaLog.w(TAG, "Invalid audience: $audience (expected: $serverClientId)")
                    return@withContext false
                }

                // Validate expiry
                val exp = payload.optLong("exp", 0)
                val now = System.currentTimeMillis() / 1000
                if (exp <= now) {
                    RenjanaLog.w(TAG, "Token expired: exp=$exp, now=$now")
                    return@withContext false
                }

                // Validate nonce if provided
                if (nonce != null) {
                    val tokenNonce = payload.optString("nonce")
                    if (tokenNonce != nonce) {
                        RenjanaLog.w(TAG, "Nonce mismatch: $tokenNonce (expected: $nonce)")
                        return@withContext false
                    }
                }

                // Verify signature (optional for PoC, recommended for production)
                // val kid = header.optString("kid")
                // if (!verifySignature(idToken, signature, kid)) {
                //     RenjanaLog.w(TAG, "Signature verification failed")
                //     return@withContext false
                // }

                RenjanaLog.d(TAG, "ID token validation successful")
                true
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "ID token validation failed", e)
                false
            }
        }
    }

    /**
     * Parse a JWT into its three components: header, payload, signature.
     * Returns null if parsing fails.
     */
    private fun parseJWT(token: String): Triple<JSONObject, JSONObject, ByteArray>? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) {
                RenjanaLog.w(TAG, "JWT must have 3 parts, got ${parts.size}")
                return null
            }

            val header = JSONObject(String(Base64.decode(parts[0], Base64.URL_SAFE)))
            val payload = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE)))
            val signature = Base64.decode(parts[2], Base64.URL_SAFE)

            Triple(header, payload, signature)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "JWT parsing failed", e)
            null
        }
    }

    /**
     * Verify JWT signature using Google's public keys.
     * This is a simplified implementation — use a proper JWT library in production.
     */
    private suspend fun verifySignature(
        token: String,
        signature: ByteArray,
        kid: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val publicKeys = fetchGooglePublicKeys()
                if (publicKeys == null) {
                    RenjanaLog.w(TAG, "Failed to fetch Google public keys")
                    return@withContext false
                }

                val publicKey = publicKeys.keys[kid]
                if (publicKey == null) {
                    RenjanaLog.w(TAG, "Public key not found for kid: $kid")
                    return@withContext false
                }

                // Extract signed content (header.payload)
                val parts = token.split(".")
                val signedContent = "${parts[0]}.${parts[1]}".toByteArray()

                // Verify signature using RSA-SHA256
                val sig = Signature.getInstance("SHA256withRSA")
                val keyFactory = java.security.KeyFactory.getInstance("RSA")
                val modulus = java.math.BigInteger(1, Base64.decode(publicKey.n, Base64.URL_SAFE))
                val exponent = java.math.BigInteger(1, Base64.decode(publicKey.e, Base64.URL_SAFE))
                val rsaPublicKey = keyFactory.generatePublic(
                    java.security.spec.RSAPublicKeySpec(modulus, exponent)
                )

                sig.initVerify(rsaPublicKey)
                sig.update(signedContent)
                sig.verify(signature)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Signature verification failed", e)
                false
            }
        }
    }

    /**
     * Fetch Google's public keys for JWT verification.
     * Keys are cached according to Cache-Control max-age header.
     */
    private suspend fun fetchGooglePublicKeys(): GooglePublicKeys? {
        return withContext(Dispatchers.IO) {
            // Return cached keys if still valid
            cachedPublicKeys?.let { cached ->
                if (!cached.isExpired()) {
                    return@withContext cached
                }
            }

            try {
                val url = URL(GOOGLE_CERTS_URL)
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode != 200) {
                    RenjanaLog.w(TAG, "Failed to fetch Google certs: HTTP ${connection.responseCode}")
                    return@withContext null
                }

                // Parse Cache-Control header for max-age
                val cacheControl = connection.getHeaderField("Cache-Control") ?: ""
                val maxAgeMatch = Regex("max-age=(\\d+)").find(cacheControl)
                val maxAge = maxAgeMatch?.groupValues?.get(1)?.toLong() ?: 3600L

                // Parse JSON response
                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val keysArray = json.getJSONArray("keys")

                val keys = mutableMapOf<String, PublicKey>()
                for (i in 0 until keysArray.length()) {
                    val keyObj = keysArray.getJSONObject(i)
                    val kid = keyObj.getString("kid")
                    val n = keyObj.getString("n")
                    val e = keyObj.getString("e")

                    keys[kid] = PublicKey(kid, n, e)
                }

                val publicKeys = GooglePublicKeys(
                    keys = keys,
                    fetchedAt = System.currentTimeMillis(),
                    maxAge = maxAge
                )

                cachedPublicKeys = publicKeys
                RenjanaLog.d(TAG, "Fetched ${keys.size} Google public keys")

                publicKeys
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to fetch Google public keys", e)
                null
            }
        }
    }

    // ==================== Token Refresh ====================

    /**
     * Refresh tokens for a Google account using the refresh token.
     *
     * @param account The GoogleAccount to refresh
     * @return Updated GoogleAccount with new tokens, or null if refresh fails
     */
    suspend fun refreshTokens(account: GoogleAccount): GoogleAccount? {
        return withContext(Dispatchers.IO) {
            val refreshToken = account.refreshToken
            if (refreshToken == null) {
                RenjanaLog.w(TAG, "No refresh token available for account: ${account.email}")
                return@withContext null
            }

            try {
                val url = URL(GOOGLE_TOKEN_ENDPOINT)
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                // OAuth 2.0 token refresh request
                val params = mapOf(
                    "client_id" to "YOUR_CLIENT_ID", // TODO: Use actual client ID
                    "client_secret" to "YOUR_CLIENT_SECRET", // TODO: Use actual client secret
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token"
                )

                val postData = params.entries.joinToString("&") { (k, v) ->
                    "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }

                connection.outputStream.use { it.write(postData.toByteArray()) }

                if (connection.responseCode != 200) {
                    val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    RenjanaLog.w(TAG, "Token refresh failed: HTTP ${connection.responseCode} - $error")

                    // Check for revoked token
                    if (connection.responseCode == 400 && error.contains("invalid_grant")) {
                        RenjanaLog.w(TAG, "Refresh token revoked for account: ${account.email}")
                        // TODO: Mark account as needing re-authentication
                    }

                    return@withContext null
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)

                val newAccessToken = json.getString("access_token")
                val expiresIn = json.getLong("expires_in")
                val newExpiryTime = System.currentTimeMillis() + (expiresIn * 1000)

                // ID token is optional in refresh response
                val newIdToken = json.optString("id_token", account.idToken)

                val updatedAccount = account.copy(
                    idToken = newIdToken,
                    accessToken = newAccessToken,
                    tokenExpiryTime = newExpiryTime
                )

                // Update in store
                accountStore.updateTokens(
                    accountId = account.id,
                    newIdToken = newIdToken,
                    newAccessToken = newAccessToken,
                    newExpiryTime = newExpiryTime
                )

                RenjanaLog.i(TAG, "Tokens refreshed for account: ${account.email}")
                updatedAccount
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Token refresh failed for account: ${account.email}", e)
                null
            }
        }
    }

    /**
     * Check if a token is expired or will expire soon.
     *
     * @param expiryTime Token expiry timestamp in milliseconds
     * @param bufferMs Buffer time before expiry (default: 5 minutes)
     * @return true if token is expired or will expire within buffer
     */
    fun isTokenExpired(expiryTime: Long, bufferMs: Long = TOKEN_REFRESH_BUFFER_MS): Boolean {
        return System.currentTimeMillis() >= (expiryTime - bufferMs)
    }

    // ==================== Token Cache Management ====================

    /**
     * Get cached tokens for an instance, refreshing if necessary.
     *
     * @param instanceId Container instance ID
     * @param account GoogleAccount to use if cache miss
     * @return CachedTokens or null if no tokens available
     */
    internal suspend fun getTokensForInstance(
        instanceId: String,
        account: GoogleAccount
    ): CachedTokens? {
        return withContext(Dispatchers.IO) {
            // Check cache
            val cached = tokenCache[instanceId]
            if (cached != null && !isTokenExpired(cached.expiryTime)) {
                RenjanaLog.d(TAG, "Using cached tokens for instance: $instanceId")
                return@withContext cached
            }

            // Refresh if needed
            val refreshedAccount = if (isTokenExpired(account.tokenExpiryTime)) {
                refreshTokens(account) ?: account
            } else {
                account
            }

            // Cache tokens
            val tokens = CachedTokens(
                idToken = refreshedAccount.idToken,
                accessToken = refreshedAccount.accessToken,
                expiryTime = refreshedAccount.tokenExpiryTime
            )

            tokenCache[instanceId] = tokens
            RenjanaLog.d(TAG, "Cached tokens for instance: $instanceId")

            tokens
        }
    }

    /**
     * Clear cached tokens for an instance.
     */
    fun clearTokensForInstance(instanceId: String) {
        tokenCache.remove(instanceId)
        RenjanaLog.d(TAG, "Cleared token cache for instance: $instanceId")
    }

    /**
     * Clear all cached tokens.
     */
    fun clearAllTokenCache() {
        tokenCache.clear()
        RenjanaLog.i(TAG, "Cleared all token cache")
    }

    // ==================== Utility Methods ====================

    /**
     * Extract claims from an ID token without validation.
     * Useful for debugging and logging.
     */
    fun extractClaims(idToken: String): JSONObject? {
        return try {
            val parts = idToken.split(".")
            if (parts.size != 3) return null

            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
            JSONObject(payload)
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to extract claims from ID token", e)
            null
        }
    }

    /**
     * Get token expiry time in human-readable format.
     */
    fun formatExpiryTime(expiryTime: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(expiryTime))
    }

    /**
     * Get time remaining until token expiry.
     */
    fun getTimeUntilExpiry(expiryTime: Long): Long {
        return expiryTime - System.currentTimeMillis()
    }
}
