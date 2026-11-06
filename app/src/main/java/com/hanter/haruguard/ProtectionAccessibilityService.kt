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
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** 화면 내용 없이 보호 분류만 부모 화면에 표시한다. 프로세스 종료 시 지워진다. */
internal object ProtectionRuntime {
    var lastBlock by mutableStateOf<SettingsScreenPolicy.Block?>(null)
}

/** 부모가 직접 허용한 설정 화면 보호. OS 정책이 아니므로 완료된 강제 중지를 취소할 수 없다.
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
 */
class ProtectionAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var packageSurfaces = emptyMap<String, ProtectedScreenPolicy.Surface>()
    private val navigation = BlockNavigation()
    private val appSelection = AppSelectionGuard()
    private var notice: Toast? = null
    private var nativeNoticeVisible = false
    private var noticeBanner: View? = null
    private var preparedNoticeBanner: View? = null
    private var nextRecovery = 0L
    private var shield: View? = null
    private var preparedShield: View? = null
    private var shieldBounds: Rect? = null
    private var shieldConfirmedAt = 0L
    private var launcherTargetPackage: String? = null
    private var launcherTargetUntil = 0L
    private var launcherPopupConfirmed = false
    private val windowHints = mutableMapOf<Int, WindowHint>()
    private var receiverRegistered = false
    private val recheck = Runnable { inspectCurrentWindows() }
    private val shieldExpiry = Runnable { expireUnconfirmedShield() }
    private val fallbackNotice = Runnable { runCatching { if (!nativeNoticeVisible) showNoticeBanner() } }
    private val hideNoticeBanner = Runnable { removeNoticeBanner() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                ParentAccess.lock()
                appSelection.clear()
                removeShield()
                removeNoticeBanner()
                handler.removeCallbacks(fallbackNotice)
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
            // 같은 종류의 이벤트가 잠잠해지기를 기다리지 않고 보호 화면 전환을 바로 받는다.
            // https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout
            notificationTimeout = 0
        }
        preparedShield = createShield()
        preparedNoticeBanner = createNoticeBanner()
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
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && surface == ProtectedScreenPolicy.Surface.SETTINGS &&
                shouldProtect() && !ParentAccess.isMaintenanceOpen() && SystemClock.uptimeMillis() - event.eventTime in 0..200) {
                val source = event.source
                val labels = try {
                    if (source?.isPassword == true || source?.isEditable == true) emptyList() else
                        event.text.map { it.toString() } + listOfNotNull(source?.text?.toString(), source?.contentDescription?.toString())
                } finally { recycle(source) }
                val now = SystemClock.elapsedRealtime()
                if (appSelection.clicked(event.windowId, labels, now)) {
                    shieldConfirmedAt = now
                    showShield()
                    scheduleRecheck()
                }
            }
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
        getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun settingsBlock(screen: SettingsScreenPolicy.Screen): SettingsScreenPolicy.Block? {
        // 읽기만 수행한다. 개발자옵션·USB 디버깅의 실제 설정값은 변경하지 않는다.
        // https://developer.android.com/reference/android/provider/Settings.Global#DEVELOPMENT_SETTINGS_ENABLED
        val developerEnabled = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        }.getOrDefault(false)
        return SettingsScreenPolicy.block(screen, developerEnabled, ParentAccess.isMaintenanceOpen())
    }

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
            appSelection.clear()
            removeShield()
            removeNoticeBanner()
            handler.removeCallbacks(fallbackNotice)
            navigation.clear()
            return
        }
        // 앱 화면이 전면이어도 분할 화면 반대편에 설정이 남아 있으면 보호한다.
        val active = rootInActiveWindow
        val activePackage = active?.packageName?.toString()
        val activeWindowId = active?.windowId
        if (ProtectedScreenPolicy.isCriticalForeground(activePackage)) {
            recycle(active)
            appSelection.clear()
            removeShield()
            removeNoticeBanner()
            handler.removeCallbacks(fallbackNotice)
            navigation.clear()
            return
        }
        var protectedBounds: Rect? = null
        var foundTarget: BlockNavigation.Target? = null
        try {
            if (active != null) foundTarget = protectedTarget(active)
        } finally {
            recycle(active)
        }
        // 전면 보호 화면이 확인되면 다른 창을 모두 읽기 전에 터치 차단·복귀부터 실행한다.
        foundTarget?.let { leaveProtectedScreen(it); return }
        val visibleWindows = windows
        val visibleIds = visibleWindows.map { it.id }.toSet()
        windowHints.keys.removeAll { it !in visibleIds && it != activeWindowId }
        var matchingFocusedWindowWithoutRoot = false
        for (window in visibleWindows) {
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root
            try {
                val target = root?.let { protectedTarget(it, window.title?.toString()) }
                if (target != null) {
                    foundTarget = target
                    if (activePackage == packageName) {
                        // 부모 PIN은 계속 누를 수 있도록 위험한 반대쪽 창만 가린다.
                        val bounds = Rect().also(window::getBoundsInScreen)
                        if (!bounds.isEmpty) {
                            if (protectedBounds == null) protectedBounds = bounds else protectedBounds.union(bounds)
                        }
                    } else {
                        leaveProtectedScreen(target)
                        return
                    }
                } else if (root == null && freshHint?.windowId == window.id &&
                    (window.isActive || window.isFocused)) {
                    matchingFocusedWindowWithoutRoot = true
                }
            } finally { recycle(root) }
        }
        // 트리를 못 읽었다고 Settings 전체로 보호 범위를 확대하지 않는다.
        // 실제 VPN·스토어 패키지나 관리자·접근성 등의 클래스가 확인될 때만 보완한다.
        val hintedBlock = freshHint?.let {
            val surface = packageSurfaces[it.packageName]
            if (surface == ProtectedScreenPolicy.Surface.SETTINGS)
                settingsBlock(SettingsScreenPolicy.Screen(className = it.className))
            else ProtectedScreenPolicy.blockWithoutContent(surface, ParentAccess.isMaintenanceOpen())
        }
        if (foundTarget == null && freshHint != null && hintedBlock != null &&
            ProtectedScreenPolicy.canUseMissingRootHint(
                SystemClock.uptimeMillis() - freshHint.eventUptime,
                matchingFocusedWindowWithoutRoot || (visibleWindows.isEmpty() && activePackage == null),
                activePackage != null && activePackage != freshHint.packageName,
            )) {
            foundTarget = BlockNavigation.Target(freshHint.windowId, hintedBlock.area, freshHint.className.hashCode())
            ProtectionRuntime.lastBlock = hintedBlock
        }
        val target = foundTarget
        if (target != null) {
            appSelection.clear()
            shieldConfirmedAt = SystemClock.elapsedRealtime()
            if (activePackage == packageName && protectedBounds != null) {
                showShield(protectedBounds)
            } else {
                leaveProtectedScreen(target)
            }
        } else {
            // 화면 전환 중의 빈 트리 때문에 가림막을 내렸다 올리는 틈을 만들지 않는다.
            // 정상 설정은 길게 가리지 않고, 직전 보호 화면 뒤에는 80ms만 보완한다.
            val now = SystemClock.elapsedRealtime()
            if (shield != null && (activePackage == null || packageSurfaces[activePackage] == ProtectedScreenPolicy.Surface.SETTINGS) &&
                (appSelection.isPending(now) || now - shieldConfirmedAt < 80)) {
                scheduleRecheck()
                return
            }
            removeShield()
            navigation.clear()
        }
    }

    private fun protectedTarget(root: AccessibilityNodeInfo, windowTitle: String? = null): BlockNavigation.Target? {
        val surface = packageSurfaces[root.packageName?.toString()] ?: return null
        // 5분 개방에서는 개발자옵션 활성화만 예외적으로 계속 보호한다.
        if (ParentAccess.isMaintenanceOpen() && surface != ProtectedScreenPolicy.Surface.SETTINGS) return null
        val target = BlockNavigation.Target(root.windowId)
        ProtectedScreenPolicy.blockWithoutContent(surface, ParentAccess.isMaintenanceOpen())?.let {
            ProtectionRuntime.lastBlock = it
            return target.copy(area = it.area)
        }
        val samples = boundedSamples(root)
        val hint = windowHints[root.windowId]?.takeIf {
            it.packageName == root.packageName?.toString() &&
                SystemClock.uptimeMillis() - it.eventUptime in 0..1_000
        }
        if (surface == ProtectedScreenPolicy.Surface.SETTINGS) {
            val pageClass = root.className?.toString().orEmpty() + " " + hint?.className.orEmpty()
            val page = SettingsScreenPolicy.screen(pageClass, samples, windowTitle)
            val block = settingsBlock(page)
            appSelection.observe(root.windowId, page, SystemClock.elapsedRealtime())
            if (block != null) ProtectionRuntime.lastBlock = block
            // 원문 제목은 보관하지 않고 같은 페이지의 중복 이벤트 판별용 숫자만 쓴다.
            return block?.let { BlockNavigation.Target(root.windowId, it.area, page.headings.ifEmpty { listOf(page.className) }.hashCode()) }
        }
        // 런처 팝업 이벤트는 홈 전체와 같은 창일 수 있으므로 전체 문구를 섞지 않는다.
        val hasWholeSurface = samples.any {
            it.viewId?.substringAfter(":id/") in setOf("notification_stack_scroller", "notification_panel",
                "notification_container_parent", "notification_shade", "workspace")
        }
        val rootClass = root.className?.toString().orEmpty() +
            if (surface == ProtectedScreenPolicy.Surface.LAUNCHER || hasWholeSurface) "" else " " + hint?.className.orEmpty()
        if (ProtectedScreenPolicy.shouldBlock(surface, rootClass,
                samples.flatMap { it.labels }, samples.mapNotNull { it.viewId })) return target
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
        return target.takeIf { scopes.any { ancestor ->
            val scoped = samples.filterIndexed { index, _ ->
                var parent = index
                while (parent >= 0 && parent != ancestor) parent = samples[parent].parent
                parent == ancestor
            }
            ProtectedScreenPolicy.shouldBlock(surface, samples[ancestor].className,
                scoped.flatMap { it.labels }, scoped.mapNotNull { it.viewId },
                scopedContent = true, longPressedOurApp = isLauncherTarget)
        } }
    }

    private fun boundedSamples(root: AccessibilityNodeInfo): List<SettingsScreenPolicy.Node> {
        val samples = ArrayList<SettingsScreenPolicy.Node>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to -1)
        try {
            while (pending.isNotEmpty() && samples.size < 120) {
                val (node, parent) = pending.removeFirst()
                try {
                    val index = samples.size
                    val labels = if (node.isPassword || node.isEditable) emptyList() else listOfNotNull(
                        node.text?.take(180)?.toString(), node.contentDescription?.take(180)?.toString())
                    samples.add(SettingsScreenPolicy.Node(parent, labels, node.viewIdResourceName, node.className?.toString().orEmpty(),
                        heading = node.isHeading, collection = node.collectionInfo != null,
                        checkable = node.isCheckable, paneTitle = node.paneTitle?.toString(), editable = node.isEditable))
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

    private fun scheduleRecheck() {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 40)
    }

    private fun leaveProtectedScreen(target: BlockNavigation.Target) {
        val now = SystemClock.elapsedRealtime()
        appSelection.clear()
        shieldConfirmedAt = now
        showShield()
        // 먼저 이전 설정으로 돌아가 Wi-Fi 등 나머지 메뉴를 계속 사용할 수 있게 한다.
        // 뒤로 이동이 실패하거나 같은 보호 화면이 남았을 때만 홈으로 나간다.
        // https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
        when (navigation.blocked(now, target)) {
            BlockNavigation.Action.BACK -> {
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) performGlobalAction(GLOBAL_ACTION_HOME)
            }
            BlockNavigation.Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            BlockNavigation.Action.WAIT -> Unit
        }
        scheduleRecheck()
    }

    private fun showShield(bounds: Rect? = null) {
        handler.removeCallbacks(shieldExpiry)
        handler.postDelayed(shieldExpiry, 350)
        if (shield != null && shieldBounds == bounds) return
        if (shield != null) {
            try {
                getSystemService(WindowManager::class.java).updateViewLayout(shield, shieldLayout(bounds))
                shieldBounds = bounds?.let(::Rect)
                return
            } catch (_: RuntimeException) { removeShield() }
        }
        removeShield()
        // 모든 가림막 진입 경로(분할 화면 포함)가 같은 Toast 요청을 사용한다.
        // 같은 객체를 재사용하고 표시 중인 알림을 취소하지 않는다. OS의 표시 제한은 남는다.
        // https://developer.android.com/reference/android/widget/Toast#show()
        val view = preparedShield ?: createShield().also { preparedShield = it }
        try {
            getSystemService(WindowManager::class.java).addView(view, shieldLayout(bounds))
            shield = view
            shieldBounds = bounds?.let(::Rect)
            handler.postDelayed(shieldExpiry, 350)
        } catch (_: RuntimeException) {
            // 가림막을 못 띄워도 Toast 요청과 BACK/HOME은 계속 시도한다.
        }
        showBlockedNotice()
    }

    private fun showBlockedNotice() {
        handler.removeCallbacks(fallbackNotice)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val toast = notice ?: Toast.makeText(this, R.string.setting_blocked_notice, Toast.LENGTH_SHORT).apply {
                    addCallback(object : Toast.Callback() {
                        override fun onToastShown() {
                            nativeNoticeVisible = true
                            handler.removeCallbacks(fallbackNotice)
                            removeNoticeBanner()
                        }
                        override fun onToastHidden() { nativeNoticeVisible = false }
                    })
                }.also { notice = it }
                toast.show()
            }
            // OS가 Toast를 제한하거나 표시를 늦추면 입력을 막지 않는 짧은 안내로 보완한다.
            handler.postDelayed(fallbackNotice, 100)
        } else runCatching { showNoticeBanner() }
    }

    private fun createNoticeBanner(): View = TextView(this).apply {
        text = getString(R.string.setting_blocked_notice)
        textSize = 14f
        maxLines = 2
        maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        setTextColor(android.graphics.Color.WHITE)
        val inset = (12 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(40, 40, 40))
            cornerRadius = 12 * resources.displayMetrics.density
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun showNoticeBanner() {
        if (!shouldProtect()) return
        val active = rootInActiveWindow
        try {
            if (ProtectedScreenPolicy.isCriticalForeground(active?.packageName?.toString())) return
        } finally { recycle(active) }
        if (noticeBanner == null) {
            val view = preparedNoticeBanner ?: createNoticeBanner().also { preparedNoticeBanner = it }
            try {
                getSystemService(WindowManager::class.java).addView(view, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (64 * resources.displayMetrics.density).toInt()
                })
                noticeBanner = view
            } catch (_: RuntimeException) { return }
        }
        handler.removeCallbacks(hideNoticeBanner)
        handler.postDelayed(hideNoticeBanner, 800)
    }

    private fun removeNoticeBanner() {
        handler.removeCallbacks(hideNoticeBanner)
        noticeBanner?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
        noticeBanner = null
    }

    private fun createShield(): View {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.rgb(24, 36, 48))
            isClickable = true
            addView(TextView(this@ProtectionAccessibilityService).apply {
                text = getString(R.string.setting_blocked_notice)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
            })
            // 앱이 열리지 않는 기기에서도 부모/홈으로 이동할 수 있는 실제 조작을 남긴다.
            addView(Button(this@ProtectionAccessibilityService).apply {
                text = "부모 인증 열기"
                setOnClickListener { launchParent(); scheduleRecheck() }
            })
            addView(Button(this@ProtectionAccessibilityService).apply {
                text = "홈으로"
                setOnClickListener {
                    if (performGlobalAction(GLOBAL_ACTION_HOME)) removeShield()
                }
            })
        }
        return view
    }

    private fun shieldLayout(bounds: Rect?): WindowManager.LayoutParams = WindowManager.LayoutParams(
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
        val remaining = 350 - (SystemClock.elapsedRealtime() - shieldConfirmedAt)
        if (remaining <= 0) removeShield() else {
            handler.removeCallbacks(shieldExpiry)
            handler.postDelayed(shieldExpiry, remaining)
        }
    }

    private fun removeShield() {
        handler.removeCallbacks(shieldExpiry)
        shield?.let {
            try { getSystemService(WindowManager::class.java).removeViewImmediate(it) } catch (_: RuntimeException) {
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
        ProtectedScreenPolicy.storePackages.forEach { put(it, ProtectedScreenPolicy.Surface.STORE) }
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
        navigation.clear()
        appSelection.clear()
        notice?.cancel()
        removeShield()
        preparedShield = null
        removeNoticeBanner()
        preparedNoticeBanner = null
        windowHints.clear()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) { node?.recycle() }

    private data class WindowHint(val packageName: String, val windowId: Int, val className: String, val eventUptime: Long)

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
