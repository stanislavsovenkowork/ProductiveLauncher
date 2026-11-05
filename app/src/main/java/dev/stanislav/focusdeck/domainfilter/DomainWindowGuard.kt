package dev.stanislav.focusdeck.domainfilter

import dev.stanislav.focusdeck.ui.restrictions.ProtectionClock
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow
import dev.stanislav.focusdeck.ui.restrictions.reliefRemaining
import dev.stanislav.focusdeck.ui.restrictions.windowActive

/** Pure binding checked for every packet; no VPN is established by this policy. */
internal fun domainWindowActive(window: ProtectionWindow?, boundWindowId: String?, policy: DomainPolicy, clock: ProtectionClock): Boolean =
    window != null && window.id == boundWindowId && windowActive(window, clock) &&
        (if (window.policyVersion == 2) window.id else window.sessionId) == policy.sessionId

internal fun domainWindowBlocks(window: ProtectionWindow?, boundWindowId: String?, policy: DomainPolicy,
                                clock: ProtectionClock, host: String): Boolean =
    domainWindowActive(window, boundWindowId, policy, clock) && reliefRemaining(requireNotNull(window), clock) == 0L && policy.blocks(host)

/** The VPN builder must receive a nonempty explicit allowlist; no default all-app routing. */
internal fun installDomainAppAllowlist(packages: Set<String>, add: (String) -> Unit) {
    require(packages.isNotEmpty())
    packages.sorted().forEach(add)
}

/** Close the TUN on explicit stop, expiry, revocation, or service destruction. */
internal fun closeDomainTunnel(tunnel: java.io.Closeable?) {
    if (tunnel != null) runCatching { tunnel.close() }
}
