package app.cutiepie.cutie_pie.core

/** Radio preparation is role-aware; the receiver's hotspot does not need station Wi-Fi. */
object NearbySetupRules {
    enum class Step { BLUETOOTH, LOCATION, WIFI, READY }
    fun next(sdk:Int, wifi:Boolean, bluetooth:Boolean, receiving:Boolean,
        bluetoothOn:Boolean, locationOn:Boolean, wifiOn:Boolean):Step = when {
        bluetooth && !bluetoothOn -> Step.BLUETOOTH
        ((sdk < 33 && wifi) || (sdk < 31 && bluetooth)) && !locationOn -> Step.LOCATION
        wifi && (!receiving || !bluetooth) && !wifiOn -> Step.WIFI
        else -> Step.READY
    }
    fun completed(step:Step, bluetoothOn:Boolean, locationOn:Boolean, wifiOn:Boolean) = when(step) {
        Step.BLUETOOTH -> bluetoothOn
        Step.LOCATION -> locationOn
        Step.WIFI -> wifiOn
        Step.READY -> true
    }
    fun receivingPermissions(sdk:Int, bluetooth:Boolean, receiving:Boolean) = bluetooth && receiving && sdk>=31
}
