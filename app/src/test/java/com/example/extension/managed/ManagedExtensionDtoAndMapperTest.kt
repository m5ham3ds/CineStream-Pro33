package com.example.extension.managed

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.repository.ManagedExtensionDto
import com.example.extension.managed.repository.ManagedExtensionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExtensionDtoAndMapperTest {

    @Test
    fun fromMap_mapsAllStandardFieldsCorrectly() {
        val map = mapOf(
            "id" to "egydead-v1",
            "name" to "EgyDead Server",
            "description" to "EgyDead Movie and Series Provider",
            "baseUrl" to "https://tv10.egydead.live",
            "iconUrl" to "https://tv10.egydead.live/icon.png",
            "scraperKey" to "egydead",
            "definitionVersion" to 2L,
            "minAppVersionCode" to 10L,
            "runtimeApiVersion" to 1L,
            "priority" to 15L,
            "language" to "ar",
            "contentTypes" to listOf("MOVIE", "SERIES", "ANIME"),
            "status" to "ACTIVE",
            "updatedAt" to 1700000000000L
        )

        val dto = ManagedExtensionDto.fromMap("doc-id-fallback", map)
        assertEquals("egydead-v1", dto.id)
        assertEquals("EgyDead Server", dto.name)
        assertEquals("https://tv10.egydead.live", dto.baseUrl)
        assertEquals("egydead", dto.scraperKey)
        assertEquals(2L, dto.definitionVersion)
        assertEquals(10L, dto.minAppVersionCode)
        assertEquals(1L, dto.runtimeApiVersion)
        assertEquals(15L, dto.priority)
        assertEquals(listOf("MOVIE", "SERIES", "ANIME"), dto.contentTypes)
        assertEquals("ACTIVE", dto.status)
        assertEquals(1700000000000L, dto.updatedAt)

        val domain = ManagedExtensionMapper.toDomain(dto, localUserEnabled = true)
        assertEquals("egydead-v1", domain.id)
        assertEquals("EgyDead Server", domain.name)
        assertEquals("https://tv10.egydead.live", domain.baseUrl)
        assertEquals("egydead", domain.scraperKey)
        assertEquals(2, domain.definitionVersion)
        assertEquals(10L, domain.minAppVersionCode)
        assertEquals(1, domain.runtimeApiVersion)
        assertEquals(15, domain.priority)
        assertEquals("ar", domain.language)
        assertEquals(setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME), domain.contentTypes)
        assertEquals(ExtensionLifecycleStatus.ACTIVE, domain.status)
        assertTrue(domain.userEnabled)
    }

    @Test
    fun missingId_fallsBackToDocumentId() {
        val map = mapOf(
            "name" to "No Id Provider",
            "baseUrl" to "https://tv10.egydead.live",
            "scraperKey" to "egydead"
        )
        val dto = ManagedExtensionDto.fromMap("auto-doc-id-99", map)
        assertEquals("auto-doc-id-99", dto.id)

        val domain = ManagedExtensionMapper.toDomain(dto)
        assertEquals("auto-doc-id-99", domain.id)
    }

    @Test
    fun contentTypesSynonyms_parsedCorrectly() {
        val dto = ManagedExtensionDto(
            id = "synonym-test",
            name = "Synonym",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            contentTypes = listOf("MOVIES", "SHOWS", "ANIMATION", "ASIAN_DRAMA", "PROGRAMS")
        )
        val domain = ManagedExtensionMapper.toDomain(dto)
        assertTrue(domain.contentTypes.contains(ContentType.MOVIE))
        assertTrue(domain.contentTypes.contains(ContentType.SERIES))
        assertTrue(domain.contentTypes.contains(ContentType.ANIME))
    }

    @Test
    fun unknownStatus_defaultsToMaintenanceForSafety() {
        val dto = ManagedExtensionDto(
            id = "unknown-status-test",
            name = "Test",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            status = "EXPLODED_STATUS"
        )
        val domain = ManagedExtensionMapper.toDomain(dto)
        assertEquals(ExtensionLifecycleStatus.MAINTENANCE, domain.status)
    }

    @Test
    fun userPreferenceDisabled_reflectedInDomainModel() {
        val dto = ManagedExtensionDto(
            id = "pref-test",
            name = "Pref Test",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead"
        )
        val domain = ManagedExtensionMapper.toDomain(dto, localUserEnabled = false)
        assertFalse("Domain model must reflect local user disablement", domain.userEnabled)
    }
}
