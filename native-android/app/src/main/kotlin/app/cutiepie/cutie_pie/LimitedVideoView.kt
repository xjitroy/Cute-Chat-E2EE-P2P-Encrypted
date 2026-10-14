package app.cutiepie.cutie_pie

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import app.cutiepie.cutie_pie.core.EphemeralMediaBytes

/** Limited video is played from RAM, never a decrypted disk cache/URI. */
class LimitedVideoView(context: Context,ownedBytes: ByteArray,private val finished: () -> Unit,
    private val failed: () -> Unit) : TextureView(context),AutoCloseable {
    private val buffer = EphemeralMediaBytes(ownedBytes)
    private val source = object: MediaDataSource() {
        override fun getSize() = buffer.size()
        override fun readAt(position: Long,target: ByteArray,offset: Int,size: Int) = buffer.read(position,target,offset,size)
        override fun close() = buffer.close()
    }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var closed = false
    init {
        surfaceTextureListener = object: SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w: Int,h: Int) {
                if(closed) return
                try {
                    surface = Surface(texture)
                    val media = MediaPlayer()
                    player = media
                    media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    media.setSurface(surface); media.setDataSource(source)
                    media.setOnPreparedListener { if(!closed) { fit(it); it.start() } }
                    media.setOnVideoSizeChangedListener { media,_,_ -> fit(media) }
                    media.setOnCompletionListener { if(!closed) finished() }
                    media.setOnErrorListener { _,_,_ -> if(!closed) { close(); failed() }; true }
                    media.prepareAsync()
                } catch (_: Exception) { close(); failed() }
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w: Int,h: Int) { player?.let(::fit) }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }
    private fun fit(media: MediaPlayer) {
        if(closed || width <= 0 || height <= 0) return
        val w = media.videoWidth; val h = media.videoHeight
        if(w <= 0 || h <= 0) return
        val scale = minOf(width.toFloat()/w,height.toFloat()/h)
        setTransform(Matrix().apply { setScale(w*scale/width,h*scale/height,width/2f,height/2f) })
    }
    override fun close() {
        if(closed) return
        closed = true
        try { player?.release() } finally { player = null; surface?.release(); surface = null; source.close() }
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
