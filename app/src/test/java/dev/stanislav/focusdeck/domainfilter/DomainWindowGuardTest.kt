package dev.stanislav.focusdeck.domainfilter

import dev.stanislav.focusdeck.ui.restrictions.ProtectionClock
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow
import dev.stanislav.focusdeck.ui.restrictions.grantRelief
import org.junit.Assert.*
import org.junit.Test

class DomainWindowGuardTest {
    @Test fun onlyExplicitPackagesGoIntoFakeVpnBuilderAndStopClosesTunnel() {
        val configured = mutableListOf<String>()
        installDomainAppAllowlist(setOf("qa.browser", "qa.second")) { configured.add(it) }
        assertEquals(listOf("qa.browser", "qa.second"), configured)
        assertThrows(IllegalArgumentException::class.java) { installDomainAppAllowlist(emptySet()) { configured.add(it) } }
        var closed = false
        closeDomainTunnel(java.io.Closeable { closed = true })
        assertTrue(closed)
        closeDomainTunnel(java.io.Closeable { throw java.io.IOException("synthetic") })
    }
    @Test fun dnsBindingFollowsFixedWindowReliefAndExit() {
        val id = "d6457bfa-352c-4da6-a271-f1f9c862c055"
        val policy = DomainPolicy(id, setOf("blocked.test"), allowedPackages = setOf("qa.browser"), consentVpnReplacementRisk = true)
        val clock = ProtectionClock(1_000_000, 10_000, 1)
        val window = ProtectionWindow(id, "study", emptySet(), startWall = clock.wall, endWall = clock.wall + 600_000,
            startElapsed = clock.elapsed, endElapsed = clock.elapsed + 600_000, boot = 1, policyVersion = 2)
        assertTrue(domainWindowBlocks(window, id, policy, clock, "blocked.test"))
        assertFalse(domainWindowBlocks(window, id, policy, clock, "sub.blocked.test"))
        assertFalse(domainWindowBlocks(window, "wrong", policy, clock, "blocked.test"))
        val relief = requireNotNull(grantRelief(window, clock))
        assertFalse(domainWindowBlocks(relief, id, policy, clock, "blocked.test"))
        val resumed = clock.copy(elapsed = clock.elapsed + 300_000, wall = clock.wall + 300_000)
        assertTrue(domainWindowBlocks(relief, id, policy, resumed, "blocked.test"))
        assertFalse(domainWindowActive(null, id, policy, resumed))
        val end = clock.copy(elapsed = clock.elapsed + 600_000, wall = clock.wall + 600_000)
        assertFalse(domainWindowActive(window, id, policy, end))
    }
}
