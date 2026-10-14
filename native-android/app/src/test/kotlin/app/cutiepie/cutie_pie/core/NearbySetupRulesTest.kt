package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class NearbySetupRulesTest {
    @Test fun senderEnablesBluetoothBeforeWifi() {
        assertEquals(NearbySetupRules.Step.BLUETOOTH,NearbySetupRules.next(36,true,true,false,false,true,false))
        assertEquals(NearbySetupRules.Step.WIFI,NearbySetupRules.next(36,true,true,false,true,true,false))
    }
    @Test fun receiverDoesNotEnableStationWifiAndDisableItsHotspot() {
        assertEquals(NearbySetupRules.Step.READY,NearbySetupRules.next(36,true,true,true,true,false,false))
    }
    @Test fun oldAndroidLocationRequiredWithoutAnyGpsRead() {
        assertEquals(NearbySetupRules.Step.LOCATION,NearbySetupRules.next(32,true,true,true,true,false,false))
        assertEquals(NearbySetupRules.Step.WIFI,NearbySetupRules.next(32,true,true,false,true,true,false))
    }
    @Test fun wifiOnlyFallbackWithoutBluetoothNeedsWifiOnBothPhones() {
        assertEquals(NearbySetupRules.Step.WIFI,NearbySetupRules.next(36,true,false,true,false,false,false))
    }
    @Test fun unrelatedRadiosAreNotRequested() {
        assertEquals(NearbySetupRules.Step.READY,NearbySetupRules.next(36,false,false,false,false,false,false))
        assertEquals(NearbySetupRules.Step.READY,NearbySetupRules.next(36,false,true,false,true,false,false))
        assertEquals(NearbySetupRules.Step.READY,NearbySetupRules.next(32,false,true,false,true,false,false))
    }
    @Test fun cancelledSettingsCannotAuthorizeDiscovery() {
        assertFalse(NearbySetupRules.completed(NearbySetupRules.Step.BLUETOOTH,false,true,true))
        assertFalse(NearbySetupRules.completed(NearbySetupRules.Step.LOCATION,true,false,true))
        assertFalse(NearbySetupRules.completed(NearbySetupRules.Step.WIFI,true,true,false))
    }
    @Test fun discoverablePermissionIsReceiverOnlyOnModernAndroid() {
        assertTrue(NearbySetupRules.receivingPermissions(31,true,true))
        assertFalse(NearbySetupRules.receivingPermissions(30,true,true))
        assertFalse(NearbySetupRules.receivingPermissions(36,true,false))
        assertFalse(NearbySetupRules.receivingPermissions(36,false,true))
    }
}
