package app.cutiepie.cutie_pie.core

import kotlin.math.abs

object TabSwipeRules {
    val pages=listOf("home","calls","gallery","sharing","contacts")
    fun next(page:String,delta:Float,velocity:Float,width:Float,threshold:Float,rtl:Boolean):String? {
        if(page !in pages && page!="chat" || !delta.isFinite() || !velocity.isFinite() || !width.isFinite() || !threshold.isFinite() || width<=0 || threshold<=0) return null
        if(abs(delta)<threshold && !(abs(delta)>=threshold*.4f && abs(velocity)>=900 && delta*velocity>0)) return null
        if(page=="chat") return "contacts"
        val direction=if((delta<0) != rtl) 1 else -1
        return pages[(pages.indexOf(page)+direction+pages.size)%pages.size]
    }
}
