package app.cutiepie.cutie_pie

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.util.Size
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File

/** Foreground-only native camera; no external app or gallery permission. */
class ChatCameraActivity : androidx.appcompat.app.AppCompatActivity(), SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("CuteChatCamera").apply { start() }
    private val capture = Handler(captureThread.looper)
    private fun cameraThread(action: () -> Unit): Boolean {
        if (Looper.myLooper() == capture.looper) return false
        capture.post(action); return true
    }
    private fun ui(action: () -> Unit) { handler.post { if (!isDestroyed) action() } }
    private lateinit var preview: SurfaceView
    private lateinit var photo: MaterialButton
    private val photoOnly get()=intent.getBooleanExtra("photoOnly",false)
    private lateinit var video: MaterialButton
    private lateinit var switch: MaterialButton
    private lateinit var status: TextView
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var front = false
    @Volatile private var running = false
    @Volatile private var recording = false
    private var recordingAt = 0L
    private var size = Size(1280, 720)
    private var sensor = 90
    private var generation = 0
    private var opening = false
    private var openRetries = 0
    @Volatile private var pendingVideo = false
    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = (SystemClock.elapsedRealtime() - recordingAt) / 1000
            status.text = "%02d:%02d · max 2:00".format(seconds / 60, seconds % 60)
            if (seconds >= 120) finishVideo(true) else handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { finish(); return }
        val bn = (application as CutieApplication).owner.preferences.getString("language", "en") == "bn"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        status = TextView(this).apply { text = if(photoOnly) (if(bn) "Bluetooth · ব্যক্তিগত ছবি" else "Bluetooth · private photos") else if (bn) "ব্যক্তিগত ক্যামেরা · ছবি / ২ মিনিটের ভিডিও" else "Private camera · photos / 2-minute video"; setTextColor(-1); textSize = 16f; setPadding(20, 18, 20, 18); gravity = Gravity.CENTER }
        root.addView(status)
        preview = SurfaceView(this); root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f)); preview.holder.addCallback(this)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(8, 8, 8, 12) }
        fun control(title: String, action: () -> Unit) = MaterialButton(this).apply { text = title; minimumHeight = 56; setOnClickListener { action() } }
        photo = control(if (bn) "ছবি" else "Photo") { takePhoto() }
        video = control(if (bn) "ভিডিও" else "Video") { if (recording) finishVideo(true) else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVideo() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 20) }
        switch = control(if (bn) "ঘোরান" else "Flip") { if (!recording) { front = !front; closeCamera(); openCamera() } }
        (if(photoOnly) listOf(photo,switch) else listOf(photo, video, switch)).forEach { controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }; root.addView(controls)
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets -> val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets }
    }
    override fun onResume() { super.onResume(); running = true; if (::preview.isInitialized && preview.holder.surface.isValid) openCamera() }
    override fun onPause() { running = false; capture.post { finishVideo(false); closeCamera(); output?.delete(); output = null }; super.onPause() }
    override fun onStop() {
        super.onStop()
        // A stopped capture must not reopen above the authenticated Main
        // entry from Recents. Fresh camera gestures remain possible there.
        if(!isChangingConfigurations && !isFinishing) finish()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); captureThread.quitSafely(); super.onDestroy() }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 20 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingVideo = true
            if (running && session != null) { pendingVideo = false; startVideo() }
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { if (running) openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }
    private fun newFile(extension: String): File = File.createTempFile("capture-", extension, File(cacheDir, "native-capture").apply { mkdirs() })
    @Suppress("MissingPermission")
    private fun openCamera() {
        if (cameraThread { openCamera() }) return
        if (!running || camera != null || opening || !preview.holder.surface.isValid) return
        opening = true
        val epoch = ++generation
        try {
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK } ?: ids.first()
            val details = manager.getCameraCharacteristics(id); sensor = details.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            front = details.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val map = details.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(MediaRecorder::class.java)
            size = sizes.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
            preview.holder.setFixedSize(size.width, size.height)
            val jpeg = map.getOutputSizes(ImageFormat.JPEG).filter { maxOf(it.width, it.height) <= 2560 }.maxByOrNull { it.width * it.height } ?: map.getOutputSizes(ImageFormat.JPEG).minBy { it.width * it.height }
            reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        if (!running || output == null) return@use
                        val file = output!!; val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                        try { check(bytes.size <= 32 * 1024 * 1024); file.writeBytes(bytes); deliver(file, "image/jpeg") }
                        catch (_: Exception) { file.delete(); failed("Photo could not be captured") }
                        finally { bytes.fill(0) }
                    }
                }, capture)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { if (!running || epoch != generation) { device.close(); return }; openRetries = 0; opening = false; camera = device; configure(false) }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (epoch != generation) return
                    opening = false
                    if (running && error in listOf(ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE) && openRetries++ < 3) {
                        reader?.close(); reader = null; capture.postDelayed({ openCamera() }, 700)
                    } else failed("Camera is unavailable ($error)")
                }
            }, capture)
        } catch (_: Exception) { failed("Camera is unavailable") }
    }
    private fun orientation(): Int {
        @Suppress("DEPRECATION") val rotation = windowManager.defaultDisplay.rotation * 90
        return if (front) (sensor + rotation) % 360 else (sensor - rotation + 360) % 360
    }
    @Suppress("DEPRECATION")
    private fun configure(record: Boolean) {
        val device = camera ?: return
        val epoch = generation
        session?.close(); session = null
        val surfaces = listOf(preview.holder.surface, if (record) recorder!!.surface else reader!!.surface)
        try { device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if (!running || epoch != generation || camera !== device) { value.close(); return }
                session = value
                try {
                    val request = device.createCaptureRequest(if (record) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview.holder.surface); if (record) addTarget(recorder!!.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    value.setRepeatingRequest(request.build(), null, handler)
                    if (record) { recorder!!.start(); recording = true; recordingAt = SystemClock.elapsedRealtime(); ui { video.text = "Stop"; photo.isEnabled = false; switch.isEnabled = false }; handler.post(tick) }
                    else if (pendingVideo && running) { pendingVideo = false; startVideo() }
                } catch (_: Exception) { failed("Camera capture could not start") }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); failed("Camera mode is unsupported") }
        }, capture) } catch (_: Exception) { failed("Camera mode is unsupported") }
    }
    private fun takePhoto() {
        if (cameraThread { takePhoto() }) return
        val device = camera ?: return
        if (!running || recording || output != null) return
        output = newFile(".jpg"); ui { photo.isEnabled = false; switch.isEnabled = false; video.isEnabled = false }
        try { val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader!!.surface); set(CaptureRequest.JPEG_ORIENTATION, orientation()); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }; session!!.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { output?.delete(); output = null; failed("Photo capture failed") }
        }, capture) } catch (_: Exception) { output?.delete(); output = null; failed("Photo capture failed") }
    }
    private fun startVideo() {
        if(photoOnly) return
        if (cameraThread { startVideo() }) return
        if (!running || camera == null || recording || recorder != null || output != null) return
        val file = newFile(".mp4"); output = file
        ui { photo.isEnabled = false; switch.isEnabled = false }
        try {
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setOutputFile(file.path)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(size.width, size.height); setVideoFrameRate(30); setVideoEncodingBitRate(1800000)
                setAudioEncodingBitRate(96000); setAudioSamplingRate(44100); setOrientationHint(orientation())
                setMaxDuration(120000); setMaxFileSize(31L * 1024 * 1024)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishVideo(true) }
                setOnErrorListener { _, _, _ -> finishVideo(false); failed("Video recording failed") }; prepare()
            }
            configure(true)
        } catch (_: Exception) { finishVideo(false); failed("Video mode is unavailable") }
    }
    private fun finishVideo(send: Boolean) {
        if (cameraThread { finishVideo(send) }) return
        val active = recorder ?: return
        val file = output
        var valid = recording
        recording = false; handler.removeCallbacks(tick)
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) { }
        try { if (valid) active.stop() } catch (_: Exception) { valid = false }
        try { active.release() } catch (_: Exception) { valid = false } finally { recorder = null }
        if (send && valid && file != null && file.length() in 1..(32L * 1024 * 1024)) deliver(file, "video/mp4")
        else { file?.delete(); output = null; if (running) { ui { photo.isEnabled = true; switch.isEnabled = true; video.text = "Video" }; configure(false) } }
    }
    private fun deliver(file: File, mime: String) {
        output = null
        val uri = FileProvider.getUriForFile(this, "$packageName.media", file)
        handler.post { if (!running || isDestroyed) file.delete() else { setResult(Activity.RESULT_OK, Intent().setDataAndType(uri, mime)); finish() } }
    }
    private fun closeCamera() {
        if (cameraThread { closeCamera() }) return
        generation++; opening = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) { }
        session?.close(); session = null; camera?.close(); camera = null; reader?.close(); reader = null
    }
    private fun failed(text: String) { android.util.Log.w("CuteChatCamera", text); ui { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); setResult(Activity.RESULT_CANCELED, Intent().putExtra("captureError", text)); finish() } }
}
