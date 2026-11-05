package dev.stanislav.focusdeck.domainfilter

import java.util.Locale
import java.util.UUID

/** Frozen per-session policy. Bare rules match exactly; subdomain rules are an explicit separate choice. */
data class DomainPolicy(
    val sessionId: String,
    val blockedDomains: Set<String>,
    val blockedSubdomainTrees: Set<String> = emptySet(),
    val protectedDomains: Set<String> = INFRASTRUCTURE,
    val allowedPackages: Set<String>,
    val durationSeconds: Long = 1500,
    val consentVpnReplacementRisk: Boolean = false,
) {
    init {
        require(UUID.fromString(sessionId).toString().equals(sessionId, ignoreCase = true))
        require(durationSeconds in 1..1500 && consentVpnReplacementRisk)
        require(allowedPackages.isNotEmpty() && allowedPackages.size <= 8)
        require(allowedPackages.all { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) && !infrastructurePackage(it) })
        require(blockedDomains.isNotEmpty() || blockedSubdomainTrees.isNotEmpty())
        require(blockedDomains.size + blockedSubdomainTrees.size <= 256)
        require((blockedDomains + blockedSubdomainTrees + protectedDomains).all { canonical(it) == it })
        require((blockedDomains + blockedSubdomainTrees).none { blocked ->
            (protectedDomains + INFRASTRUCTURE).any { safe -> inTree(blocked, safe) || inTree(safe, blocked) }
        })
    }
    fun blocks(host: String): Boolean {
        val name = runCatching { canonical(host) }.getOrNull() ?: return false
        if ((protectedDomains + INFRASTRUCTURE).any { inTree(name, it) }) return false
        return name in blockedDomains || blockedSubdomainTrees.any { inTree(name, it) }
    }
    companion object {
        val INFRASTRUCTURE = setOf("gitlab.eskhata.com", "openai.com", "chatgpt.com")
        fun canonical(value: String): String {
            val result = value.trimEnd('.').lowercase(Locale.ROOT)
            require(result.length in 1..253 && result.contains('.'))
            require(result.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) })
            return result
        }
        fun inTree(host: String, root: String) = host == root || host.endsWith(".$root")
        fun infrastructurePackage(name: String) = listOf("com.openai.", "net.openvpn.", "org.openvpn.",
            "com.tunnelblick.", "com.v2box.", "com.hossinasaadi.", "hossin.asaadi.").any(name::startsWith) ||
            name in setOf("android", "com.android.systemui", "com.android.settings")
    }
}

class DomainSession(policy: DomainPolicy, private val startedElapsedMillis: Long, private val maxTotalMillis:Long=1800_000) {
    val policy = policy.copy(blockedDomains=policy.blockedDomains.toSet(),blockedSubdomainTrees=policy.blockedSubdomainTrees.toSet(),
        protectedDomains=policy.protectedDomains.toSet(),allowedPackages=policy.allowedPackages.toSet())
    @Volatile var deadline = startedElapsedMillis + policy.durationSeconds * 1000
        private set
    private var pauseUsed = false
    private var pauseUntil = 0L
    @Synchronized fun pause(now: Long, seconds: Long): Boolean {
        if (pauseUsed || seconds !in 1..300 || now >= deadline) return false
        pauseUsed = true; deadline = minOf(startedElapsedMillis + maxTotalMillis, deadline + seconds * 1000)
        pauseUntil = minOf(deadline, now + seconds * 1000); return true
    }
    @Synchronized fun blocks(host: String, now: Long) = now < deadline && now >= pauseUntil && policy.blocks(host)
    @Synchronized fun paused(now: Long) = now < pauseUntil
}

/** Debug APK delivery is production-like unless this exact, explicit synthetic scope is requested. */
internal fun syntheticQaPolicy(policy:DomainPolicy,ownPackage:String,debuggable:Boolean,requested:Boolean):Boolean =
    requested && debuggable && ownPackage.endsWith(".qa") && policy.allowedPackages==setOf(ownPackage) &&
        policy.blockedDomains==setOf("blocked.test") && policy.blockedSubdomainTrees.isEmpty() &&
        policy.durationSeconds in 1..120 && policy.protectedDomains==DomainPolicy.INFRASTRUCTURE
