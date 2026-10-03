package com.example.extension.managed

import com.example.extension.managed.runtime.CredentialProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialProjectorTest {

    @Test
    fun onlyRequiredHeadersAreProjected() {
        val sessionHeaders = mapOf(
            "Referer" to "https://tv10.egydead.live",
            "User-Agent" to "CineStreamEngine/1.0",
            "X-Internal-Token" to "secret-session-token-12345",
            "Authorization" to "Bearer private-user-token"
        )
        val sessionCookies = mapOf(
            "cf_clearance" to "clearance_token_xyz",
            "session_id" to "session_private_999"
        )

        // Request projection of only Referer and cf_clearance
        val projected = CredentialProjector.projectHeaders(
            sessionHeaders = sessionHeaders,
            sessionCookies = sessionCookies,
            requiredHeaderKeys = setOf("referer"),
            requiredCookieKeys = setOf("cf_clearance")
        )

        // Assertions
        assertTrue(projected.containsKey("Referer"))
        assertEquals("https://tv10.egydead.live", projected["Referer"])

        // Sensitive headers must NOT leak
        assertFalse(projected.containsKey("X-Internal-Token"))
        assertFalse(projected.containsKey("Authorization"))

        // Cookies
        assertTrue(projected.containsKey("Cookie"))
        val cookieHeader = projected["Cookie"] ?: ""
        assertTrue(cookieHeader.contains("cf_clearance=clearance_token_xyz"))
        assertFalse("Unrelated session cookie must not be projected", cookieHeader.contains("session_id"))
    }

    @Test
    fun defaultStandardHeaders_usedWhenNoneExplicitlyRequested() {
        val sessionHeaders = mapOf(
            "Referer" to "https://tv10.egydead.live",
            "User-Agent" to "CustomUA",
            "Private-Header" to "value"
        )

        val projected = CredentialProjector.projectHeaders(
            sessionHeaders = sessionHeaders,
            sessionCookies = emptyMap()
        )

        assertTrue(projected.containsKey("Referer"))
        assertTrue(projected.containsKey("User-Agent"))
        assertFalse(projected.containsKey("Private-Header"))
    }
}
