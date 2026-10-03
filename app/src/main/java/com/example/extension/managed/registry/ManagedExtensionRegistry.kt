package com.example.extension.managed.registry

import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry responsible exclusively for managing validated remote ManagedExtension definitions.
 * Decoupled from ScraperRegistry (which manages bundled executable scrapers).
 *
 * Flow:
 * ManagedExtension.scraperKey -> ScraperRegistry.getScraper(key) -> BaseSiteScraper
 */
class ManagedExtensionRegistry(
    initialExtensions: List<ManagedExtension> = emptyList()
) {
    private val extensionsList = CopyOnWriteArrayList<ManagedExtension>(initialExtensions)
    private val _extensionsFlow = MutableStateFlow<List<ManagedExtension>>(initialExtensions)
    val extensionsFlow: StateFlow<List<ManagedExtension>> = _extensionsFlow.asStateFlow()

    fun setExtensions(newExtensions: List<ManagedExtension>) {
        extensionsList.clear()
        extensionsList.addAll(newExtensions)
        _extensionsFlow.value = newExtensions.toList()
    }

    fun getAllExtensions(): List<ManagedExtension> {
        return extensionsList.toList()
    }

    fun getActiveExtensions(): List<ManagedExtension> {
        return extensionsList.filter { it.status == ExtensionLifecycleStatus.ACTIVE }
    }

    fun getExtension(id: String): ManagedExtension? {
        return extensionsList.firstOrNull { it.id == id }
    }

    fun getExtensionById(id: String): ManagedExtension? {
        return getExtension(id)
    }

    fun getExtensionByScraperKey(scraperKey: String): ManagedExtension? {
        return extensionsList.firstOrNull { it.scraperKey.equals(scraperKey, ignoreCase = true) }
    }

    fun updateExtensionUserPreference(extensionId: String, enabled: Boolean) {
        val updated = extensionsList.map { ext ->
            if (ext.id == extensionId) ext.copy(userEnabled = enabled) else ext
        }
        setExtensions(updated)
    }

    companion object {
        val INSTANCE: ManagedExtensionRegistry by lazy { ManagedExtensionRegistry() }
    }
}
