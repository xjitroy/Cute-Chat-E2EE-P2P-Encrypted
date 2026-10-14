package app.cutiepie.cutie_pie

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Receiver-controlled export. Limited media never reaches this path. */
object NativeMediaFiles {
    fun saveToGallery(context: Context, name: String, mime: String, bytes: ByteArray) {
        val uri=beginGallery(context,name,mime)
        try { context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) };finishGallery(context,uri,true) }
        catch(failure:Throwable) { finishGallery(context,uri,false);throw failure }
    }
    fun beginGallery(context:Context,name:String,mime:String):android.net.Uri {
        check(Build.VERSION.SDK_INT >= 29) { "Use Save file on older Android" }
        require(mime.startsWith("image/") || mime.startsWith("video/"))
        val image = mime.startsWith("image/")
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name.substringAfterLast('/').substringAfterLast('\\').take(120))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/Cute Chat" else "Movies/Cute Chat")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        return resolver.insert(collection, values) ?: error("Gallery is unavailable")
    }
    fun finishGallery(context:Context,uri:android.net.Uri,success:Boolean) {
        if(success) check(context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1)
        else context.contentResolver.delete(uri,null,null)
    }
}
