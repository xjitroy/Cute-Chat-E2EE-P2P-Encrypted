package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
class CallEntryAccessTest {
    @Test fun merelyOpeningNotificationNeverGrantsCapture() { val a=CallEntryAccess();a.visibility("call",true);assertFalse(a.permits("call",false,false)) }
    @Test fun visibleExplicitCurrentIncomingAnswerOnly() { val a=CallEntryAccess();assertFalse(a.accept("call","call",true));a.visibility("call",true);assertFalse(a.accept("call","other",true));assertFalse(a.accept("call","call",false));assertTrue(a.accept("call","call",true));assertTrue(a.permits("call",false,false));assertFalse(a.permits("other",false,false)) }
    @Test fun readyServiceUsesBoundConfirmedAnswerButBackgroundCannotStartPreparation() { val a=CallEntryAccess();a.visibility("call",true);a.accept("call","call",true);a.visibility("call",false);assertFalse(a.permits("call",false,false));assertTrue(a.confirmed("call"));assertFalse(a.confirmed("other")) }
    @Test fun hangupRevokesAndAppAuthRemainsSeparate() { val a=CallEntryAccess();a.visibility("call",true);a.accept("call","call",true);a.clear();assertFalse(a.permits("call",false,false));assertFalse(a.confirmed("call"));assertTrue(a.permits("new",true,true));assertFalse(a.permits("new",true,false)) }
}
