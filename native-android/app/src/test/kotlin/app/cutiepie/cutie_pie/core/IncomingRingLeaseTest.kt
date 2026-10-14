package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test

class IncomingRingLeaseTest {
    @Test fun incomingRingKeepsControlConnectedAfterPingExpiryWithoutAcceptingACall() {
        var now=0L;val lease=BackgroundLease {now}
        lease.ping("abcdefgh12345678");now=50000;lease.incomingRing(now+60000)
        now=61000;assertEquals(BackgroundLease.Reason.RING,lease.decision().reason)
        now=110001;assertFalse(lease.decision().active)
    }
    @Test fun rejectStopAndGenuineAcceptanceReplaceTheBoundedRingLease() {
        var now=0L;val lease=BackgroundLease {now};lease.incomingRing(60000)
        lease.incomingRing(0);assertFalse(lease.decision().active)
        lease.incomingRing(60000);lease.acceptedCall(true);assertEquals(BackgroundLease.Reason.CALL,lease.decision().reason)
        lease.stop();assertFalse(lease.decision().active)
        assertThrows(IllegalArgumentException::class.java) { lease.incomingRing(60001) }
    }
}
