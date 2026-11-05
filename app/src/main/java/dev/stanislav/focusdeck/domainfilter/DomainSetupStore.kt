package dev.stanislav.focusdeck.domainfilter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class DomainSetupStore(context:Context) {
    private val prefs=context.getSharedPreferences("domain-filter-setup",Context.MODE_PRIVATE)
    fun load():DomainSetup = runCatching {
        if(!prefs.contains("packages"))return DomainSetup()
        val value=DomainSetup(prefs.getBoolean("enabled",false),prefs.getStringSet("packages",emptySet()).orEmpty().toSet(),
            prefs.getStringSet("blocked",DomainSetup.PRESET).orEmpty().toSet(),prefs.getStringSet("protected",emptySet()).orEmpty().toSet())
        if(value.packages.isEmpty())value.copy(enabledForWindows=false) else value.validated()
    }.getOrElse { DomainSetup() } // Preserve damaged prefs until an explicit save, never auto-enable.
    fun save(value:DomainSetup):Boolean = prefs.edit().putBoolean("enabled",value.enabledForWindows)
        .putStringSet("packages",value.packages).putStringSet("blocked",value.blocked)
        .putStringSet("protected",value.additionalProtected).commit()
}

internal fun DomainPolicy.json():String = JSONObject().put("sessionId",sessionId)
    .put("blockedDomains",JSONArray(blockedDomains.sorted())).put("blockedSubdomainTrees",JSONArray(blockedSubdomainTrees.sorted()))
    .put("protectedDomains",JSONArray(protectedDomains.sorted())).put("allowedPackages",JSONArray(allowedPackages.sorted()))
    .put("durationSeconds",durationSeconds).put("consentVpnReplacementRisk",consentVpnReplacementRisk).toString()
