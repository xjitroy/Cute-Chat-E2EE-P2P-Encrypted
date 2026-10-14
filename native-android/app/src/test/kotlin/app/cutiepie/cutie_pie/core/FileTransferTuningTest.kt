package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class FileTransferTuningTest {
    @Test fun wifiPipelineAllowsMegabytesWithoutAllocatingFromFileSize() {
        assertTrue(FileTransferTuning.canAdvance(4*1024*1024L,0,"wifi",false))
        assertFalse(FileTransferTuning.canAdvance(4*1024*1024L+1,0,"wifi",false))
        assertTrue(FileTransferTuning.window("wifi",false)>32L*LargeFileRules.CHUNK)
    }
    @Test fun memoryIsBoundedAndControlRetainsAnIndependentReserve() {
        assertTrue(FileTransferTuning.WIFI_QUEUE<=2*1024*1024)
        assertTrue(FileTransferTuning.CONTROL_RESERVE>=LocalSharingRules.MAX_PACKET)
        assertTrue(FileTransferTuning.STREAM_BUFFER<=FileTransferTuning.WIFI_QUEUE)
    }
    @Test fun acknowledgedLongCountersCoverFiveGiBAndZeroLengthFinal() {
        val base=5*1024*1024*1024L
        assertTrue(FileTransferTuning.canAdvance(base,base-4*1024*1024,"wifi",false))
        assertFalse(FileTransferTuning.canAdvance(base,base-4*1024*1024-1,"wifi",false))
        assertTrue(FileTransferTuning.canAdvance(0,0,"wifi",false))
    }
    @Test fun staleImpossibleAndNegativeCountersCannotOpenAWindow() {
        assertFalse(FileTransferTuning.canAdvance(-1,0,"wifi",false))
        assertFalse(FileTransferTuning.canAdvance(1,-1,"wifi",false))
        assertFalse(FileTransferTuning.canAdvance(1,2,"wifi",false))
    }
    @Test fun concurrentCallAndInternetUseSmallerBoundedWindows() {
        assertTrue(FileTransferTuning.window("internet",false)<FileTransferTuning.window("wifi",false))
        for(medium in listOf("wifi","internet")) {
            assertTrue(FileTransferTuning.canAdvance(512*1024L,0,medium,true))
            assertFalse(FileTransferTuning.canAdvance(512*1024L+1,0,medium,true))
        }
    }
    @Test fun wireChunksStayCompatibleAndBufferedFramesPreserveEveryByte() {
        assertEquals(32768,LargeFileRules.CHUNK)
        val destination=ByteArrayOutputStream()
        val output=DataOutputStream(BufferedOutputStream(destination,FileTransferTuning.STREAM_BUFFER))
        val payload=ByteArray(32793) { (it%251).toByte() }
        repeat(32) { LocalSharingRules.write(output,payload,false) };output.flush()
        val input=DataInputStream(ByteArrayInputStream(destination.toByteArray()))
        repeat(32) { assertArrayEquals(payload,LocalSharingRules.read(input)) }
        assertEquals(-1,input.read())
    }
}
