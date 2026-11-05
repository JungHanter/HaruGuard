package com.hanter.haruguard

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

internal object UpdateApkReader {
    @Suppress("DEPRECATION")
    fun readArchive(manager: PackageManager, file: File): PackageInfo? {
        // Android 10의 APK 파일 파서는 GET_SIGNATURES가 있어야 인증서를 수집한다.
        // 두 플래그를 함께 요청해 구버전에서도 SigningInfo를 채우며,
        // 실제 비교는 계속 SigningInfo.apkContentsSigners의 현재 서명 전체를 사용한다.
        // https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/android/content/pm/PackageManager.java#L5148-L5153
        // Android 15 파서도 두 플래그를 함께 처리한다.
        // https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/content/pm/PackageManager.java#L8139-L8142
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        return manager.getPackageArchiveInfo(file.absolutePath, flags)
    }
}
