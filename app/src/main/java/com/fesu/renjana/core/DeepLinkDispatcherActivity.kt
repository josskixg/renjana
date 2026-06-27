package com.fesu.renjana.core

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.utils.RenjanaLog

/**
 * DeepLinkDispatcherActivity — Receives deep links from the OS and routes them
 * to the correct Renjana instance instead of the original app.
 *
 * Flow:
 * 1. OS delivers a VIEW intent (http/https/custom scheme) to this activity.
 * 2. We extract the URI and ask IntentRouter to resolve the matching instance.
 * 3. If an instance is registered for that scheme, we build a StubActivity intent
 *    that carries the original URI data and launch it.
 * 4. If no instance is registered, we pass the intent through to the original app.
 *
 * This activity is transparent (Theme.Translucent.NoTitleBar) and finishes
 * immediately after dispatching, so the user never sees it.
 */
class DeepLinkDispatcherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeepLinkDispatcher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: run {
            RenjanaLog.w(TAG, "No URI in intent — finishing")
            finish()
            return
        }

        RenjanaLog.i(TAG, "Dispatching deep link: $uri")

        val router = RenjanaApplication.get().intentRouter
        val instanceId = router.resolveDeepLink(uri)

        when {
            instanceId != null -> {
                RenjanaLog.i(TAG, "Routing deep link to instance: $instanceId, uri=$uri")
                val launchIntent = router.buildDeepLinkIntent(this, instanceId, intent)
                startActivity(launchIntent)
            }
            else -> {
                // No instance registered for this URI — pass through to the original app.
                // Strip our component so Android re-resolves to other handlers.
                RenjanaLog.d(TAG, "No instance for scheme '${uri.scheme}' — passing through")
                val fallback = Intent(intent).apply {
                    component = null
                    `package` = null
                    // Remove FLAG_ACTIVITY_BROUGHT_TO_FRONT to avoid loops
                    flags = flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT.inv()
                }
                try {
                    startActivity(fallback)
                } catch (e: android.content.ActivityNotFoundException) {
                    RenjanaLog.w(TAG, "No handler found for URI: $uri")
                }
            }
        }

        finish()
    }
}
