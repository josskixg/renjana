package com.fesu.renjana.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.fesu.renjana.utils.RenjanaLog

class ContainerService : Service() {
    companion object {
        private const val TAG = "ContainerService"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ContainerService = this@ContainerService
    }

    override fun onBind(intent: Intent?): IBinder {
        RenjanaLog.d(TAG, "onBind")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        RenjanaLog.i(TAG, "ContainerService created")
    }

    override fun onDestroy() {
        RenjanaLog.i(TAG, "ContainerService destroyed")
        super.onDestroy()
    }
}
