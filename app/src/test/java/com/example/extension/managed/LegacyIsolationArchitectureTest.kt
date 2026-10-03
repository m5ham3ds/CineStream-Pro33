package com.example.extension.managed

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LegacyIsolationArchitectureTest {

    private val forbiddenLegacyImports = listOf(
        "com.example.extensions.ExtensionManager",
        "com.example.extensions.ProviderExtension",
        "com.example.extensions.ExtensionReflectionWrapper",
        "com.example.extensions.BuiltInExtension",
        "com.example.extensions.BuiltInExtensions",
        "com.example.ui.screens.player.SiteScripts",
        "com.example.ui.screens.player.ServerStateStore",
        "com.example.ui.screens.player.VideoExtractor",
        "com.example.ui.screens.player.ServerSelectionDialog",
        "com.example.ui.components.BatchDownloadProcessor",
        "com.example.data.repository.CloudExtensionsRepository",
        "com.example.data.model.ExtensionItem"
    )

    @Test
    fun verifyManagedPackageHasZeroLegacyDependencies() {
        val rootDir = File("src/main/java/com/example/extension/managed")
        val altRootDir = File("app/src/main/java/com/example/extension/managed")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        if (!targetDir.exists()) {
            fail("Managed extension directory not found: ${targetDir.absolutePath}")
        }

        val kotlinFiles = targetDir.walkTopDown().filter { it.extension == "kt" }.toList()
        if (kotlinFiles.isEmpty()) {
            fail("No Kotlin files found under ${targetDir.absolutePath}")
        }

        val violations = mutableListOf<String>()

        for (file in kotlinFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    for (forbidden in forbiddenLegacyImports) {
                        if (trimmed.contains(forbidden)) {
                            violations.add("${file.name}:${index + 1} imports forbidden legacy class: $forbidden")
                        }
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Architecture violation! Managed package contains forbidden legacy dependencies:\n" +
                    violations.joinToString("\n"))
        }
    }

    @Test
    fun verifyTotalRemovalOfLegacyExtensionCodeFromProject() {
        val rootDir = File("src/main/java")
        val altRootDir = File("app/src/main/java")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        if (!targetDir.exists()) {
            fail("Main source directory not found: ${targetDir.absolutePath}")
        }

        val forbiddenGlobalTokens = listOf(
            "DexClassLoader",
            "PathClassLoader",
            "com.example.extensions.ExtensionManager",
            "DynamicProvidersManager",
            "ScraperRepository",
            "HiddenVideoExtractor",
            "SiteScripts.getScript"
        )

        val kotlinFiles = targetDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val violations = mutableListOf<String>()

        for (file in kotlinFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                for (token in forbiddenGlobalTokens) {
                    if (trimmed.contains(token)) {
                        violations.add("${file.name}:${index + 1} contains legacy token: $token")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Residual legacy code found in Users App:\n" + violations.joinToString("\n"))
        }
    }
}
