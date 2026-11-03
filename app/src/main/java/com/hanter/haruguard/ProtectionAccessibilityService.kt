package com.hanter.haruguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** 부모가 직접 허용한 설정 화면 보호. OS 정책이 아니므로 완료된 강제 중지를 취소할 수 없다.
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
 */
class ProtectionAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var packageSurfaces = emptyMap<String, ProtectedScreenPolicy.Surface>()
    private var lastRedirect = -2_000L
    private var nextRecovery = 0L
    private var shield: View? = null
    private var shieldBounds: Rect? = null
    private var shieldConfirmedAt = 0L
    private var launcherTargetPackage: String? = null
    private var launcherTargetUntil = 0L
    private var launcherPopupConfirmed = false
    private val windowHints = mutableMapOf<Int, WindowHint>()
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                ParentAccess.lock()
                removeShield()
            } else {
                nextRecovery = 0
                inspectCurrentWindows()
            }
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            // 화면이 바뀌지 않아도 유지보수 만료 후 다시 보호한다. 깊은 절전은 깨우지 않는다.
            try {
                inspectCurrentWindows()
                if (SystemClock.elapsedRealtime() >= nextRecovery) {
                    nextRecovery = SystemClock.elapsedRealtime() + 5_000
                    try {
                        if (VpnPreferences(this@ProtectionAccessibilityService).filteringRequested) {
                            VpnRecovery.request(this@ProtectionAccessibilityService)
                        }
                    } catch (_: RuntimeException) {
                        // 저장소·제조사 서비스 오류가 화면 보호 서비스 전체를 종료시키지 않게 한다.
                    }
                }
            } finally {
                handler.postDelayed(this, 1_000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        connected = true
        packageSurfaces = buildPackageSurfaces()
        serviceInfo = serviceInfo.apply {
            packageNames = packageSurfaces.keys.toTypedArray()
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val sourcePackage = event.packageName?.toString().orEmpty()
            val surface = packageSurfaces[sourcePackage]
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
                surface == ProtectedScreenPolicy.Surface.LAUNCHER) {
                val source = event.source
                val labels = try {
                    if (source?.isPassword == true) emptyList() else listOfNotNull(
                        source?.text?.toString(), source?.contentDescription?.toString()) +
                        event.text.map { it.toString() }
                } finally { recycle(source) }
                launcherTargetPackage = sourcePackage.takeIf { ProtectedScreenPolicy.containsOurApp(labels) }
                launcherTargetUntil = SystemClock.elapsedRealtime() + 3_000
                launcherPopupConfirmed = false
            }
            val hint = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && surface != null) {
                WindowHint(sourcePackage, event.windowId, event.className?.toString().orEmpty(), event.eventTime)
                    .also {
                        if (windowHints.size >= 24) windowHints.clear()
                        windowHints[event.windowId] = it
                    }
            } else null
            // 알림·입력 본문은 저장하지 않는다. 트리 누락은 새 창 상태 이벤트로만 보완한다.
            inspectCurrentWindows(hint)
        } catch (_: RuntimeException) {
            // 다음 이벤트와 주기 검사에서 보호 대상 판정을 다시 시도한다.
            expireUnconfirmedShield()
        }
    }

    private fun shouldProtect(): Boolean = ParentAccess.isProtectionEnabled(this) &&
        !ParentAccess.isMaintenanceOpen() && getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun inspectCurrentWindows(freshHint: WindowHint? = null) {
        try {
            inspectWindows(freshHint)
        } catch (_: RuntimeException) {
            // OS에서 일시적으로 트리를 가져오지 못해도 같은 예외로 감시를 종료하지 않는다.
            expireUnconfirmedShield()
        }
    }

    private fun inspectWindows(freshHint: WindowHint?) {
        if (!shouldProtect()) {
            removeShield()
            return
        }
        // 앱 화면이 전면이어도 분할 화면 반대편에 설정이 남아 있으면 보호한다.
        val active = rootInActiveWindow
        val activePackage = active?.packageName?.toString()
        val activeWindowId = active?.windowId
        if (ProtectedScreenPolicy.isCriticalForeground(activePackage)) {
            recycle(active)
            removeShield()
            return
        }
        var protectedBounds: Rect? = null
        var foundProtected = false
        try {
            if (active != null && isProtected(active)) foundProtected = true
        } finally {
            recycle(active)
        }
        val visibleWindows = windows
        val visibleIds = visibleWindows.map { it.id }.toSet()
        windowHints.keys.removeAll { it !in visibleIds && it != activeWindowId }
        var matchingFocusedWindowWithoutRoot = false
        for (window in visibleWindows) {
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root
            try {
                if (root != null && isProtected(root)) {
                    foundProtected = true
                    if (activePackage == packageName) {
                        // 부모 PIN은 계속 누를 수 있도록 위험한 반대쪽 창만 가린다.
                        val bounds = Rect().also(window::getBoundsInScreen)
                        if (!bounds.isEmpty) {
                            if (protectedBounds == null) protectedBounds = bounds else protectedBounds.union(bounds)
                        }
                    }
                } else if (root == null && freshHint?.windowId == window.id &&
                    (window.isActive || window.isFocused)) {
                    matchingFocusedWindowWithoutRoot = true
                }
            } finally { recycle(root) }
        }
        if (!foundProtected && freshHint != null &&
            packageSurfaces[freshHint.packageName] in setOf(ProtectedScreenPolicy.Surface.SETTINGS,
                ProtectedScreenPolicy.Surface.VPN_DIALOG) &&
            ProtectedScreenPolicy.canUseMissingRootHint(
                SystemClock.uptimeMillis() - freshHint.eventUptime,
                matchingFocusedWindowWithoutRoot || (visibleWindows.isEmpty() && activePackage == null),
                activePackage != null && activePackage != freshHint.packageName,
            )) {
            foundProtected = true
        }
        if (foundProtected) {
            shieldConfirmedAt = SystemClock.elapsedRealtime()
            if (activePackage == packageName && protectedBounds != null) {
                showShield(protectedBounds)
            } else {
                redirectToParent()
            }
        } else {
            removeShield()
        }
    }

    private fun isProtected(root: AccessibilityNodeInfo): Boolean {
        val surface = packageSurfaces[root.packageName?.toString()] ?: return false
        if (surface == ProtectedScreenPolicy.Surface.SETTINGS ||
            surface == ProtectedScreenPolicy.Surface.VPN_DIALOG) return true
        val samples = boundedSamples(root)
        val hint = windowHints[root.windowId]?.takeIf {
            it.packageName == root.packageName?.toString() &&
                SystemClock.uptimeMillis() - it.eventUptime in 0..1_000
        }
        // 런처 팝업 이벤트는 홈 전체와 같은 창일 수 있으므로 전체 문구를 섞지 않는다.
        val hasWholeSurface = samples.any {
            it.viewId?.substringAfter(":id/") in setOf("notification_stack_scroller", "notification_panel",
                "notification_container_parent", "notification_shade", "workspace")
        }
        val rootClass = root.className?.toString().orEmpty() +
            if (surface == ProtectedScreenPolicy.Surface.LAUNCHER || hasWholeSurface) "" else " " + hint?.className.orEmpty()
        if (ProtectedScreenPolicy.shouldBlock(surface, rootClass,
                samples.flatMap { it.labels }, samples.mapNotNull { it.viewId })) return true
        val scopes = samples.indices.filter { index ->
            ProtectedScreenPolicy.isScopedContainer(samples[index].className, samples[index].viewId)
        }.take(6)
        val isLauncherTarget = surface == ProtectedScreenPolicy.Surface.LAUNCHER &&
            root.packageName?.toString() == launcherTargetPackage &&
            (launcherPopupConfirmed || SystemClock.elapsedRealtime() <= launcherTargetUntil)
        if (surface == ProtectedScreenPolicy.Surface.LAUNCHER &&
            root.packageName?.toString() == launcherTargetPackage) {
            if (scopes.isNotEmpty() && isLauncherTarget) launcherPopupConfirmed = true
            else if (SystemClock.elapsedRealtime() > launcherTargetUntil) {
                launcherTargetPackage = null
                launcherPopupConfirmed = false
            }
        }
        return scopes.any { ancestor ->
            val scoped = samples.filterIndexed { index, _ ->
                var parent = index
                while (parent >= 0 && parent != ancestor) parent = samples[parent].parent
                parent == ancestor
            }
            ProtectedScreenPolicy.shouldBlock(surface, samples[ancestor].className,
                scoped.flatMap { it.labels }, scoped.mapNotNull { it.viewId },
                scopedContent = true, longPressedOurApp = isLauncherTarget)
        }
    }

    private fun boundedSamples(root: AccessibilityNodeInfo): List<NodeSample> {
        val samples = ArrayList<NodeSample>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to -1)
        try {
            while (pending.isNotEmpty() && samples.size < 120) {
                val (node, parent) = pending.removeFirst()
                try {
                    val index = samples.size
                    val labels = if (node.isPassword) emptyList() else listOfNotNull(
                        node.text?.take(180)?.toString(), node.contentDescription?.take(180)?.toString())
                    samples.add(NodeSample(parent, labels, node.viewIdResourceName, node.className?.toString().orEmpty()))
                    for (child in 0 until minOf(node.childCount, 30)) {
                        if (pending.size + samples.size >= 120) break
                        node.getChild(child)?.let { pending.add(it to index) }
                    }
                } finally { if (node !== root) recycle(node) }
            }
        } finally {
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst().first
                if (node !== root) recycle(node)
            }
        }
        return samples
    }

    private fun redirectToParent() {
        val now = SystemClock.elapsedRealtime()
        showShield()
        if (now - lastRedirect < 1_500) return
        lastRedirect = now
        // 자동으로 시스템 버튼을 누르거나 동의를 대신하지 않는다. 위험 화면에서만 벗어난다.
        performGlobalAction(GLOBAL_ACTION_HOME)
        launchParent()
        handler.postDelayed({ inspectCurrentWindows() }, 250)
    }

    private fun showShield(bounds: Rect? = null) {
        if (shield != null && shieldBounds == bounds) return
        removeShield()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.rgb(24, 36, 48))
            isClickable = true
            addView(TextView(this@ProtectionAccessibilityService).apply {
                text = getString(R.string.parent_required)
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
            })
            // 앱이 열리지 않는 기기에서도 부모/홈으로 이동할 수 있는 실제 조작을 남긴다.
            addView(Button(this@ProtectionAccessibilityService).apply {
                text = "부모 인증 열기"
                setOnClickListener { launchParent(); handler.postDelayed({ inspectCurrentWindows() }, 250) }
            })
            addView(Button(this@ProtectionAccessibilityService).apply {
                text = "홈으로"
                setOnClickListener {
                    if (performGlobalAction(GLOBAL_ACTION_HOME)) removeShield()
                }
            })
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, WindowManager.LayoutParams(
                bounds?.width() ?: WindowManager.LayoutParams.MATCH_PARENT,
                bounds?.height() ?: WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds?.left ?: 0
                y = bounds?.top ?: 0
            })
            shield = view
            shieldBounds = bounds?.let(::Rect)
            handler.postDelayed({ expireUnconfirmedShield() }, 2_000)
        } catch (_: RuntimeException) {
            // 오버레이가 지원되지 않아도 홈과 부모 화면으로 이동은 계속 시도한다.
        }
    }

    private fun launchParent() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: RuntimeException) {
            // 기기 제한으로 부모 화면 실행이 거부되어도 화면의 홈 이동 경로는 남긴다.
        }
    }

    private fun expireUnconfirmedShield() {
        // 확인할 수 없는 화면을 무기한 덮지 않는다. 다음 정상 이벤트에서 필요하면 다시 보호한다.
        if (SystemClock.elapsedRealtime() - shieldConfirmedAt >= 2_000) removeShield()
    }

    private fun removeShield() {
        shield?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: RuntimeException) {
                // 시스템이 이미 창을 회수했으면 참조만 정리한다.
            }
        }
        shield = null
        shieldBounds = null
    }

    private fun buildPackageSurfaces(): Map<String, ProtectedScreenPolicy.Surface> = buildMap {
        val settings = ProtectedScreenPolicy.Surface.SETTINGS
        listOf("com.android.settings", "com.samsung.android.settings", "com.samsung.android.lool",
            "com.samsung.android.sm", "com.google.android.apps.wellbeing").forEach { put(it, settings) }
        // 제조사별 기본 설정 앱도 시스템이 반환한 패키지로 추가한다.
        packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
            ?.let { put(it, settings) }
        put("com.android.vpndialogs", ProtectedScreenPolicy.Surface.VPN_DIALOG)
        put("com.android.systemui", ProtectedScreenPolicy.Surface.SYSTEM_UI)
        listOf("com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.samsung.android.packageinstaller").forEach { put(it, ProtectedScreenPolicy.Surface.INSTALLER) }
        listOf("com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher").forEach { put(it, ProtectedScreenPolicy.Surface.LAUNCHER) }
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .forEach { putIfAbsent(it.activityInfo.packageName, ProtectedScreenPolicy.Surface.LAUNCHER) }
    }

    override fun onInterrupt() { removeShield() }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        connected = false
        instance.clear()
        handler.removeCallbacksAndMessages(null)
        removeShield()
        windowHints.clear()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) { node?.recycle() }

    private data class WindowHint(val packageName: String, val windowId: Int, val className: String, val eventUptime: Long)
    private data class NodeSample(val parent: Int, val labels: List<String>, val viewId: String?, val className: String)

    companion object {
        @Volatile var connected: Boolean = false
            private set
        private var instance = WeakReference<ProtectionAccessibilityService>(null)

        fun isEnabled(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == ProtectionAccessibilityService::class.java.name }

        fun disableForParent(): Boolean {
            if (!ParentAccess.isAuthenticated()) return false
            val service = instance.get() ?: return false
            service.disableSelf()
            return true
        }
    }
}
