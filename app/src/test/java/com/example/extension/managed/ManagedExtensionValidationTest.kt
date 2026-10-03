package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExtensionValidationTest {

    private fun createValidExtension(
        id: String = "egydead-prod",
        baseUrl: String = "https://tv10.egydead.live",
        scraperKey: String = "egydead",
        priority: Int = 10,
        minAppVersionCode: Long = 1L,
        runtimeApiVersion: Int = 1,
        contentTypes: Set<ContentType> = setOf(ContentType.MOVIE, ContentType.SERIES)
    ): ManagedExtension {
        return ManagedExtension(
            id = id,
            name = "EgyDead",
            baseUrl = baseUrl,
            scraperKey = scraperKey,
            priority = priority,
            minAppVersionCode = minAppVersionCode,
            runtimeApiVersion = runtimeApiVersion,
            contentTypes = contentTypes,
            status = ExtensionLifecycleStatus.ACTIVE
        )
    }

    @Test
    fun validExtension_passesValidation() {
        val ext = createValidExtension()
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue("Valid extension should pass validation", result is ManagedExtensionValidator.ValidationResult.Valid)
    }

    @Test
    fun blankId_failsValidation() {
        val ext = createValidExtension(id = "   ")
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
        val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
        assertTrue(error is ExtensionError.InvalidBaseUrl)
    }

    @Test
    fun blankScraperKey_failsValidation() {
        val ext = createValidExtension(scraperKey = "")
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
        val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
        assertTrue(error is ExtensionError.UnknownScraperKey)
    }

    @Test
    fun httpUrl_failsValidation() {
        val ext = createValidExtension(baseUrl = "http://tv10.egydead.live")
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
        val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
        assertTrue(error is ExtensionError.InvalidBaseUrl)
        assertTrue(error.message.contains("Only HTTPS", ignoreCase = true))
    }

    @Test
    fun malformedUrl_failsValidation() {
        val ext = createValidExtension(baseUrl = "not a valid url")
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
    }

    @Test
    fun localIpTarget_failsValidation() {
        val localHosts = listOf(
            "https://localhost/api",
            "https://127.0.0.1:8080/test",
            "https://192.168.1.100",
            "https://10.0.0.1",
            "https://172.20.0.5",
            "https://0.0.0.0"
        )
        for (host in localHosts) {
            val ext = createValidExtension(baseUrl = host)
            val result = ManagedExtensionValidator.validate(ext)
            assertTrue("Host $host should be rejected", result is ManagedExtensionValidator.ValidationResult.Invalid)
            val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
            assertTrue(error is ExtensionError.InvalidBaseUrl)
            assertTrue(error.message.contains("Private or local IP", ignoreCase = true))
        }
    }

    @Test
    fun negativePriority_failsValidation() {
        val ext = createValidExtension(priority = -5)
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
    }

    @Test
    fun negativeMinAppVersionCode_failsValidation() {
        val ext = createValidExtension(minAppVersionCode = -1L)
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
    }

    @Test
    fun invalidRuntimeApiVersion_failsValidation() {
        val ext = createValidExtension(runtimeApiVersion = 0)
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
        val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
        assertTrue(error is ExtensionError.IncompatibleRuntime)
    }

    @Test
    fun emptyContentTypes_failsValidation() {
        val ext = createValidExtension(contentTypes = emptySet())
        val result = ManagedExtensionValidator.validate(ext)
        assertTrue(result is ManagedExtensionValidator.ValidationResult.Invalid)
        val error = (result as ManagedExtensionValidator.ValidationResult.Invalid).error
        assertTrue(error is ExtensionError.ContentTypeUnsupported)
    }
}
