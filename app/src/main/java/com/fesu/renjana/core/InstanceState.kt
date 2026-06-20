package com.fesu.renjana.core

import com.fesu.renjana.utils.RenjanaLog

/**
 * Instance lifecycle states.
 *
 * IDLE    → not running, ready to launch
 * RUNNING → activity visible, guest app active
 * PAUSED  → activity minimized (onPause), container alive via foreground service
 * STOPPED → activity destroyed, user explicitly stopped or crashed
 * ERROR   → launch failed or guest app crashed
 */
enum class InstanceState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR;

    val isAlive: Boolean get() = this == RUNNING || this == PAUSED
    val isRunning: Boolean get() = this == RUNNING
}

/**
 * Tracks the runtime state of a running instance.
 */
data class RunningInstance(
    val instanceId: String,
    val packageName: String,
    val appName: String,
    var state: InstanceState,
    val startedAt: Long,
    var lastHealthCheck: Long = System.currentTimeMillis()
) {
    companion object {
        private const val TAG = "RunningInstance"
    }

    fun updateState(newState: InstanceState) {
        RenjanaLog.d(TAG, "Instance $instanceId: $state → $newState")
        state = newState
        lastHealthCheck = System.currentTimeMillis()
    }
}
