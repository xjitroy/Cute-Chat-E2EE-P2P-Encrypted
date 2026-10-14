package app.cutiepie.cutie_pie
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import app.cutiepie.cutie_pie.core.AssistSafety
object RemoteAppScope {
    private val management=setOf("android.permission.BIND_DEVICE_ADMIN","android.permission.BIND_ACCESSIBILITY_SERVICE","android.permission.BIND_VPN_SERVICE","android.permission.BIND_NOTIFICATION_LISTENER_SERVICE","android.permission.BIND_AUTOFILL_SERVICE","android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE")
    fun allowed(manager:PackageManager,pkg:String):Boolean { return try {
        val info=manager.getPackageInfo(pkg,PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS)
        val app=info.applicationInfo ?: return false
        AssistSafety.packageAllowed(pkg,manager.getApplicationLabel(app).toString(),app.flags and ApplicationInfo.FLAG_SYSTEM!=0) &&
            info.services.orEmpty().none { it.permission in management } && info.receivers.orEmpty().none { it.permission in management } &&
            info.requestedPermissions.orEmpty().none { it=="android.permission.ACCESS_SUPERUSER" }
    } catch (_: Exception) { false } }
}
