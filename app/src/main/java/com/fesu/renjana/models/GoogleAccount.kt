package com.fesu.renjana.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a Google account stored in Renjana
 */
@Parcelize
data class GoogleAccount(
    val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?,
    val idToken: String,
    val accessToken: String?,
    val refreshToken: String?,
    val tokenExpiryTime: Long,
    val createdAt: Long
) : Parcelable {
    
    fun isTokenExpired(): Boolean {
        return System.currentTimeMillis() > tokenExpiryTime
    }
}
