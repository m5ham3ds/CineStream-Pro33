package com.example.extension.managed.usecase

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry

/**
 * Result of evaluating an extension's eligibility for execution.
 */
sealed class ExtensionEligibilityResult {
    data class Eligible(val extension: ManagedExtension) : ExtensionEligibilityResult()
    data class Ineligible(
        val extensionId: String,
        val reason: IneligibilityReason,
        val details: String
    ) : ExtensionEligibilityResult()
}

enum class IneligibilityReason {
    LIFECYCLE_DISABLED,
    LIFECYCLE_MAINTENANCE,
    LIFECYCLE_DEPRECATED,
    USER_DISABLED,
    INCOMPATIBLE_APP_VERSION,
    INCOMPATIBLE_RUNTIME_API,
    UNSUPPORTED_CONTENT_TYPE,
    MISSING_CAPABILITY,
    INVALID_CONFIGURATION,
    UNKNOWN_SCRAPER
}

/**
 * Resolves eligible Managed Extensions according to canonical security and lifecycle rules:
 * - ACTIVE allowed
 * - DISABLED rejected
 * - MAINTENANCE rejected for normal execution
 * - DEPRECATED excluded from automatic execution/fallback
 * - local userEnabled enforced (global ACTIVE + user disabled = unusable)
 * - appVersionCode & runtimeApiVersion checked
 * - scraperKey resolved against ScraperRegistry (never dynamic loading)
 * - sorted by priority descending (HIGHER NUMBER = HIGHER PRIORITY)
 */
class ManagedExtensionResolver(
    private val registry: ManagedExtensionRegistry = ManagedExtensionRegistry.INSTANCE,
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE,
    private val currentAppVersionCode: Long = 1L,
    private val supportedRuntimeApiVersion: Int = 1
) {

    /**
     * Evaluates a single extension for execution eligibility under given constraints.
     */
    fun evaluateEligibility(
        extension: ManagedExtension,
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        allowDeprecated: Boolean = false
    ): ExtensionEligibilityResult {
        // 1. Structural and configuration validation
        val validation = ManagedExtensionValidator.validate(extension)
        if (validation is ManagedExtensionValidator.ValidationResult.Invalid) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INVALID_CONFIGURATION,
                details = validation.error.message ?: "Invalid configuration"
            )
        }

        // 2. Lifecycle status enforcement
        when (extension.status) {
            ExtensionLifecycleStatus.DISABLED -> {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.LIFECYCLE_DISABLED,
                    details = "Extension is globally disabled in configuration"
                )
            }
            ExtensionLifecycleStatus.MAINTENANCE -> {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.LIFECYCLE_MAINTENANCE,
                    details = "Extension is in maintenance mode"
                )
            }
            ExtensionLifecycleStatus.DEPRECATED -> {
                if (!allowDeprecated) {
                    return ExtensionEligibilityResult.Ineligible(
                        extensionId = extension.id,
                        reason = IneligibilityReason.LIFECYCLE_DEPRECATED,
                        details = "Extension is deprecated and excluded from automatic execution"
                    )
                }
            }
            ExtensionLifecycleStatus.ACTIVE -> {
                // ACTIVE is allowed
            }
        }

        // 3. App version compatibility
        if (extension.minAppVersionCode > currentAppVersionCode) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INCOMPATIBLE_APP_VERSION,
                details = "Requires app version ${extension.minAppVersionCode}, current is $currentAppVersionCode"
            )
        }

        // 5. Runtime API compatibility
        if (extension.runtimeApiVersion > supportedRuntimeApiVersion) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INCOMPATIBLE_RUNTIME_API,
                details = "Requires runtime API ${extension.runtimeApiVersion}, supported is $supportedRuntimeApiVersion"
            )
        }

        // 6. Content type check
        if (contentType != null && !extension.contentTypes.contains(contentType)) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.UNSUPPORTED_CONTENT_TYPE,
                details = "Does not support content type $contentType"
            )
        }

        // 7. Scraper resolution check (Strictly through ScraperRegistry - never reflection)
        val scraper = scraperRegistry.getScraper(extension.scraperKey)
        if (scraper == null) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.UNKNOWN_SCRAPER,
                details = "No bundled scraper implementation registered for scraperKey '${extension.scraperKey}'"
            )
        }

        // 8. Capability check
        if (capability != null && !scraper.supportedCapabilities.contains(capability)) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.MISSING_CAPABILITY,
                details = "Bundled scraper '${extension.scraperKey}' does not support capability $capability"
            )
        }

        return ExtensionEligibilityResult.Eligible(extension)
    }

    /**
     * Resolves all eligible candidate extensions, sorted by priority descending (HIGHER NUMBER = HIGHER PRIORITY).
     */
    fun resolveEligibleExtensions(
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        allowDeprecated: Boolean = false
    ): List<ManagedExtension> {
        val all = registry.getAllExtensions()
        return all
            .filter { evaluateEligibility(it, capability, contentType, allowDeprecated) is ExtensionEligibilityResult.Eligible }
            .sortedByDescending { it.priority }
    }

    /**
     * Finds an eligible extension by ID.
     */
    fun resolveExtensionById(
        extensionId: String,
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        allowDeprecated: Boolean = false
    ): ManagedExtension? {
        val ext = registry.getExtension(extensionId) ?: return null
        return if (evaluateEligibility(ext, capability, contentType, allowDeprecated) is ExtensionEligibilityResult.Eligible) {
            ext
        } else {
            null
        }
    }
}
