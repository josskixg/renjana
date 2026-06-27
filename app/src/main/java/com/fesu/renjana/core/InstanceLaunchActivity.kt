package com.fesu.renjana.core

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fesu.renjana.RenjanaApplication
import kotlinx.coroutines.launch

/**
 * Transparent trampoline activity launched by homescreen pinned shortcuts.
 *
 * Receives the instance ID from the shortcut intent, delegates to
 * InstanceLauncher, then immediately finishes itself. The activity is
 * transparent and excluded from Recents so it is invisible to the user.
 */
class InstanceLaunchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            RenjanaApplication.get().instanceLauncher.launchInstance(instanceId)
            finish()
        }
    }
}
