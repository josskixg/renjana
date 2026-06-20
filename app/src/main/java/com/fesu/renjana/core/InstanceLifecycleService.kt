package com.fesu.renjana.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * InstanceLifecycleService — Foreground service that keeps container alive
 * when user minimizes the app.
 *
 * Responsibilities:
 * 1. Track running instances and their states
 * 2. Show per-instance notifications
 * 3. Health check every 30s
 * 4. Allow re-opening instances via notification tap
 * 5. Allow stopping instances via notification action
 *
 * The service starts as foreground when the first instance launches,
 * and stops when no instances are running.
 */
class InstanceLifecycleService : Service() {

    companion object {
        private const val TAG = "LifecycleService"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        const val NOTIFICATION_ID = 9000

        fun startForInstance(context: android.content.Context, instanceId: String) {
            val intent = Intent(context, InstanceLifecycleService::class.java).apply {
                action = InstanceNotificationManager.ACTION_OPEN_INSTANCE
                putExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID, instanceId)
            }
            context.startForegroundService(intent)
            RenjanaLog.i(TAG, "Service start requested for instance $instanceId")
        }

        fun stopInstance(context: android.content.Context, instanceId: String) {
            val intent = Intent(context, InstanceLifecycleService::class.java).apply {
                action = InstanceNotificationManager.ACTION_STOP_INSTANCE
                putExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID, instanceId)
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val runningInstances = mutableMapOf<String, RunningInstance>()
    private lateinit var notifManager: InstanceNotificationManager

    inner class LocalBinder : Binder() {
        fun getService(): InstanceLifecycleService = this@InstanceLifecycleService
    }

    override fun onCreate() {
        super.onCreate()
        notifManager = InstanceNotificationManager(this)
        RenjanaApplication.get().lifecycleService = this
        RenjanaLog.i(TAG, "InstanceLifecycleService created")
        startHealthCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            InstanceNotificationManager.ACTION_OPEN_INSTANCE -> {
                val instanceId = intent.getStringExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID)
                if (instanceId != null) {
                    startForeground(NOTIFICATION_ID, notifManager.createForegroundNotification())
                    registerInstance(instanceId)
                } else {
                    startForeground(NOTIFICATION_ID, notifManager.createForegroundNotification())
                }
            }
            InstanceNotificationManager.ACTION_STOP_INSTANCE -> {
                val instanceId = intent.getStringExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID)
                if (instanceId != null) {
                    stopInstance(instanceId)
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, notifManager.createForegroundNotification())
            }
        }
        return START_STICKY
    }

    /**
     * Register an instance as running. Called by InstanceLauncher before
     * starting WrapperActivity.
     */
    private fun registerInstance(instanceId: String) {
        scope.launch {
            try {
                val instanceManager = RenjanaApplication.get().instanceManager
                val instance = instanceManager.getInstanceById(instanceId)
                if (instance != null) {
                    val running = RunningInstance(
                        instanceId = instanceId,
                        packageName = instance.packageName,
                        appName = instance.appName,
                        state = InstanceState.RUNNING,
                        startedAt = System.currentTimeMillis()
                    )
                    runningInstances[instanceId] = running
                    notifManager.showInstanceNotification(running)
                    RenjanaLog.i(TAG, "Instance registered: $instanceId (${instance.appName})")
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to register instance $instanceId", e)
            }
        }
    }

    /**
     * Called by WrapperActivity when it becomes visible (onResume).
     */
    fun onInstanceResumed(instanceId: String) {
        runningInstances[instanceId]?.let { running ->
            running.updateState(InstanceState.RUNNING)
            notifManager.showInstanceNotification(running)
            RenjanaLog.d(TAG, "Instance $instanceId resumed → RUNNING")
        }
    }

    /**
     * Called by WrapperActivity when it goes to background (onPause).
     */
    fun onInstancePaused(instanceId: String) {
        runningInstances[instanceId]?.let { running ->
            running.updateState(InstanceState.PAUSED)
            notifManager.showInstanceNotification(running)
            RenjanaLog.d(TAG, "Instance $instanceId paused → PAUSED")
        }
    }

    /**
     * Called by WrapperActivity when it's destroyed (onDestroy).
     */
    fun onInstanceDestroyed(instanceId: String) {
        runningInstances[instanceId]?.let { running ->
            running.updateState(InstanceState.STOPPED)
            notifManager.cancelInstanceNotification(instanceId)
            RenjanaLog.d(TAG, "Instance $instanceId destroyed → STOPPED")
        }
        runningInstances.remove(instanceId)
        checkAndStopIfEmpty()
    }

    /**
     * Called by WrapperActivity when guest app crashes.
     */
    fun onInstanceError(instanceId: String, error: String) {
        runningInstances[instanceId]?.let { running ->
            running.updateState(InstanceState.ERROR)
            notifManager.showInstanceNotification(running)
            RenjanaLog.e(TAG, "Instance $instanceId error: $error")
        }
        runningInstances.remove(instanceId)
        checkAndStopIfEmpty()
    }

    /**
     * Stop a specific instance (from notification action or UI).
     */
    fun stopInstance(instanceId: String) {
        runningInstances.remove(instanceId)
        notifManager.cancelInstanceNotification(instanceId)

        // Update DB
        scope.launch {
            try {
                RenjanaApplication.get().instanceManager.updateInstanceUsage(
                    instanceId, System.currentTimeMillis(), false
                )
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update instance usage on stop", e)
            }
        }

        RenjanaLog.i(TAG, "Instance $instanceId stopped")
        checkAndStopIfEmpty()
    }

    /**
     * Get current state of an instance.
     */
    fun getInstanceState(instanceId: String): InstanceState {
        return runningInstances[instanceId]?.state ?: InstanceState.IDLE
    }

    /**
     * Get all running instances.
     */
    fun getRunningInstances(): List<RunningInstance> {
        return runningInstances.values.toList()
    }

    /**
     * If no instances running, stop the foreground service.
     */
    private fun checkAndStopIfEmpty() {
        if (runningInstances.isEmpty()) {
            RenjanaLog.i(TAG, "No instances running, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Health check loop — verify instances are still alive every 30s.
     */
    private fun startHealthCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val staleInstances = runningInstances.filter { (_, running) ->
                    now - running.lastHealthCheck > HEALTH_CHECK_INTERVAL_MS * 3
                }
                staleInstances.forEach { (id, _) ->
                    RenjanaLog.w(TAG, "Instance $id appears stale, marking as STOPPED")
                    runningInstances.remove(id)
                    notifManager.cancelInstanceNotification(id)
                }
                checkAndStopIfEmpty()
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        job.cancel()
        runningInstances.clear()
        RenjanaApplication.get().lifecycleService = null
        RenjanaLog.i(TAG, "InstanceLifecycleService destroyed")
        super.onDestroy()
    }
}
