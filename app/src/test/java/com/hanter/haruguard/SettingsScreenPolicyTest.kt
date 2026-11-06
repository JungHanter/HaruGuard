package com.hanter.haruguard

import com.hanter.haruguard.SettingsScreenPolicy.Area
import com.hanter.haruguard.SettingsScreenPolicy.Block
import com.hanter.haruguard.SettingsScreenPolicy.Node
import com.hanter.haruguard.SettingsScreenPolicy.Scope
import com.hanter.haruguard.SettingsScreenPolicy.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsScreenPolicyTest {
    @Test fun hotspotAndTetheringPagesAreProtectedAndParentMaintenanceStillWorks() {
        for (title in listOf("Hotspot & tethering", "Mobile Hotspot and Tethering", "Wi-Fi hotspot",
            "USB tethering", "Bluetooth tethering", "Ethernet tethering", "Auto Hotspot",
            "모바일 핫스팟 및 테더링", "모바일 핫스팟", "USB 테더링", "자동 핫스팟")) {
            val page = Screen(headings = listOf(title))
            assertEquals(title, Block(Area.TETHERING, Scope.CATEGORY), SettingsScreenPolicy.block(page))
            assertNull(title, SettingsScreenPolicy.block(page, maintenanceOpen = true))
        }
        for (name in listOf("com.android.settings.TetherSettings", "Settings\$WifiTetherSettingsActivity",
            "com.samsung.android.settings.wifi.mobileap.WifiApSettings", "MobileApSettings", "WifiApAutoHotspotSettings")) {
            assertEquals(name, Block(Area.TETHERING, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(className = name)))
        }
    }

    @Test fun hotspotRowsAndStaleClassHintsDoNotBlockGeneralNetworkOrWifi() {
        for (title in listOf("Connections", "Network & internet", "Wi-Fi", "Bluetooth", "연결")) {
            assertNull(SettingsScreenPolicy.block(Screen(className = "WifiApSettings", headings = listOf(title),
                labels = listOf("Mobile Hotspot and Tethering", "USB tethering"), list = true)))
        }
        val search = listOf(
            Node(-1, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(0, listOf("모바일 핫스팟"), "android:id/title", "TextView"),
            Node(-1, listOf("USB tethering"), "android:id/search_src_text", "EditText", editable = true),
        )
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("SearchActivity", search)))
    }

    @Test fun explicitInlineTetheringControlsAreProtectedWithoutBlockingPlainMenuRows() {
        val control = Node(-1, listOf("Mobile Hotspot, Off"), "android:id/switch_widget", "Switch", checkable = true)
        val page = SettingsScreenPolicy.screen("SubSettings", listOf(control), "Connections")
        assertEquals(Block(Area.TETHERING, Scope.INLINE_LIST), SettingsScreenPolicy.block(page))
        assertEquals(Block(Area.TETHERING, Scope.INLINE_LIST), SettingsScreenPolicy.block(Screen(
            headings = listOf("연결"), viewIds = listOf("com.android.settings:id/usb_tethering_switch"))))
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("SubSettings", listOf(control.copy(checkable = false)), "Connections")))
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("SubSettings", listOf(control.copy(editable = true)), "Connections")))
    }

    @Test fun settingsHomeDoesNotBlockBecauseSensitiveMenuNamesAreVisible() {
        val page = Screen(className = "com.android.settings.homepage.SettingsHomepageActivity",
            headings = listOf("Settings"), labels = listOf("Apps", "Accessibility", "VPN", "HaruGuard", "Battery optimization"))
        assertNull(SettingsScreenPolicy.block(page))
    }

    @Test fun networkAndConnectionsRemainAvailableWithAVpnEntry() {
        for (title in listOf("Network & internet", "Connections", "네트워크 및 인터넷", "연결")) {
            // 이전 VPN 페이지의 창 이벤트가 잠깐 남아도 현재 제목을 우선한다.
            assertNull(SettingsScreenPolicy.block(Screen("Settings\$VpnSettingsActivity", listOf(title),
                labels = listOf("Wi-Fi", "VPN", "Always-on VPN", "HaruGuard"))))
        }
    }

    @Test fun brightnessWifiBluetoothAndSoundDoNotRequireParentAccess() {
        for (title in listOf("Display", "Display & touch", "Brightness", "Wi-Fi", "Bluetooth", "Sound & vibration", "디스플레이", "소리")) {
            assertNull(SettingsScreenPolicy.block(Screen(headings = listOf(title))))
        }
    }

    @Test fun resetOverviewAndDirectResetConfirmationAreProtected() {
        for (title in listOf("Reset options", "초기화", "Reset mobile network settings", "Reset app preferences",
                "접근성 설정 초기화", "Reset Wi-Fi and Bluetooth settings", "Erase all data (factory reset)", "Reset app preferences?")) {
            assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(headings = listOf(title))))
        }
        assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(className = "ResetNetworkConfirm")))
        assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(className = "Settings\$MasterClearActivity")))
        assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(className = "ResetAccessibilitySettings")))
    }

    @Test fun resetMenuRowsDoNotBlockSystemOrGeneralManagementOverview() {
        for (title in listOf("Settings", "System", "General management", "일반", "Apps")) {
            assertNull(SettingsScreenPolicy.block(Screen(headings = listOf(title),
                labels = listOf("Reset", "초기화", "Reset app preferences"), list = true)))
        }
        assertNull(SettingsScreenPolicy.block(Screen("ResetNetworkConfirm", headings = listOf("Network & internet"))))
    }

    @Test fun resetSearchQueryAndResultDoNotBlockUntilTheResultIsOpened() {
        val nodes = listOf(
            Node(-1, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(0, listOf("Reset"), "android:id/search_src_text", "EditText", editable = true),
            Node(-1, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(2, listOf("Reset network settings"), "android:id/title", "TextView"),
        )
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("SearchActivity", nodes)))
        assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(
            SettingsScreenPolicy.screen("FrameLayout", emptyList(), "Reset network settings")))
    }

    @Test fun samsungBuildNumberPageIsProtectedOnlyWhileDeveloperOptionsAreOff() {
        val software = Screen(headings = listOf("Software information"), labels = listOf("Build number"))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(software))
        assertNull(SettingsScreenPolicy.block(software, developerOptionsEnabled = true))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen(className = "com.samsung.android.settings.deviceinfo.SoftwareInfoSettings")))
    }

    @Test fun aboutPageOnlyFallsBackToProtectionWhenItContainsBuildNumber() {
        val about = Screen(headings = listOf("About tablet"), labels = listOf("Software information", "Model"))
        assertNull(SettingsScreenPolicy.block(about))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            about.copy(labels = listOf("Build number", "Model"))))
        assertNull(SettingsScreenPolicy.block(about.copy(labels = listOf("Build number")), developerOptionsEnabled = true))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen(headings = listOf("휴대전화 정보"), labels = listOf("빌드 번호"))))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen(headings = listOf("About emulated device"), labels = listOf("Build number"))))
    }

    @Test fun aospBuildNumberRowInAboutPreferenceListTriggersProtection() {
        val nodes = listOf(
            Node(-1, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(0, listOf("About phone"), "android:id/title", "TextView"),
            Node(-1, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(2, listOf("Build number"), "android:id/title", "TextView"),
        )
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            SettingsScreenPolicy.screen("SubSettings", nodes)))
    }

    @Test fun existingDeveloperMenuIsAllowedUntilItIsTurnedOff() {
        val developer = Screen(className = "DevelopmentSettingsDashboardFragment", headings = listOf("Developer options"))
        assertNull(SettingsScreenPolicy.block(developer, developerOptionsEnabled = true))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(developer, developerOptionsEnabled = false))
    }

    @Test fun maintenanceAllowsNormalProtectedPagesButNeverDeveloperActivation() {
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Reset")), maintenanceOpen = true))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("VPN")), maintenanceOpen = true))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("App info"), appLabels = listOf("HaruGuard")), maintenanceOpen = true))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen(headings = listOf("Software information")), maintenanceOpen = true))
        assertNull(SettingsScreenPolicy.block(Screen("SoftwareInfoSettings", headings = listOf("Reset")), maintenanceOpen = true))
        assertEquals(Block(Area.DEVELOPER, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen("ResetNetworkConfirm", headings = listOf("Software information")), maintenanceOpen = true))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Developer options")),
            developerOptionsEnabled = true, maintenanceOpen = true))
        assertEquals(Block(Area.RESET, Scope.CATEGORY), SettingsScreenPolicy.block(
            Screen(headings = listOf("Reset")), maintenanceOpen = false))
    }

    @Test fun developerMenuNamesInSystemOverviewAndSearchResultsStayAllowed() {
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("System"), labels = listOf("Developer options"))))
        assertNull(SettingsScreenPolicy.block(Screen(className = "SearchActivity", labels = listOf("Build number", "Software information"), list = true)))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Settings"), labels = listOf("About tablet", "Build number"))))
    }

    @Test fun vpnListAndDetailAreProtectedButNetworkLabelsAloneAreNot() {
        assertEquals(Block(Area.VPN, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(headings = listOf("VPN"))))
        assertEquals(Block(Area.VPN, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(className = "com.android.settings.vpn2.VpnSettings")))
        assertNull(SettingsScreenPolicy.block(Screen(className = "android.widget.FrameLayout", labels = listOf("VPN"))))
    }

    @Test fun onlySecuritySubcategoriesAreProtectedNotSecurityOverview() {
        assertEquals(Block(Area.ADMIN, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(headings = listOf("Device admin apps"))))
        assertEquals(Block(Area.ACCESSIBILITY, Scope.CATEGORY), SettingsScreenPolicy.block(Screen(headings = listOf("접근성"))))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Security & privacy"), labels = listOf("Device admin apps", "Accessibility"))))
    }

    @Test fun appDetailsProtectOurAppAndAllowAnIdentifiedOtherApp() {
        assertEquals(Block(Area.APP, Scope.OUR_APP), SettingsScreenPolicy.block(Screen(headings = listOf("App info"), appLabels = listOf("HaruGuard"))))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("App info"), appLabels = listOf("Calculator"))))
    }

    @Test fun unidentifiableAppDetailsAreTheFallbackBoundaryRatherThanAllSettings() {
        assertEquals(Block(Area.APP, Scope.UNKNOWN_APP), SettingsScreenPolicy.block(Screen(className = "AppInfoDashboardFragment")))
        assertNull(SettingsScreenPolicy.block(Screen(className = "SubSettings")))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Apps"))))
    }

    @Test fun ourAppStorageAndDirectClearDataPageAreProtected() {
        assertEquals(Block(Area.APP, Scope.OUR_APP), SettingsScreenPolicy.block(Screen(headings = listOf("Storage & cache"), appLabels = listOf("하루가드"))))
        assertEquals(Block(Area.APP, Scope.UNKNOWN_APP), SettingsScreenPolicy.block(Screen(viewIds = listOf("com.android.settings:id/clear_data_button"))))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Storage"), labels = listOf("Apps", "HaruGuard"))))
    }

    @Test fun normalBatteryOptimizationListStaysOpenEvenWithOurAppInIt() {
        val screen = Screen(headings = listOf("Battery optimization"),
            labels = listOf("HaruGuard", "Calculator", "Not optimized"), list = true)
        assertNull(SettingsScreenPolicy.block(screen))
    }

    @Test fun batteryListWithInlineCheckboxesUsesListFallback() {
        assertEquals(Block(Area.BATTERY, Scope.INLINE_LIST), SettingsScreenPolicy.block(Screen(
            headings = listOf("Battery optimization"), list = true, inlineControls = true)))
    }

    @Test fun aospHighPowerDialogsDistinguishOurAppFromAnotherApp() {
        fun dialog(app: String) = listOf(
            Node(-1, emptyList(), null, "android.widget.LinearLayout"),
            Node(0, listOf(app), "android:id/alertTitle", "android.widget.TextView"),
            Node(0, listOf("Don't optimize"), "com.android.settings:id/ignore_on", "RadioButton", checkable = true),
            Node(0, listOf("Optimize"), "com.android.settings:id/ignore_off", "RadioButton", checkable = true),
        )
        assertEquals(Block(Area.BATTERY, Scope.OUR_APP), SettingsScreenPolicy.block(
            SettingsScreenPolicy.screen("AlertDialog", dialog("HaruGuard"))))
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("AlertDialog", dialog("Calculator"))))
    }

    @Test fun alarmListStaysOpenButOurDetailIsProtected() {
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Alarms & reminders"), list = true,
            labels = listOf("HaruGuard", "Calculator", "Allowed"))))
        assertEquals(Block(Area.ALARM, Scope.OUR_APP), SettingsScreenPolicy.block(Screen(
            headings = listOf("Alarms & reminders"), appLabels = listOf("HaruGuard"))))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Alarms & reminders"), appLabels = listOf("Calculator"))))
    }

    @Test fun alarmListWithInlineSwitchesAndUnknownAlarmDetailFailClosedWithinTheirPage() {
        assertEquals(Block(Area.ALARM, Scope.INLINE_LIST), SettingsScreenPolicy.block(Screen(
            headings = listOf("Alarms & reminders"), inlineControls = true, list = true)))
        assertEquals(Block(Area.ALARM, Scope.UNKNOWN_APP), SettingsScreenPolicy.block(Screen(className = "AlarmsAndRemindersDetails")))
    }

    @Test fun backgroundAppDetailsDistinguishAppsAndNormalUsageListStaysOpen() {
        assertEquals(Block(Area.BACKGROUND, Scope.OUR_APP), SettingsScreenPolicy.block(Screen(
            headings = listOf("App battery usage"), appLabels = listOf("HaruGuard"), list = true, inlineControls = true)))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("App battery usage"), appLabels = listOf("Calculator"))))
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("App battery usage"), list = true,
            labels = listOf("HaruGuard", "Calculator"))))
    }

    @Test fun samsungBatchSleepListsAreProtectedWhileBatteryOverviewIsAllowed() {
        for (title in listOf("Background usage limits", "Never auto sleeping apps", "절전 상태 앱")) {
            assertEquals(Block(Area.BACKGROUND, Scope.INLINE_LIST), SettingsScreenPolicy.block(Screen(headings = listOf(title))))
        }
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Battery"), labels = listOf("HaruGuard", "Background usage limits"))))
    }

    @Test fun rowNamesInsideARecyclerViewAreNotAppIdentity() {
        val nodes = listOf(
            Node(-1, emptyList(), null, "FrameLayout"),
            Node(0, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(1, listOf("Battery optimization"), "android:id/title", "TextView"),
            Node(0, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(3, listOf("HaruGuard"), "com.android.settings:id/app_name", "TextView"),
            Node(3, listOf("Calculator"), "com.android.settings:id/app_name", "TextView"),
        )
        val page = SettingsScreenPolicy.screen("SubSettings", nodes)
        assertEquals(emptyList<String>(), page.appLabels)
        assertEquals(listOf("Battery optimization"), page.headings)
        assertNull(SettingsScreenPolicy.block(page))
    }

    @Test fun entityHeaderInsidePreferenceRecyclerStillIdentifiesOneApp() {
        val nodes = listOf(
            Node(-1, emptyList(), null, "RecyclerView", collection = true),
            Node(0, emptyList(), "com.android.settings:id/entity_header", "LinearLayout"),
            Node(1, listOf("HaruGuard"), "com.android.settings:id/entity_header_title", "TextView"),
        )
        val page = SettingsScreenPolicy.screen("AdvancedPowerUsageDetail", nodes)
        assertEquals(listOf("HaruGuard"), page.appLabels)
        assertEquals(Block(Area.BACKGROUND, Scope.OUR_APP), SettingsScreenPolicy.block(page))
    }

    @Test fun vpnMenuRowDoesNotBecomeAPageTitle() {
        val nodes = listOf(
            Node(-1, emptyList(), null, "FrameLayout"),
            Node(0, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(1, listOf("Network & internet"), "android:id/title", "TextView"),
            Node(0, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(3, listOf("VPN"), "android:id/title", "TextView"),
        )
        val page = SettingsScreenPolicy.screen("SubSettings", nodes)
        assertEquals(listOf("Network & internet"), page.headings)
        assertNull(SettingsScreenPolicy.block(page))
    }

    @Test fun unknownLayoutDoesNotRevertToBlockingSettingsPackage() {
        assertNull(SettingsScreenPolicy.block(SettingsScreenPolicy.screen("FrameLayout", emptyList())))
        assertEquals(Block(Area.VPN, Scope.CATEGORY), SettingsScreenPolicy.block(
            SettingsScreenPolicy.screen("FrameLayout", emptyList(), "VPN")))
    }

    @Test fun searchingForVpnDoesNotTurnTheSearchQueryIntoTheCurrentPageTitle() {
        val nodes = listOf(
            Node(-1, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(0, listOf("VPN"), "android:id/search_src_text", "EditText", editable = true),
            Node(-1, emptyList(), "android:id/list", "RecyclerView", collection = true),
            Node(2, listOf("VPN"), "android:id/title", "TextView"),
        )
        val page = SettingsScreenPolicy.screen("SearchActivity", nodes)
        assertEquals(emptyList<String>(), page.headings)
        assertNull(SettingsScreenPolicy.block(page))
    }

    @Test fun toolbarTitleWinsOverVpnSectionMarkedAsAHeadingOnNetworkPage() {
        val nodes = listOf(
            Node(-1, emptyList(), null, "LinearLayout"),
            Node(0, emptyList(), "com.android.settings:id/toolbar", "Toolbar"),
            Node(1, listOf("Network & internet"), "android:id/title", "TextView"),
            Node(0, listOf("VPN"), "android:id/title", "TextView", heading = true),
        )
        val page = SettingsScreenPolicy.screen("SubSettings", nodes)
        assertEquals(listOf("Network & internet"), page.headings)
        assertNull(SettingsScreenPolicy.block(page))
    }

    @Test fun multipleAppNamesDoNotBecomeASingleProtectedAppHeader() {
        assertNull(SettingsScreenPolicy.block(Screen(headings = listOf("Battery optimization"),
            appLabels = listOf("HaruGuard", "Calculator"), list = true)))
    }
}
