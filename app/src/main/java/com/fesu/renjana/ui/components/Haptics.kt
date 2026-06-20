package com.fesu.renjana.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Haptic feedback helpers for Compose.
 *
 * Usage:
 *   val haptics = rememberHaptics()
 *   haptics.tap()      // light tap
 *   haptics.confirm()  // stronger confirm
 *   haptics.reject()   // reject/buzz
 */
class Haptics(private val view: android.view.View) {
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return Haptics(view)
}
