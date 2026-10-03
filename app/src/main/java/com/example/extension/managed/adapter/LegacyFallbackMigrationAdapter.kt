package com.example.extension.managed.adapter

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.runtime.FallbackManager

/**
 * Migration & Fallback Adapter governing the boundary between the Managed Extension System
 * and the Legacy Extension System.
 *
 * Rules:
 * 1. Fallback decisions are centralized via FallbackManager.
 * 2. Only recoverable errors (e.g. MediaNotFound, network timeout where fallback makes sense)
 *    are permitted to trigger fallback.
 * 3. Security, compatibility, and configuration errors STRICTLY HALT execution and NEVER
 *    trigger automatic fallback to legacy:
 *    - IncompatibleRuntime
 *    - UnknownScraper
 *    - InvalidConfiguration
 *    - Security/Integrity violations
 * 4. The legacy system remains completely isolated and cannot contaminate the managed pipeline.
 */
class LegacyFallbackMigrationAdapter(
    private val fallbackManager: FallbackManager
) {

    sealed class FallbackDecision {
        data class AllowLegacyFallback(val reason: String) : FallbackDecision()
        data class DisallowFallback(val error: ExtensionError, val reason: String) : FallbackDecision()
    }

    /**
     * Evaluates whether an error from the Managed Extension execution pipeline
     * qualifies for legacy fallback.
     */
    fun evaluateFallbackEligibility(error: Throwable): FallbackDecision {
        if (error !is ExtensionError) {
            return FallbackDecision.DisallowFallback(
                error = ExtensionError.ExtractionFailed("Unclassified error: ${error.message}", error),
                reason = "Unclassified exceptions do not qualify for automatic legacy fallback"
            )
        }

        // Check if the error is considered recoverable by the centralized FallbackManager
        val isRecoverable = fallbackManager.isRecoverable(error)
        if (!isRecoverable) {
            return FallbackDecision.DisallowFallback(
                error = error,
                reason = "Security, compatibility, or configuration errors cannot trigger legacy fallback (${error::class.simpleName})"
            )
        }

        // Security, compatibility, and configuration errors strictly halt execution and never trigger legacy fallback
        if (error is ExtensionError.MissingBundledScraper || error is ExtensionError.UnknownScraperKey) {
            return FallbackDecision.DisallowFallback(
                error = error,
                reason = "Missing or unknown scrapers represent configuration mismatches and cannot trigger legacy fallback"
            )
        }

        // Offline / No Internet: Fallback to legacy cannot fix lack of network
        if (error is ExtensionError.NoInternet) {
            return FallbackDecision.DisallowFallback(
                error = error,
                reason = "No internet connection; offline state prevents both managed and legacy execution"
            )
        }

        // Recoverable error: permit fallback
        return FallbackDecision.AllowLegacyFallback(
            reason = "Managed candidate returned recoverable error: ${error.message}"
        )
    }
}
