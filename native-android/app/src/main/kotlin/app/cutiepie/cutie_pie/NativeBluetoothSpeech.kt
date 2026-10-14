package app.cutiepie.cutie_pie

import android.media.*
import app.cutiepie.cutie_pie.core.G711MuLaw
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit accepted call only:8kHz PCMU,100ms bounded packets; no recordings/history/temp files. */
class NativeBluetoothSpeech(private val send:(ByteArray)->Unit,private val failed:()->Unit) : Closeable {
    private val stopped=AtomicBoolean(false)
    @Volatile var muted=false
    private val received=ArrayBlockingQueue<ByteArray>(3)
    private var recorder:AudioRecord?=null
    private var track:AudioTrack?=null
    private var echo:android.media.audiofx.AcousticEchoCanceler?=null
    private var noise:android.media.audiofx.NoiseSuppressor?=null
    @Suppress("MissingPermission") fun start() {
        val input=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
            maxOf(6400,AudioRecord.getMinBufferSize(8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)))
        recorder=input;check(input.state==AudioRecord.STATE_INITIALIZED)
        echo=runCatching { if(android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled=true } else null }.getOrNull()
        noise=runCatching { if(android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(input.audioSessionId)?.apply { enabled=true } else null }.getOrNull()
        val playback=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(3200,AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT))).build()
        track=playback;check(playback.state==AudioTrack.STATE_INITIALIZED)
        input.startRecording();playback.play()
        Thread({
            val samples=ShortArray(800)
            try { while(!stopped.get()) {
                var count=0
                while(count<samples.size && !stopped.get()) { val n=input.read(samples,count,samples.size-count,AudioRecord.READ_BLOCKING);check(n>0);count+=n }
                if(stopped.get()) break
                if(muted) samples.fill(0)
                send(G711MuLaw.encode(samples,count));samples.fill(0)
            } } catch(_:Exception) { if(!stopped.get()) failed() } finally { samples.fill(0) }
        },"CuteBluetoothMic").apply { isDaemon=true;start() }
        Thread({ try { while(!stopped.get()) {
            val packet=received.poll(500,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            val samples=try { G711MuLaw.decode(packet) } finally { packet.fill(0) }
            try { var offset=0;while(offset<samples.size && !stopped.get()) { val n=playback.write(samples,offset,samples.size-offset,AudioTrack.WRITE_BLOCKING);check(n>0);offset+=n } }
            finally { samples.fill(0) }
        } } catch(_:Exception) { if(!stopped.get()) failed() } },"CuteBluetoothSpeaker").apply { isDaemon=true;start() }
    }
    fun receive(bytes:ByteArray) {
        if(stopped.get() || !received.offer(bytes)) bytes.fill(0)
    }
    override fun close() {
        if(!stopped.compareAndSet(false,true)) return
        runCatching { recorder?.stop() };runCatching { recorder?.release() };recorder=null
        runCatching { track?.stop() };runCatching { track?.release() };track=null
        runCatching { echo?.release() };echo=null;runCatching { noise?.release() };noise=null
        received.forEach { it.fill(0) };received.clear()
    }
}
