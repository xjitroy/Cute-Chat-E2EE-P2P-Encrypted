package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class NotificationContentPolicyTest {
    @Test fun explicitPreviewWorksWithoutEnteringTheLockedApp() {
        assertTrue(NotificationContentPolicy.preview(true,false))
        assertFalse(NotificationContentPolicy.hideIdentity(false,false,true))
    }
    @Test fun defaultOffRemainsPrivateEvenWithThePhoneUnlocked() {
        assertFalse(NotificationContentPolicy.preview(false,false))
        assertTrue(NotificationContentPolicy.hideIdentity(false,false,false))
    }
    @Test fun phoneLockAlwaysHidesContentAndIdentity() {
        for(enabled in listOf(true,false)) {
            assertFalse(NotificationContentPolicy.preview(enabled,true))
            assertTrue(NotificationContentPolicy.hideIdentity(true,true,enabled))
        }
    }
}
