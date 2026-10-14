package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class EntryAuthSessionTest {
    @Test fun staleOrRepeatedSuccessCannotUnlockAnotherEntry() {
        val session=EntryAuthSession();val first=session.begin();session.invalidate();val second=session.begin()
        assertFalse(session.finish(first));assertTrue(session.isCurrent(second))
        assertTrue(session.finish(second));assertFalse(session.finish(second))
    }
    @Test fun DeviceCredentialFallbackSupersedesTheBiometricCallback() {
        val session=EntryAuthSession();val biometric=session.begin();val credential=session.begin()
        assertFalse(session.isCurrent(biometric));assertFalse(session.finish(biometric))
        assertTrue(session.finish(credential))
    }
}
