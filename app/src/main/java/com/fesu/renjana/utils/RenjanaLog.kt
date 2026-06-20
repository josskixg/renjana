package com.fesu.renjana.utils

import android.util.Log

/**
 * Logging utility for Renjana
 */
object RenjanaLog {
    private const val TAG = "Renjana"
    private var debugMode = false

    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
    }

    fun v(tag: String, message: String) {
        if (debugMode) {
            Log.v("$TAG:$tag", message)
        }
    }

    fun d(tag: String, message: String) {
        if (debugMode) {
            Log.d("$TAG:$tag", message)
        }
    }

    fun i(tag: String, message: String) {
        Log.i("$TAG:$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$TAG:$tag", message, throwable)
        } else {
            Log.w("$TAG:$tag", message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$TAG:$tag", message, throwable)
        } else {
            Log.e("$TAG:$tag", message)
        }
    }
}
