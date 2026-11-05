package com.hanter.haruguard

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UpdateUiState(
    val downloading: Boolean = false,
    val checking: Boolean = true,
    val received: Long = 0,
    val total: Long = -1,
    val readyVersion: String? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = downloading || checking
}

// 일부 기기의 FileProvider 직접 사용 문제를 피한다. APK 대기 폴더만 공유한다.
// https://developer.android.com/reference/androidx/core/content/FileProvider
class UpdateFileProvider : FileProvider()

/** Activity 재생성에도 다운로드를 이어가되, 완료만으로 설치 화면을 자동으로 띄우지 않는다. */
internal class AppUpdater private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory = File(context.cacheDir, "apk_updates")
    private val partial = File(directory, "download.part")
    private val ready = File(directory, "ready/update.apk")
    private var job: Job? = null
    var state by mutableStateOf(UpdateUiState())
        private set

    init {
        // 프로세스 종료로 남은 부분 파일은 지우고 완성된 APK만 다시 검증한다.
        job = scope.launch {
            val version = withContext(Dispatchers.IO) {
                runCatching {
                    partial.delete()
                    if (ready.isFile) validate(ready) else null
                }.getOrElse { runCatching { ready.delete() }; null }
            }
            state = UpdateUiState(checking = false, readyVersion = version,
                message = if (version != null) "다운로드한 업데이트 APK가 있습니다." else null)
        }
    }

    fun download(link: String) {
        // 취소된 Job도 finally의 파일 정리가 끝나기 전에는 다음 다운로드와 겹치지 않는다.
        if (!ParentAccess.isAuthenticated() || state.busy || job?.isCompleted == false) return
        state = UpdateUiState(downloading = true, checking = false, message = "다운로드 중…")
        job = scope.launch {
            try {
                val version = withContext(Dispatchers.IO) {
                    val operation = coroutineContext
                    if (!directory.isDirectory && !directory.mkdirs()) throw UpdateFailure("다운로드 폴더를 만들지 못했습니다.")
                    ready.delete()
                    UpdateDownload().download(link, partial, checkActive = { operation.ensureActive() }) { bytes, total ->
                        scope.launch {
                            if (state.downloading) state = state.copy(received = bytes, total = total)
                        }
                    }
                    operation.ensureActive()
                    withContext(Dispatchers.Main) {
                        state = state.copy(downloading = false, checking = true, message = "APK 확인 중…")
                    }
                    val version = validate(partial)
                    if (!ready.parentFile!!.isDirectory && !ready.parentFile!!.mkdirs()) throw UpdateFailure("설치 파일을 저장하지 못했습니다.")
                    if (!partial.renameTo(ready)) throw UpdateFailure("설치 파일을 저장하지 못했습니다. 다시 다운로드해 주세요.")
                    version
                }
                state = UpdateUiState(checking = false, readyVersion = version,
                    message = "다운로드 완료. ‘설치하기’를 눌러 업데이트를 승인해 주세요.")
            } catch (cancelled: CancellationException) {
                state = UpdateUiState(checking = false, message = "다운로드를 취소했습니다.")
                throw cancelled
            } catch (error: Exception) {
                state = UpdateUiState(checking = false, message = if (error is UpdateFailure) error.message
                    else "다운로드하지 못했습니다. 인터넷 연결·공유 권한·저장 공간을 확인하고 다시 시도해 주세요.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { partial.delete() } }
            }
        }
    }

    fun cancel() {
        if (ParentAccess.isAuthenticated() && state.downloading) {
            state = state.copy(message = "다운로드 취소 중…")
            job?.cancel()
        }
    }

    fun showInstallMessage(message: String) {
        state = state.copy(message = message)
    }

    suspend fun fileForInstall(): File? {
        if (!ParentAccess.isAuthenticated() || state.busy || state.readyVersion == null) return null
        state = state.copy(checking = true)
        return try {
            val version = withContext(Dispatchers.IO) { validate(ready) }
            state = state.copy(checking = false, readyVersion = version)
            ready
        } catch (cancelled: CancellationException) {
            state = state.copy(checking = false)
            throw cancelled
        } catch (error: Exception) {
            withContext(Dispatchers.IO) { runCatching { ready.delete() } }
            state = UpdateUiState(checking = false, message = if (error is UpdateFailure) error.message
                else "APK를 확인하지 못했습니다. 다시 다운로드해 주세요.")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File): String {
        if (!file.isFile) throw UpdateFailure("다운로드한 APK가 없습니다. 다시 다운로드해 주세요.")
        val manager = context.packageManager
        val candidate = UpdateApkReader.readArchive(manager, file)
            ?: throw UpdateFailure("유효한 APK가 아닙니다. 공유 웹 페이지가 아닌 APK 파일 링크인지 확인해 주세요.")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        UpdateApkPolicy.validate(metadata(installed), metadata(candidate), Build.VERSION.SDK_INT)
        return candidate.versionName ?: candidate.longVersionCode.toString()
    }

    private fun metadata(info: PackageInfo) = UpdateApkMetadata(
        packageName = info.packageName, versionCode = info.longVersionCode,
        minSdk = info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
        testOnly = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_TEST_ONLY) != 0,
        signers = info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.toSet(),
    )

    companion object {
        // Activity가 아닌 applicationContext만 보관해 화면 재생성 중 다운로드를 유지한다.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context.applicationContext).also { instance = it }
        }
    }
}
