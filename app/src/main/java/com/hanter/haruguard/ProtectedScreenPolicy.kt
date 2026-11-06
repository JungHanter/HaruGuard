package com.hanter.haruguard

import java.util.Locale

/** 화면 내용은 이 판정에만 잠시 사용하며 저장하거나 로그로 내보내지 않는다. */
internal object ProtectedScreenPolicy {
    enum class Surface { SETTINGS, VPN_DIALOG, INSTALLER, SYSTEM_UI, LAUNCHER, OTHER }

    fun shouldBlock(
        surface: Surface,
        className: String,
        labels: List<String>,
        viewIds: List<String> = emptyList(),
        scopedContent: Boolean = false,
        longPressedOurApp: Boolean = false,
    ): Boolean {
        // 설정 앱은 별도의 페이지·대상 앱 판정이 필요하다. 패키지 전체를 차단하지 않는다.
        if (surface == Surface.SETTINGS) return false
        if (surface == Surface.VPN_DIALOG) return true
        if (surface == Surface.OTHER) return false
        val name = className.lowercase(Locale.ROOT)
        val values = labels.map { it.trim().lowercase(Locale.ROOT) }
        val hasApp = containsOurApp(values)
        fun includes(vararg words: String) = values.any { text -> words.any { text.contains(it) } }
        // 알림 본문에 같은 문자열이 있어도 시스템 컨트롤의 ID로 취급하지 않는다.
        val ids = viewIds.map { it.lowercase(Locale.ROOT).substringAfter(":id/", "") }
        val dialog = scopedContent || isDialogClass(name)
        return when (surface) {
            // 삭제 확인 화면은 앱 이름이 접근성 트리에 노출되기 전에도 닫는다.
            Surface.INSTALLER -> name.contains("uninstall") || (dialog && hasApp &&
                includes("uninstall", "delete", "remove", "삭제", "제거"))
            Surface.SYSTEM_UI -> name.contains("fgsmanager") || name.contains("foregroundservices") ||
                name.contains("hotspotdialog") || name.contains("tetherdialog") ||
                (dialog && values.any(SettingsScreenPolicy::isTetheringTitle)) ||
                name.contains("vpnconfig") || name.contains("vpndialog") ||
                ids.any { it.startsWith("fgs_manager_app") || it == "fgs_manager_stop_button" ||
                    it == "fgs_stop" || it == "foreground_service_stop" } ||
                (dialog && includes("active apps", "실행 중인 앱", "활성 앱") && includes("stop", "중지")) ||
                (dialog && hasApp && includes("disconnect", "turn off", "stop app", "연결 해제", "사용 안 함", "사용 중지"))
            Surface.LAUNCHER -> dialog && (hasApp || longPressedOurApp) && includes("pause app", "pause", "uninstall", "app info",
                "일시중지", "일시 중지", "삭제", "앱 정보")
            else -> false
        }
    }

    fun containsOurApp(labels: List<String>): Boolean = labels.any {
        val value = it.lowercase(Locale.ROOT)
        value.contains("haruguard") || value.contains("하루가드") || value.contains("com.hanter.haruguard")
    }

    /** 전체 알림창·홈의 내용과 팝업 내용이 섞이지 않도록 작은 컨테이너만 선택한다. */
    fun isScopedContainer(className: String, viewId: String?): Boolean {
        val name = className.lowercase(Locale.ROOT)
        val id = viewId?.lowercase(Locale.ROOT)?.substringAfter(":id/", "").orEmpty()
        return isDialogClass(name) || id in setOf("parentpanel", "alertdialog", "dialog_root",
            "dialog_container", "popup_container", "deep_shortcuts_container")
    }

    fun isDialogClass(className: String): Boolean {
        val name = className.lowercase(Locale.ROOT)
        return name.contains("dialog") || name.contains("popup")
    }

    /** 누락된 트리는 현재 전면 창에 대응하는 신선한 이벤트가 있을 때만 보완한다. */
    fun canUseMissingRootHint(
        ageMillis: Long,
        matchesFocusedWindow: Boolean,
        hasKnownOtherForeground: Boolean,
    ): Boolean = ageMillis in 0..500 && matchesFocusedWindow && !hasKnownOtherForeground

    /** 통화·긴급·부모 감독 화면이 전면일 때는 뒤쪽 설정 창 때문에 가리지 않는다. */
    fun isCriticalForeground(packageName: String?): Boolean = packageName in setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.incallui",
        "com.android.phone", "com.android.server.telecom", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.samsung.android.app.telephonyui",
        "com.android.emergency", "com.google.android.apps.safetyhub",
        "com.google.android.apps.kids.familylink", "com.google.android.apps.kids.familylinkhelper",
        "com.google.android.gms.supervision", "com.google.android.gms",
    )
}
