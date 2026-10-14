package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class ContactConnectionPolicyTest {
    @Test fun checksSavedContactsWithoutAnOpenConversationAndBoundsConcurrentRequests() {
        val policy = ContactConnectionPolicy({ 100L })
        val peers = (1..20).map { "peer$it" }
        val first = policy.due(peers, emptySet(), emptySet(), emptySet(), "peer18")
        assertEquals(4, first.size); assertEquals("peer18", first.first())
        val next = policy.due(peers, emptySet(), emptySet(), first.toSet(), null)
        assertEquals(4, next.size); assertTrue(next.none { it in first })
        val allocated = peers.take(16).toSet()
        assertTrue(policy.due(peers, allocated, allocated, emptySet(), "peer20").isEmpty())
        assertEquals(ContactConnectionState.WAITING, policy.snapshot(peers, allocated, allocated, emptySet(), true)["peer20"])
    }
    @Test fun failedServiceIsNotOfflineAndOnlyAuthenticatedConnectionIsConnected() {
        val policy = ContactConnectionPolicy({ 100L })
        fun state(connected: Set<String> = emptySet(), allocated: Set<String> = emptySet(), ready: Boolean = true) =
            policy.snapshot(listOf("friend"), connected, allocated, emptySet(), ready)["friend"]
        assertEquals(ContactConnectionState.CHECKING, state())
        policy.began("friend"); assertEquals(ContactConnectionState.CONNECTING, state())
        policy.failed("friend"); assertEquals(ContactConnectionState.RETRYING, state())
        policy.available("friend", false); assertEquals(ContactConnectionState.OFFLINE, state())
        assertEquals(ContactConnectionState.CONNECTING, state(allocated = setOf("friend")))
        assertEquals(ContactConnectionState.CONNECTED, state(connected = setOf("friend"), ready = false))
        assertEquals(ContactConnectionState.RETRYING, state(ready = false))
    }
    @Test fun reconnectsAfterBoundedBackoffAndRemovesDeletedContacts() {
        var time = 100L
        val policy = ContactConnectionPolicy({ time })
        policy.available("friend", false)
        assertTrue(policy.due(listOf("friend"), emptySet(), emptySet(), emptySet(), null).isEmpty())
        time += 15000
        assertEquals(listOf("friend"), policy.due(listOf("friend"), emptySet(), emptySet(), emptySet(), null))
        policy.closed("friend"); time += 4999
        assertTrue(policy.due(listOf("friend"), emptySet(), emptySet(), emptySet(), null).isEmpty())
        time++
        assertEquals(listOf("friend"), policy.due(listOf("friend"), emptySet(), emptySet(), emptySet(), null))
        policy.snapshot(emptyList(), emptySet(), emptySet(), emptySet(), true)
        assertEquals(ContactConnectionState.CHECKING, policy.snapshot(listOf("friend"), emptySet(), emptySet(), emptySet(), true)["friend"])
    }
}
