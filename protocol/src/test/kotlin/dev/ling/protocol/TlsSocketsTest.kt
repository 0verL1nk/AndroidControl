package dev.ling.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TlsSocketsTest {

    @Test
    fun fingerprint_normalization_tolerates_colons_spaces_and_case() {
        val canonical = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(canonical, TlsSockets.normalizeFingerprint(canonical.uppercase()))
        assertEquals(
            canonical,
            TlsSockets.normalizeFingerprint(
                "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:" +
                    "B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            ),
        )
        assertEquals(
            canonical,
            TlsSockets.normalizeFingerprint(canonical.chunked(8).joinToString(" ")),
        )
    }

    @Test
    fun sha256_matches_the_known_digest_of_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TlsSockets.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun a_short_fingerprint_is_rejected_before_connecting() {
        // 未连接就应报错，避免"配错指纹却一直重连"这种难查的故障
        assertFailsWith<IllegalArgumentException> {
            TlsSockets.connectPinned("127.0.0.1", 1, "abcd", 100)
        }
    }
}
