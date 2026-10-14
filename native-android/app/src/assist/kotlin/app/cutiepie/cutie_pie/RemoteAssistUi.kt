package app.cutiepie.cutie_pie
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

    fun MainActivity.remoteSettings() {
        val assist=(application as CutieApplication).assist
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12)) }
        body.addView(label(tr("Optional live-call assistance. The viewer can tap, swipe and explicitly go Back only in apps you select. No keyboard/password, lock-screen, system/security or installation control. Android cannot reliably identify every custom sensitive screen. You can stop control at any time without ending the call.","ঐচ্ছিক live-call assistance। বেছে দেওয়া অ্যাপে প্রাপক tap, swipe ও স্পষ্ট Back করতে পারবেন। Keyboard/password, lock screen, system/security ও installation কন্ট্রোল হবে না। সব custom sensitive screen Android শনাক্ত করতে পারে না। কল না কেটেও যেকোনো সময় control বন্ধ করুন।"),13f))
        fun switch(key:String,title:String,default:Boolean) { body.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply { text=title;isChecked=owner.preferences.getBoolean(key,default);setOnCheckedChangeListener { _,on -> owner.preferences.edit().putBoolean(key,on).apply() } }) }
        switch("assistBack",tr("Allow explicit Back on the shared phone","শেয়ার করা ফোনে স্পষ্ট Back অনুমতি"),true)
        switch("assistConfirmBack",tr("Confirm before remote Back","রিমোট Back-এর আগে নিশ্চিত করুন"),true)
        switch("assistPointer",tr("Show pointer in control view","কন্ট্রোল ভিউতে pointer দেখান"),true)
        body.addView(button(tr("Control duration","কন্ট্রোলের সময়")+" · ${owner.preferences.getInt("assistMinutes",10)} min") {
            val choices=intArrayOf(5,10,15,30)
            MaterialAlertDialogBuilder(this).setTitle(tr("Maximum control session","সর্বোচ্চ কন্ট্রোল সেশন")).setItems(choices.map { "$it min" }.toTypedArray()) { _,i -> owner.preferences.edit().putInt("assistMinutes",choices[i]).apply() }.secureShow()
        })
        body.addView(button(tr("Idle timeout","কোনো কন্ট্রোল না হলে সময়সীমা")+" · ${owner.preferences.getInt("assistIdle",120)} s") {
            val choices=intArrayOf(30,60,120,300)
            MaterialAlertDialogBuilder(this).setTitle(tr("Control inactivity limit","কন্ট্রোল নিষ্ক্রিয়তার সময়সীমা")).setItems(choices.map { "$it s" }.toTypedArray()) { _,i -> owner.preferences.edit().putInt("assistIdle",choices[i]).apply() }.secureShow()
        })
        body.addView(button(tr("Enable Android Accessibility manually","Android Accessibility নিজে চালু করুন")) {
            MaterialAlertDialogBuilder(this).setTitle(tr("Remote assistance access","রিমোট সাহায্যের অনুমতি"))
                .setMessage(tr("Android Accessibility lets this service inspect window/node information (including visible labels) locally and perform allowed tap/swipe/Back during your explicitly granted screen-share call. No window queries or input while idle. The selected app list stays local. Enable Cute Chat remote assistance yourself in Android settings. If Android restricts a sideloaded app, follow its own App Info/settings controls; the app cannot enable or bypass this permission.","Android Accessibility দিয়ে service আপনার অনুমতি দেওয়া screen-share call-এ window/node তথ্য ও দৃশ্যমান label স্থানীয়ভাবে দেখে অনুমোদিত tap/swipe/Back করতে পারে। Idle অবস্থায় window query/input হয় না। বেছে দেওয়া অ্যাপের তালিকা ফোনেই থাকে। Android settings-এ Cute Chat remote assistance নিজে চালু করুন। Sideloaded app-এর অনুমতি Android আটকে দিলে তার App Info/settings ব্যবহার করুন; অ্যাপ নিজে এই অনুমতি চালু বা bypass করতে পারে না।"))
                .setPositiveButton(tr("Open Android settings","Android settings খুলুন")) { _,_ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
        })
        body.addView(button(tr("Choose apps · authenticate to allow control","অ্যাপ বেছে fingerprint/password দিয়ে control অনুমতি দিন")) { chooseControlApps() }.apply { isEnabled=calls.screenSharing && calls.status=="connected" })
        if(assist.hostGranted) body.addView(button(tr("Stop control only","শুধু control বন্ধ করুন")) { assist.stopLocal() })
        MaterialAlertDialogBuilder(this).setTitle(tr("Remote control","রিমোট কন্ট্রোল")).setView(ScrollView(this).apply { addView(body) }).setNegativeButton(tr("Close","বন্ধ করুন"),null).secureShow()
    }
    private fun MainActivity.chooseControlApps() {
        if(!remoteUiAllowed || !calls.screenSharing || calls.status!="connected") return
        if(RemoteControlService.instance==null) { toast(tr("Enable the optional Accessibility service first","আগে ঐচ্ছিক Accessibility service চালু করুন"));return }
        if(!owner.supportsAssist(calls.peer)) { toast(tr("Both phones need the updated remote-assistance version","দুই ফোনেই remote assistance-এর নতুন version লাগবে"));return }
        val target=calls.id
        val available=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .distinctBy { it.activityInfo.packageName }.filter { info -> RemoteAppScope.allowed(packageManager,info.activityInfo.packageName) }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }.take(200)
        if(available.isEmpty()) { toast(tr("No supported ordinary app is available","সমর্থিত সাধারণ অ্যাপ পাওয়া যায়নি"));return }
        val saved=owner.preferences.getStringSet("assistApps",emptySet()).orEmpty();val checked=BooleanArray(available.size) { available[it].activityInfo.packageName in saved }
        MaterialAlertDialogBuilder(this).setTitle(tr("Allow only these apps · up to 8","শুধু এই অ্যাপগুলিতে অনুমতি · সর্বোচ্চ ৮টি"))
            .setMultiChoiceItems(available.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray(),checked) { _,i,on -> checked[i]=on }
            .setPositiveButton(tr("Authenticate and grant","যাচাই করে অনুমতি দিন")) { _,_ ->
                if(!remoteUiAllowed || calls.id!=target || !calls.screenSharing) return@setPositiveButton
                val selected=available.filterIndexed { i,_ -> checked[i] }.map { it.activityInfo.packageName }.toSet()
                if(selected.isEmpty() || selected.size>8) { toast(tr("Choose one to eight apps","এক থেকে আটটি অ্যাপ বেছে নিন"));return@setPositiveButton }
                val assist=(application as CutieApplication).assist
                val ticket=assist.ticket(selected) ?: run { toast(tr("Grant unavailable; stop other control or check this call","অনুমতি দেওয়া যাচ্ছে না; অন্য control বন্ধ করুন বা call চেক করুন"));return@setPositiveButton }
                owner.preferences.edit().putStringSet("assistApps",selected).apply()
                remoteConsent.launch(Intent(this,RemoteConsentActivity::class.java).putExtra("ticket",ticket))
            }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
internal fun MainActivity.openRemoteScreen(callId:String,epoch:String) {
    if(remoteUiAllowed) startActivity(Intent(this,RemoteControlActivity::class.java).putExtra("callId",callId).putExtra("epoch",epoch))
}
