package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
class AppViewSessionTest {
    @Test fun coldProcessNeverRestoresAnUnlockFromElapsedTime() { assertFalse(AppViewSession().usable(999999,60));val first=AppViewSession();first.authenticated();assertTrue(first.usable(999999,5));assertFalse(AppViewSession().usable(999999,5)) }
    @Test fun defaultFiveMinuteBoundaryLocksAndCannotBeRevivedByReturn() { val s=AppViewSession();s.authenticated();s.leave(1000);assertTrue(s.usable(300999,5));assertFalse(s.resume(301000,5));assertFalse(s.resume(301001,60)) }
    @Test fun shortPickerReturnResumesWithoutAnotherCredentialAndRestartsNextAbsence() { val s=AppViewSession();s.authenticated();s.leave(1000);s.leave(9000);assertTrue(s.resume(10000,5));assertTrue(s.usable(999999,5));s.leave(1000000);assertTrue(s.resume(1000200,5)) }
    @Test fun zeroDelayLocksOnLeavingButNeverDuringActiveForegroundUse() { val s=AppViewSession();s.authenticated();assertTrue(s.usable(100000,0));s.leave(100000);assertFalse(s.resume(100000,0)) }
    @Test fun phoneLockAndClockRollbackInvalidateTheMemoryGrant() { val s=AppViewSession();s.authenticated();assertFalse(s.resume(1,5,true));assertFalse(s.resume(2,5,false));s.authenticated();s.leave(10);assertFalse(s.resume(9,5)) }
    @Test fun shorterDelayDoesNotExtendAnExistingAbsence() { val s=AppViewSession();s.authenticated();s.leave(1000);assertTrue(s.usable(121000,5));assertFalse(s.resume(121000,1));s.authenticated();assertTrue(s.usable(121001,1)) }
}
