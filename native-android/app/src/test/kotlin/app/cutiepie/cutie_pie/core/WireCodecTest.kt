package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WireCodecTest {
    @Test fun canonicalSigningMatchesLegacyUnicodeAndKeyOrdering() {
        val json = JSONObject("""{"z":null,"name":"বাংলা/Русский","a":{"b":2,"a":1},"line":"a\nb"}""")
        assertEquals("""{"a":{"a":1,"b":2},"line":"a\nb","name":"বাংলা/Русский","z":null}""", WireCodec.canonical(json))
    }
    @Test fun base64RejectsAlternateEncodingsAndSizeAbuse() {
        assertArrayEquals(byteArrayOf(0, -1), WireCodec.decode("AP8", 2))
        for (invalid in listOf("AP8=", "AP9", "AP8\n", "", "***")) {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.decode(invalid, 2) }
        }
        assertThrows(IllegalArgumentException::class.java) { WireCodec.decode("AP8", 1) }
    }
    @Test fun digestUsesFullUnsignedBytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", WireCodec.digest(byteArrayOf()))
    }
    @Test fun depthLimitIgnoresBracketsInsideEscapedStringsAndRejectsNestedAbuse() {
        assertEquals("[{}]", WireCodec.parseObject("""{"text":"[{}]","escaped":"\"["}""", 100).getString("text"))
        assertThrows(IllegalArgumentException::class.java) { WireCodec.parseObject("""{"a":${"[".repeat(100)}0${"]".repeat(100)}}""", 1000) }
        assertThrows(IllegalArgumentException::class.java) { WireCodec.parseObject("""{"a":"unterminated}""", 100) }
    }
}
