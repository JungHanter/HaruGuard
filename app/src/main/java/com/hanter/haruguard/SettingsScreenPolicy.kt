package com.hanter.haruguard

import java.util.Locale

/** 설정 메뉴의 문구가 목록에 보이는 것과 실제 해당 페이지에 진입한 것을 구분한다. */
internal object SettingsScreenPolicy {
    enum class Area(val label: String) {
        APP("앱 관리"), ADMIN("기기 관리자"), ACCESSIBILITY("접근성"), VPN("VPN"),
        BATTERY("배터리 최적화"), BACKGROUND("백그라운드 실행"), ALARM("알람 권한"),
        RESET("초기화"), DEVELOPER("개발자옵션 활성화"),
    }
    enum class Scope(val label: String) {
        OUR_APP("HaruGuard 상세 화면"), CATEGORY("해당 메뉴"),
        UNKNOWN_APP("대상 앱을 구분할 수 없는 상세 화면"), INLINE_LIST("직접 변경이 가능한 목록"),
    }
    data class Block(val area: Area, val scope: Scope)
    data class Node(
        val parent: Int,
        val labels: List<String>,
        val viewId: String?,
        val className: String,
        val heading: Boolean = false,
        val collection: Boolean = false,
        val checkable: Boolean = false,
        val paneTitle: String? = null,
        val editable: Boolean = false,
    )
    data class Screen(
        val className: String = "",
        val headings: List<String> = emptyList(),
        val appLabels: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
        val viewIds: List<String> = emptyList(),
        val list: Boolean = false,
        val inlineControls: Boolean = false,
    )
    private enum class Kind { APP, BATTERY_DETAIL, BATTERY_LIST, BACKGROUND_DETAIL, BACKGROUND_LIST, ALARM_DETAIL, ALARM_LIST }

    /** 상위 Settings·Network·Battery 등은 메뉴 항목 이름만으로 차단하지 않는다. */
    fun block(screen: Screen, developerOptionsEnabled: Boolean = false, maintenanceOpen: Boolean = false): Block? {
        val result = classify(screen, developerOptionsEnabled)
        // 개발자옵션 활성화는 5분 개방으로 허용하지 않고, 부모가 설정 보호를 꺼야 한다.
        return result?.takeIf { !maintenanceOpen || it.area == Area.DEVELOPER }
    }

    private fun classify(screen: Screen, developerOptionsEnabled: Boolean): Block? {
        val name = screen.className.lowercase(Locale.ROOT)
        val titles = screen.headings.map { normalize(it).trimEnd('?', '？') }.toSet()
        val labels = screen.labels.map(::normalize)
        val ids = screen.viewIds.map(::id)
        fun named(vararg values: String) = values.any { name.contains(it) }
        fun titled(vararg values: String) = values.any { normalize(it) in titles }
        val appNames = screen.appLabels.filter { it.isNotBlank() }.distinctBy(::normalize)
        val ours = appNames.isNotEmpty() && appNames.all(::ourApp)
        val knownApp = ours || appNames.size == 1

        // 실제 페이지 제목이 일반 설정이면 이전 창의 클래스 힌트를 사용하지 않는다.
        // 특히 Network 화면의 VPN 메뉴 항목과 VPN 페이지를 혼동하면 Wi-Fi까지 막힌다.
        val safeTitle = titled("Network & internet", "Network and internet", "Connections", "Internet", "Wi-Fi",
            "Bluetooth", "Display", "Display & touch", "Sound", "Sound & vibration", "Brightness",
            "네트워크 및 인터넷", "연결", "인터넷", "와이파이", "블루투스", "디스플레이", "소리", "소리 및 진동")
        val vpnTitle = titled("VPN", "VPN settings", "VPN 설정", "가상 사설망")
        val adminTitle = titled("Device admin apps", "Device administrators", "Device administrator",
            "기기 관리자 앱", "디바이스 관리자 앱", "기기 관리자")
        val accessibilityTitle = titled("Accessibility", "접근성")
        val resetTitle = titled("Reset", "Reset options", "Reset settings", "Reset all settings",
            "Reset network settings", "Reset mobile network settings", "Reset Wi-Fi and Bluetooth settings",
            "Reset Bluetooth & Wi-Fi", "Reset Bluetooth and Wi-Fi", "Reset Wi-Fi, mobile & Bluetooth",
            "Reset Wi-Fi, mobile and Bluetooth", "Reset app preferences",
            "Reset accessibility settings", "Factory data reset", "Factory reset", "Erase all data (factory reset)",
            "Erase all data", "초기화", "초기화 옵션", "설정 초기화", "모든 설정 초기화", "네트워크 설정 초기화",
            "모바일 네트워크 설정 초기화", "Wi-Fi 및 블루투스 설정 초기화", "와이파이 및 블루투스 설정 초기화",
            "Wi-Fi, 모바일, 블루투스 초기화", "네트워크 재설정", "앱 환경설정 재설정",
            "앱 설정 초기화", "앱 환경설정 초기화", "접근성 설정 초기화", "기기 전체 초기화", "공장 초기화",
            "모든 데이터 삭제", "모든 데이터 삭제(초기화)", "모든 데이터 삭제 (초기화)")
        if (safeTitle && !vpnTitle && !adminTitle && !accessibilityTitle && !resetTitle) return null
        // 실제 초기화 제목을 이전 Software information 창의 클래스 힌트보다 우선한다.
        if (resetTitle) return Block(Area.RESET, Scope.CATEGORY)
        val softwareInfo = titled("Software information", "Software info", "소프트웨어 정보") ||
            named("softwareinformation", "softwareinfo")
        val aboutDevice = titled("About phone", "About tablet", "About device", "About emulated device",
            "About emulated phone", "About emulated tablet", "휴대전화 정보", "태블릿 정보", "디바이스 정보", "기기 정보") ||
            named("mydeviceinfo", "deviceinfodashboard", "aboutphone", "abouttablet")
        val buildNumber = labels.any { it == "build number" || it == "빌드 번호" } || ids.any { it == "build_number" }
        val developmentSettings = titled("Developer options", "Developer settings", "Development settings", "개발자 옵션", "개발자옵션") ||
            named("developmentsettings", "developmentdashboard", "developmentpreferences", "developeroptions")
        // 7번째 탭 뒤에 취소하는 대신 빌드 번호가 있는 페이지에서 미리 복귀한다.
        // 삼성은 Software information, AOSP는 About 화면에 빌드 번호가 있다.
        // 이미 개발자옵션이 켜져 있으면 이 제한으로 개발자 메뉴 전체를 막지 않는다.
        // https://developer.android.com/studio/debug/dev-options#enable
        if (!developerOptionsEnabled && (softwareInfo || aboutDevice && buildNumber || developmentSettings ||
                titled("Build number", "빌드 번호") || named("buildnumber"))) {
            return Block(Area.DEVELOPER, Scope.CATEGORY)
        }
        // 초기화 목록뿐 아니라 검색 등으로 직접 연 개별 실행·확인 화면도 보호한다.
        // 네트워크 초기화는 VPN 승인까지 해제할 수 있다.
        // https://android.googlesource.com/platform/packages/apps/Settings/+/021f36b3fa9/src/com/android/settings/ResetNetworkConfirm.java
        if (named("resetdashboard", "resetoptions", "resetnetwork", "resetbluetooth", "resetwifi",
                "resetaccessibility", "resetsettings", "resetapp", "masterclear", "factoryreset", "mainclear")) {
            return Block(Area.RESET, Scope.CATEGORY)
        }
        if (vpnTitle || named("vpnsettings", "vpndetails", "vpnappdialog", "vpnconfig", "vpnpreferences")) {
            return Block(Area.VPN, Scope.CATEGORY)
        }
        if (adminTitle || named("deviceadmin", "deviceadministrator")) return Block(Area.ADMIN, Scope.CATEGORY)
        if (accessibilityTitle || named("accessibility", "toggleservice")) return Block(Area.ACCESSIBILITY, Scope.CATEGORY)
        if (titles.any(::ourApp) && labels.any { it.contains("accessibility") || it.contains("접근성") }) {
            return Block(Area.ACCESSIBILITY, Scope.OUR_APP)
        }

        // AOSP는 목록에서 앱을 고르면 개별 대화상자/상세 페이지를 연다.
        // https://android.googlesource.com/platform/packages/apps/Settings/+/master/src/com/android/settings/fuelgauge/HighPowerDetail.java
        // https://android.googlesource.com/platform/packages/apps/Settings/+/ea6a06c8e862cabe589cd6f4e2cb5f9672049d61/src/com/android/settings/applications/manageapplications/ManageApplications.java
        val kind = when {
            named("highpowerdetail", "requestignorebatteryoptimizations") || ids.any { it == "ignore_on" || it == "ignore_off" } ||
                knownApp && labels.any { it == "don't optimize" || it == "최적화하지 않음" || it == "최적화 안 함" } -> Kind.BATTERY_DETAIL
            named("alarmsandremindersdetails", "exactalarmdetails") -> Kind.ALARM_DETAIL
            named("advancedpowerusagedetail", "appbatterysettings", "appbatteryusagedetail") -> Kind.BACKGROUND_DETAIL
            named("appinfodashboard", "installedappdetails", "appstoragesettings", "appinfodetails") -> Kind.APP
            titled("App info", "Application info", "Storage & cache", "앱 정보", "애플리케이션 정보", "저장공간 및 캐시", "저장 공간 및 캐시") -> Kind.APP
            titled("Battery optimization", "Optimize battery usage", "배터리 최적화", "배터리 사용량 최적화") ||
                named("highpowerapplications", "ignorebatteryoptimizationssettings") -> if (knownApp) Kind.BATTERY_DETAIL else Kind.BATTERY_LIST
            titled("Alarms & reminders", "Alarms and reminders", "알람 및 리마인더", "알람 및 알림") ||
                named("alarmsandremindersactivity", "alarmsandremindersapplist") -> if (knownApp) Kind.ALARM_DETAIL else Kind.ALARM_LIST
            titled("Background usage limits", "Sleeping apps", "Deep sleeping apps", "Never sleeping apps", "Never auto sleeping apps",
                "백그라운드 사용 제한", "절전 상태 앱", "초절전 상태 앱", "절전 예외 앱", "절전 상태로 전환하지 않을 앱") -> Kind.BACKGROUND_LIST
            titled("App battery usage", "Allow background usage", "앱 배터리 사용량", "백그라운드 사용 허용") ->
                if (screen.list && !knownApp) Kind.BACKGROUND_LIST else Kind.BACKGROUND_DETAIL
            knownApp && labels.any { it in setOf("unrestricted", "optimized", "restricted", "allow background usage",
                "제한 없음", "최적화", "제한됨", "백그라운드 사용 허용") } -> Kind.BACKGROUND_DETAIL
            ids.any { it in setOf("force_stop_button", "clear_data_button", "clear_storage_button", "clear_data") } -> Kind.APP
            knownApp && !screen.list && labels.any { it in setOf("force stop", "uninstall", "clear storage", "강제 중지", "제거", "데이터 삭제") } -> Kind.APP
            else -> return null
        }
        val area = when (kind) {
            Kind.APP -> Area.APP
            Kind.BATTERY_DETAIL, Kind.BATTERY_LIST -> Area.BATTERY
            Kind.BACKGROUND_DETAIL, Kind.BACKGROUND_LIST -> Area.BACKGROUND
            Kind.ALARM_DETAIL, Kind.ALARM_LIST -> Area.ALARM
        }
        if (kind in setOf(Kind.BATTERY_LIST, Kind.ALARM_LIST)) {
            // 목록 안의 스위치·체크박스로 바로 변경하는 경우에만 목록 전체를 보호한다.
            return if (screen.inlineControls) Block(area, Scope.INLINE_LIST) else null
        }
        if (kind == Kind.BACKGROUND_LIST) {
            // 삼성 절전 목록은 다중 선택·삭제 등으로 예외를 바로 변경할 수 있어 목록을 보호한다.
            val sleepList = titled("Background usage limits", "Sleeping apps", "Deep sleeping apps", "Never sleeping apps",
                "Never auto sleeping apps", "백그라운드 사용 제한", "절전 상태 앱", "초절전 상태 앱", "절전 예외 앱", "절전 상태로 전환하지 않을 앱")
            return if (screen.inlineControls || sleepList) Block(area, Scope.INLINE_LIST) else null
        }
        return when {
            ours -> Block(area, Scope.OUR_APP)
            knownApp -> null
            else -> Block(area, Scope.UNKNOWN_APP)
        }
    }

    /** 목록 행의 HaruGuard 이름을 상세 화면의 대상 앱으로 잘못 해석하지 않는다. */
    fun screen(className: String, nodes: List<Node>, windowTitle: String? = null): Screen {
        val headings = ArrayList<String>()
        val secondaryHeadings = ArrayList<String>()
        val apps = ArrayList<String>()
        val headerIds = setOf("entity_header", "entity_header_content", "app_snippet", "app_detail_header", "app_header")
        val appTitleIds = setOf("entity_header_title", "app_name", "app_label", "app_title", "application_name")
        fun ancestors(index: Int): Sequence<Node> = sequence {
            var at = nodes[index].parent
            var limit = 0
            while (at in nodes.indices && limit++ < nodes.size) {
                yield(nodes[at])
                at = nodes[at].parent
            }
        }
        nodes.forEachIndexed { index, node ->
            val nodeId = id(node.viewId.orEmpty())
            val parents = ancestors(index).toList()
            val inList = parents.any { it.collection || it.className.contains("RecyclerView") || it.className.contains("ListView") }
            val inHeader = nodeId in headerIds || parents.any { id(it.viewId.orEmpty()) in headerIds }
            val toolbar = node.className.contains("Toolbar") || nodeId in setOf("toolbar", "action_bar", "collapsing_toolbar") ||
                parents.any { it.className.contains("Toolbar") || id(it.viewId.orEmpty()) in setOf("toolbar", "action_bar", "collapsing_toolbar") }
            if (!node.editable && (toolbar || nodeId in setOf("action_bar_title", "toolbar_title", "collapsing_toolbar_title", "page_title"))) {
                headings += node.labels
            } else if (!node.editable && node.heading && !inList) secondaryHeadings += node.labels
            node.paneTitle?.takeIf { it.isNotBlank() }?.let(secondaryHeadings::add)
            if (!node.editable && (inHeader && nodeId in appTitleIds || nodeId == "entity_header_title" ||
                (!inList && nodeId in appTitleIds))) apps += node.labels
            if (nodeId == "alerttitle") {
                headings += node.labels
                // HighPowerDetail의 제목은 선택한 앱 이름이다. 일반 라디오 목록의 행 이름은 제외된다.
                val batteryChoice = nodes.any { id(it.viewId.orEmpty()) in setOf("ignore_on", "ignore_off") }
                if (batteryChoice || node.labels.any(::ourApp)) apps += node.labels
            }
        }
        // 도구막대 제목이 있으면 본문의 섹션 제목은 페이지 제목으로 승격시키지 않는다.
        return Screen(className, headings.ifEmpty { secondaryHeadings }.ifEmpty { listOfNotNull(windowTitle) }.distinct(), apps.distinct(),
            nodes.filterNot { it.editable }.flatMap { it.labels }, nodes.mapNotNull { it.viewId },
            list = nodes.any { it.collection || it.className.contains("RecyclerView") || it.className.contains("ListView") },
            inlineControls = nodes.any { it.checkable })
    }

    private fun ourApp(label: String): Boolean = normalize(label).let {
        it == "haruguard" || it == "하루가드" || it == "com.hanter.haruguard" ||
            it.startsWith("haruguard ") || it.startsWith("하루가드 ")
    }
    private fun id(value: String): String = value.substringAfter(":id/", value).lowercase(Locale.ROOT)
    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
