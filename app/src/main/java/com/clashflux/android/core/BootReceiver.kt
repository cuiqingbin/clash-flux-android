package com.clashflux.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.model.ConnectionStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository.getInstance(appContext).getSnapshot()
                if (!settings.startOnBoot) return@launch
                if (ClashVpnService.serviceState.value != ConnectionStatus.Disconnected) return@launch

                if (VpnService.prepare(appContext) != null) {
                    Log.w("BootReceiver", "VPN permission not granted; skipping auto-start on boot")
                    return@launch
                }

                val profiles = ProfileRepository.getInstance(appContext).loadProfiles()
                val hasUsableFile = profiles.any { File(it.filePath).let { f -> f.exists() && f.length() > 0L } }
                if (!hasUsableFile) {
                    Log.i("BootReceiver", "No usable profile files found; skipping auto-start on boot")
                    return@launch
                }

                ClashVpnService.start(appContext)
            } catch (e: Throwable) {
                Log.e("BootReceiver", "Auto-start on boot failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
