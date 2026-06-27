package com.fesu.renjana.core

sealed class LaunchResult {
    object Success : LaunchResult()
    data class FallbackNoIsolation(val reason: String) : LaunchResult()
    data class Failure(val message: String) : LaunchResult()
}
