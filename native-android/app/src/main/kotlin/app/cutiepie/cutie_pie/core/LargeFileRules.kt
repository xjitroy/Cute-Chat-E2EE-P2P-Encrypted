package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** Long counters throughout: never allocate a buffer from a file's declared size. */
object LargeFileRules {
    const val MAX_SIZE = 16L * 1024 * 1024 * 1024
    const val CHUNK = 32 * 1024
    const val OVERHEAD = 17
    const val RESERVE = 64L * 1024 * 1024
    fun diskBytes(size:Long):Long {
        require(size in 1..MAX_SIZE)
        return size + ((size + CHUNK - 1) / CHUNK + 1) * (OVERHEAD + 4) + RESERVE
    }
    fun validate(value:JSONObject) {
        val type=value.getString("type")
        if(type=="large_offer") {
            NativeIdentity.exact(value,setOf("type","id","name","mime","size","hash","key","header","quick"))
            ProtocolRules.id(value.get("id"));require(WireCodec.key(value.getString("id"),24).size==24)
            val name=value.getString("name");require(name.length in 1..120 && name.none { Character.isISOControl(it) || it=='/' || it=='\\' })
            require(value.getString("mime").matches(Regex("[a-zA-Z0-9.+_-]{1,80}/[a-zA-Z0-9.+_-]{1,80}")))
            require(ProtocolRules.integer(value.get("size")) in 1..MAX_SIZE)
            require(value.get("quick") is Boolean)
            ProtocolRules.hash(value.get("hash"))
            WireCodec.key(value.getString("key"),32).fill(0);WireCodec.key(value.getString("header"),24).fill(0)
        } else {
            require(type in setOf("large_accept","large_done","large_abort"))
            NativeIdentity.exact(value,setOf("type","id"));WireCodec.key(value.getString("id"),24).fill(0)
        }
    }
    fun read(input:DataInputStream):ByteArray {
        val n=input.readInt();require(n in OVERHEAD..CHUNK+OVERHEAD)
        return ByteArray(n).also { input.readFully(it) }
    }
    fun write(output:DataOutputStream,bytes:ByteArray) {
        require(bytes.size in OVERHEAD..CHUNK+OVERHEAD);output.writeInt(bytes.size);output.write(bytes)
    }
    fun aad(id:String,size:Long)=("cute-chat/local-file/v1\n$id\n$size").toByteArray(Charsets.UTF_8)
}
