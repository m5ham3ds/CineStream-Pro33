package com.example.extension.managed.repository

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract for caching verified ManagedExtension definitions.
 */
interface ManagedExtensionCache {
    fun getCached(): List<ManagedExtension>?
    fun getLastKnownGood(): List<ManagedExtension>? = getCached()
    fun saveCache(extensions: List<ManagedExtension>)
    fun clear()
    fun clearCache() = clear()
    fun isExpired(): Boolean
    fun getCachedTimestamp(): Long
}

/**
 * Thread-safe persistent local metadata cache with configurable TTL (default: 30 minutes).
 * Enforces:
 * 1. Persistent disk storage surviving process death and application restarts.
 * 2. Re-validation upon cache retrieval to prevent corrupted/tampered metadata exposure.
 * 3. Strict preservation of Admin DISABLED status across restarts and offline mode.
 * 4. Separate getLastKnownGood() retrieval when remote synchronization fails.
 */
class SafeLocalMetadataCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val storageDir: File? = null
) : ManagedExtensionCache {

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30 * 60 * 1000L // 30 minutes
        const val CACHE_FILE_NAME = "managed_extensions_lkg.json"
    }

    private val cachedData = AtomicReference<List<ManagedExtension>?>(null)
    private val cachedTimestamp = AtomicLong(0L)

    init {
        // Attempt immediate restoration from persistent disk cache on startup
        loadFromDisk()
    }

    private fun resolveDir(): File? {
        return storageDir ?: try {
            com.example.MyApplication.appContext.filesDir
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadFromDisk() {
        val dir = resolveDir() ?: return
        val file = File(dir, CACHE_FILE_NAME)
        if (!file.exists() || !file.canRead()) return

        try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<ManagedExtension>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ext = jsonToExtension(obj)
                if (ext != null && ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid) {
                    list.add(ext)
                }
            }
            if (list.isNotEmpty()) {
                cachedData.set(list)
                cachedTimestamp.set(file.lastModified())
            }
        } catch (_: Throwable) {}
    }

    private fun persistToDisk(extensions: List<ManagedExtension>) {
        val dir = resolveDir() ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, CACHE_FILE_NAME)
            val jsonArray = JSONArray()
            for (ext in extensions) {
                jsonArray.put(extensionToJson(ext))
            }
            file.writeText(jsonArray.toString())
        } catch (_: Throwable) {}
    }

    override fun getCached(): List<ManagedExtension>? {
        if (cachedData.get() == null) {
            loadFromDisk()
        }
        val data = cachedData.get() ?: return null
        if (isExpired()) {
            return null
        }

        // Re-validate cached items to ensure integrity
        val validItems = data.filter { ext ->
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
        }

        return if (validItems.isNotEmpty()) validItems else null
    }

    override fun getLastKnownGood(): List<ManagedExtension>? {
        if (cachedData.get() == null) {
            loadFromDisk()
        }
        val data = cachedData.get() ?: return null

        // Re-validate cached items to ensure integrity (regardless of TTL expiration)
        val validItems = data.filter { ext ->
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
        }

        return if (validItems.isNotEmpty()) validItems else null
    }

    override fun saveCache(extensions: List<ManagedExtension>) {
        // Only save validated extensions
        val validated = extensions.filter { ext ->
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
        }
        cachedData.set(validated)
        val now = clock()
        cachedTimestamp.set(now)
        persistToDisk(validated)
    }

    override fun clear() {
        cachedData.set(null)
        cachedTimestamp.set(0L)
        val dir = resolveDir()
        if (dir != null) {
            try {
                val file = File(dir, CACHE_FILE_NAME)
                if (file.exists()) file.delete()
            } catch (_: Throwable) {}
        }
    }

    override fun isExpired(): Boolean {
        val timestamp = cachedTimestamp.get()
        if (timestamp == 0L) return true
        return (clock() - timestamp) > ttlMillis
    }

    override fun getCachedTimestamp(): Long {
        return cachedTimestamp.get()
    }

    private fun extensionToJson(ext: ManagedExtension): JSONObject {
        val obj = JSONObject()
        obj.put("id", ext.id)
        obj.put("name", ext.name)
        obj.put("description", ext.description)
        obj.put("baseUrl", ext.baseUrl)
        obj.put("iconUrl", ext.iconUrl)
        obj.put("scraperKey", ext.scraperKey)
        obj.put("definitionVersion", ext.definitionVersion)
        obj.put("minAppVersionCode", ext.minAppVersionCode)
        obj.put("runtimeApiVersion", ext.runtimeApiVersion)
        obj.put("priority", ext.priority)
        obj.put("language", ext.language)
        val ctArr = JSONArray()
        for (ct in ext.contentTypes) {
            ctArr.put(ct.name)
        }
        obj.put("contentTypes", ctArr)
        obj.put("status", ext.status.name)
        obj.put("updatedAt", ext.updatedAt)
        obj.put("userEnabled", ext.userEnabled)
        return obj
    }

    private fun jsonToExtension(obj: JSONObject): ManagedExtension? {
        return try {
            val id = obj.getString("id")
            val name = obj.getString("name")
            val desc = obj.optString("description", "")
            val baseUrl = obj.getString("baseUrl")
            val iconUrl = obj.optString("iconUrl", "")
            val scraperKey = obj.getString("scraperKey")
            val defVer = obj.optInt("definitionVersion", 1)
            val minAppVer = obj.optLong("minAppVersionCode", 1L)
            val runtimeVer = obj.optInt("runtimeApiVersion", 1)
            val priority = obj.optInt("priority", 0)
            val lang = obj.optString("language", "ar")
            val ctArr = obj.optJSONArray("contentTypes")
            val contentTypes = mutableSetOf<ContentType>()
            if (ctArr != null) {
                for (i in 0 until ctArr.length()) {
                    val str = ctArr.getString(i)
                    try {
                        contentTypes.add(ContentType.valueOf(str.uppercase()))
                    } catch (_: Exception) {}
                }
            }
            val statusStr = obj.optString("status", ExtensionLifecycleStatus.ACTIVE.name)
            val status = try {
                ExtensionLifecycleStatus.valueOf(statusStr.uppercase())
            } catch (_: Exception) {
                ExtensionLifecycleStatus.ACTIVE
            }
            val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            val userEnabled = obj.optBoolean("userEnabled", true)

            ManagedExtension(
                id = id,
                name = name,
                description = desc,
                baseUrl = baseUrl,
                iconUrl = iconUrl,
                scraperKey = scraperKey,
                definitionVersion = defVer,
                minAppVersionCode = minAppVer,
                runtimeApiVersion = runtimeVer,
                priority = priority,
                language = lang,
                contentTypes = contentTypes,
                status = status,
                updatedAt = updatedAt,
                userEnabled = userEnabled
            )
        } catch (_: Exception) {
            null
        }
    }
}
