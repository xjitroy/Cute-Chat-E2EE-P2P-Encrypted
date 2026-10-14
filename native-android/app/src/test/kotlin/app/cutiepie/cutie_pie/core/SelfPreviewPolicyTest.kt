package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class SelfPreviewPolicyTest {
    @Test fun ownVideoReturnsToPartnerAfterTenSecondsWithoutExtendingOnUpdates() {
        val policy = SelfPreviewPolicy()
        assertTrue(policy.tap("call-A", true, 100))
        assertTrue(policy.expanded("call-A", true, 10_099))
        assertFalse(policy.expanded("call-A", true, 10_100))
        assertFalse(policy.expanded("call-A", true, 10_101))
    }
    @Test fun secondTapAndLeavingForegroundOrPipRestorePartner() {
        val policy = SelfPreviewPolicy()
        policy.tap("call-A", true, 0)
        assertFalse(policy.tap("call-A", true, 500))
        policy.tap("call-A", true, 600)
        assertFalse(policy.expanded("call-A", false, 700))
        assertFalse(policy.expanded("call-A", true, 800))
        assertFalse(policy.tap("", true, 900))
    }
    @Test fun delayedOldCallCallbackCannotShowSelfInAnotherCall() {
        val policy = SelfPreviewPolicy()
        policy.tap("call-A", true, 0)
        assertFalse(policy.expanded("call-B", true, 10))
        assertTrue(policy.tap("call-B", true, 20))
        policy.reset(); assertFalse(policy.expanded("call-B", true, 21))
    }
}
