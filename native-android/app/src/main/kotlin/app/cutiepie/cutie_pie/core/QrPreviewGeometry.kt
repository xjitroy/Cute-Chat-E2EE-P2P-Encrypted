package app.cutiepie.cutie_pie.core

/** TextureView already receives Camera2's sensor-orientation transform.
 * Correct aspect/crop and DISPLAY rotation; do not apply the sensor angle twice. */
object QrPreviewGeometry {
    data class Transform(val scaleX: Float, val scaleY: Float, val rotation: Float)
    fun transform(sensor: Int, displayQuarter: Int, bufferWidth: Int, bufferHeight: Int,
        viewWidth: Int, viewHeight: Int): Transform {
        require(sensor in setOf(0,90,180,270) && displayQuarter in 0..3)
        require(bufferWidth>0 && bufferHeight>0 && viewWidth>0 && viewHeight>0)
        val naturalWidth=if(sensor%180==0) bufferWidth else bufferHeight
        val naturalHeight=if(sensor%180==0) bufferHeight else bufferWidth
        val outputWidth=if(displayQuarter%2==0) naturalWidth else naturalHeight
        val outputHeight=if(displayQuarter%2==0) naturalHeight else naturalWidth
        val fit=maxOf(viewWidth.toFloat()/outputWidth,viewHeight.toFloat()/outputHeight)
        return Transform(fit*naturalWidth/viewWidth,fit*naturalHeight/viewHeight,-90f*displayQuarter)
    }
}
