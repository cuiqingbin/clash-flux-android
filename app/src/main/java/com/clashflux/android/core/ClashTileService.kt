package com.clashflux.android.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clashflux.android.MainActivity
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.model.ConnectionStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClashTileService : TileService() {

    private val tileScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateCollectorJob: Job? = null
    private var pendingStartCheckJob: Job? = null

    companion object {
        internal fun canStartTileProfileCheck(
            state: ConnectionStatus,
            checkInFlight: Boolean
        ): Boolean = state == ConnectionStatus.Disconnected && !checkInFlight

        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, ClashTileService::class.java)
                )
            } catch (_: Throwable) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (pendingStartCheckJob?.isActive != true) {
            syncTileState(ClashVpnService.serviceState.value)
        }

        stateCollectorJob?.cancel()
        stateCollectorJob = tileScope.launch {
            ClashVpnService.serviceState.collect { status ->
                if (status == ConnectionStatus.Disconnected && pendingStartCheckJob?.isActive == true) {
                    return@collect
                }
                syncTileState(status)
            }
        }
    }

    override fun onStopListening() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentState = ClashVpnService.serviceState.value
        when (currentState) {
            ConnectionStatus.Connected, ConnectionStatus.Connecting -> {
                pendingStartCheckJob?.cancel()
                pendingStartCheckJob = null
                syncTileState(ConnectionStatus.Disconnecting)
                ClashVpnService.stop(applicationContext)
            }
            ConnectionStatus.Disconnected -> {
                if (!canStartTileProfileCheck(currentState, pendingStartCheckJob?.isActive == true)) {
                    return
                }
                val prepareIntent = VpnService.prepare(applicationContext)
                if (prepareIntent != null) {
                    openMainActivity()
                } else {
                    syncTileState(ConnectionStatus.Connecting)
                    val checkJob = tileScope.launch(Dispatchers.IO) {
                        val hasUsableProfile = ProfileRepository.getInstance(applicationContext)
                            .loadProfiles()
                            .any { File(it.filePath).let { f -> f.exists() && f.length() > 0L } }
                        withContext(Dispatchers.Main) {
                            if (!isActive || ClashVpnService.serviceState.value != ConnectionStatus.Disconnected) {
                                return@withContext
                            }
                            if (hasUsableProfile) {
                                ClashVpnService.start(applicationContext)
                            } else {
                                syncTileState(ConnectionStatus.Disconnected)
                                openMainActivity()
                            }
                        }
                    }
                    pendingStartCheckJob = checkJob
                    checkJob.invokeOnCompletion {
                        if (pendingStartCheckJob === checkJob) {
                            pendingStartCheckJob = null
                        }
                    }
                }
            }
            ConnectionStatus.Disconnecting -> Unit
        }
    }

    private fun openMainActivity() {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityLegacy(openIntent)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun syncTileState(status: ConnectionStatus) {
        val tile = qsTile ?: return
        tile.label = "ClashFlux"
        tile.state = when (status) {
            ConnectionStatus.Connected, ConnectionStatus.Connecting -> Tile.STATE_ACTIVE
            ConnectionStatus.Disconnected -> Tile.STATE_INACTIVE
            ConnectionStatus.Disconnecting -> Tile.STATE_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                ConnectionStatus.Connected -> "代理已开启"
                ConnectionStatus.Connecting -> "正在连接..."
                ConnectionStatus.Disconnecting -> "正在断开..."
                ConnectionStatus.Disconnected -> "点击开启"
            }
        }
        try {
            tile.updateTile()
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }
}
