package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class LocalFileDiscardTest {
    @Test fun cancellationDrainCannotCrossPeerSessionDeadlineOrUnboundedHistory() {
        var time=0L;val policy=LocalFileDiscard { time }
        policy.add("alice","old-session","file")
        assertTrue(policy.allows("alice","old-session","file"))
        assertFalse(policy.allows("bob","old-session","file"));assertFalse(policy.allows("alice","new-session","file"));assertFalse(policy.allows("alice","old-session","other-file"))
        time=60_000_000_000;assertFalse(policy.allows("alice","old-session","file"))
        repeat(65) { policy.add("alice","session","file-$it") }
        assertFalse(policy.allows("alice","session","file-0"));assertTrue(policy.allows("alice","session","file-64"))
    }
}
