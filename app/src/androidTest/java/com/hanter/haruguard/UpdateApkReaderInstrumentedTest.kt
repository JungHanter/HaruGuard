package com.hanter.haruguard

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 모의 메타데이터가 아닌 실제 OS 파서로 API 29/35/37의 인증서 수집 회귀를 검사한다. */
@RunWith(AndroidJUnit4::class)
class UpdateApkReaderInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun copiedApkHasSameCurrentSignersForPartialAndReadyFileNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val expected = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        assertTrue("설치 앱의 현재 서명이 있어야 함", expected.isNotEmpty())
        // 실제 다운로드 직후의 .part와 설치 대기 중의 .apk를 모두 같은 운영 코드로 읽는다.
        for (suffix in listOf(".part", ".apk")) {
            val copied = File.createTempFile("update-signature-", suffix, context.cacheDir)
            try {
                File(context.applicationInfo.sourceDir).inputStream().use { source ->
                    copied.outputStream().use { target -> source.copyTo(target) }
                }
                val archive = UpdateApkReader.readArchive(manager, copied)
                assertNotNull("$suffix 파일의 APK 정보를 읽어야 함", archive)
                assertEquals(installed.packageName, archive!!.packageName)
                assertEquals(installed.longVersionCode, archive.longVersionCode)
                val actual = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
                assertTrue("$suffix 파일의 현재 서명이 비어 있으면 안 됨", actual.isNotEmpty())
                assertEquals(expected, actual)
            } finally {
                copied.delete()
            }
        }
    }
}
