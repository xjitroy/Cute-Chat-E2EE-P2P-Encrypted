package app.cutiepie.cutie_pie.core

/** Bulk records use the authenticated route, with bounded buffers and control headroom. */
interface FileStreamLink : NativeLink {
    var onBinary:((String,ByteArray)->Unit)?
    fun sendBinary(id:String,cipher:ByteArray,alive:()->Boolean):Boolean
}
