package ai.secondfactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of SecondFactor.kt that decide what may be opened and what a
 * return URL says. They are plain Kotlin, so they run on the JVM without a
 * device. The same cases as the JavaScript library's tests.
 */
class SecondFactorTest {
    @Test
    fun acceptsVerificationPages() {
        for (url in listOf(
            "https://verify.secondfactor.ai/#vst_Qm9yZWFsaXMgb2YgdGhlIG5vcnRo-_x",
            "https://uat-verify.secondfactor.ai/#vst_abc",
            "http://verify.localhost:5175/#vst_abc",
            "http://localhost:5175/#vst_abc",
            "http://127.0.0.1:5175/#vst_abc",
            "http://[::1]:3000/fake-verify?sid=VSN1#vst_demo",
        )) {
            assertTrue(url, SecondFactor.isVerificationPageUrl(url))
        }
    }

    @Test
    fun refusesEverythingElse() {
        for (url in listOf(
            "javascript:alert(document.cookie)//#vst_abc",
            "data:text/html,<script>alert(1)</script>#vst_abc",
            "intent://verify.secondfactor.ai/#Intent;scheme=https;end#vst_abc",
            "http://verify.secondfactor.ai/#vst_abc", // plain http off a loopback host
            "https://verify.secondfactor.ai/", // no client token
            "https://verify.secondfactor.ai/#vsr_abc", // a return token, not a client token
            "https://user:pass@verify.secondfactor.ai/#vst_abc",
            "//verify.secondfactor.ai/#vst_abc",
            "https://verify.secondfactor.ai/#vst_abc def",
            "",
        )) {
            assertFalse(url, SecondFactor.isVerificationPageUrl(url))
        }
    }

    @Test
    fun readsSessionIdAndReturnToken() {
        assertEquals(
            VerificationReturn("VSN26H7K3MQ2XWZ8RT4", "vsr_abc-_9"),
            SecondFactor.parseVerificationReturn(
                "https://app.example.com/verified?step=2&sf_session_id=VSN26H7K3MQ2XWZ8RT4&sf_return_token=vsr_abc-_9#top",
            ),
        )
    }

    @Test
    fun hasNoReturnTokenWhenUserDidNotVerify() {
        assertEquals(
            VerificationReturn("VSN1", null),
            SecondFactor.parseVerificationReturn("https://app.example.com/back?sf_session_id=VSN1&sf_return_token="),
        )
    }

    @Test
    fun isNullForUrlsTheHostedPageDidNotSend() {
        for (url in listOf(
            "https://app.example.com/verified",
            "https://app.example.com/verified?sf_return_token=vsr_abc",
            "https://app.example.com/verified#?sf_session_id=VSN1",
            "",
            null,
        )) {
            assertNull(url, SecondFactor.parseVerificationReturn(url))
        }
    }

    @Test
    fun decodesAndKeepsTheFirstOfARepeatedParameter() {
        assertEquals(
            VerificationReturn("VSN 1", "vsr_a"),
            SecondFactor.parseVerificationReturn(
                "https://app.example.com/v?sf_session_id=VSN%201&sf_return_token=vsr_a&sf_return_token=vsr_b&bad=%E0%A4%A",
            ),
        )
    }

    @Test
    fun neverPrintsTheReturnToken() {
        val printed = VerificationReturn("VSN1", "vsr_secret").toString()
        assertFalse(printed, "vsr_secret" in printed)
    }
}
