package dev.stanislav.focusdeck

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.WindowManager

internal object QaScreenControl {
    internal fun shouldKeep(packageName: String, debuggable: Boolean, requested: Boolean, resumed: Boolean) =
        packageName == "dev.stanislav.focusdeck.qa" && debuggable && requested && resumed

    fun update(activity: Activity, resumed: Boolean) {
        val keep = shouldKeep(activity.packageName,
            activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            activity.intent.getBooleanExtra("qa_keep_screen_on", false), resumed)
        if (keep) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
