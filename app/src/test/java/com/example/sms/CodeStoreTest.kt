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
