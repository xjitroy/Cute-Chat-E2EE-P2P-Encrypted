package app.cutiepie.cutie_pie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*

@RunWith(AndroidJUnit4::class)
class LiveFileStreamAndroidTest {
    @Test fun liveNativeCryptoDeliversBeforeWholeSourceAndRejectsTrailingRecords() {
        val crypto=LiveSecretFileStream(LazySodiumAndroid(SodiumAndroid()));val id=WireCodec.id()
        val bytes=ByteArray(1024*1024+123) { (it%251).toByte() };val input=ByteArrayInputStream(bytes);val output=ByteArrayOutputStream()
        crypto.Writer(id,bytes.size.toLong()).use { writer ->
            crypto.Reader(id,bytes.size.toLong(),writer.key,writer.header).use { reader ->
                var first=true
                writer.stream(input,{ cipher,_,final ->
                    if(first) { first=false;assertTrue(input.available()>0) }
                    reader.consume(cipher,output);assertEquals(final,reader.complete)
                },{ true })
                assertEquals(bytes.size.toLong(),reader.bytes);assertArrayEquals(bytes,output.toByteArray())
                var rejected=false;try { reader.consume(ByteArray(49),output) } catch(_:Exception) { rejected=true };assertTrue(rejected)
            }
        }
    }
}
