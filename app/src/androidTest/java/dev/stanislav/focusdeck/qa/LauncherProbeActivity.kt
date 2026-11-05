package dev.stanislav.focusdeck.qa

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.WindowManager

/** Harmless target used only by the separate instrumentation APK. */
class LauncherProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "FocusDeck QA probe" })
    }
}
