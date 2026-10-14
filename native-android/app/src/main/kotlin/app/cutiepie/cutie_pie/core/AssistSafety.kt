package app.cutiepie.cutie_pie.core

object AssistSafety {
    private val blocked=Regex("bank|wallet|vault|password|authenticator|(^|[._ ])pin([._ ]|$)|(^|[._ ])lock([._ ]|$)|permission|installer|keyguard|systemui|(^|[._])pay|ব্যাংক|পাসওয়ার্ড|बैंक|банк",RegexOption.IGNORE_CASE)
    private val ordinarySystem=setOf("com.android.chrome","com.google.android.apps.photos","com.google.android.apps.maps","com.sec.android.gallery3d","com.samsung.android.gallery3d")
    fun clickableClass(name:String)=name in setOf("android.widget.Button","android.widget.ImageButton","android.widget.Switch","android.widget.CheckBox","android.widget.RadioButton","android.widget.TextView","android.widget.ImageView","android.widget.FrameLayout","android.widget.LinearLayout","android.widget.RelativeLayout","android.view.ViewGroup")
    fun normalize(viewWidth:Int,viewHeight:Int,width:Int,height:Int,actualRatio:Float,x:Float,y:Float):Pair<Int,Int>? {
        if(viewWidth<=0 || viewHeight<=0 || width<=0 || height<=0 || !actualRatio.isFinite() || !x.isFinite() || !y.isFinite()) return null
        val ratio=width.toFloat()/height
        if(actualRatio<=0 || kotlin.math.abs(actualRatio-ratio)/ratio>0.04f) return null
        val fittedWidth=minOf(viewWidth.toFloat(),viewHeight*ratio);val fittedHeight=fittedWidth/ratio
        val left=(viewWidth-fittedWidth)/2;val top=(viewHeight-fittedHeight)/2
        if(x<left || y<top || x>=left+fittedWidth || y>=top+fittedHeight) return null
        return ((x-left)*10000/fittedWidth).toInt().coerceIn(0,9999) to ((y-top)*10000/fittedHeight).toInt().coerceIn(0,9999)
    }
    fun packageAllowed(name:String,label:String,system:Boolean):Boolean = name!= "app.cutiepie.cutie_pie" &&
        !name.startsWith("com.android.settings") && !blocked.containsMatchIn(name+" "+label) && (!system || name in ordinarySystem)
}
