package com.hanter.haruguard

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadTest {
    @Test fun dropboxKeepsEncodedAccessKeyAndReplacesConflictingDownloadOptions() {
        assertEquals("https://www.dropbox.com/scl/fi/a/update.apk?rlkey=a%2Bb%2Fc&dl=1",
            UpdateLinkPolicy.prepare(" https://www.dropbox.com/scl/fi/a/update.apk?rlkey=a%2Bb%2Fc&dl=0&raw=1#preview ").toString())
    }

    @Test fun oneDriveShortLinkAndSharePointRequestFileDownload() {
        assertEquals("https://1drv.ms/u/s!example?e=abc&download=1",
            UpdateLinkPolicy.prepare("https://1drv.ms/u/s!example?download=0&e=abc").toString())
        assertEquals("https://example.sharepoint.com/:u:/s/files/abc?e=a%2Bb&download=1",
            UpdateLinkPolicy.prepare("https://example.sharepoint.com/:u:/s/files/abc?e=a%2Bb").toString())
    }

    @Test fun legacyOneDriveResourceIsConvertedWithoutLosingAuthKey() {
        assertEquals("https://onedrive.live.com/download?resid=A%211&authkey=%21Ab%2BC&download=1",
            UpdateLinkPolicy.prepare("https://onedrive.live.com/redir?resid=A%211&authkey=%21Ab%2BC").toString())
    }

    @Test fun oneDriveIdCanSupplyLegacyResourceId() {
        assertTrue(UpdateLinkPolicy.prepare("https://onedrive.live.com/?id=A%211&authkey=abc")
            .toString().contains("/download?id=A%211&authkey=abc&resid=A%211&download=1"))
    }

    @Test fun otherHostsAndSignedQueriesAreUnchanged() {
        val url = "https://cdn.example.com/update.apk?token=a%2Bb&dl=0"
        assertEquals(url, UpdateLinkPolicy.prepare(url).toString())
        val lookalike = "https://www.dropbox.com.evil.example/file?dl=0"
        assertEquals(lookalike, UpdateLinkPolicy.prepare(lookalike).toString())
    }

    @Test fun unsafeInputAndRedirectDowngradeAreRejected() {
        listOf("http://example.com/a.apk", "file:///tmp/a.apk", "https://user:pass@example.com/a",
            "https://example.com:0/a", "https://", "not a url").forEach { input ->
            expectFailure { UpdateLinkPolicy.prepare(input) }
        }
        expectFailure { UpdateLinkPolicy.redirect(URI("https://example.com/a"), "http://example.com/b") }
    }

    @Test fun relativeHttpsRedirectIsSupported() {
        assertEquals("https://example.com/files/update.apk?key=x%2By",
            UpdateLinkPolicy.redirect(URI("https://example.com/share/file"), "../files/update.apk?key=x%2By").toString())
    }

    @Test fun oneDriveRedirectIsNormalizedAgainButCdnTokenIsUnchanged() {
        val resolved = UpdateLinkPolicy.redirect(URI("https://1drv.ms/u/short?download=1"),
            "https://onedrive.live.com/redir?resid=ABC%211&authkey=a%2Bb")
        assertEquals("https://onedrive.live.com/download?resid=ABC%211&authkey=a%2Bb&download=1", resolved.toString())
        val cdn = "https://public.files.1drv.com/token?signature=ABC%2B123"
        assertEquals(cdn, UpdateLinkPolicy.redirect(resolved, cdn).toString())
    }

    @Test fun followsRedirectAndClosesBothConnections() {
        val redirect = FakeConnection(302, location = "/update.apk")
        val file = FakeConnection(200, bytes = "apk-content".toByteArray(), length = 11)
        val seen = mutableListOf<URI>()
        val downloader = UpdateDownload(connect = { uri -> seen += uri; if (seen.size == 1) redirect else file })
        withTarget { target ->
            downloader.download("https://example.com/shared", target, {}, { _, _ -> })
            assertEquals("apk-content", target.readText())
            assertEquals(URI("https://example.com/update.apk"), seen.last())
            assertTrue(redirect.closed && file.closed)
        }
    }

    @Test fun htmlLoginResponseAndHttpFailureNeverLeaveAFile() {
        listOf(FakeConnection(200, type = "text/html; charset=utf-8"), FakeConnection(403)).forEach { response ->
            withTarget { target ->
                target.writeText("old partial")
                expectFailure { UpdateDownload({ response }).download("https://example.com/a", target, {}, { _, _ -> }) }
                assertFalse(target.exists())
                assertTrue(response.closed)
            }
        }
    }

    @Test fun declaredAndStreamingSizeLimitsAreEnforced() {
        listOf(FakeConnection(200, length = 6), FakeConnection(200, bytes = ByteArray(6))).forEach { response ->
            withTarget { target ->
                expectFailure { UpdateDownload({ response }, maxBytes = 5).download("https://example.com/a", target, {}, { _, _ -> }) }
                assertFalse(target.exists())
            }
        }
    }

    @Test fun truncatedAndEmptyFilesAreRejected() {
        listOf(FakeConnection(200, bytes = byteArrayOf(1, 2), length = 4), FakeConnection(200)).forEach { response ->
            withTarget { target ->
                expectFailure { UpdateDownload({ response }).download("https://example.com/a", target, {}, { _, _ -> }) }
                assertFalse(target.exists())
            }
        }
    }

    @Test fun cancellationRemovesPartialAndDisconnects() {
        val response = FakeConnection(200, bytes = ByteArray(8))
        withTarget { target ->
            var checks = 0
            try {
                UpdateDownload({ response }).download("https://example.com/a", target,
                    { if (++checks > 1) throw CancellationException() }, { _, _ -> })
                throw AssertionError("취소가 전달되지 않음")
            } catch (_: CancellationException) { /* 예상한 취소 */ }
            assertFalse(target.exists())
            assertTrue(response.closed)
        }
    }

    @Test fun redirectLoopAndExcessiveHopsAreRejected() {
        withTarget { target ->
            expectFailure { UpdateDownload({ FakeConnection(302, location = "/a") })
                .download("https://example.com/a", target, {}, { _, _ -> }) }
            var hop = 0
            expectFailure { UpdateDownload({ FakeConnection(302, location = "/${++hop}") })
                .download("https://example.com/start", target, {}, { _, _ -> }) }
            assertFalse(target.exists())
        }
    }

    private fun withTarget(block: (java.io.File) -> Unit) {
        val folder = Files.createTempDirectory("haruguard-update-test").toFile()
        try { block(java.io.File(folder, "download.part")) } finally { folder.deleteRecursively() }
    }

    private fun expectFailure(block: () -> Unit) {
        try { block() } catch (_: UpdateFailure) { return }
        throw AssertionError("실패해야 하는 입력")
    }

    private class FakeConnection(
        private val code: Int,
        private val location: String? = null,
        private val bytes: ByteArray = ByteArray(0),
        private val length: Long = -1,
        private val type: String = "application/octet-stream",
    ) : HttpURLConnection(URI("https://example.com").toURL()) {
        var closed = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = length
        override fun getContentType() = type
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
