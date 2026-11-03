package com.hanter.haruguard

import java.util.Locale

/** 앱 목록에서 HaruGuard를 선택한 직후, 상세 페이지가 그려지기 전의 짧은 입력 공백을 보완한다. */
internal class AppSelectionGuard {
    private var listWindowId: Int? = null
    private var listSeenAt = 0L
    private var pendingUntil = 0L

    fun observe(windowId: Int, screen: SettingsScreenPolicy.Screen, now: Long) {
        val appList = screen.list && screen.appLabels.isEmpty() && screen.headings.any {
            it.trim().lowercase(Locale.ROOT) in setOf("apps", "all apps", "see all apps", "app list",
                "애플리케이션", "앱", "모든 앱", "앱 목록")
        }
        if (appList) {
            listWindowId = windowId
            listSeenAt = now
        } else if (listWindowId == windowId) listWindowId = null
    }

    fun clicked(windowId: Int, labels: List<String>, now: Long): Boolean {
        if (listWindowId != windowId || now - listSeenAt !in 0..1_500) return false
        val ours = labels.any { it.trim().lowercase(Locale.ROOT) in setOf("haruguard", "하루가드", "com.hanter.haruguard") }
        if (!ours) return false
        pendingUntil = now + 150
        return true
    }

    fun isPending(now: Long): Boolean = now < pendingUntil

    fun clear() { listWindowId = null; pendingUntil = 0 }
}
