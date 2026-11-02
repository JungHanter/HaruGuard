package com.hanter.haruguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanter.haruguard.vpn.DnsTunnel
import com.hanter.haruguard.vpn.YouTubeDnsFilter

/** 저장된 선택값과 구분하여 프로세스 내 실제 VPN 상태를 표시한다. */
object VpnRuntime {
    var status by mutableStateOf(VpnStatus())
        internal set
}

class YouTubeVpnService : VpnService() {
    private val preferences by lazy { VpnPreferences(this) }
    private val session = VpnSession(
        publish = { VpnRuntime.status = it },
        describeFailure = { getString(R.string.vpn_failed, it.javaClass.simpleName) },
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                preferences.filteringRequested = false
                stopFiltering()
                return START_NOT_STICKY
            }
            ACTION_FILTER -> preferences.filteringRequested = true
            null, SERVICE_INTERFACE -> {
                // 관리자 VPN의 시스템 시작은 마지막 선택을 복원하며, 꺼 둔 필터를 임의로 켜지 않는다.
                if (!preferences.filteringRequested) {
                    stopFiltering()
                    return START_NOT_STICKY
                }
            }
            else -> return START_NOT_STICKY
        }
        if (session.isRunning) return START_NOT_STICKY
        session.starting()
        var unclaimedDescriptor: ParcelFileDescriptor? = null
        try {
            val device = DpcController(this).readState()
            check(device.isDeviceOwner) { getString(R.string.do_required) }
            val notification = notification()
            // DO는 systemExempted 포그라운드 서비스의 공식 허용 조건이다.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val descriptor = Builder()
                .setSession(getString(R.string.vpn_session))
                .setConfigureIntent(openAppIntent())
                .setMtu(32_767)
                .addAddress(YouTubeDnsFilter.VPN_ADDRESS, 32)
                .addDnsServer(YouTubeDnsFilter.DNS_ADDRESS)
                .addRoute(YouTubeDnsFilter.DNS_ADDRESS, 32)
                // 일반 IPv6 연결은 유지하며 AAAA DNS 질의는 가상 DNS 경로에서 처리한다.
                .allowFamily(OsConstants.AF_INET6)
                .addDisallowedApplication(YouTubeDnsFilter.YOUTUBE_PACKAGE)
                .establish() ?: error(getString(R.string.vpn_permission_missing))
            unclaimedDescriptor = descriptor

            lateinit var current: DnsTunnel
            current = DnsTunnel(
                this,
                descriptor,
                onBlocked = { mainExecutor.execute {
                    session.recordBlocked(current)
                } },
                onResolveError = { mainExecutor.execute {
                    session.recordResolveError(current)
                } },
                onFailure = { error -> mainExecutor.execute {
                    if (session.isCurrent(current)) fail(getString(R.string.vpn_failed, error.javaClass.simpleName))
                } },
            )
            session.connected(current)
            unclaimedDescriptor = null // 생성에 성공한 DnsTunnel이 연결 자원의 정리를 맡는다.
            current.start()
        } catch (error: Exception) {
            runCatching { unclaimedDescriptor?.close() }
            val message = when (error) {
                is android.content.pm.PackageManager.NameNotFoundException -> getString(R.string.youtube_missing)
                else -> getString(R.string.vpn_failed, error.javaClass.simpleName)
            }
            fail(message)
        }
        // 변경 잠금이 없으면 수동 시작하며, 관리자 VPN의 시스템 시작은 저장된 선택을 따른다.
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        stopFiltering(message)
    }

    private fun stopFiltering(error: String? = null) {
        // 연결된 VPN은 시스템 바인딩으로 유지되므로 onDestroy를 기다리지 않고 TUN을 닫는다.
        // https://developer.android.com/reference/android/net/VpnService.Builder#establish()
        session.stop(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        val revoked = session.currentConnection
        mainExecutor.execute {
            if (revoked != null && session.isCurrent(revoked)) {
                preferences.filteringRequested = false
                fail(getString(R.string.vpn_revoked))
            }
        }
    }

    override fun onDestroy() {
        session.stop(session.status.error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, YouTubeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.youtube_allow_all), stop).build())
            .build()
    }

    companion object {
        const val ACTION_FILTER = "com.hanter.haruguard.FILTER_YOUTUBE"
        const val ACTION_STOP = "com.hanter.haruguard.STOP_YOUTUBE_FILTER"
        private const val CHANNEL = "youtube_filter"
        private const val NOTIFICATION_ID = 1
    }
}
