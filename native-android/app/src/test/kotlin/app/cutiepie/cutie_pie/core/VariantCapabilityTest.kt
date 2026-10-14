package app.cutiepie.cutie_pie.core
import app.cutiepie.cutie_pie.BuildConfig
import org.junit.Assert.*
import org.junit.Test
class VariantCapabilityTest {
    @Test fun compiledVariantAdvertisesOnlyItsOwnCapability() {
        val features=NativeExtensions.parse(NativeExtensions.features(BuildConfig.REMOTE_CONTROL))!!.getJSONArray("features")
        val names=(0 until features.length()).map { features.getString(it) }
        assertEquals(BuildConfig.FLAVOR=="assist",BuildConfig.REMOTE_CONTROL)
        assertEquals(BuildConfig.REMOTE_CONTROL,names.contains("assist"))
        assertTrue(names.containsAll(listOf("delete","profile","call_ring")))
    }
}
