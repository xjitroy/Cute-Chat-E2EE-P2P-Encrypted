package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class CallUiPolicyTest {
    @Test fun consentIsBoundToOneConnectedVideoCall() {
        assertTrue(CallUiPolicy.mayProject("call-A", "call-A", true, "connected", true))
        assertFalse(CallUiPolicy.mayProject("call-A", "call-B", true, "connected", true))
        assertFalse(CallUiPolicy.mayProject("", "", true, "connected", true))
    }
    @Test fun noCaptureForBackgroundAudioOrUnansweredCall() {
        assertFalse(CallUiPolicy.mayProject("call-A", "call-A", true, "connected", false))
        assertFalse(CallUiPolicy.mayProject("call-A", "call-A", false, "connected", true))
        assertFalse(CallUiPolicy.mayProject("call-A", "call-A", true, "incoming", true))
        assertFalse(CallUiPolicy.mayProject("call-A", "call-A", true, "idle", true))
    }
    @Test fun pipRequiresVisibleUnlockedActiveCall() {
        assertTrue(CallUiPolicy.mayEnterPip("call-A", true, true))
        assertFalse(CallUiPolicy.mayEnterPip("call-A", true, false))
        assertFalse(CallUiPolicy.mayEnterPip("call-A", false, true))
        assertFalse(CallUiPolicy.mayEnterPip("", true, true))
    }
}
