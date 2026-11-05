package dev.stanislav.focusdeck.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager

/** Conservative package-name floor; dynamic home, dialer and keyboard checks follow. */
internal fun safetyNameExempt(packageName: String, ownPackage: String): Boolean {
    val name = packageName.lowercase()
    return packageName == ownPackage || listOf("systemui", "settings", "dialer", "emergency", "telecom", "phone", "cellbroadcast", "permissioncontroller", "messaging", "contacts").any(name::contains)
}

/** Recovery, phone and current home/keyboard must remain usable even if selected accidentally. */
internal fun safetyExempt(context: Context, packageName: String): Boolean {
    if (safetyNameExempt(packageName, context.packageName)) return true
    return try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (homes.any { it.activityInfo?.packageName == packageName }) return true
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        if (packageName == dialer) return true
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        packageName == keyboard
    } catch (_: Exception) {
        true // If recovery exemptions cannot be checked, do not intercept the app.
    }
}
