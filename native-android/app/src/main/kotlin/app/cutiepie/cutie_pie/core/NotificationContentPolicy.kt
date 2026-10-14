package app.cutiepie.cutie_pie.core

object NotificationContentPolicy {
    // App-view authentication does not gate an explicitly enabled OS preview.
    // The phone lock screen still receives only the generic public version.
    fun preview(enabled: Boolean, deviceLocked: Boolean) = enabled && !deviceLocked
    fun hideIdentity(deviceLocked: Boolean, appUnlocked: Boolean, previewEnabled: Boolean) =
        deviceLocked || (!appUnlocked && !previewEnabled)
}
