package com.fesu.renjana.hooks

import com.fesu.renjana.utils.RenjanaLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "XposedEntry"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        RenjanaLog.d(TAG, "Loaded package: ${lpparam.packageName}")
    }
}
