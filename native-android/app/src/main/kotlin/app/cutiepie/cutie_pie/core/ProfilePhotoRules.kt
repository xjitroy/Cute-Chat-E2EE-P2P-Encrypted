package app.cutiepie.cutie_pie.core

object ProfilePhotoRules {
    fun sample(width:Int,height:Int):Int {
        require(width in 1..20000 && height in 1..20000)
        var value=1
        while(width/value>512 || height/value>512) value*=2
        return value
    }
}
