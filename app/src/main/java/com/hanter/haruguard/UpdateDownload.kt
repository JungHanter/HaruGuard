package com.hanter.haruguard

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal class UpdateFailure(message: String) : IOException(message)

/** 공유 링크의 접근 키와 인코딩은 보존하고 다운로드 옵션만 바꾼다. */
internal object UpdateLinkPolicy {
    fun prepare(input: String): URI {
        val uri = try { URI(input.trim()) } catch (_: Exception) {
            throw UpdateFailure("올바른 HTTPS 다운로드 링크를 입력해 주세요.")
        }
        if (input.length > 8_192 || !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65_535 || uri.port == 0) {
            throw UpdateFailure("HTTPS 링크만 사용할 수 있습니다. 주소와 공유 설정을 확인해 주세요.")
        }
        val base = uri.toASCIIString().substringBefore('#').substringBefore('?')
        val parts = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }.orEmpty()
        val host = uri.host.lowercase(Locale.ROOT)
        // Dropbox 공식 다운로드 옵션. rlkey 등 기존 접근 정보는 삭제하지 않는다.
        // https://help.dropbox.com/share/force-download
        if (host == "dropbox.com" || host == "www.dropbox.com") {
            return URI("$base?${replace(parts, setOf("dl", "raw"), "dl=1")}")
        }
        // OneDrive는 공유 방식에 따라 이 옵션을 무시할 수 있다. HTML 응답은 다운로드 단계에서 거부한다.
        // https://learn.microsoft.com/en-us/answers/questions/5190893/force-one-drive-link-to-open-as-desktop-app-and-no
        if (host == "1drv.ms" || host == "onedrive.live.com" || host.endsWith(".sharepoint.com")) {
            val resource = parts.firstOrNull { key(it) == "resid" }
                ?: parts.firstOrNull { key(it) == "id" && decode(it.substringAfter('=')).contains('!') }
                    ?.let { "resid=${it.substringAfter('=')}" }
            val downloadBase = if (host == "onedrive.live.com" && resource != null) {
                "https://onedrive.live.com/download"
            } else base
            val query = if (resource != null && parts.none { key(it) == "resid" }) parts + resource else parts
            return URI("$downloadBase?${replace(query, setOf("download"), "download=1")}")
        }
        return URI(uri.toASCIIString().substringBefore('#'))
    }

    fun redirect(from: URI, location: String): URI = try {
        prepare(from.resolve(location).toASCIIString())
    } catch (_: Exception) {
        throw UpdateFailure("안전한 HTTPS 파일 주소로 연결되지 않았습니다. 공유 링크를 확인해 주세요.")
    }

    private fun replace(parts: List<String>, names: Set<String>, option: String) =
        (parts.filterNot { key(it) in names } + option).joinToString("&")

    private fun key(part: String) = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}

/** 부분 파일은 외부에 노출하지 않으며 실패·취소 때 제거한다. URL/서버 응답을 로그에 남기지 않는다. */
internal class UpdateDownload(
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val maxBytes: Long = 256L * 1024 * 1024,
) {
    fun download(input: String, target: File, checkActive: () -> Unit, progress: (Long, Long) -> Unit) {
        var completed = false
        val started = System.nanoTime()
        fun check() {
            checkActive()
            if (System.nanoTime() - started > 15L * 60 * 1_000_000_000) {
                throw UpdateFailure("다운로드 시간이 초과되었습니다. 네트워크를 확인하고 다시 시도해 주세요.")
            }
        }
        try {
            var uri = UpdateLinkPolicy.prepare(input)
            val visited = mutableSetOf<URI>()
            for (hop in 0..10) {
                check()
                if (!visited.add(uri)) throw UpdateFailure("공유 링크 연결이 반복됩니다. 파일 링크를 확인해 주세요.")
                val connection = connect(uri)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val code = connection.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 10) throw UpdateFailure("공유 링크 연결 단계가 너무 많습니다.")
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateFailure("공유 링크에 다운로드 파일 주소가 없습니다.")
                        uri = UpdateLinkPolicy.redirect(uri, location)
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw UpdateFailure("다운로드 실패 (HTTP $code). 링크 만료·공유 권한·인터넷 연결을 확인해 주세요.")
                    }
                    val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                    if (type.contains("text/html") || type.contains("application/xhtml")) {
                        throw UpdateFailure("APK 대신 공유·로그인 페이지가 내려왔습니다. 로그인 없이 다운로드 가능한 파일 링크를 사용해 주세요.")
                    }
                    val total = connection.contentLengthLong
                    if (total > maxBytes) throw UpdateFailure("APK가 너무 큽니다. 최대 256MB까지 받을 수 있습니다.")
                    var received = 0L
                    var lastReport = 0L
                    connection.inputStream.use { source ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                check()
                                val count = source.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > maxBytes) throw UpdateFailure("APK가 너무 큽니다. 최대 256MB까지 받을 수 있습니다.")
                                output.write(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - lastReport >= 250_000_000) {
                                    progress(received, total)
                                    lastReport = now
                                }
                            }
                        }
                    }
                    if (received == 0L || (total >= 0 && received != total)) {
                        throw UpdateFailure("파일이 끝까지 다운로드되지 않았습니다. 다시 다운로드해 주세요.")
                    }
                    progress(received, total)
                    completed = true
                    return
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            if (!completed) target.delete()
        }
    }
}
