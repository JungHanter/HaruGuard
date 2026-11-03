package com.hanter.haruguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.os.UserManager

/** 프로세스 밖의 시스템 알람으로 복구를 보완한다. 강제 중지 상태에서는 알람도 취소된다. */
internal object VpnRecovery {
    const val ACTION_CHECK = "com.hanter.haruguard.CHECK_VPN"
    const val ACTION_ADB_RECOVER = "com.hanter.haruguard.ADB_RECOVER_VPN"
    private const val HEARTBEAT_MS = 30_000L
    private val deadline = RecoveryAlarmDeadline()

    fun schedule(context: Context, delayMs: Long = HEARTBEAT_MS) {
        if (!VpnPreferences(context).filteringRequested) return
        val at = deadline.reserve(SystemClock.elapsedRealtime(), delayMs) ?: return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(context)
        // 정확한 알람은 허용됐을 때만 사용하고, 권한이 없으면 지연 가능한 알람을 사용한다.
        // https://developer.android.com/develop/background-work/services/alarms/schedule
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
            }
        } catch (error: Exception) {
            try {
                if (error !is SecurityException) throw error
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
            } catch (fallbackError: Exception) {
                deadline.clear()
                throw fallbackError
            }
        }
    }

    fun cancel(context: Context) {
        deadline.clear()
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    fun alarmDelivered() {
        deadline.clear()
    }

    fun request(context: Context): String {
        return try {
            val preferences = VpnPreferences(context)
            if (!preferences.filteringRequested) {
                cancel(context)
                "OFF"
            } else if (VpnService.prepare(context) != null) {
                if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                    // 부팅 중 시스템 VPN 준비가 늦더라도 저장된 ON 선택을 지우지 않는다.
                    schedule(context)
                    return "LOCKED"
                }
                // 승인 상실은 부모가 OFF를 선택한 것과 다르다. ON 의도는 보존하지만
                // 새 시스템 동의 없이 VPN을 재시작하거나 다른 VPN의 연결을 빼앗지 않는다.
                cancel(context)
                VpnRuntime.status = VpnStatus(VpnPhase.ERROR, error = context.getString(R.string.vpn_permission_missing))
                "NEEDS_CONSENT"
            } else {
                schedule(context)
                if (YouTubeVpnService.checkRunningService()) {
                    "RUNNING"
                } else {
                    context.startForegroundService(
                        Intent(context, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_RECOVER),
                    )
                    "REQUESTED"
                }
            }
        } catch (error: Exception) {
            if (VpnRuntime.status.phase != VpnPhase.FILTERING &&
                VpnRuntime.status.phase != VpnPhase.RECOVERING) {
                VpnRuntime.status = VpnStatus(VpnPhase.ERROR,
                    error = context.getString(R.string.vpn_failed, error.javaClass.simpleName))
            }
            // OS가 이번 백그라운드 시작을 거부한 경우 다음 알람·always-on 시작으로 보완한다.
            runCatching { if (VpnPreferences(context).filteringRequested) schedule(context) }
            "DEFERRED"
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 10, Intent(context, VpnRecoveryReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 부팅·앱 업데이트·자체 알람에만 반응한다. 저장된 ON 선택이 없으면 실행하지 않는다. */
class VpnRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == VpnRecovery.ACTION_CHECK ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            VpnRecovery.alarmDelivered()
        }
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, VpnRecovery.ACTION_CHECK,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            VpnRecovery.request(context)
        }
    }
}

/** DUMP 권한을 가진 ADB shell의 복구 요청만 받는다. 일반 앱에는 공개하지 않는다. */
class AdbVpnRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnRecovery.ACTION_ADB_RECOVER) return
        resultData = VpnRecovery.request(context)
    }
}
