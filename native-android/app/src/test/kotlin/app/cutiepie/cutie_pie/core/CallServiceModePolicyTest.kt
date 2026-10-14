package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test

class CallServiceModePolicyTest {
    @Test fun delayedChatStartsCannotDowngradeAnActiveCaptureService() {
        for(mode in listOf(1,2,4)) assertEquals(mode,CallServiceModePolicy.mode(mode,3,true))
        assertEquals(1,CallServiceModePolicy.mode(3,1,true))
        assertEquals(4,CallServiceModePolicy.mode(1,4,true))
    }
    @Test fun idleChatNeverRetainsMicrophoneCameraOrProjectionTypes() {
        for(before in 1..4) for(request in 1..4) assertEquals(3,CallServiceModePolicy.mode(before,request,false))
    }
    @Test fun aStaleCallTicketCannotStartCaptureForANewCall() {
        assertFalse(CallServiceModePolicy.ticket("new-call","old-call",true))
        assertFalse(CallServiceModePolicy.ticket("","",true))
        assertFalse(CallServiceModePolicy.ticket("call","call",false))
        assertTrue(CallServiceModePolicy.ticket("call","call",true))
    }
}
