package app.cutiepie.cutie_pie.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Instrumentation runs with the target UID, not the separately installed test APK UID.
 * Keep synthetic vaults under a namespaced target cache and never touch the user vault. */
internal class NativeTestContext(private val slot: String) : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
    init { require(slot.matches(Regex("[a-zA-Z0-9]+"))) }
    override fun getPackageName() = super.getPackageName() + ".synthetic.$slot.test"
    override fun getFilesDir(): File = File(super.getCacheDir(), "native-instrumentation/$slot").apply {
        check(mkdirs() || isDirectory) { "Synthetic fixture storage unavailable" }
    }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("native-test.$slot.$name", mode)
    override fun getApplicationContext(): Context = this
}
