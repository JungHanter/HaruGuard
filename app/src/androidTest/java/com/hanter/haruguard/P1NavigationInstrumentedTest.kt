package com.hanter.haruguard

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hanter.haruguard.ui.theme.HaruGuardTheme
import com.hanter.haruguard.vpn.DomainGroup
import com.hanter.haruguard.vpn.FilterService
import com.hanter.haruguard.vpn.FilterSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 접근성 트리를 조작한다. API 37에서 구형 Espresso의 내부 API 의존을 피한다. */
class P1NavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val state = mutableStateOf(ParentUiState(configured = true, authenticated = true, authenticationMs = 300_000))
    private val settings = mutableStateOf(FilterSettings())
    private val settingRequests = mutableListOf<ParentSetting>()
    private var saveError: String? = null

    private fun showScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity -> activity.setContent {
            HaruGuardTheme {
                ProtectionScreen(
                    state.value, VpnStatus(VpnPhase.STOPPED), false, false, false, null, null,
                    onAuth = { _, _, _, _ -> }, onVpn = { state.value = state.value.copy(filteringRequested = it) },
                    onProtection = { state.value = state.value.copy(protection = it, authenticated = !it) },
                    onSettings = { settingRequests += it }, onLock = { state.value = state.value.copy(authenticated = false) },
                    onRecoveryCode = {}, onRemoveProtection = {}, update = UpdateUiState(checking = false),
                    onUpdateDownload = {}, onUpdateCancel = {}, onUpdateInstall = {},
                    filters = settings.value, filterError = null, installedServices = setOf(FilterService.YOUTUBE),
                    onRetryFilters = {},
                    onServiceFilter = { service, enabled -> settings.value = settings.value.copy(
                        enabledServices = if (enabled) settings.value.enabledServices + service else settings.value.enabledServices - service) },
                    onGroupFilter = { group, enabled -> settings.value = when (group) {
                        DomainGroup.KAKAO -> settings.value.copy(kakaoEnabled = enabled)
                        DomainGroup.NAVER -> settings.value.copy(naverEnabled = enabled)
                        DomainGroup.ADS -> settings.value.copy(adsEnabled = enabled)
                        DomainGroup.P2P -> settings.value.copy(p2pEnabled = enabled)
                        DomainGroup.CUSTOM -> settings.value
                    } },
                    onDomainEdit = { group, old, value ->
                        if (saveError == null) settings.value = settings.value.withDomains(group,
                            settings.value.domains(group) - setOfNotNull(old) + setOfNotNull(value))
                        saveError
                    },
                )
            }
        } }
        awaitText("부모 관리")
    }

    @After fun close() { scenario?.close() }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.refresh()
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(automation.rootInActiveWindow)
        return result
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        val visible = nodes().filter { it.isVisibleToUser }
        return visible.firstOrNull { it.contentDescription?.toString() == text }
            ?: visible.firstOrNull { it.text?.toString()?.lines()?.contains(text) == true }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        runCatching { automation.waitForIdle(100, 2_000) }
        SystemClock.sleep(100)
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            settle()
            if (find(text) != null) return
            SystemClock.sleep(50)
        }
        error("화면에 없음: $text; 표시 내용: ${nodes().mapNotNull { it.text }}")
    }

    private fun visible(text: String): AccessibilityNodeInfo {
        repeat(32) {
            settle()
            find(text)?.let { return it }
            nodes().filter { it.isScrollable }.any {
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            // 화면 교체 직후에는 접근성 트리에 이전 스크롤 노드가 잠시 남는다.
            SystemClock.sleep(350)
        }
        error("스크롤 후에도 화면에 없음: $text")
    }

    private fun click(text: String) {
        repeat(12) {
            var node: AccessibilityNodeInfo? = visible(text)
            while (node != null) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(500)
                    settle()
                    return
                }
                node = node.parent
            }
            SystemClock.sleep(150)
        }
        error("클릭할 수 없음: $text")
    }

    private fun enter(value: String) {
        var field: AccessibilityNodeInfo? = null
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (field == null && SystemClock.uptimeMillis() < deadline) {
            settle()
            field = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            if (field == null) SystemClock.sleep(100)
        }
        checkNotNull(field) { "입력 필드 없음: ${nodes().mapNotNull { it.text }}" }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        settle()
    }

    private fun change(action: () -> Unit) { instrumentation.runOnMainSync(action); settle() }
    private fun back() = click("뒤로")
    private fun screenshot(name: String) {
        settle()
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        requireNotNull(automation.takeScreenshot()).let { bitmap ->
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun menusReachExistingSettingsUpdateAndOtherControls() {
        showScreen()
        screenshot("p1-menu")
        click("4. 처음 시작: 설정 보호 옵션")
        screenshot("p1-setup")
        for (title in listOf("1. 일반 기기 관리자", "2. 접근성 설정 보호", "3. 항상 켜진 VPN",
            "4. 배터리 최적화 예외", "5. 정확한 알람", "6. 상태 알림 (선택)")) visible(title)
        click("남은 시간 동안 설정 열기")
        assertEquals(listOf(ParentSetting.ALL), settingRequests)
        back()
        click("5. 업데이트")
        awaitText("앱 업데이트")
        back()
        click("6. 기타")
        click("부모 PIN 변경")
        awaitText("새 부모 PIN")
    }

    @Test fun serviceToggleAndFiveEditableListsRemainIndependent() {
        showScreen()
        click("3. VPN 필터 설정")
        screenshot("p1-filters")
        click("YouTube")
        for ((group, title) in listOf(DomainGroup.KAKAO to "카카오톡 숏폼 도메인 편집",
            DomainGroup.NAVER to "네이버 클립 도메인 편집", DomainGroup.ADS to "광고 도메인 편집", DomainGroup.P2P to "P2P 도메인 편집",
            DomainGroup.CUSTOM to "사용자 지정 도메인 편집")) {
            click(title)
            if (group == DomainGroup.NAVER) screenshot("p1-naver")
            if (group == DomainGroup.ADS) screenshot("p1-ads")
            click("도메인 추가")
            enter("added.example.com")
            click("저장")
            assertTrue("그룹별 추가", "added.example.com" in settings.value.domains(group))
            back()
        }
        assertFalse(FilterService.YOUTUBE in settings.value.enabledServices)
    }

    @Test fun advertisingToggleAndDomainDeletionKeepOtherListsAndMenuOrder() {
        settings.value = settings.value.copy(adDomains = setOf("ad.example.org"))
        val before = settings.value
        showScreen()
        click("3. VPN 필터 설정")
        // 순서대로 아래로 스크롤해 세 메뉴에 도달할 수 있어야 한다.
        visible("D. 광고 차단")
        click("광고 도메인 편집")
        awaitText("광고 차단")
        click("광고 차단")
        assertFalse(settings.value.adsEnabled)
        click("삭제")
        click("삭제")
        awaitText("등록된 도메인이 없습니다. 이 목록에서 차단하는 주소가 없습니다.")
        assertEquals(before.copy(adsEnabled = false, adDomains = emptySet()), settings.value)
        back()
        visible("E. P2P 차단")
        visible("F. 사용자 지정 차단 도메인")
    }

    @Test fun domainEditorSupportsRetryEditAndDelete() {
        settings.value = settings.value.copy(customDomains = setOf("before.example.com"))
        showScreen()
        click("3. VPN 필터 설정")
        click("사용자 지정 도메인 편집")
        click("수정")
        enter("after.example.com")
        change { saveError = "저장 공간 오류" }
        click("저장")
        awaitText("저장 공간 오류")
        assertEquals(setOf("before.example.com"), settings.value.customDomains)
        change { saveError = null }
        click("저장")
        awaitText("after.example.com")
        click("삭제")
        awaitText("이 도메인을 삭제할까요?")
        click("삭제")
        awaitText("등록된 도메인이 없습니다. 이 목록에서 차단하는 주소가 없습니다.")
    }

    @Test fun expiredAuthenticationDiscardsNavigationAndUnsavedDialog() {
        showScreen()
        click("3. VPN 필터 설정")
        click("사용자 지정 도메인 편집")
        click("도메인 추가")
        enter("unsaved.example.com")
        change { state.value = state.value.copy(authenticated = false, authenticationMs = 0) }
        awaitText("부모 PIN 입력")
        assertEquals(null, find("저장"))
        assertEquals(null, find("사용자 지정 차단 도메인"))
        change { state.value = state.value.copy(authenticated = true, authenticationMs = 300_000) }
        awaitText("부모 관리")
        assertEquals(null, find("unsaved.example.com"))
    }

    @Test fun enablingProtectionReturnsToPinEntry() {
        showScreen()
        click("1. 설정 화면 보호")
        awaitText("부모 PIN 입력")
        assertEquals(null, find("3. VPN 필터 설정"))
    }
}
