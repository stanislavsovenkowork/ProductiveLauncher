package dev.stanislav.focusdeck.domainfilter

import org.junit.Assert.*
import org.junit.Test

class DomainSetupTest {
    private val session="3de91f5b-6690-46f4-96c7-19a8849fc11e"
    @Test fun offDefaultAndDeclinedConsentCannotProduceStartSnapshot() {
        val loaded=DomainSetup(packages=setOf("com.android.chrome"))
        assertFalse(loaded.enabledForWindows)
        assertNull(loaded.snapshot(session))
        assertNull(loaded.afterConsent(false).snapshot(session))
    }
    @Test fun savedOptInIsNotRuntimeActivity() {
        val setup=DomainSetup(true,setOf("com.android.chrome"))
        val state=DomainPanelState(setup=setup)
        assertTrue(state.setup.enabledForWindows)
        assertFalse(state.runtime.active)
    }
    @Test fun changingFutureSelectionCannotMutateFrozenWindowPolicy() {
        val selected=mutableSetOf("com.android.chrome")
        val old=DomainSetup(true,selected,blocked=setOf("blocked.example"))
        val frozen=requireNotNull(old.snapshot(session))
        selected.clear()
        val changed=old.copy(blocked=setOf("different.example"),enabledForWindows=false)
        assertTrue(frozen.blocks("blocked.example"))
        assertFalse(frozen.blocks("different.example"))
        assertEquals(setOf("com.android.chrome"),frozen.allowedPackages)
        assertNull(changed.snapshot(session))
    }
    @Test fun invalidOrEmptyScopeRefusedBeforeConsent() {
        assertThrows(IllegalArgumentException::class.java){DomainSetup(packages=emptySet()).validated()}
        assertThrows(IllegalArgumentException::class.java){DomainSetup(packages=setOf("net.openvpn.connect")).validated()}
        assertThrows(IllegalArgumentException::class.java){DomainSetup(packages=setOf("com.android.chrome"),blocked=emptySet()).validated()}
    }
}
