package app.cutiepie.cutie_pie.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyContactIdTest {
    private val code = "CP1.ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"

    @Test fun friendlyIdIsStableAndDoesNotExposeTheInvitation() {
        val first = FriendlyContactId.create("Maya", code)
        assertEquals(first, FriendlyContactId.create("Maya", code))
        assertTrue(first.matches(Regex("MAYA-[0-9]{10}")))
        assertTrue(!first.contains("CP1"))
    }

    @Test fun sharedTextKeepsTheSignedInvitationForAuthenticatedPairing() {
        val shared = FriendlyContactId.share("মায়া", code)
        assertEquals(code, FriendlyContactId.extract(shared))
        assertEquals(code, FriendlyContactId.extract(code))
    }
    @Test fun readableIdParsingDoesNotTreatTheNamePrefixAsIdentityAuthority() {
        assertEquals("0123456789",FriendlyContactId.number(" MAYA-0123456789 "))
        assertEquals("0123456789",FriendlyContactId.number("মমমম-0123456789"))
        assertEquals("0123456789",FriendlyContactId.number("0123456789"))
        for(invalid in listOf("+910123456789","MAYA-123","MAYA-0123456789\nforged","CP1.something")) assertEquals(null,FriendlyContactId.number(invalid))
    }
    @Test fun uppercaseExpansionStillProducesAnExportableFourCharacterPrefix() {
        val id=FriendlyContactId.create("ßeta",code)
        assertEquals("SSET",id.substringBefore('-'))
        assertTrue(FriendlyContactId.number(id) != null)
    }
    @org.junit.Test fun numberOnlyShareContainsNoNameOrSignedCode() {
        val shared=FriendlyContactId.shareNumber("signed-identity-public-uid")
        org.junit.Assert.assertTrue(shared.matches(Regex("[0-9]{10}")))
        org.junit.Assert.assertEquals(FriendlyContactId.number(FriendlyContactId.create("SECRETNAME","signed-identity-public-uid")),shared)
    }
}
