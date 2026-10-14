package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class BackgroundLeaseTest {
    @Test fun backgroundPingIsOneMinuteEvenDuringFiveMinuteGrace() {
        var clock = 1_000L
        val lease = BackgroundLease { clock }
        lease.enterForeground(); lease.leaveForeground(5)
        assertTrue(lease.ping("0123456789abcdef"))
        clock += 59_999
        lease.actualContentActivity(30)
        assertEquals(BackgroundLease.Reason.PING, lease.decision().reason)
        assertFalse(lease.ping("0123456789abcdef"))
        clock += 1
        assertFalse(lease.decision().active)
    }
    @Test fun realCallAndApprovedScheduleSurvivePingExpiry() {
        var clock = 0L
        val lease = BackgroundLease { clock }
        lease.ping("0123456789abcdef")
        lease.schedule(600_000)
        clock = 61_000
        assertEquals(BackgroundLease.Reason.SCHEDULE, lease.decision().reason)
        lease.acceptedCall(true)
        assertEquals(BackgroundLease.Reason.CALL, lease.decision().reason)
        lease.cancelSchedule(); lease.acceptedCall(false)
        assertFalse(lease.decision().active)
    }
    @Test fun genuineActivityRefreshesGraceButUnrelatedPingEndDoesNotCancel() {
        var clock = 0L
        val lease = BackgroundLease { clock }
        lease.enterForeground(); lease.leaveForeground(5)
        clock = 299_000; lease.actualContentActivity(5)
        clock = 301_000
        assertTrue(lease.decision().active)
        lease.ping("0123456789abcdef")
        lease.endPing("unrelated-stale-id")
        assertTrue(lease.decision().active)
        lease.stop()
        assertFalse(lease.decision().active)
        assertFalse(lease.ping("0123456789abcdef"))
    }
}
