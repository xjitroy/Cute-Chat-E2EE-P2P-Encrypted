package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test

class PeerPresenceTest {
    @Test fun backgroundConnectionIsNotOnlineAndAuthenticatedForegroundIs() {
        var now=0L;val p=PeerPresence { now };val connected=setOf("a")
        assertTrue(p.online(connected).isEmpty());p.received("a",false);assertTrue(p.online(connected).isEmpty())
        p.received("a",true);assertEquals(connected,p.online(connected))
        p.received("a",false);assertTrue(p.online(connected).isEmpty())
    }
    @Test fun stalePresenceCannotKeepAnOfflineOrReconnectedPeerOnline() {
        var now=0L;val p=PeerPresence { now };p.received("a",true)
        assertTrue(p.online(emptySet()).isEmpty());now=90001;assertTrue(p.online(setOf("a")).isEmpty())
        p.received("a",true);p.remove("a");assertTrue(p.online(setOf("a")).isEmpty())
        p.received("a",true);p.clear();assertTrue(p.online(setOf("a")).isEmpty())
    }
}
