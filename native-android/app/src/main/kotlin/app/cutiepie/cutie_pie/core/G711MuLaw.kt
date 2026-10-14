package app.cutiepie.cutie_pie.core

/** Standard G.711 PCMU telephony speech, NOT a lossless file codec or cryptography. */
object G711MuLaw {
    fun encode(samples:ShortArray,count:Int):ByteArray {
        require(count in 0..samples.size)
        return ByteArray(count) { i ->
            var value=samples[i].toInt();val sign=if(value<0) 0x80 else 0
            if(value<0) value=-value
            value=value.coerceAtMost(32635)+132
            var exponent=7;var mask=0x4000
            while(exponent>0 && value and mask==0) { exponent--;mask=mask shr 1 }
            (sign or (exponent shl 4) or ((value shr (exponent+3)) and 0x0f)).inv().toByte()
        }
    }
    fun decode(bytes:ByteArray)=ShortArray(bytes.size) { i ->
        val u=bytes[i].toInt().inv() and 0xff
        var value=((u and 15) shl 3)+132;value=value shl ((u shr 4) and 7)
        (if(u and 128!=0) 132-value else value-132).toShort()
    }
}
