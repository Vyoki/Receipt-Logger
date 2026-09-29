package com.kitchenreceipts.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.ui.AppNavHost
import com.kitchenreceipts.app.ui.LockScreen
import com.kitchenreceipts.app.ui.theme.KitchenReceiptsTheme

class MainActivity : FragmentActivity() {

    private val container get() = (application as KitchenReceiptsApp).container
    private val locked = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        // Operator's language choice (Settings), independent of the phone's language.
        super.attachBaseContext(AppSettings.wrapWithLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureScreen()
        handleRoute(intent)
        setContent {
            KitchenReceiptsTheme {
                if (locked.value) LockScreen(onUnlock = ::authenticate) else AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRoute(intent)
    }

    /** Opens the screen a notification points to. */
    private fun handleRoute(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { container.pendingRoute.value = it }
        intent?.removeExtra(EXTRA_ROUTE)
    }

    override fun onStart() {
        super.onStart()
        // Reading left over from before (or restored after a restart) continues in the background service.
        com.kitchenreceipts.app.jobs.ReadingService.ensureRunning(this)
        applySecureScreen()
        val c = container
        val away = c.lastBackgroundAt > 0 && SystemClock.elapsedRealtime() - c.lastBackgroundAt > LOCK_AFTER_MS
        if (c.settings.appLock && (!c.unlocked || away)) {
            c.unlocked = false
            locked.value = true
            authenticate()
        } else {
            locked.value = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.lastBackgroundAt = SystemClock.elapsedRealtime()
    }

    fun applySecureScreen() {
        if (container.settings.blockScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun authenticate() {
        val c = container
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint/face/screen lock on this phone: the lock cannot work, don't trap the operator.
            c.log.event("APP_LOCK_UNAVAILABLE")
            c.unlocked = true
            locked.value = false
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    c.unlocked = true
                    locked.value = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.app_name))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        const val EXTRA_ROUTE = "route"
        private const val LOCK_AFTER_MS = 3 * 60 * 1000L

        fun canUseAppLock(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
