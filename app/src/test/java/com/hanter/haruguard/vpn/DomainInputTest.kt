package com.hanter.haruguard.vpn

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DomainInputTest {
    private val suffixes by lazy { File("src/main/assets/public_suffix_list.dat").bufferedReader().use { PublicSuffixes(it.lineSequence()) } }

    @Test fun normalizesCaseTrailingDotAndInternationalDomains() {
        assertEquals("dcinside.com", suffixes.validate("  DCINSIDE.COM.  "))
        assertEquals("www.dcinside.com", suffixes.validate("www.dcinside.com"))
        assertEquals("xn--bcher-kva.de", suffixes.validate("bücher.de"))
        assertEquals(suffixes.validate("한국.kr"), suffixes.validate(DomainInput.normalize("한국.kr")))
    }

    @Test fun rejectsUrlsPortsIpsWildcardsAndMalformedLabels() {
        listOf("", "https://dcinside.com", "http://dcinside.com", "dcinside.com/path", "dcinside.com:443",
            "127.0.0.1", "[::1]", "*.dcinside.com", "a..com", "-abc.com", "abc-.com", "a_b.com",
            "a b.com", "a\n.com", "a.com?x=1", "a.com#x", "a.com,b.com", "a".repeat(64) + ".com")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { suffixes.validate(it) } }
    }

    @Test fun rejectsPublicAndPrivateSuffixesIncludingWildcardExceptions() {
        listOf("com", "co.kr", "co.uk", "github.io", "blogspot.com", "foo.ck").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { suffixes.validate(it) }
        }
        listOf("example.co.kr", "user.github.io", "www.ck", "example.foo.ck").forEach {
            assertEquals(it, suffixes.validate(it))
        }
    }

    @Test fun everyBundledServiceAndDefaultDomainIsValid() {
        (FilterCatalog.domains.values.flatten() + DomainGroup.entries.flatMap { FilterSettings().domains(it) }).forEach {
            assertEquals(it, suffixes.validate(it))
        }
    }
}
