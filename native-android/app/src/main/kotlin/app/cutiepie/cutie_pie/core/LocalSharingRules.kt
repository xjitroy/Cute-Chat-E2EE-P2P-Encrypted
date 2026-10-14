package app.cutiepie.cutie_pie.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Framing is not encryption. Only metadata or Signal ciphertext is allowed on a link. */
object LocalSharingRules {
    const val MAX_PACKET = 56_000
    const val MAX_SELECTION = 12
    const val BLUETOOTH_PHOTO_LIMIT = 8 * 1024 * 1024
    fun read(input: DataInputStream): ByteArray {
        val size=input.readInt(); require(size in 1..MAX_PACKET)
        return ByteArray(size).also { input.readFully(it) }
    }
    fun write(output: DataOutputStream, bytes: ByteArray, flush: Boolean = true) {
        require(bytes.size in 1..MAX_PACKET)
        output.writeInt(bytes.size); output.write(bytes); if (flush) output.flush()
    }
    fun context(self:String,peer:String,nonce:String,remoteNonce:String,medium:String):String {
        ProtocolRules.hash(self);ProtocolRules.hash(peer);ProtocolRules.id(nonce);ProtocolRules.id(remoteNonce)
        require(self!=peer && medium in setOf("wifi","bluetooth"))
        return "cute-chat/local-accept/v1\n$self\n$peer\n$nonce\n$remoteNonce\n$medium"
    }
    fun request(nonce:String,other:String):String {
        ProtocolRules.id(nonce);ProtocolRules.id(other);require(nonce!=other)
        return "local_"+WireCodec.digest(listOf(nonce,other).sorted().joinToString("|").toByteArray())
    }
    fun safety(self:String,peer:String,nonce:String,other:String):String {
        ProtocolRules.hash(self);ProtocolRules.hash(peer)
        return WireCodec.digest(("cute-chat/local-comparison/v1\n"+listOf(self,peer).sorted().joinToString("|")+"\n"+request(nonce,other)).toByteArray()).take(12).uppercase(java.util.Locale.ROOT).chunked(6).joinToString(" ")
    }
    fun attachment(medium:String,mime:String,size:Int) {
        require(size in 1..ProtocolRules.MAX_ATTACHMENT)
        if(medium=="bluetooth") require(mime.startsWith("image/") && size<=BLUETOOTH_PHOTO_LIMIT) { "Bluetooth shares one photo up to8MB; use Wi-Fi for other files" }
    }
}
