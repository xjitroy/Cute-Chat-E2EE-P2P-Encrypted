package app.cutiepie.cutie_pie.core

import com.goterl.lazysodium.interfaces.SecretStream
import java.io.*
import java.security.MessageDigest

/** Vetted libsodium secretstream, not a custom nonce/key/encryption construction.
 * Framed encrypted spool is also the at-rest representation. Key/header stay in SQLCipher.
 * FINAL, length and SHA-256 must all pass before a file is marked received. */
class SecretFileStream(private val sodium:SecretStream.Native) {
    data class Prepared(val key:ByteArray,val header:ByteArray,val hash:String)
    fun prepare(source:InputStream,destination:OutputStream,id:String,size:Long,progress:(Long)->Unit={},alive:()->Boolean={true}):Prepared {
        require(size in 1..LargeFileRules.MAX_SIZE)
        val key=ByteArray(32).also(sodium::cryptoSecretStreamKeygen);val header=ByteArray(24);val state=SecretStream.State()
        val aad=LargeFileRules.aad(id,size);val plain=ByteArray(LargeFileRules.CHUNK);val hash=MessageDigest.getInstance("SHA-256")
        var successful=false
        try {
            check(sodium.cryptoSecretStreamInitPush(state,header,key))
            val output=DataOutputStream(destination);var total=0L
            while(total<size) {
                check(alive()) { "Cancelled" }
                val needed=minOf(plain.size.toLong(),size-total).toInt();var n=0
                while(n<needed) { val read=source.read(plain,n,needed-n);check(read>0) { "Source truncated" };n+=read }
                hash.update(plain,0,n)
                val cipher=ByteArray(n+LargeFileRules.OVERHEAD);val length=LongArray(1)
                check(sodium.cryptoSecretStreamPush(state,cipher,length,plain,n.toLong(),aad,aad.size.toLong(),SecretStream.TAG_MESSAGE))
                check(length[0]==cipher.size.toLong());LargeFileRules.write(output,cipher);cipher.fill(0)
                plain.fill(0);total+=n;progress(total)
            }
            check(source.read()==-1) { "Source changed size" };check(alive()) { "Cancelled" }
            val last=ByteArray(LargeFileRules.OVERHEAD);val length=LongArray(1)
            check(sodium.cryptoSecretStreamPush(state,last,length,ByteArray(0),0,aad,aad.size.toLong(),SecretStream.TAG_FINAL))
            check(length[0]==last.size.toLong());LargeFileRules.write(output,last);last.fill(0);output.flush()
            successful=true;return Prepared(key,header,hash.digest().hex())
        } finally { plain.fill(0);aad.fill(0);wipe(state);if(!successful) key.fill(0) }
    }
    inner class Reader(id:String,private val size:Long,key:ByteArray,header:ByteArray,private val expectedHash:String):Closeable {
        private val state=SecretStream.State();private val aad=LargeFileRules.aad(id,size)
        private val digest=MessageDigest.getInstance("SHA-256");var bytes=0L;private set
        var complete=false;private set
        init { require(size in 1..LargeFileRules.MAX_SIZE);check(sodium.cryptoSecretStreamInitPull(state,header,key)) }
        fun consume(cipher:ByteArray,plainOutput:OutputStream?=null) {
            check(!complete);require(cipher.size in LargeFileRules.OVERHEAD..LargeFileRules.CHUNK+LargeFileRules.OVERHEAD)
            val plain=ByteArray(cipher.size-LargeFileRules.OVERHEAD);val length=LongArray(1);val tag=ByteArray(1)
            try {
                check(sodium.cryptoSecretStreamPull(state,plain,length,tag,cipher,cipher.size.toLong(),aad,aad.size.toLong())) { "File authentication failed" }
                check(length[0]==plain.size.toLong() && bytes+length[0]<=size)
                if(tag[0]==SecretStream.TAG_FINAL) {
                    check(plain.isEmpty() && bytes==size && digest.digest().hex()==expectedHash) { "File integrity failed" };complete=true
                } else {
                    check(tag[0]==SecretStream.TAG_MESSAGE && plain.isNotEmpty())
                    digest.update(plain);plainOutput?.write(plain);bytes+=length[0]
                }
            } finally { plain.fill(0) }
        }
        override fun close() { wipe(state);aad.fill(0) }
    }
    fun export(source:InputStream,destination:OutputStream,id:String,size:Long,key:ByteArray,header:ByteArray,hash:String,alive:()->Boolean={true}) {
        Reader(id,size,key,header,hash).use { reader ->
            val input=DataInputStream(source)
            while(!reader.complete) { check(alive());val cipher=LargeFileRules.read(input);try { reader.consume(cipher,destination) } finally { cipher.fill(0) } }
            check(input.read()==-1) { "Trailing file data" };destination.flush()
        }
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun wipe(state:SecretStream.State) { state.k.fill(0);state.nonce.fill(0);state._pad.fill(0);state.write() }
}
