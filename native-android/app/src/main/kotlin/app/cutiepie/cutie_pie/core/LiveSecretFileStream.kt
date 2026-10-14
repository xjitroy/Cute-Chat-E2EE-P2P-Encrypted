package app.cutiepie.cutie_pie.core

import com.goterl.lazysodium.interfaces.SecretStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Standard libsodium secretstream. Each record authenticates before it is written.
 * A digest in the authenticated FINAL record permits streaming without a whole-file prepass.
 * The destination stays pending until FINAL, original-byte digest and Long length all pass. */
class LiveSecretFileStream(private val sodium:SecretStream.Native) {
    private fun aad(id:String,size:Long)=("cute-chat/live-file/v1\n$id\n$size").toByteArray(Charsets.UTF_8)
    private fun wipe(s:SecretStream.State) { s.k.fill(0);s.nonce.fill(0);s._pad.fill(0);s.write() }
    inner class Writer(private val id:String,private val size:Long):Closeable {
        val key=ByteArray(32).also(sodium::cryptoSecretStreamKeygen);val header=ByteArray(24)
        private val state=SecretStream.State();private var used=false;private var closed=false
        init { require(size in 0..LargeFileRules.MAX_SIZE);check(sodium.cryptoSecretStreamInitPush(state,header,key)) }
        fun stream(source:InputStream,emit:(ByteArray,Long,Boolean)->Unit,alive:()->Boolean) {
            check(!used && !closed);used=true
            val associated=aad(id,size);val plain=ByteArray(LargeFileRules.CHUNK);val digest=MessageDigest.getInstance("SHA-256")
            try {
                var total=0L
                while(total<size) {
                    check(alive());val needed=minOf(plain.size.toLong(),size-total).toInt();var count=0
                    while(count<needed) { check(alive());val n=source.read(plain,count,needed-count);check(n>0) { "Source truncated" };count+=n }
                    digest.update(plain,0,count);val cipher=ByteArray(count+LargeFileRules.OVERHEAD);val length=LongArray(1)
                    try { check(sodium.cryptoSecretStreamPush(state,cipher,length,plain,count.toLong(),associated,associated.size.toLong(),SecretStream.TAG_MESSAGE));check(length[0]==cipher.size.toLong());total+=count;emit(cipher,total,false) } finally { cipher.fill(0);plain.fill(0) }
                }
                check(alive() && source.read()==-1) { "Source changed size" }
                val hash=digest.digest();val cipher=ByteArray(32+LargeFileRules.OVERHEAD);val length=LongArray(1)
                try { check(sodium.cryptoSecretStreamPush(state,cipher,length,hash,32,associated,associated.size.toLong(),SecretStream.TAG_FINAL));check(length[0]==cipher.size.toLong());emit(cipher,total,true) } finally { hash.fill(0);cipher.fill(0) }
            } finally { plain.fill(0);associated.fill(0);close() }
        }
        override fun close() { if(!closed) { closed=true;wipe(state);key.fill(0);header.fill(0) } }
    }
    inner class Reader(id:String,private val size:Long,key:ByteArray,header:ByteArray):Closeable {
        private val state=SecretStream.State();private val associated=aad(id,size);private val digest=MessageDigest.getInstance("SHA-256")
        var bytes=0L;private set
        var complete=false;private set
        init { require(size in 0..LargeFileRules.MAX_SIZE);check(sodium.cryptoSecretStreamInitPull(state,header,key)) }
        fun consume(cipher:ByteArray,output:OutputStream) {
            check(!complete);require(cipher.size in LargeFileRules.OVERHEAD..LargeFileRules.CHUNK+LargeFileRules.OVERHEAD)
            val plain=ByteArray(cipher.size-LargeFileRules.OVERHEAD);val length=LongArray(1);val tag=ByteArray(1)
            try {
                check(sodium.cryptoSecretStreamPull(state,plain,length,tag,cipher,cipher.size.toLong(),associated,associated.size.toLong())) { "File authentication failed" }
                check(length[0]==plain.size.toLong())
                if(tag[0]==SecretStream.TAG_FINAL) {
                    check(bytes==size && plain.size==32 && MessageDigest.isEqual(digest.digest(),plain)) { "File integrity failed" };complete=true
                } else { check(tag[0]==SecretStream.TAG_MESSAGE && plain.isNotEmpty() && bytes+plain.size<=size);digest.update(plain);output.write(plain);bytes+=plain.size }
            } finally { plain.fill(0) }
        }
        override fun close() { wipe(state);associated.fill(0) }
    }
}
