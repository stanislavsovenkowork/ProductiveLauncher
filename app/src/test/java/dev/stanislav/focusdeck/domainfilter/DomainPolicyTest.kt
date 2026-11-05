package dev.stanislav.focusdeck.domainfilter

import org.junit.Assert.*
import org.junit.Test

class DomainPolicyTest {
    private fun policy(exact:Set<String> = setOf("blocked.example"),trees:Set<String> = emptySet()) =
        DomainPolicy("3de91f5b-6690-46f4-96c7-19a8849fc11e",exact,trees,allowedPackages=setOf("dev.stanislav.focusdeck.qa"),consentVpnReplacementRisk=true)
    @Test fun exactIsExactAndTreesAreExplicit() {
        assertTrue(policy().blocks("BLOCKED.EXAMPLE."))
        assertFalse(policy().blocks("sub.blocked.example"))
        assertFalse(policy().blocks("notblocked.example"))
        assertFalse(policy().blocks("blocked.example.evil.test"))
        assertTrue(policy(emptySet(),setOf("blocked.example")).blocks("sub.blocked.example"))
    }
    @Test fun infrastructureAndInvalidPoliciesRefused() {
        for(name in listOf("openai.com","sub.openai.com","com","gitlab.eskhata.com")) {
            assertThrows(IllegalArgumentException::class.java){policy(setOf(name))}
        }
        assertThrows(IllegalArgumentException::class.java){policy(setOf("https://blocked.example/path"))}
        assertThrows(IllegalArgumentException::class.java){policy().copy(consentVpnReplacementRisk=false)}
        assertThrows(IllegalArgumentException::class.java){policy().copy(allowedPackages=setOf("net.openvpn.connect"))}
    }
    @Test fun vpnProvidersAndUnreadablePackageMetadataAreExempt() {
        assertTrue(vpnServiceExempt(listOf("android.permission.BIND_VPN_SERVICE")))
        assertTrue(vpnServiceExempt(null))
        assertFalse(vpnServiceExempt(emptyList()))
        assertFalse(vpnServiceExempt(listOf("android.permission.BIND_JOB_SERVICE")))
    }
    @Test fun debuggableBuildAloneNeverGrantsInstantStopScope() {
        val probe=policy(setOf("blocked.test")).copy(durationSeconds=120)
        assertFalse(syntheticQaPolicy(probe,"dev.stanislav.focusdeck.qa",true,false))
        assertTrue(syntheticQaPolicy(probe,"dev.stanislav.focusdeck.qa",true,true))
        assertFalse(syntheticQaPolicy(probe.copy(blockedDomains=setOf("youtube.com")),"dev.stanislav.focusdeck.qa",true,true))
        assertFalse(syntheticQaPolicy(probe,"dev.stanislav.focusdeck",true,true))
        assertFalse(syntheticQaPolicy(probe,"dev.stanislav.focusdeck.qa",false,true))
        assertFalse(syntheticQaPolicy(probe.copy(durationSeconds=121),"dev.stanislav.focusdeck.qa",true,true))
        val bounded=DomainSession(probe,1000,maxTotalMillis=120_000)
        assertTrue(bounded.pause(2000,120))
        assertFalse(bounded.blocks("blocked.test",121000))
    }
    @Test fun sessionCopiesMutablePolicyInputs() {
        val domains=mutableSetOf("blocked.example")
        val session=DomainSession(policy(domains),1000)
        domains.clear()
        assertTrue(session.blocks("blocked.example",2000))
    }
    @Test fun monotonicBoundAndOnePauseNoAutomaticWorkClaim() {
        val session=DomainSession(policy(),1000)
        assertTrue(session.blocks("blocked.example",2000))
        assertTrue(session.pause(3000,120))
        assertFalse(session.blocks("blocked.example",4000))
        assertFalse(session.pause(5000,120))
        assertTrue(session.blocks("blocked.example",123000))
        assertTrue(session.blocks("blocked.example",1501000))
        assertFalse(session.blocks("blocked.example",1621000))
        assertFalse(session.pause(1621000,120))
    }
}
