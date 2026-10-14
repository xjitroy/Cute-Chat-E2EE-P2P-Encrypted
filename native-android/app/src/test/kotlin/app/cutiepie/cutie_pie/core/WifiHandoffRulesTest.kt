package app.cutiepie.cutie_pie.core
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class WifiHandoffRulesTest {
    @Test fun optionalBandCapabilityIsStrictAndLegacyRequestsRemainValid() {
        val request=JSONObject().put("type","handoff_request").put("id",WireCodec.id()).put("expiresAt",1234)
        WifiHandoffRules.validate(request)
        WifiHandoffRules.validate(JSONObject(request.toString()).put("band5",true))
        for(bad in listOf("yes",1,JSONObject.NULL)) {
            try { WifiHandoffRules.validate(JSONObject(request.toString()).put("band5",bad));fail("Nonboolean band accepted") } catch(_:Exception) { }
        }
    }
    @Test fun fiveGhzNeedsBothDeviceCapabilitiesAndThePublicConfigurationApi() {
        assertTrue(WifiHandoffRules.fastBand(36,true,true))
        assertFalse(WifiHandoffRules.fastBand(35,true,true))
        assertFalse(WifiHandoffRules.fastBand(36,false,true))
        assertFalse(WifiHandoffRules.fastBand(36,true,false))
    }
    @Test fun newBandFeatureFitsBothExistingNormalAndAssistFeatureEnvelopes() {
        for(assist in listOf(false,true)) {
            val feature=NativeExtensions.parse(NativeExtensions.features(assist))!!.getJSONArray("features")
            assertTrue(feature.length()<=8)
            assertTrue((0 until feature.length()).any { feature.getString(it)=="wifi_band" })
        }
    }
    private fun config()=JSONObject().put("type","handoff_wifi").put("id",WireCodec.id()).put("ssid","AndroidShare").put("password","12345678").put("security","wpa2").put("port",54321).put("hosts",JSONArray(listOf("192.168.43.1")))
    @Test fun boundedSecureCredentialsRejectOpenNetworksAndUnexpectedFields() {
        WifiHandoffRules.validate(config())
        for(v in listOf(config().put("security","open"),config().put("security","WEP"),config().put("password","short"),config().put("ssid","界".repeat(32)),config().put("ssid","bad\nssid"),config().put("port",0),config().put("port",65536),config().put("hosts",JSONArray()),config().put("hosts",JSONArray(listOf("evil.example"))),config().put("extra",true))) {
            try { WifiHandoffRules.validate(v);fail("Hostile configuration accepted") } catch(_:Exception) { }
        }
    }
    @Test fun addressParsingRejectsDnsLoopbackMulticastAndOverflow() {
        for(ip in listOf("127.0.0.1","0.0.0.0","224.0.0.1","256.1.1.1","::1","example.com","1.2.3.4:80","1.2.3","169.254.1.1")) assertFalse(ip,WifiHandoffRules.ipv4(ip))
        assertTrue(WifiHandoffRules.ipv4("192.168.43.1"))
    }
    @Test fun staleAndFarFutureRequestLeasesAreRejected() {
        assertTrue(WifiHandoffRules.fresh(2000,1000));assertFalse(WifiHandoffRules.fresh(1000,1000));assertFalse(WifiHandoffRules.fresh(999,1000));assertFalse(WifiHandoffRules.fresh(121001,1000))
    }
    @Test fun receiverConsentCannotMoveToAnotherPeerOrSessionOrReplay() {
        val id=WireCodec.id();val e=WifiHandoffRules.Gate("peer",id,true,1000)
        assertFalse(e.accept("other",id,1001));assertFalse(e.accept("peer",WireCodec.id(),1001));assertTrue(e.accept("peer",id,1001));assertFalse(e.accept("peer",id,1002));assertFalse(e.credentials("peer",id,1002));e.close();assertFalse(e.matches("peer",id));assertFalse(e.accept("peer",id,1003))
    }
    @Test fun senderCredentialsAreSingleUseAndCannotSwitchRoles() {
        val id=WireCodec.id();val e=WifiHandoffRules.Gate("peer",id,false,1000)
        assertFalse(e.accept("peer",id,1001));assertFalse(e.credentials("other",id,1001));assertTrue(e.credentials("peer",id,1001));assertFalse(e.credentials("peer",id,1002));e.connected();assertEquals("connected",e.phase);e.close();assertFalse(e.credentials("peer",id,1003))
    }
    @Test fun expiredSessionsCannotStartAHotspotOrJoinOne() {
        val id=WireCodec.id();assertFalse(WifiHandoffRules.Gate("peer",id,true,1000).accept("peer",id,121000));assertFalse(WifiHandoffRules.Gate("peer",id,false,1000).credentials("peer",id,121000))
    }
    @Test fun controlsFitTheExistingAuthenticatedSignalEnvelope() {
        for(v in listOf(config(),JSONObject().put("type","handoff_request").put("id",WireCodec.id()).put("expiresAt",123456),JSONObject().put("type","handoff_abort").put("id",WireCodec.id()))) {
            val parsed=NativeExtensions.parse(NativeExtensions.envelope(v))!!;assertEquals(v.getString("id"),parsed.getString("id"));assertEquals(v.getString("type"),parsed.getString("type"));WifiHandoffRules.validate(parsed)
        }
    }
}
