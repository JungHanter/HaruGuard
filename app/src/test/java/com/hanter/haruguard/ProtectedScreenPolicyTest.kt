package com.hanter.haruguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hanter.haruguard.ProtectedScreenPolicy.Surface

class ProtectedScreenPolicyTest {
    @Test fun hotspotDialogsAreProtectedWithoutClosingTheOrdinaryQuickPanel() {
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "WifiHotspotDialog", emptyList()))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "Dialog", listOf("모바일 핫스팟", "켜기")))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "FrameLayout", listOf("Mobile Hotspot"), scopedContent = true))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "NotificationShade", listOf("Mobile Hotspot", "Wi-Fi", "Bluetooth")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "InternetDialog", listOf("Wi-Fi", "Mobile data")))
    }

    @Test fun settingsNeedsPageClassificationWhileVpnConsentIsProtected() {
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SETTINGS, "", emptyList()))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.VPN_DIALOG, "", emptyList()))
    }

    @Test fun normalNotificationShadeAndLockScreenStayAvailable() {
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "NotificationShade",
            listOf("HaruGuard VPN 필터", "다른 앱의 YouTube DNS 요청을 차단합니다.")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "Keyguard",
            listOf("Emergency", "긴급전화", "알림")))
    }

    @Test fun foregroundStopAndVpnDialogsAreProtected() {
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "FgsManagerDialog", emptyList()))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "Dialog", listOf("Active apps", "Stop")))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "Dialog", listOf("HaruGuard", "연결 해제")))
    }

    @Test fun installerProtectsUninstallButNotNormalPermissionOrInstallation() {
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.INSTALLER, "UninstallerActivity", emptyList()))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.INSTALLER, "GrantPermissionsActivity",
            listOf("HaruGuard", "Allow notifications")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.INSTALLER, "InstallActivity", listOf("HaruGuard", "Install")))
    }

    @Test fun launcherRequiresOurAppAndADestructiveAction() {
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Popup", listOf("HaruGuard", "Pause app")))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Popup", listOf("하루가드", "앱 정보")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Popup", listOf("YouTube", "Pause app")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Home", listOf("HaruGuard", "YouTube")))
    }

    @Test fun unrelatedAppsCannotTriggerGuardByDisplayingSimilarText() {
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.OTHER, "UninstallerActivity",
            listOf("HaruGuard", "Stop", "Active apps", "Delete")))
    }

    @Test fun unrelatedNotificationActionsDoNotCombineWithOurOngoingNotification() {
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "NotificationShade",
            listOf("HaruGuard VPN 필터", "Bluetooth", "Disconnect", "Turn off")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "NotificationShade",
            listOf("HaruGuard", "Active apps", "Music", "Stop")))
        // 알림 본문은 진짜 시스템 View ID와 구분한다.
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "FrameLayout",
            listOf("com.android.systemui:id/fgs_manager_stop_button")))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "FrameLayout", emptyList(),
            viewIds = listOf("com.android.systemui:id/fgs_manager_stop_button")))
    }

    @Test fun launcherHomeIconDoesNotIdentifyTheTargetOfAnotherAppsPopup() {
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Home",
            listOf("HaruGuard", "YouTube", "Pause app", "App info")))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Home",
            listOf("Pause app"), longPressedOurApp = true))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "LinearLayout",
            listOf("Pause app", "App info"), scopedContent = true, longPressedOurApp = true))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "LinearLayout",
            listOf("YouTube", "Pause app"), scopedContent = true))
    }

    @Test fun onlyDialogAndPopupContainersCanSupplyScopedLabels() {
        assertTrue(ProtectedScreenPolicy.isScopedContainer("PopupContainerWithArrow", null))
        assertTrue(ProtectedScreenPolicy.isScopedContainer("LinearLayout", "android:id/parentPanel"))
        assertFalse(ProtectedScreenPolicy.isScopedContainer("NotificationShadeWindowView",
            "com.android.systemui:id/notification_stack_scroller"))
        assertFalse(ProtectedScreenPolicy.isScopedContainer("FrameLayout", "com.android.launcher3:id/workspace"))
    }

    @Test fun missingRootFallbackRequiresFreshForegroundEvidence() {
        assertTrue(ProtectedScreenPolicy.canUseMissingRootHint(50, true, false))
        assertFalse(ProtectedScreenPolicy.canUseMissingRootHint(501, true, false))
        assertFalse(ProtectedScreenPolicy.canUseMissingRootHint(-1, true, false))
        assertFalse(ProtectedScreenPolicy.canUseMissingRootHint(50, false, false))
        // 전화 등 다른 화면이 이미 전면이면 뒤늦게 도착한 설정 이벤트를 적용하지 않는다.
        assertFalse(ProtectedScreenPolicy.canUseMissingRootHint(50, true, true))
    }

    @Test fun callsEmergencyAndFamilyLinkRemainUsableWithSettingsBehindThem() {
        assertTrue(ProtectedScreenPolicy.isCriticalForeground("com.samsung.android.incallui"))
        assertTrue(ProtectedScreenPolicy.isCriticalForeground("com.android.emergency"))
        assertTrue(ProtectedScreenPolicy.isCriticalForeground("com.google.android.gms.supervision"))
        assertFalse(ProtectedScreenPolicy.isCriticalForeground("com.android.settings"))
        assertFalse(ProtectedScreenPolicy.isCriticalForeground("com.hanter.haruguard"))
    }
}
