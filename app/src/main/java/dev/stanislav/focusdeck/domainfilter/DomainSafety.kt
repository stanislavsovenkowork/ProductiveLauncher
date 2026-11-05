package dev.stanislav.focusdeck.domainfilter

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.pm.ApplicationInfo
import dev.stanislav.focusdeck.ui.apps.safetyExempt

/** Recovery exclusions are evaluated now, not frozen forever as launcher/keyboard roles can change. */
internal fun domainSafetyExempt(context:Context,packageName:String,allowSyntheticOwnQa:Boolean=false):Boolean {
    if(DomainPolicy.infrastructurePackage(packageName))return true
    val ownQa=allowSyntheticOwnQa && packageName==context.packageName && packageName.endsWith(".qa") &&
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if(ownQa)return false
    if(safetyExempt(context,packageName))return true
    val permissions=runCatching {
        context.packageManager.getPackageInfo(packageName,PackageManager.GET_SERVICES).services.orEmpty().mapNotNull { it.permission }
    }.getOrNull()
    return vpnServiceExempt(permissions)
}

/** Missing package metadata is not evidence that an app is safe to intercept. */
internal fun vpnServiceExempt(declaredPermissions:List<String>?):Boolean =
    declaredPermissions==null || Manifest.permission.BIND_VPN_SERVICE in declaredPermissions
