package app.cutiepie.cutie_pie

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.*
import android.view.WindowManager
import android.widget.TextView

/** Non-exported, native fresh authentication for one call-bound control consent. */
class RemoteConsentActivity:Activity() {
    private val app get()=application as CutieApplication
    private var ticket="";private var attempted=false;private var credentialOK=false
    private var verifiedAt=0L
    private var cancellation:CancellationSignal?=null
    private var visible=false
    override fun onStart() { super.onStart();visible=true }
    override fun onStop() { visible=false;super.onStop() }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ticket=intent.getStringExtra("ticket").orEmpty()
        setContentView(TextView(this).apply { text="Cute Chat\nAuthenticate on this phone to allow remote control.\nPasswords stay in Android.";textSize=20f;gravity=android.view.Gravity.CENTER })
        if(!app.assist.ticketValid(ticket) || !getSystemService(KeyguardManager::class.java).isDeviceSecure) finish()
    }
    override fun onPostResume() { super.onPostResume();if(verifiedAt>0) { if(SystemClock.elapsedRealtime()-verifiedAt<=5000) success() else finish();return }
        if(credentialOK) { verified();return };if(!attempted) { attempted=true;authenticate() } }
    private fun credential() {
        val intent=getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Cute Chat remote control","Allow this verified peer to control only the apps you selected")
        if(intent==null) finish() else startActivityForResult(intent,74)
    }
    private fun authenticate() {
        if(Build.VERSION.SDK_INT<28) { credential();return }
        val builder=BiometricPrompt.Builder(this).setTitle("Allow remote control").setSubtitle("Fresh authentication · this screen-share call only")
        if(Build.VERSION.SDK_INT>=30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else if(Build.VERSION.SDK_INT>=29) builder.setDeviceCredentialAllowed(true)
        else builder.setNegativeButton("Use device password",mainExecutor) { _,_ -> credential() }
        cancellation=CancellationSignal()
        builder.build().authenticate(cancellation!!,mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { verified() }
            override fun onAuthenticationError(code:Int,message:CharSequence) { if(code==BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || code==BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT || code==BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) credential() else finish() }
        })
    }
    private fun verified() { verifiedAt=SystemClock.elapsedRealtime();if(visible) success() }
    private fun success() { if(visible && app.assist.authenticated(ticket)) setResult(RESULT_OK,Intent().putExtra("ticket",ticket));finish() }
    override fun onActivityResult(request:Int,result:Int,data:Intent?) { super.onActivityResult(request,result,data);if(request==74) { credentialOK=result==RESULT_OK;if(!credentialOK) finish() } }
    override fun onDestroy() { cancellation?.cancel();super.onDestroy() }
}
