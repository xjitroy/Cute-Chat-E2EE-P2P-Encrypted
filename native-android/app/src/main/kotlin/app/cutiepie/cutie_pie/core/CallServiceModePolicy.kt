package app.cutiepie.cutie_pie.core

object CallServiceModePolicy {
    fun mode(previous: Int,requested: Int,callActive: Boolean): Int {
        require(previous in 1..4 && requested in 1..4)
        if(!callActive) return 3
        return if(requested == 3 && previous != 3) previous else requested
    }
    fun ticket(current: String,expected: String,active: Boolean) = active && current.isNotEmpty() && current == expected
}
