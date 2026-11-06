package com.hanter.haruguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.hanter.haruguard.ProtectedScreenPolicy.Surface

class ProtectedScreenPolicyTest {
    @Test fun storeScreensAreProtectedBeforeAnyPageTextIsAvailable() {
        assertEquals(SettingsScreenPolicy.Block(SettingsScreenPolicy.Area.STORE, SettingsScreenPolicy.Scope.CATEGORY),
            ProtectedScreenPolicy.blockWithoutContent(Surface.STORE))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.STORE, "", emptyList()))
        assertTrue(ProtectedScreenPolicy.shouldBlock(Surface.STORE, "DetailActivity", listOf("Calculator")))
        for (surface in listOf(null, Surface.SETTINGS, Surface.OTHER, Surface.LAUNCHER, Surface.SYSTEM_UI, Surface.INSTALLER)) {
            assertNull(ProtectedScreenPolicy.blockWithoutContent(surface))
        }
    }

    @Test fun storeNamesInOtherAppsHomeAndNotificationsDoNotTriggerProtection() {
        val names = listOf("Play 스토어", "Galaxy Store", "com.android.vending", "com.sec.android.app.samsungapps")
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.OTHER, "MainActivity", names))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.LAUNCHER, "Home", names))
        assertFalse(ProtectedScreenPolicy.shouldBlock(Surface.SYSTEM_UI, "NotificationShade", names))
    }

    @Test fun storeAccessRequiresExplicitMaintenanceAndReturnsAtTheOriginalDeadline() {
        var elapsed = 1_000L
        val session = ParentSession { elapsed }
        fun block() = ProtectedScreenPolicy.blockWithoutContent(Surface.STORE, session.maintenanceRemainingMillis() > 0)
        assertEquals(SettingsScreenPolicy.Area.STORE, block()?.area)
        session.authenticate(session.generation())
        // PIN을 입력한 것만으로 스토어를 열지 않고, 명시적 임시 개방을 요구한다.
        assertEquals(SettingsScreenPolicy.Area.STORE, block()?.area)
        elapsed += 60_000L
        session.openMaintenance()
        session.lockAuthenticationOnly()
        assertNull(block())
        elapsed += ParentSession.AUTH_DURATION_MILLIS - 60_000L - 1
        assertNull(block())
        elapsed++
        assertEquals(SettingsScreenPolicy.Area.STORE, block()?.area)
    }

    @Test fun lockingRevokesTemporaryStoreAccessAndVpnDialogsFollowTheSameRule() {
        val session = ParentSession { 1_000L }
        session.authenticate(session.generation())
        session.openMaintenance()
        for (surface in listOf(Surface.STORE, Surface.VPN_DIALOG)) {
            assertNull(ProtectedScreenPolicy.blockWithoutContent(surface, session.maintenanceRemainingMillis() > 0))
        }
        session.lock()
        assertEquals(SettingsScreenPolicy.Area.STORE, ProtectedScreenPolicy.blockWithoutContent(
            Surface.STORE, session.maintenanceRemainingMillis() > 0)?.area)
        assertEquals(SettingsScreenPolicy.Area.VPN, ProtectedScreenPolicy.blockWithoutContent(
            Surface.VPN_DIALOG, session.maintenanceRemainingMillis() > 0)?.area)
    }

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
