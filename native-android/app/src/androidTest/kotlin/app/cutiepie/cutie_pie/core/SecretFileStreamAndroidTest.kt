package app.cutiepie.cutie_pie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*

@RunWith(AndroidJUnit4::class)
class SecretFileStreamAndroidTest {
    @Test fun androidNativeSecretstreamRoundTripPreservesOriginalBytes() {
        val crypto=SecretFileStream(LazySodiumAndroid(SodiumAndroid()));val id=WireCodec.id()
        val source=ByteArray(1024*1024+123) { (it%251).toByte() };val spool=ByteArrayOutputStream()
        val prepared=crypto.prepare(ByteArrayInputStream(source),spool,id,source.size.toLong())
        val restored=ByteArrayOutputStream()
        try { crypto.export(ByteArrayInputStream(spool.toByteArray()),restored,id,source.size.toLong(),prepared.key,prepared.header,prepared.hash);assertArrayEquals(source,restored.toByteArray()) }
        finally { prepared.key.fill(0);source.fill(0) }
    }
    @Test fun androidNativeSecretstreamRejectsTamperingAndMissingFinal() {
        val crypto=SecretFileStream(LazySodiumAndroid(SodiumAndroid()));val id=WireCodec.id();val spool=ByteArrayOutputStream()
        val prepared=crypto.prepare(ByteArrayInputStream(ByteArray(70001)),spool,id,70001)
        try {
            val tampered=spool.toByteArray().also { it[10]=(it[10].toInt() xor 1).toByte() }
            var failed=false;try { crypto.export(ByteArrayInputStream(tampered),ByteArrayOutputStream(),id,70001,prepared.key,prepared.header,prepared.hash) } catch(_:Exception) { failed=true };assertTrue(failed)
            failed=false;try { crypto.export(ByteArrayInputStream(spool.toByteArray().dropLast(21).toByteArray()),ByteArrayOutputStream(),id,70001,prepared.key,prepared.header,prepared.hash) } catch(_:Exception) { failed=true };assertTrue(failed)
        } finally { prepared.key.fill(0) }
    }
}
