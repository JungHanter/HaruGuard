package com.hanter.haruguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserManager
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanter.haruguard.vpn.DnsTunnel
import com.hanter.haruguard.vpn.YouTubeDnsFilter

/** 저장된 선택값과 구분하여 실제 VPN 연결 상태를 표시한다. */
object VpnRuntime {
    var status by mutableStateOf(VpnStatus())
        internal set
}

class YouTubeVpnService : VpnService() {
    private val preferences by lazy { VpnPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private val session = VpnSession(
        publish = { VpnRuntime.status = it },
        describeFailure = { getString(R.string.vpn_failed, it.javaClass.simpleName) },
    )
    private val reconnect = VpnReconnectLoop(
        now = SystemClock::elapsedRealtime,
        shouldRun = { preferences.filteringRequested },
        schedule = { delay, action -> handler.postDelayed({ action() }, delay) },
        cancelScheduled = { handler.removeCallbacksAndMessages(null) },
        connect = ::connectTunnel,
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    preferences.filteringRequested = false
                    finishFiltering()
                    return START_NOT_STICKY
                }
                ACTION_FILTER -> preferences.filteringRequested = true
                null, SERVICE_INTERFACE, ACTION_RECOVER -> {
                    if (!preferences.filteringRequested) {
                        finishFiltering()
                        return START_NOT_STICKY
                    }
                }
                else -> return START_NOT_STICKY
            }
            if (prepare(this) != null) {
                finishWithoutConsent()
                return START_NOT_STICKY
            }
            ensureForeground()
            VpnRecovery.schedule(this)
            val current = session.currentConnection as? DnsTunnel
            if (current != null) {
                if (!current.isHealthy) retryAfterFailure(getString(R.string.vpn_tunnel_stalled))
            } else if (session.status.phase != VpnPhase.RECOVERING) {
                connectTunnel()
            }
        } catch (error: Exception) {
            // FGS 시작 자체가 거부되면 제한 시간 안에 서비스를 종료하고 시스템 알람으로 보완한다.
            val message = getString(R.string.vpn_failed, error.javaClass.simpleName)
            finishFiltering(message)
            runCatching { if (preferences.filteringRequested) VpnRecovery.schedule(this) }
        }
        // 메모리 회수·프로세스 종료 후 Android가 null Intent로 재생성하도록 요청한다.
        // https://developer.android.com/reference/android/app/Service#START_STICKY
        return if (preferences.filteringRequested) START_STICKY else START_NOT_STICKY
    }

    private fun connectTunnel() {
        if (!preferences.filteringRequested) {
            finishFiltering()
            return
        }
        if (prepare(this) != null) {
            finishWithoutConsent()
            return
        }
        session.starting()
        var unclaimed: ParcelFileDescriptor? = null
        try {
            ensureForeground()
            val descriptor = Builder()
                .setSession(getString(R.string.vpn_session))
                .setConfigureIntent(openAppIntent())
                .setMtu(32_767)
                .addAddress(YouTubeDnsFilter.VPN_ADDRESS, 32)
                .addDnsServer(YouTubeDnsFilter.DNS_ADDRESS)
                .addRoute(YouTubeDnsFilter.DNS_ADDRESS, 32)
                // 일반 IPv6 통신을 유지하며 DNS의 AAAA 질의는 가상 DNS에서 처리한다.
                .allowFamily(OsConstants.AF_INET6)
                .addDisallowedApplication(YouTubeDnsFilter.YOUTUBE_PACKAGE)
                .establish() ?: error(getString(R.string.vpn_permission_missing))
            unclaimed = descriptor
            lateinit var current: DnsTunnel
            current = DnsTunnel(
                this, descriptor,
                onBlocked = { mainExecutor.execute { session.recordBlocked(current) } },
                onResolveError = { mainExecutor.execute { session.recordResolveError(current) } },
                onFailure = { error -> mainExecutor.execute {
                    if (session.isCurrent(current)) {
                        retryAfterFailure(getString(R.string.vpn_failed, error.javaClass.simpleName))
                    }
                } },
            )
            session.connected(current)
            unclaimed = null
            current.start()
            reconnect.connected()
            updateNotification()
        } catch (error: Exception) {
            runCatching { unclaimed?.close() }
            if (error is android.content.pm.PackageManager.NameNotFoundException) {
                finishFiltering(getString(R.string.youtube_missing), withdrawRequest = true)
            } else {
                retryAfterFailure(getString(R.string.vpn_failed, error.javaClass.simpleName))
            }
        }
    }

    private fun retryAfterFailure(message: String) {
        session.stop(message)
        if (!preferences.filteringRequested) {
            finishFiltering()
            return
        }
        val delay = reconnect.retry()
        session.recovering(getString(R.string.vpn_recovery_detail, delay / 1_000, message))
        updateNotification()
        VpnRecovery.schedule(this)
    }

    private fun finishWithoutConsent() {
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        finishFiltering(getString(R.string.vpn_permission_missing), withdrawRequest = unlocked)
        if (!unlocked && preferences.filteringRequested) VpnRecovery.schedule(this)
    }

    private fun finishFiltering(error: String? = null, withdrawRequest: Boolean = false) {
        if (withdrawRequest) preferences.filteringRequested = false
        reconnect.stop()
        VpnRecovery.cancel(this)
        // 시스템 바인딩이 남아도 연결을 즉시 닫아 중지 완료를 onDestroy에 맡기지 않는다.
        session.stop(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onRevoke() {
        val revoked = session.currentConnection
        mainExecutor.execute {
            if ((revoked != null && session.isCurrent(revoked)) ||
                (revoked == null && prepare(this) != null)) {
                // 다른 VPN 선택·권한 회수는 재승인이 필요하다. 연결을 빼앗는 재시도는 하지 않는다.
                finishFiltering(getString(R.string.vpn_revoked), withdrawRequest = true)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (preferences.filteringRequested) VpnRecovery.schedule(this, 1_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val retryDelay = if (session.status.phase == VpnPhase.ERROR) 30_000L else 1_000L
        reconnect.stop()
        session.stop(session.status.error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // 일반 파괴를 보완한다. 강제 중지·즉시 프로세스 종료에는 이 콜백이 보장되지 않는다.
        runCatching { if (preferences.filteringRequested) VpnRecovery.schedule(this, retryDelay) }
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (foreground) return
        // 연결 승인을 받은 VPN 앱 자격으로 systemExempted를 사용한다.
        // https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        foreground = true
    }

    private fun updateNotification() {
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_vpn_status)
        .setContentTitle(getString(R.string.vpn_notification_title))
        .setContentText(getString(if (session.status.phase == VpnPhase.FILTERING)
            R.string.vpn_notification_text else R.string.vpn_recovering))
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .build()

    companion object {
        const val ACTION_FILTER = "com.hanter.haruguard.FILTER_YOUTUBE"
        const val ACTION_STOP = "com.hanter.haruguard.STOP_YOUTUBE_FILTER"
        const val ACTION_RECOVER = "com.hanter.haruguard.RECOVER_YOUTUBE_FILTER"
        private const val CHANNEL = "youtube_filter"
        private const val NOTIFICATION_ID = 1
    }
}
