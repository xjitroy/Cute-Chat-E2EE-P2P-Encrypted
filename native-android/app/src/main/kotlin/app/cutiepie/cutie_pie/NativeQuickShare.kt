package app.cutiepie.cutie_pie

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileOutputStream
import java.io.OutputStream

/** Session opt-in. Android10+ Downloads or an explicit SAF folder; files are outside the vault. */
class NativeQuickShare(private val context:Context) {
    @Volatile var enabled=false;private set
    @Volatile var epoch=0L;private set
    private val prefs=context.getSharedPreferences("native_settings",0)
    init { val interrupted=org.json.JSONObject(prefs.getString("local.quick.pending","{}") ?: "{}");Thread({ recover(interrupted) },"CuteQuickCleanup").apply { isDaemon=true;start() } }
    private fun recover(pending:org.json.JSONObject) {
        // Recover only our own recorded partial document names. Never remove a finalized user file.
        pending.keys().asSequence().toList().forEach { value ->
            val uri=Uri.parse(value);val partial=pending.getString(value)
            runCatching {
                if(partial=="DOWNLOAD_PENDING" && Build.VERSION.SDK_INT>=29) {
                    val pendingRow=context.contentResolver.query(uri,arrayOf(MediaStore.Downloads.IS_PENDING),null,null,null)?.use { if(it.moveToFirst()) it.getInt(0)==1 else false } ?: false
                    if(pendingRow) context.contentResolver.delete(uri,null,null)
                    forget(uri);return@runCatching
                }
                val name=context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
                if(name!=null && (name==partial || name.startsWith(partial+"."))) DocumentsContract.deleteDocument(context.contentResolver,uri)
                forget(uri)
            }
        }
    }
    @Synchronized private fun remember(uri:Uri,name:String) {
        val pending=org.json.JSONObject(prefs.getString("local.quick.pending","{}") ?: "{}");pending.put(uri.toString(),name)
        check(prefs.edit().putString("local.quick.pending",pending.toString()).commit())
    }
    @Synchronized private fun forget(uri:Uri) {
        val pending=org.json.JSONObject(prefs.getString("local.quick.pending","{}") ?: "{}");pending.remove(uri.toString())
        prefs.edit().putString("local.quick.pending",pending.toString()).commit()
    }
    fun enable(tree:Uri) {
        val resolver=context.contentResolver
        check(resolver.persistedUriPermissions.any { it.uri==tree && it.isWritePermission }) { "Choose a writable folder" }
        DocumentsContract.getTreeDocumentId(tree)
        prefs.edit().putString("local.quick.folder",tree.toString()).apply();epoch++;enabled=true
    }
    fun enableDownloads() {
        check(Build.VERSION.SDK_INT>=29)
        prefs.edit().remove("local.quick.folder").apply();epoch++;enabled=true
    }
    fun enableSavedDestination():Boolean {
        val folder=prefs.getString("local.quick.folder",null)
        return runCatching { if(folder!=null) enable(Uri.parse(folder)) else enableDownloads();true }.getOrDefault(false)
    }
    fun disable() { enabled=false;epoch++ }
    fun open(name:String,mime:String,size:Long=0):Destination {
        check(enabled)
        val folder=prefs.getString("local.quick.folder",null)
        if(folder==null && Build.VERSION.SDK_INT>=29) {
            require(size in 0..app.cutiepie.cutie_pie.core.LargeFileRules.MAX_SIZE)
            check(android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes>=size+app.cutiepie.cutie_pie.core.LargeFileRules.RESERVE) { "Not enough Downloads storage" }
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Cute Chat")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Downloads unavailable")
            try { remember(uri,"DOWNLOAD_PENDING");return Destination(uri,name,context.contentResolver.openOutputStream(uri,"w") ?: error("Downloads unavailable"),true) }
            catch(failure:Exception) { runCatching { context.contentResolver.delete(uri,null,null) }.onSuccess { forget(uri) };throw failure }
        }
        val tree=Uri.parse(folder ?: error("Choose a folder first"))
        check(context.contentResolver.persistedUriPermissions.any { it.uri==tree && it.isWritePermission })
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val partial="."+name.take(80)+"."+java.util.UUID.randomUUID()+".partial"
        val uri=DocumentsContract.createDocument(context.contentResolver,parent,mime,partial) ?: error("Folder unavailable")
        try { remember(uri,partial);return Destination(uri,name,context.contentResolver.openOutputStream(uri,"w") ?: error("Folder unavailable")) }
        catch(failure:Exception) { runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }.onSuccess { forget(uri) };throw failure }
    }
    inner class Destination(private val uri:Uri,private val name:String,private val output:OutputStream,private val downloads:Boolean=false):OutputStream() {
        private var ended=false
        override fun write(value:Int)=output.write(value)
        override fun write(bytes:ByteArray,offset:Int,length:Int)=output.write(bytes,offset,length)
        override fun flush()=output.flush()
        fun finish(success:Boolean) {
            if(ended) return
            try {
                output.flush();if(output is FileOutputStream) runCatching { (output as FileOutputStream).fd.sync() };output.close()
                if(downloads && Build.VERSION.SDK_INT>=29) {
                    if(success) check(context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)==1) { "Could not finalize Downloads" }
                    else context.contentResolver.delete(uri,null,null)
                } else if(success) check(DocumentsContract.renameDocument(context.contentResolver,uri,name)!=null) { "Could not finalize destination" }
                else DocumentsContract.deleteDocument(context.contentResolver,uri)
                forget(uri)
            } catch(failure:Exception) { runCatching { output.close() };runCatching { if(downloads) context.contentResolver.delete(uri,null,null) else DocumentsContract.deleteDocument(context.contentResolver,uri) }.onSuccess { forget(uri) };throw failure }
            finally { ended=true }
        }
        override fun close()=finish(false)
    }
}
