package com.example.extension.managed

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.registry.ManagedExtensionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExtensionRegistryTest {

    private fun sampleExtension(
        id: String,
        status: ExtensionLifecycleStatus = ExtensionLifecycleStatus.ACTIVE,
        userEnabled: Boolean = true
    ) = ManagedExtension(
        id = id,
        name = "Extension $id",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        contentTypes = setOf(ContentType.MOVIE),
        status = status,
        userEnabled = userEnabled
    )

    @Test
    fun setAndGetAllExtensions_worksAsExpected() {
        val registry = ManagedExtensionRegistry()
        val list = listOf(sampleExtension("ext-1"), sampleExtension("ext-2"))
        registry.setExtensions(list)

        assertEquals(2, registry.getAllExtensions().size)
        assertEquals("ext-1", registry.getExtension("ext-1")?.id)
        assertNull(registry.getExtension("non-existent"))
    }

    @Test
    fun getActiveExtensions_filtersOnlyByGlobalAdminActiveStatus() {
        val registry = ManagedExtensionRegistry()
        val extActive = sampleExtension("active-1", status = ExtensionLifecycleStatus.ACTIVE, userEnabled = true)
        val extMaintenance = sampleExtension("maint-1", status = ExtensionLifecycleStatus.MAINTENANCE, userEnabled = true)
        val extDisabledGlobal = sampleExtension("dis-1", status = ExtensionLifecycleStatus.DISABLED, userEnabled = true)
        val extDeprecated = sampleExtension("dep-1", status = ExtensionLifecycleStatus.DEPRECATED, userEnabled = true)
        val extUserDisabled = sampleExtension("user-dis-1", status = ExtensionLifecycleStatus.ACTIVE, userEnabled = false)

        registry.setExtensions(listOf(extActive, extMaintenance, extDisabledGlobal, extDeprecated, extUserDisabled))

        val active = registry.getActiveExtensions()
        // Phase 05C: Admin status ACTIVE has sole authority; both active-1 and user-dis-1 are returned
        assertEquals("Globally ACTIVE extensions must be returned regardless of userEnabled", 2, active.size)
        val activeIds = active.map { it.id }.toSet()
        assertTrue(activeIds.contains("active-1"))
        assertTrue(activeIds.contains("user-dis-1"))
        assertFalse(activeIds.contains("maint-1"))
        assertFalse(activeIds.contains("dis-1"))
        assertFalse(activeIds.contains("dep-1"))
    }

    @Test
    fun updateExtensionUserPreference_togglesLocalStateWithoutAffectingActiveStatus() {
        val registry = ManagedExtensionRegistry()
        val ext = sampleExtension("ext-toggle", userEnabled = true)
        registry.setExtensions(listOf(ext))

        assertTrue(registry.getExtension("ext-toggle")!!.userEnabled)

        registry.updateExtensionUserPreference("ext-toggle", false)
        assertFalse(registry.getExtension("ext-toggle")!!.userEnabled)
        // Phase 05C: getActiveExtensions remains 1 because status is ACTIVE
        assertEquals(1, registry.getActiveExtensions().size)

        registry.updateExtensionUserPreference("ext-toggle", true)
        assertTrue(registry.getExtension("ext-toggle")!!.userEnabled)
        assertEquals(1, registry.getActiveExtensions().size)
    }
}
