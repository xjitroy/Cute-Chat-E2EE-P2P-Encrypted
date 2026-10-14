package app.cutiepie.cutie_pie.core

/** A live call-only UI grant; never changes app-view authentication. */
class CallEntryAccess {
    @Volatile private var accepted = ""
    @Volatile private var visible = ""
    fun visibility(id: String, showing: Boolean) { if(showing) visible=id else if(visible==id) visible="" }
    fun accept(id: String, current: String, incoming: Boolean): Boolean {
        if(id.isEmpty() || id!=current || !incoming || visible!=id) return false
        accepted=id; return true
    }
    fun permits(id: String, appForeground: Boolean, appUnlocked: Boolean) =
        id.isNotEmpty() && ((appForeground && appUnlocked) || (accepted==id && visible==id))
    fun visibleFor(id:String)=id.isNotEmpty() && visible==id
    fun confirmed(id:String)=id.isNotEmpty() && accepted==id
    fun clear() { accepted=""; visible="" }
}
