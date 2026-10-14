package app.cutiepie.cutie_pie

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.widget.*
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors

/** App-private PDF/plain-text preview. No WebView, upload or external grant. */
class PrivateDocumentActivity : androidx.appcompat.app.AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    private var pdf: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var bitmap: Bitmap? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var page = 0
    private lateinit var image: ImageView
    private lateinit var position: TextView
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private val owner get() = (application as CutieApplication).owner
    private var deletionRevision = -1L
    private val deletionObserver: () -> Unit = {
        if(!closed && deletionRevision != owner.deletionRevision) {
            deletionRevision = owner.deletionRevision
            val peer=intent.getStringExtra("peer"); val id=intent.getStringExtra("id")
            if(peer != null && id != null) owner.checkMediaDeleted(peer,id) { if(it && !closed) finish() }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val candidate = intent.getStringExtra("file")?.let(::File)
        if(candidate == null || candidate.canonicalFile.parentFile != cacheDir.canonicalFile || !candidate.name.startsWith("native-view-document-") || !candidate.isFile || candidate.length() > 32L*1024*1024) { finish(); return }
        file = candidate
        owner.observe(deletionObserver); deletionObserver()
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        body.addView(TextView(this).apply { text=intent.getStringExtra("name")?.take(120) ?: "Private document"; textSize=18f; setPadding(0,8,0,16) })
        val close=MaterialButton(this).apply { text="Close"; isAllCaps=false; setOnClickListener { finish() } }
        if(intent.type == "application/pdf") {
            image=ImageView(this).apply { adjustViewBounds=true; setBackgroundColor(Color.WHITE); contentDescription="Private PDF page" }
            body.addView(ScrollView(this).apply { addView(image) },LinearLayout.LayoutParams(-1,0,1f))
            val navigation=LinearLayout(this)
            previous=MaterialButton(this).apply { text="‹"; contentDescription="Previous page"; setOnClickListener { showPage(page-1) } }
            next=MaterialButton(this).apply { text="›"; contentDescription="Next page"; setOnClickListener { showPage(page+1) } }
            position=TextView(this).apply { gravity=android.view.Gravity.CENTER }
            navigation.addView(previous,LinearLayout.LayoutParams(72,-2)); navigation.addView(position,LinearLayout.LayoutParams(0,-1,1f)); navigation.addView(next,LinearLayout.LayoutParams(72,-2)); body.addView(navigation)
            previous.isEnabled=false; next.isEnabled=false
            io.execute {
                try { descriptor=ParcelFileDescriptor.open(candidate,ParcelFileDescriptor.MODE_READ_ONLY); pdf=PdfRenderer(descriptor!!); check(pdf!!.pageCount>0); main.post { if(!closed) showPage(0) } }
                catch (_: Exception) { fail() }
            }
        } else {
            val text=TextView(this).apply { textSize=15f; typeface=android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
            body.addView(ScrollView(this).apply { addView(text) },LinearLayout.LayoutParams(-1,0,1f))
            io.execute {
                try { check(candidate.length() <= 1024*1024); val content=candidate.readText(Charsets.UTF_8); main.post { if(!closed) text.text=content } }
                catch (_: Exception) { fail() }
            }
        }
        body.addView(close); setContentView(body)
    }
    private fun showPage(index: Int) {
        val renderer=pdf ?: return
        if(closed || index !in 0 until renderer.pageCount) return
        page=index; val token=++generation; previous.isEnabled=false; next.isEnabled=false
        io.execute {
            try {
                val rendered=renderer.openPage(index).use { source ->
                    val scale=minOf(1200f/source.width,2000f/source.height)
                    Bitmap.createBitmap((source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); source.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
                main.post {
                    if(closed || token!=generation) { rendered.recycle(); return@post }
                    image.setImageDrawable(null); bitmap?.recycle(); bitmap=rendered; image.setImageBitmap(rendered)
                    position.text="${index+1} / ${renderer.pageCount}"; previous.isEnabled=index>0; next.isEnabled=index<renderer.pageCount-1
                }
            } catch (_: Exception) { fail() }
        }
    }
    private fun fail() { main.post { if(!closed) { Toast.makeText(this,"Cannot preview this document. Return to the gallery and choose another app.",Toast.LENGTH_LONG).show(); finish() } } }
    override fun onStop() { super.onStop(); finish() }
    override fun onDestroy() {
        owner.unobserve(deletionObserver)
        closed=true; generation++; if(::image.isInitialized) image.setImageDrawable(null); bitmap?.recycle(); bitmap=null
        io.execute { runCatching { pdf?.close() }; runCatching { descriptor?.close() }; file?.delete() }; io.shutdown()
        super.onDestroy()
    }
}
