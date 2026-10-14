package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
class QuickReceiveConsentTest {
    @Test fun receiveModeDoesNotAuthorizeAnySenderOrAnotherChannel() { val c=QuickReceiveConsent();assertFalse(c.allow("a","x",1,0));c.approve("a","x",1,0);assertTrue(c.allow("a","x",1,1));assertFalse(c.allow("b","x",1,1));assertFalse(c.allow("a","y",1,1)) }
    @Test fun stopRestartAndDisconnectRevokeOldApproval() { val c=QuickReceiveConsent();c.approve("a","x",1,0);assertFalse(c.allow("a","x",-1,1));assertFalse(c.allow("a","x",2,1));c.approve("a","x",2,10);c.disconnected("a");assertFalse(c.allow("a","x",2,11)) }
    @Test fun onlyApprovedSessionProgressRefreshesIdleConsent() { val c=QuickReceiveConsent();c.approve("a","x",1,0);c.progress("b","x",1,100000);assertFalse(c.allow("a","x",1,120000));c.approve("a","x",1,200000);c.progress("a","x",1,300000);assertTrue(c.allow("a","x",1,419999));assertFalse(c.allow("a","x",1,420000)) }
    @Test fun boundedApprovalsDoNotCarryIntoAnotherReceiverEpoch() { val c=QuickReceiveConsent();for(i in 0..16) c.approve("p$i","s",3,0);assertFalse(c.allow("p0","s",3,1));assertTrue(c.allow("p16","s",3,1));assertFalse(c.allow("p16","s",4,1)) }
}
