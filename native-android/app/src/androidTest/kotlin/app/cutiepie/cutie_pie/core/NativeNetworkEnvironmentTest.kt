package app.cutiepie.cutie_pie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Public, unauthenticated endpoint only. Never disables TLS or logs credentials.
 * Separates emulator/proxy readiness from the authenticated peer regression. */
@RunWith(AndroidJUnit4::class)
class NativeNetworkEnvironmentTest {
    @Test fun publicCoordinationHealthUsesNormalAndroidTls() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
        try {
            try {
                http.newCall(Request.Builder()
                    .url("https://cutie-pie-signaling.xbrstu.workers.dev/health").build())
                    .execute().use { assertEquals("Public coordination health", 200, it.code) }
            } catch (failure: Exception) {
                val categories = generateSequence(failure as Throwable) { it.cause }
                    .take(6).joinToString(" -> ") {
                        if (it is android.system.ErrnoException)
                            "${it.javaClass.simpleName}(${android.system.OsConstants.errnoName(it.errno)})"
                        else it.javaClass.simpleName
                    }
                throw AssertionError("Android network environment: $categories")
            }
        } finally {
            http.dispatcher.cancelAll(); http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
