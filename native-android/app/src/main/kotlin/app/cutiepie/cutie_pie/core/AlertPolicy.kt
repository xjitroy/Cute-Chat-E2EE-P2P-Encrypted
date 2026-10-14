package app.cutiepie.cutie_pie.core

/** Only an unlocked, resumed conversation can suppress an actual content alert. */
object AlertPolicy {
    fun visibleConversation(foreground: Boolean, unlocked: Boolean, visiblePeer: String?, sender: String) =
        foreground && unlocked && visiblePeer == sender
    fun shouldAlert(foreground: Boolean, unlocked: Boolean, visiblePeer: String?, sender: String) =
        !visibleConversation(foreground, unlocked, visiblePeer, sender)
}
