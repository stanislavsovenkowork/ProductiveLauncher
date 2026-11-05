package dev.stanislav.focusdeck.domainfilter

import java.util.UUID

/** Preferences are an opt-in for later explicit protection windows, never a live-connection receipt. */
data class DomainSetup(
    val enabledForWindows:Boolean=false,
    val packages:Set<String> = emptySet(),
    val blocked:Set<String> = PRESET,
    val additionalProtected:Set<String> = emptySet(),
) {
    fun validated():DomainSetup {
        DomainPolicy(UUID.randomUUID().toString(),blocked,protectedDomains=additionalProtected+DomainPolicy.INFRASTRUCTURE,
            allowedPackages=packages,consentVpnReplacementRisk=true)
        return this
    }
    fun snapshot(sessionId:String):DomainPolicy? = if(!enabledForWindows)null else
        DomainPolicy(sessionId,blocked.toSet(),protectedDomains=additionalProtected.toSet()+DomainPolicy.INFRASTRUCTURE,
            allowedPackages=packages.toSet(),consentVpnReplacementRisk=true)
    fun afterConsent(granted:Boolean)=copy(enabledForWindows=granted)
    companion object {
        val PRESET=setOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be","instagram.com","www.instagram.com",
            "tiktok.com","www.tiktok.com","reddit.com","www.reddit.com","x.com","twitter.com")
    }
}

data class DomainAppChoice(val packageName:String,val label:String)

/** Load is deliberately disconnected. A persisted preference never means a VPN is active. */
data class DomainPanelState(
    val setup:DomainSetup=DomainSetup(),val runtime:DomainFilterStatus=DomainFilterStatus(),
    val message:String="Выключено по умолчанию · сначала выберите браузеры и прочитайте границы",
    val busy:Boolean=false,
)
