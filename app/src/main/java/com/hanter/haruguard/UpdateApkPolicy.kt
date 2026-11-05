package com.hanter.haruguard

internal data class UpdateApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val testOnly: Boolean,
    val signers: Set<String>,
)

/** 고정 키로 서명한 더 높은 버전만 받는다. 최종 설치·서명 검증은 Android가 수행한다. */
internal object UpdateApkPolicy {
    fun validate(installed: UpdateApkMetadata, candidate: UpdateApkMetadata, sdk: Int) {
        if (candidate.packageName != installed.packageName) throw UpdateFailure("HaruGuard APK가 아닙니다.")
        if (candidate.testOnly) throw UpdateFailure("testOnly=true APK는 파일로 설치할 수 없습니다. testOnly=false로 Release를 만들어 주세요.")
        if (candidate.minSdk > sdk) throw UpdateFailure("이 APK는 현재 기기의 Android 버전을 지원하지 않습니다.")
        if (candidate.versionCode <= installed.versionCode) throw UpdateFailure("이미 설치된 버전과 같거나 더 오래된 APK입니다. 더 높은 버전의 Release를 사용해 주세요.")
        if (installed.signers.isEmpty()) {
            throw UpdateFailure("설치된 앱의 서명 정보를 읽지 못했습니다. 앱을 다시 열고 시도해 주세요.")
        }
        if (candidate.signers.isEmpty()) {
            throw UpdateFailure("APK의 서명 정보를 읽지 못했습니다. 서명된 Release APK인지 확인하고 다시 다운로드해 주세요.")
        }
        if (candidate.signers != installed.signers) {
            throw UpdateFailure("설치된 앱과 서명 키가 다릅니다. 처음 설치할 때 사용한 고정 키로 만든 APK를 사용해 주세요.")
        }
    }
}
