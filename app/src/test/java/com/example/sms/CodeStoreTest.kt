package com.example.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeStoreTest {
    @Test
    fun extractCode_matchesDefaultFourToEightDigits() {
        val code = CodeStore.extractCode("Your verification code is 123456.", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("123456", code)
    }

    @Test
    fun extractCode_ignoresMarketingServiceNumber() {
        val text = "中国移动【中国移动 和包】尊敬的用户，和包消暑季活动来啦，最高888商城分等你抽！点击 https://p.10086.cn/i/HX/DP9uNL 马上参与，拒收请回复 R。"
        val code = CodeStore.extractCode(text, "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("", code)
    }

    @Test
    fun extractCode_matchesChineseKeywordBeforeCode() {
        val code = CodeStore.extractCode("【银行】验证码 246810，用于登录，请勿泄露。", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("246810", code)
    }

    @Test
    fun extractCode_matchesChineseKeywordAfterCode() {
        val code = CodeStore.extractCode("246810 是您的验证码，请勿泄露。", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("246810", code)
    }

    @Test
    fun extractCode_matchesAlphaNumericCode() {
        val code = CodeStore.extractCode("Your verification code is AB12CD.", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("AB12CD", code)
    }

    @Test
    fun extractCode_ignoresAlphaWordsWithoutDigits() {
        val code = CodeStore.extractCode("Your verification code is ABCDEF.", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("", code)
    }

    @Test
    fun extractToken_matchesManagedClipboardCode() {
        assertEquals("123456", CodeStore.extractToken("123456"))
        assertEquals("AB12CD", CodeStore.extractToken("AB12CD"))
    }

    @Test
    fun extractToken_ignoresPlainAlphaWord() {
        assertEquals("", CodeStore.extractToken("ABCDEF"))
    }

    @Test
    fun extractCode_usesFirstCaptureGroup() {
        val code = CodeStore.extractCode("code: AB-7890", "code:\\s*([A-Z]{2}-\\d{4})")

        assertEquals("AB-7890", code)
    }

    @Test
    fun extractCode_returnsEmptyWhenNoCodeFound() {
        val code = CodeStore.extractCode("No token here.", "(?<!\\d)(\\d{4,8})(?!\\d)")

        assertEquals("", code)
    }

    @Test
    fun extractCode_fallsBackWhenRegexIsInvalid() {
        val code = CodeStore.extractCode("Verification code 246810", "[")

        assertEquals("246810", code)
    }
}
