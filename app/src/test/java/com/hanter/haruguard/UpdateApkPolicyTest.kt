package com.hanter.haruguard

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class UpdateApkPolicyTest {
    private val installed = UpdateApkMetadata("com.hanter.haruguard", 3, 29, false, setOf("fixed-key"))
    private val newer = installed.copy(versionCode = 4)

    @Test fun acceptsOnlyNewerCompatibleSameKeyPackage() {
        UpdateApkPolicy.validate(installed, newer, 29)
        UpdateApkPolicy.validate(installed, newer, 37)
    }

    @Test fun rejectsOtherAppEvenWithSameSigner() = reject(newer.copy(packageName = "other.app"))
    @Test fun rejectsDebugOrDifferentReleaseKey() = reject(newer.copy(signers = setOf("different-key")))
    @Test fun missingArchiveSignerIsReportedAsReadFailureInsteadOfKeyMismatch() {
        val error = assertThrows(UpdateFailure::class.java) {
            UpdateApkPolicy.validate(installed, newer.copy(signers = emptySet()), 29)
        }
        assertEquals("APK의 서명 정보를 읽지 못했습니다. 서명된 Release APK인지 확인하고 다시 다운로드해 주세요.", error.message)
    }
    @Test fun missingInstalledSignerIsReportedAsReadFailureInsteadOfKeyMismatch() {
        val error = assertThrows(UpdateFailure::class.java) {
            UpdateApkPolicy.validate(installed.copy(signers = emptySet()), newer, 29)
        }
        assertEquals("설치된 앱의 서명 정보를 읽지 못했습니다. 앱을 다시 열고 시도해 주세요.", error.message)
    }
    @Test fun rejectsExtraSigner() = reject(newer.copy(signers = setOf("fixed-key", "other-key")))
    @Test fun rejectsTestOnlyApk() = reject(newer.copy(testOnly = true))
    @Test fun rejectsUnsupportedAndroidVersion() = reject(newer.copy(minSdk = 30))
    @Test fun rejectsSameVersion() = reject(installed)
    @Test fun rejectsDowngrade() = reject(newer.copy(versionCode = 2))

    private fun reject(candidate: UpdateApkMetadata) {
        try { UpdateApkPolicy.validate(installed, candidate, 29) } catch (_: UpdateFailure) { return }
        throw AssertionError("설치 후보가 거부되어야 함")
    }
}
