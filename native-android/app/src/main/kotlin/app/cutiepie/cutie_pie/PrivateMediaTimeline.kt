package app.cutiepie.cutie_pie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cutiepie.cutie_pie.core.GalleryTimeline
import app.cutiepie.cutie_pie.core.NativeOwner
import app.cutiepie.cutie_pie.core.PrivateLibrary
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Virtualized, phone-private timeline. Two bounded decrypt requests, one decoder. */
class PrivateMediaTimeline(context: Context, private val owner: NativeOwner,
    private val open: (PrivateLibrary.Media) -> Unit) : RecyclerView(context) {
    private data class Item(val day: Long? = null, val media: PrivateLibrary.Media? = null)
    private val main=Handler(Looper.getMainLooper())
    private val decoder=Executors.newSingleThreadExecutor()
    private val cache=object:LruCache<String,Bitmap>(4*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    private var items=listOf<Item>()
    private val holders=mutableSetOf<Cell>()
    private val waiting=java.util.ArrayDeque<Pair<Cell,PrivateLibrary.Media>>()
    private var inFlight=0
    @Volatile private var disposed=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun key(media:PrivateLibrary.Media)=media.peer+":"+media.id
    private val binder=object:Adapter<Cell>() {
        override fun getItemCount()=items.size
        override fun getItemViewType(position:Int)=if(items[position].day != null) 0 else 1
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Cell {
            val frame=FrameLayout(context).apply { setPadding(dp(3),dp(3),dp(3),dp(3)) }
            val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; clipToOutline=true
                background=MaterialYou.shape(MaterialYou.color(this,com.google.android.material.R.attr.colorSurfaceContainer),dp(14).toFloat()) }
            val label=TextView(context).apply { setTextColor(MaterialYou.color(this,com.google.android.material.R.attr.colorOnSurface)) }
            if(type == 0) { frame.minimumHeight=dp(54); frame.addView(label,FrameLayout.LayoutParams(-1,-2).apply { gravity=Gravity.CENTER_VERTICAL }) }
            else {
                frame.addView(image,FrameLayout.LayoutParams(-1,-1))
                label.textSize=12f; label.setTextColor(android.graphics.Color.WHITE); label.setPadding(dp(7),dp(4),dp(7),dp(4))
                label.background=MaterialYou.shape(0xAA142032.toInt(),dp(12).toFloat())
                frame.addView(label,FrameLayout.LayoutParams(-2,-2).apply { gravity=Gravity.BOTTOM or Gravity.END; setMargins(dp(6),dp(6),dp(6),dp(6)) })
            }
            return Cell(frame,image,label).also { holders.add(it) }
        }
        override fun onBindViewHolder(holder:Cell,position:Int) {
            val item=items[position]; holder.bound=null; holder.image.setImageDrawable(null)
            if(item.day != null) {
                holder.label.text=DateFormat.getDateInstance(DateFormat.FULL,Locale.forLanguageTag(owner.preferences.getString("language","en")!!)).format(Date(item.day))
                holder.label.textSize=16f; holder.label.setPadding(dp(5),dp(10),dp(5),dp(5)); holder.label.setTypeface(holder.label.typeface,android.graphics.Typeface.BOLD)
                holder.itemView.layoutParams=LayoutParams(-1,-2); holder.itemView.setOnClickListener(null)
            } else {
                val media=item.media!!; holder.bound=key(media)
                val width=(this@PrivateMediaTimeline.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels-dp(32)))/((layoutManager as? GridLayoutManager)?.spanCount ?: 3)
                holder.itemView.layoutParams=LayoutParams(-1,width.coerceAtLeast(dp(80)))
                holder.label.visibility=if(media.mime.startsWith("video/")) View.VISIBLE else View.GONE
                holder.label.text="▶"
                holder.itemView.contentDescription=media.name+" · "+DateFormat.getDateTimeInstance().format(Date(media.at))
                holder.itemView.isFocusable=true; holder.itemView.setOnClickListener { if(!disposed && holder.bound == key(media)) open(media) }
                val cached=cache.get(key(media))
                if(cached != null) holder.image.setImageBitmap(cached)
                else { holder.image.setImageDrawable(MaterialYou.icon(context,if(media.mime.startsWith("video/")) "video" else "gallery",MaterialYou.color(holder.label,com.google.android.material.R.attr.colorOnSurfaceVariant)))
                    waiting.add(holder to media); drain() }
            }
        }
        override fun onViewRecycled(holder:Cell) { holder.bound=null; holder.image.setImageDrawable(null) }
    }
    private class Cell(frame:FrameLayout,val image:ImageView,val label:TextView):ViewHolder(frame) { var bound:String?=null }
    init {
        tag="private-media-timeline"; clipToPadding=false; setPadding(0,dp(6),0,dp(12))
        layoutManager=GridLayoutManager(context,3).apply { spanSizeLookup=object:GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position:Int)=if(items[position].day != null) (layoutManager as? GridLayoutManager)?.spanCount ?: 3 else 1
        } }
        adapter=binder; itemAnimator=null
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        (layoutManager as? GridLayoutManager)?.spanCount=(w/dp(140).coerceAtLeast(1)).coerceIn(2,6)
    }
    fun update(media:List<PrivateLibrary.Media>) {
        if(disposed) return
        waiting.clear(); holders.forEach { it.bound=null; it.image.setImageDrawable(null) }
        items=GalleryTimeline.groups(media).flatMap { (day,photos) -> listOf(Item(day=day))+photos.map { Item(media=it) } }
        val valid=media.filter { GalleryTimeline.visual(it.mime) }.map(::key).toSet()
        cache.snapshot().keys.filter { it !in valid }.forEach(cache::remove)
        binder.notifyDataSetChanged()
    }
    private fun drain() {
        while(!disposed && inFlight < 2 && waiting.isNotEmpty()) {
            val (holder,media)=waiting.removeFirst(); val id=key(media)
            if(holder.bound != id) continue
            inFlight++
            owner.readMediaPreview(media.peer,media.id) { bytes ->
                if(disposed) { bytes?.fill(0); return@readMediaPreview }
                try { decoder.execute {
                    val bitmap=try { if(bytes == null || disposed) null else thumbnail(bytes,media.mime) }
                        catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                        finally { bytes?.fill(0) }
                    main.post {
                        inFlight--
                        if(disposed || holder.bound != id) bitmap?.recycle()
                        else if(bitmap != null) { cache.put(id,bitmap); holder.image.setImageBitmap(bitmap) }
                        drain()
                    }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { bytes?.fill(0) }
            }
        }
    }
    private fun thumbnail(bytes:ByteArray,mime:String):Bitmap? {
        if(mime.startsWith("image/")) {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth !in 1..30000 || bounds.outHeight !in 1..30000 || bounds.outWidth.toLong()*bounds.outHeight > 100_000_000) return null
            var sample=1; while(bounds.outWidth/sample > 640 || bounds.outHeight/sample > 640) sample*=2
            return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
        }
        // Older decoders have no scaled-frame API; keep the safe video tile.
        if(Build.VERSION.SDK_INT < 27) return null
        val file=File.createTempFile("native-thumb-",".media",context.cacheDir)
        val retriever=MediaMetadataRetriever()
        return try { file.writeBytes(bytes); retriever.setDataSource(file.path)
            retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320)
        } finally { try { retriever.release() } finally { file.delete() } }
    }
    fun dispose() {
        disposed=true; waiting.clear(); holders.forEach { it.bound=null; it.image.setImageDrawable(null) }; holders.clear()
        cache.evictAll(); adapter=null; decoder.shutdown()
    }
}
