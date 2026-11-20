package com.github.kr328.clash.core.bridge

import kotlinx.coroutines.CompletableDeferred

object Bridge {
    init {
        try {
            System.loadLibrary("clash")
            System.loadLibrary("bridge")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    external fun nativeInit(home: String, geoDir: String, uid: Int)
    external fun nativeCoreVersion(): String
    external fun nativeStartTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        tunInterface: TunInterface
    )
    external fun nativeStopTun()
    external fun nativeStartHttp(listen: String): String
    external fun nativeStopHttp()
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long
    external fun nativeQueryGroupNames(includeAll: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String
    external fun nativePatchSelector(group: String, name: String): Boolean
    external fun nativeHealthCheck(callback: CompletableDeferred<Unit>?, group: String)
    external fun nativeHealthCheckAll()
    external fun nativeLoad(callback: CompletableDeferred<Unit>?, path: String)
    external fun nativeSubscribeLogcat(logcat: LogcatInterface)
    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeSuspend(suspend: Boolean)
    external fun nativeQueryTunnelState(): String
    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, data: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeNotifyDnsChanged(dns: String)
    external fun nativeNotifyInstalledAppChanged(packages: String)
    external fun nativeNotifyTimeZoneChanged(timeZone: String, offset: Int)
}
