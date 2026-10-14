package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class EphemeralMediaBytesTest {
    @Test fun closeZeroesTheOwnedBufferAndPreventsFurtherReads() {
        val owned = byteArrayOf(3,8,5,4); val stream = EphemeralMediaBytes(owned)
        val output = ByteArray(2); assertEquals(2,stream.read(1,output,0,2)); assertArrayEquals(byteArrayOf(8,5),output)
        stream.close(); stream.close(); assertArrayEquals(ByteArray(4),owned)
        assertEquals(0L,stream.size()); assertEquals(-1,stream.read(0,output,0,2))
    }
    @Test fun decoderSeeksCannotOverflowTheOwnedArray() {
        val stream = EphemeralMediaBytes(byteArrayOf(4,7)); val output = byteArrayOf(9,9,9)
        assertEquals(-1,stream.read(Long.MAX_VALUE,output,0,3)); assertEquals(-1,stream.read(-1,output,0,3))
        assertEquals(1,stream.read(1,output,1,2)); assertArrayEquals(byteArrayOf(9,7,9),output)
        assertThrows(IllegalArgumentException::class.java) { stream.read(0,output,2,2) }
        stream.close()
    }
}
