package app.cutiepie.cutie_pie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import app.cutiepie.cutie_pie.core.QrPreviewGeometry
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.Closeable

/** Foreground-only rear Camera2 scanner. No mic, photograph, file or network upload. */
class NativeQrScanner(context:Context,private val decoded:(String)->Unit,private val unavailable:()->Unit):FrameLayout(context),Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val texture=TextureView(context)
    private val manager=context.getSystemService(CameraManager::class.java)
    private var camera:CameraDevice?=null;private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null;private var surface:Surface?=null;private var worker:HandlerThread?=null
    @Volatile private var generation=0L;@Volatile private var started=false
    @Volatile private var found=false
    private var sampleAt=0L
    private var previewSize:Size?=null;private var sensorOrientation=0
    private val displays=context.getSystemService(DisplayManager::class.java)
    private val displayListener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(id:Int) {}
        override fun onDisplayRemoved(id:Int) {}
        override fun onDisplayChanged(id:Int) { if(started && display?.displayId==id) transform() }
    }
    init {
        setBackgroundColor(Color.rgb(20,23,31));addView(texture,LayoutParams(-1,-1))
        addView(object:View(context) {
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas) {
                val d=resources.displayMetrics.density;val side=minOf(width,height)*.72f;val x=(width-side)/2;val y=(height-side)/2
                paint.color=Color.WHITE;paint.alpha=225;paint.strokeWidth=3*d;paint.style=Paint.Style.STROKE
                for((left,top) in listOf(true to true,false to true,true to false,false to false)) {
                    val px=if(left) x else x+side;val py=if(top) y else y+side
                    val p=Path().apply { moveTo(px,py+(if(top) 1 else -1)*22*d);lineTo(px,py);lineTo(px+(if(left) 1 else -1)*22*d,py) };c.drawPath(p,paint)
                }
            }
        },LayoutParams(-1,-1))
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int) { if(started) open() }
            override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int) { transform() }
            override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean { close();return true }
            override fun onSurfaceTextureUpdated(t:SurfaceTexture) {}
        }
        contentDescription="Live QR camera scanner. Point at a friend's signed Cute Chat QR."
    }
    fun start() {
        if(started) return
        if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { unavailable();return }
        started=true;found=false;sampleAt=0;generation++;displays.registerDisplayListener(displayListener,main)
        if(texture.isAvailable) open()
    }
    @android.annotation.SuppressLint("MissingPermission") // Rechecked at every explicit foreground start; revocation closes below.
    private fun open() {
        if(!started || camera!=null || reader!=null) return
        val token=generation
        try {
            val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK } ?: error("No rear camera")
            val chars=manager.getCameraCharacteristics(id)
            val formats=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No camera formats")
            val previews=formats.getOutputSizes(SurfaceTexture::class.java).orEmpty().toSet()
            val sizes=formats.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().filter { it in previews }
            val size=sizes.filter { it.width.toLong()*it.height<=1280L*720 && it.width>=320 && it.height>=240 }.minByOrNull { kotlin.math.abs(it.width.toLong()*it.height-640L*480) } ?: error("No bounded scanner format")
            previewSize=size;sensorOrientation=chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val t=checkNotNull(texture.surfaceTexture);t.setDefaultBufferSize(size.width,size.height)
            val preview=Surface(t);surface=preview
            val thread=HandlerThread("CuteQrDecode").apply { start() };worker=thread
            val images=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2);reader=images
            images.setOnImageAvailableListener({ src ->
                val image=runCatching { src.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if(!started || generation!=token || found || SystemClock.elapsedRealtime()-sampleAt<200) return@setOnImageAvailableListener
                    sampleAt=SystemClock.elapsedRealtime()
                    val plane=image.planes[0];val bytes=ByteArray(image.width*image.height);val buffer=plane.buffer
                    try {
                        for(y in 0 until image.height) for(x in 0 until image.width) { val at=y*plane.rowStride+x*plane.pixelStride;check(at<buffer.limit());bytes[y*image.width+x]=buffer.get(at) }
                        val qr=MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)) }
                        val luminance=PlanarYUVLuminanceSource(bytes,image.width,image.height,0,0,image.width,image.height,false)
                        val result=qr.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
                        if(started && generation==token && result.length<=8192 && result.startsWith("CP1.")) { found=true;main.post { if(started && generation==token) { close();decoded(result) } } }
                    } finally { bytes.fill(0) }
                } catch(_:Exception) { /* Unknown/non-QR frames are ignored, not retained. */ } finally { runCatching { image.close() } }
            },Handler(thread.looper))
            transform()
            manager.openCamera(id,object:CameraDevice.StateCallback() {
                override fun onOpened(device:CameraDevice) {
                    if(!started || generation!=token) { device.close();return }
                    camera=device
                    try { device.createCaptureSession(listOf(preview,images.surface),object:CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value:CameraCaptureSession) {
                            if(!started || generation!=token) { value.close();return };session=value
                            try { val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview);addTarget(images.surface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            };value.setRepeatingRequest(request.build(),null,main) } catch(_:Exception) { fail(token) }
                        }
                        override fun onConfigureFailed(value:CameraCaptureSession) { value.close();fail(token) }
                    },main) } catch(_:Exception) { fail(token) }
                }
                override fun onDisconnected(device:CameraDevice) { device.close();fail(token) }
                override fun onError(device:CameraDevice,error:Int) { device.close();fail(token) }
            },main)
        } catch(_:Exception) { fail(token) }
    }
    @Suppress("DEPRECATION") private fun transform() {
        val size=previewSize ?: return
        if(texture.width<=0 || texture.height<=0) return
        val rotation=display?.rotation ?: (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val plan=QrPreviewGeometry.transform(sensorOrientation,rotation,size.width,size.height,texture.width,texture.height)
        val x=texture.width/2f;val y=texture.height/2f
        texture.setTransform(Matrix().apply { setScale(plan.scaleX,plan.scaleY,x,y);postRotate(plan.rotation,x,y) })
    }
    private fun fail(token:Long) { main.post { if(generation==token && started) { close();unavailable() } } }
    override fun close() {
        generation++;started=false
        displays.unregisterDisplayListener(displayListener);previewSize=null
        runCatching { session?.stopRepeating() };runCatching { session?.close() };session=null
        runCatching { camera?.close() };camera=null
        runCatching { reader?.close() };reader=null;surface?.release();surface=null
        worker?.quitSafely();worker=null
    }
    override fun onDetachedFromWindow() { close();super.onDetachedFromWindow() }
}
