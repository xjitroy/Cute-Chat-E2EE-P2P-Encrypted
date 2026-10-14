package app.cutiepie.cutie_pie.core

/** User-approved sender on one authenticated local link and one Receive-mode epoch. */
class QuickReceiveConsent {
    private data class Grant(val epoch:Long,var until:Long)
    private val grants=linkedMapOf<Pair<String,String>,Grant>()
    @Synchronized fun allow(peer:String,session:String,epoch:Long,now:Long):Boolean {
        grants.entries.removeIf { it.value.until<=now || it.value.epoch!=epoch }
        return epoch>=0 && grants[peer to session]?.let { it.epoch==epoch && it.until>now }==true
    }
    @Synchronized fun approve(peer:String,session:String,epoch:Long,now:Long) {
        require(epoch>=0);allow(peer,session,epoch,now)
        if(grants.size>=16 && peer to session !in grants) grants.remove(grants.keys.first())
        grants[peer to session]=Grant(epoch,now+120_000)
    }
    @Synchronized fun progress(peer:String,session:String,epoch:Long,now:Long) {
        if(allow(peer,session,epoch,now)) grants[peer to session]?.until=now+120_000
    }
    @Synchronized fun disconnected(peer:String) { grants.keys.removeIf { it.first==peer } }
}
