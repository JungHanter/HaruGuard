package com.hanter.haruguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.hanter.haruguard.vpn.DnsPackets
import com.hanter.haruguard.vpn.DomainRouting
import com.hanter.haruguard.vpn.FilterPolicy
import com.hanter.haruguard.vpn.FilterSettings
import java.lang.ref.WeakReference

/** 저장된 선택값과 구분하여 실제 VPN 연결 상태를 표시한다. */
object VpnRuntime {
    var status by mutableStateOf(VpnStatus())
        internal set
    var isAlwaysOn by mutableStateOf<Boolean?>(null)
        internal set
    var isLockdownEnabled by mutableStateOf<Boolean?>(null)
        internal set
}

class YouTubeVpnService : VpnService() {
    private val preferences by lazy { VpnPreferences(this) }
    private val filterPreferences by lazy { FilterPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var destroyed = false
    private var monitoring = false
    private var networkCallbackRegistered = false
    private var unlockReceiverRegistered = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val session = VpnSession(
        publish = { VpnRuntime.status = it },
        describeFailure = { getString(R.string.vpn_failed, it.javaClass.simpleName) },
    )
    private val reconnect = VpnReconnectLoop(
        now = SystemClock::elapsedRealtime,
        shouldRun = { preferences.filteringRequested },
        schedule = { delay, action -> reconnectHandler.postDelayed({ action() }, delay) },
        cancelScheduled = { reconnectHandler.removeCallbacksAndMessages(null) },
        connect = ::connectTunnel,
    )
    private val healthCheck = object : Runnable {
        override fun run() {
            if (!monitoring) return
            checkConnectionSafely()
            if (monitoring) handler.postDelayed(this, HEALTH_CHECK_MS)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 오프라인 동안 기다리던 재연결은 물리 네트워크가 돌아오면 즉시 다시 시도한다.
            // 정상 터널은 DNS 질의마다 현재 네트워크를 선택하므로 불필요하게 재생성하지 않는다.
            checkConnectionSafely(networkReturned = true)
        }

        override fun onLost(network: Network) {
            checkConnectionSafely()
        }
    }
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED || intent.action == Intent.ACTION_USER_PRESENT) {
                checkConnectionSafely()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningService = WeakReference(this)
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
            checkConnection()
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

    private fun checkConnection(networkReturned: Boolean = false) {
        if (!foreground || destroyed) return
        if (!preferences.filteringRequested) {
            finishFiltering()
            return
        }
        if (prepare(this) != null) {
            finishWithoutConsent()
            return
        }
        updateSystemVpnState()
        val current = session.currentConnection as? DnsTunnel
        if (current != null) {
            if (!current.isHealthy) retryAfterFailure(getString(R.string.vpn_tunnel_stalled))
            else current.applySettings(filterPreferences.load())
        } else if (session.status.phase != VpnPhase.RECOVERING || networkReturned) {
            if (networkReturned) reconnect.stop()
            connectTunnel()
        }
    }

    private fun checkConnectionSafely(networkReturned: Boolean = false) {
        try {
            checkConnection(networkReturned)
        } catch (error: Exception) {
            finishFiltering(getString(R.string.vpn_failed, error.javaClass.simpleName))
            runCatching { scheduleIfRecoverable() }
        }
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
            val policy = FilterPolicy(filterPreferences.load())
            val descriptor = Builder()
                .apply {
                    // 결제·인증 앱은 DNS와 데이터 통신 모두 VPN에서 제외한다.
                    // https://developer.android.com/develop/connectivity/vpn#disallowed-apps
                    VPN_EXCLUDED_PACKAGES.forEach { packageName ->
                        try {
                            addDisallowedApplication(packageName)
                        } catch (_: PackageManager.NameNotFoundException) {
                            // 미설치 앱 하나 때문에 다른 앱의 VPN 필터가 중단되지 않게 한다.
                        }
                    }
                }
                .setSession(getString(R.string.vpn_session))
                .setConfigureIntent(openAppIntent())
                .setMtu(32_767)
                .addAddress(DnsPackets.VPN_ADDRESS, 32)
                .addDnsServer(DnsPackets.DNS_ADDRESS)
                .addRoute(DnsPackets.DNS_ADDRESS, 32)
                .addRoute(DomainRouting.ROUTE, DomainRouting.PREFIX)
                // 일반 IPv6 통신을 유지하며 DNS의 AAAA 질의는 가상 DNS에서 처리한다.
                .allowFamily(OsConstants.AF_INET6)
                .establish() ?: error(getString(R.string.vpn_permission_missing))
            unclaimed = descriptor
            lateinit var current: DnsTunnel
            current = DnsTunnel(
                this, descriptor, policy,
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
            retryAfterFailure(getString(R.string.vpn_failed, error.javaClass.simpleName))
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
        finishFiltering(getString(R.string.vpn_permission_missing))
        scheduleIfRecoverable()
    }

    private fun finishFiltering(error: String? = null, withdrawRequest: Boolean = false) {
        if (withdrawRequest) preferences.filteringRequested = false
        reconnect.stop()
        stopMonitoring()
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
                finishFiltering(getString(R.string.vpn_revoked))
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { scheduleIfRecoverable(1_000) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        if (runningService?.get() === this) runningService = null
        val retryDelay = if (session.status.phase == VpnPhase.ERROR) 30_000L else 1_000L
        reconnect.stop()
        stopMonitoring()
        session.stop(session.status.error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // 일반 파괴를 보완한다. 강제 중지·즉시 프로세스 종료에는 이 콜백이 보장되지 않는다.
        runCatching { scheduleIfRecoverable(retryDelay) }
        VpnRuntime.isAlwaysOn = null
        VpnRuntime.isLockdownEnabled = null
        super.onDestroy()
    }

    private fun scheduleIfRecoverable(delayMs: Long = 30_000) {
        if (preferences.filteringRequested && (prepare(this) == null ||
                !getSystemService(UserManager::class.java).isUserUnlocked)) {
            VpnRecovery.schedule(this, delayMs)
        }
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
        updateSystemVpnState()
        startMonitoring()
    }

    private fun updateSystemVpnState() {
        VpnRuntime.isAlwaysOn = runCatching { isAlwaysOn }.getOrNull()
        VpnRuntime.isLockdownEnabled = runCatching { isLockdownEnabled }.getOrNull()
    }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        handler.postDelayed(healthCheck, HEALTH_CHECK_MS)
        // VPN 자신의 가상 네트워크 변화로 재연결이 반복되지 않도록 물리 네트워크만 감시한다.
        // https://developer.android.com/develop/connectivity/network-ops/reading-network-state
        networkCallbackRegistered = runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), networkCallback, handler,
            )
            true
        }.getOrDefault(false)
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED).apply { addAction(Intent.ACTION_USER_PRESENT) }
        // USER_UNLOCKED는 manifest 수신기가 아닌 실행 중 등록한 수신기에만 전달된다.
        // https://developer.android.com/reference/android/content/Intent#ACTION_USER_UNLOCKED
        unlockReceiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(unlockReceiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    private fun stopMonitoring() {
        monitoring = false
        handler.removeCallbacksAndMessages(null)
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (unlockReceiverRegistered) runCatching { unregisterReceiver(unlockReceiver) }
        networkCallbackRegistered = false
        unlockReceiverRegistered = false
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
        private const val HEALTH_CHECK_MS = 5_000L
        // 공식 스토어에서 확인한 결제·인증 앱 패키지만 제외한다.
        // https://galaxystore.samsung.com/detail/com.samsung.android.spay
        // https://galaxystore.samsung.com/detail/com.samsung.android.samsungpass
        // https://galaxystore.samsung.com/detail/com.samsung.android.samsungpassautofill
        // https://play.google.com/store/apps/details?id=com.kt.ktauth
        private val VPN_EXCLUDED_PACKAGES = listOf(
            "com.samsung.android.spay", // 삼성월렛
            "com.samsung.android.samsungpass", // 삼성 Pass
            "com.samsung.android.samsungpassautofill", // Samsung Pass 자동 입력
            "com.kt.ktauth", // PASS by KT
        )
        private var runningService: WeakReference<YouTubeVpnService>? = null

        /** 부모의 VPN OFF 선택을 바꾸거나 새 서비스를 시작하지 않고 실행 중 정책만 교체한다. */
        internal fun applyFilterSettings(settings: FilterSettings): Boolean {
            val service = runningService?.get() ?: return false
            if (!service.foreground || service.destroyed) return false
            val tunnel = service.session.currentConnection as? DnsTunnel ?: return false
            if (!tunnel.isHealthy) return false
            tunnel.applySettings(settings)
            return true
        }

        /** 같은 프로세스의 접근성 감시·알람은 실행 중인 FGS를 중복 시작하지 않는다. */
        internal fun checkRunningService(): Boolean {
            val service = runningService?.get() ?: return false
            if (!service.foreground || service.destroyed) return false
            service.handler.post { service.checkConnectionSafely() }
            return true
        }
    }
}
