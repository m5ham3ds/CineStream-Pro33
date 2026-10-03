package com.example.extension.managed.repository

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension

/**
 * Pure mapper converting untrusted Firestore DTOs into Domain ManagedExtension models.
 * Applies case-insensitive parsing, fallback defaults, and respects local user preference.
 */
object ManagedExtensionMapper {

    fun toDomain(dto: ManagedExtensionDto, localUserEnabled: Boolean = true): ManagedExtension {
        val id = dto.id.orEmpty().trim()
        val name = dto.name.orEmpty().trim()
        val description = dto.description.orEmpty().trim()
        val baseUrl = dto.baseUrl.orEmpty().trim()
        val iconUrl = dto.iconUrl.orEmpty().trim()
        val scraperKey = dto.scraperKey.orEmpty().trim()
        val definitionVersion = dto.definitionVersion?.toInt() ?: 1
        val minAppVersionCode = dto.minAppVersionCode ?: 1L
        val runtimeApiVersion = dto.runtimeApiVersion?.toInt() ?: 1
        val priority = dto.priority?.toInt() ?: 0
        val language = dto.language?.trim()?.ifBlank { "ar" } ?: "ar"

        val contentTypes: Set<ContentType> = dto.contentTypes
            ?.mapNotNull { parseContentType(it) }
            ?.toSet()
            ?: emptySet()

        val status = parseStatus(dto.status)
        val updatedAt = dto.updatedAt ?: 0L

        return ManagedExtension(
            id = id,
            name = name,
            description = description,
            baseUrl = baseUrl,
            iconUrl = iconUrl,
            scraperKey = scraperKey,
            definitionVersion = definitionVersion,
            minAppVersionCode = minAppVersionCode,
            runtimeApiVersion = runtimeApiVersion,
            priority = priority,
            language = language,
            contentTypes = contentTypes,
            status = status,
            updatedAt = updatedAt,
            userEnabled = localUserEnabled
        )
    }

    private fun parseContentType(raw: String?): ContentType? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().uppercase()
        return try {
            ContentType.valueOf(normalized)
        } catch (_: Exception) {
            when (normalized) {
                "MOVIES", "FILM", "FILMS" -> ContentType.MOVIE
                "SERIES", "SHOWS", "TV", "TV_SHOWS", "ASIAN", "DRAMA", "ASIAN_DRAMA", "PROGRAMS" -> ContentType.SERIES
                "ANIME", "ANIMATION" -> ContentType.ANIME
                else -> null
            }
        }
    }

    private fun parseStatus(raw: String?): ExtensionLifecycleStatus {
        if (raw.isNullOrBlank()) return ExtensionLifecycleStatus.ACTIVE
        val normalized = raw.trim().uppercase()
        return try {
            ExtensionLifecycleStatus.valueOf(normalized)
        } catch (_: Exception) {
            // Safe fallback: unknown remote status is treated as MAINTENANCE to prevent unintended execution
            ExtensionLifecycleStatus.MAINTENANCE
        }
    }
}
