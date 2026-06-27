package com.fesu.renjana.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fesu.renjana.ui.screens.SplashScreen
import com.fesu.renjana.core.CrashHandler
import com.fesu.renjana.ui.screens.CrashScreen
import com.fesu.renjana.ui.theme.AccentBlue
import com.fesu.renjana.ui.theme.RenjanaTheme
import com.fesu.renjana.utils.PermissionManager
import com.fesu.renjana.utils.ThemePreferences

class MainActivity : ComponentActivity() {

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Log.w(TAG, "Storage permissions denied — app will continue with limited access")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        setContent {
            val darkMode = remember { mutableStateOf(ThemePreferences.isDarkMode(this)) }
            val accentColor = remember { mutableStateOf(ThemePreferences.getAccentColor(this)) }

            // Check for pending crash from previous session
            val pendingCrash = remember { mutableStateOf(CrashHandler.getPendingCrash(this)) }
            val crashContent = remember(pendingCrash.value) {
                pendingCrash.value?.readText()
            }

            // Splash state: rememberSaveable so rotation doesn't re-show splash,
            // but a fresh process start (first launch) always shows it.
            var showSplash by rememberSaveable { mutableStateOf(false) }

            RenjanaTheme(
                darkTheme = darkMode.value,
                dynamicColor = false,
                accentColor = accentColor.value
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(durationMillis = 400),
                        label = "SplashToApp",
                    ) { isSplash ->
                        if (isSplash) {
                            SplashScreen(
                                onAnimationComplete = { showSplash = false }
                            )
                        } else {
                            if (crashContent != null) {
                                // Show crash screen
                                CrashScreen(
                                    crashContent = crashContent,
                                    onRestart = {
                                        CrashHandler.clearCrashMarker(this)
                                        pendingCrash.value = null
                                        // Restart the app cleanly
                                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                        finish()
                                    },
                                    onDismiss = {
                                        // Clear marker and continue to normal app
                                        CrashHandler.clearCrashMarker(this)
                                        pendingCrash.value = null
                                    }
                                )
                            } else {
                                RenjanaApp(
                                    darkMode = darkMode.value,
                                    onToggleDarkMode = { enabled ->
                                        darkMode.value = enabled
                                        ThemePreferences.setDarkMode(this, enabled)
                                    },
                                    accentColor = accentColor.value,
                                    onAccentChange = { color ->
                                        accentColor.value = color
                                        ThemePreferences.setAccentColor(this, color)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (PermissionManager.hasStoragePermission(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionManager.openManageStorageSettings(this)
        } else {
            storagePermissionLauncher.launch(PermissionManager.getRequiredPermissions())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
