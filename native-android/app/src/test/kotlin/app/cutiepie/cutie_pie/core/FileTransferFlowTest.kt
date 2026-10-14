package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class FileTransferFlowTest {
    @Test fun oneSideCannotResumeTheOtherSidesPauseAndOldControlsDoNotReplay() {
        val flow=FileTransferFlow();assertEquals(1L,flow.local(true,100))
        assertTrue(flow.received(true,1,200));assertEquals(2L,flow.local(false,300));assertTrue(flow.paused());assertFalse(flow.locallyPaused())
        assertFalse(flow.received(false,1,400));assertTrue(flow.paused());assertTrue(flow.received(false,2,500));assertFalse(flow.paused())
        assertFalse(flow.received(true,1,600));assertFalse(flow.paused())
    }
    @Test fun remotePauseRetriesDoNotRenewTheTenMinuteLease() {
        val flow=FileTransferFlow();flow.received(true,1,100)
        assertFalse(flow.expired(600_099));assertTrue(flow.received(true,2,500_000));assertTrue(flow.expired(600_100))
        flow.received(false,3,600_101);assertFalse(flow.expired(600_101));flow.local(true,700_000);assertTrue(flow.expired(699_999))
    }
    @Test fun verifiedLongByteRatesAndEtaDoNotOverflowAndStaleRatesAreHidden() {
        val rate=FileTransferRate();val size=5L*1024*1024*1024
        assertNull(rate.remaining(size,1));rate.note(0,1);rate.note(size,1_000_000_001)
        assertEquals(size,rate.speed(1_000_000_001));assertEquals(0L,rate.remaining(size,1_000_000_001))
        assertEquals(0L,rate.speed(6_000_000_002));assertNull(rate.remaining(size,6_000_000_002))
    }
    @Test fun decreasingProgressCannotCreateFalseSpeedOrInflateRemainingBytes() {
        val rate=FileTransferRate();rate.note(0,1);rate.note(1024,1_000_000_001);rate.note(0,2_000_000_001)
        assertEquals(1L,rate.remaining(2048,2_000_000_001))
    }
    @Test fun delayedQuietWakeCannotRestartAFullMinuteOrExtendByReplay() { var now=100L;val l=BackgroundLease {now};l.ping(WireCodec.id(),1000);val id=WireCodec.id();l.ping(id,1000);now=900;assertFalse(l.ping(id,60000));now=1100;assertFalse(l.decision().active) }
    @Test fun approvedFileLeaseIsIndependentOfPingAndPrivateViewForeground() {
        var now=0L;val lease=BackgroundLease { now };lease.enterForeground();lease.leaveForeground(5);lease.ping(WireCodec.id());lease.fileTransfer(true)
        now=1_000_000;assertEquals(BackgroundLease.Reason.FILE,lease.decision().reason)
        lease.fileTransfer(false);assertFalse(lease.decision().active)
        lease.fileTransfer(true);lease.stop();assertFalse(lease.decision().active)
    }
}
