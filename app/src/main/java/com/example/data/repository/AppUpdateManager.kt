package com.example.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.model.AppConfig
import com.example.utils.NetworkUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AppUpdateInfo(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val isMandatory: Boolean = false,
    val publishedAt: Long = System.currentTimeMillis(),
    val currentVersionName: String = ""
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult()
    object NoInternet : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    const val APP_UPDATES_COLLECTION = "app_updates"

    fun getCurrentVersionInfo(context: Context): Pair<Long, String> {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            Pair(code, pInfo.versionName ?: "1.0.0")
        } catch (e: Exception) {
            Pair(1L, "1.0.0")
        }
    }

    /**
     * Parses an update document from /app_updates/{updateId} or simulated payload.
     * Evaluates version comparison deterministically using numerical versionCode.
     */
    fun parseUpdateDoc(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        currentCode: Long,
        currentVersionName: String = "1.0.0"
    ): AppUpdateInfo? {
        val isActive = when (val active = doc.get("isActive") ?: doc.get("published") ?: doc.get("active")) {
            is Boolean -> active
            is String -> active.trim().equals("true", ignoreCase = true) ||
                         active.trim().equals("published", ignoreCase = true) ||
                         active.trim().equals("active", ignoreCase = true)
            is Number -> active.toInt() == 1
            else -> true
        }
        if (!isActive) return null

        val targetCode = when (val c = doc.get("versionCode") ?: doc.get("latestVersionCode") ?: doc.get("version_code")) {
            is Number -> c.toLong()
            is String -> c.toLongOrNull() ?: 0L
            else -> 0L
        }
        if (targetCode <= 0L) return null

        val targetName = doc.getString("version")
            ?: doc.getString("versionName")
            ?: doc.getString("latestVersionName")
            ?: doc.getString("version_name")
            ?: "v$targetCode"

        val notes = doc.getString("releaseNotes")
            ?: doc.getString("notes")
            ?: doc.getString("description")
            ?: doc.getString("release_notes")
            ?: ""

        val url = doc.getString("updateUrl")
            ?: doc.getString("downloadUrl")
            ?: doc.getString("apkUrl")
            ?: doc.getString("apk_url")
            ?: ""

        val minCode = when (val m = doc.get("minVersionCode") ?: doc.get("minimumVersionCode") ?: doc.get("min_version_code")) {
            is Number -> m.toLong()
            is String -> m.toLongOrNull() ?: 0L
            else -> 0L
        }

        val isMandatoryFlag = when (val mand = doc.get("isMandatory") ?: doc.get("mandatory") ?: doc.get("forceUpdate") ?: doc.get("mandatoryUpdate")) {
            is Boolean -> mand
            is String -> mand.trim().equals("true", ignoreCase = true) || mand.trim() == "1"
            is Number -> mand.toInt() == 1
            else -> false
        }

        val createdAt = when (val ca = doc.get("createdAt") ?: doc.get("publishedAt") ?: doc.get("updatedAt")) {
            is com.google.firebase.Timestamp -> ca.toDate().time
            is Number -> ca.toLong()
            else -> System.currentTimeMillis()
        }

        val isBelowMin = currentCode < minCode
        val isUpdateAvailable = targetCode > currentCode && url.isNotBlank()
        val isMandatory = isBelowMin || (isUpdateAvailable && isMandatoryFlag)

        return AppUpdateInfo(
            versionCode = targetCode,
            versionName = targetName,
            releaseNotes = notes,
            downloadUrl = url,
            isMandatory = isMandatory,
            publishedAt = createdAt,
            currentVersionName = currentVersionName
        )
    }

    /**
     * Checks for updates and returns a detailed UpdateCheckResult.
     * Priority 1: Canonical /app_updates/{updateId} collection.
     * Priority 2: Fallback to /config/app AppConfig document.
     */
    suspend fun checkForUpdateResult(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isInternetAvailable(context)) {
            return@withContext UpdateCheckResult.NoInternet
        }

        try {
            val (currentCode, currentVersionName) = getCurrentVersionInfo(context)
            val firestore = FirebaseFirestore.getInstance()

            // 1. Try canonical /app_updates collection
            try {
                val updatesSnapshot = withTimeoutOrNull(7000L) {
                    try {
                        firestore.collection(APP_UPDATES_COLLECTION)
                            .get(Source.SERVER).await()
                    } catch (e: Exception) {
                        try {
                            firestore.collection(APP_UPDATES_COLLECTION).get().await()
                        } catch (e2: Exception) {
                            null
                        }
                    }
                }

                if (updatesSnapshot != null && !updatesSnapshot.isEmpty) {
                    var latestActiveInfo: AppUpdateInfo? = null
                    var maxActiveCode = currentCode
                    var latestVersionLabel = currentVersionName
                    var hasValidActiveDoc = false

                    for (doc in updatesSnapshot.documents) {
                        val parsed = parseUpdateDoc(doc, currentCode, currentVersionName) ?: continue
                        hasValidActiveDoc = true
                        if (parsed.versionCode > maxActiveCode) {
                            maxActiveCode = parsed.versionCode
                            latestVersionLabel = parsed.versionName
                            if (parsed.versionCode > currentCode || parsed.isMandatory) {
                                latestActiveInfo = parsed
                            }
                        }
                    }

                    if (latestActiveInfo != null) {
                        return@withContext UpdateCheckResult.UpdateAvailable(latestActiveInfo)
                    } else if (hasValidActiveDoc) {
                        return@withContext UpdateCheckResult.UpToDate(
                            currentVersion = currentVersionName,
                            latestVersion = latestVersionLabel
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AppUpdateManager", "Notice: /app_updates check skipped: ${e.message}")
            }

            // 2. Fallback to /config/app
            val fetchedDoc = withTimeoutOrNull(10000L) {
                val docRef = firestore.collection(AppConfig.CONFIG_COLLECTION)
                    .document(AppConfig.CONFIG_DOCUMENT)

                val doc = try {
                    docRef.get(Source.SERVER).await()
                } catch (e: Exception) {
                    try {
                        docRef.get().await()
                    } catch (e2: Exception) {
                        null
                    }
                }

                doc
            }

            if (fetchedDoc == null || !fetchedDoc.exists()) {
                val isAr = java.util.Locale.getDefault().language == "ar"
                return@withContext UpdateCheckResult.Error(
                    if (isAr) "تعذر الاتصال بخادم التحديثات، يرجى المحاولة لاحقاً"
                    else "Unable to reach update server, please try again later"
                )
            }

            val config = AppConfig.fromDocument(fetchedDoc)

            val isBelowMin = currentCode < config.minVersionCode
            val isUpdateAvailable = config.latestVersionCode > currentCode && config.apkUrl.isNotBlank()
            val isMandatory = isBelowMin || (isUpdateAvailable && config.forceUpdate)

            if (isUpdateAvailable || isBelowMin) {
                val info = AppUpdateInfo(
                    versionCode = config.latestVersionCode,
                    versionName = if (config.latestVersionName.isNotBlank()) config.latestVersionName else "v${config.latestVersionCode}",
                    releaseNotes = config.releaseNotes,
                    downloadUrl = config.apkUrl,
                    isMandatory = isMandatory,
                    publishedAt = config.updatedAt,
                    currentVersionName = currentVersionName
                )
                UpdateCheckResult.UpdateAvailable(info)
            } else {
                UpdateCheckResult.UpToDate(
                    currentVersion = currentVersionName,
                    latestVersion = if (config.latestVersionName.isNotBlank()) config.latestVersionName else currentVersionName
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to connect to update server")
        }
    }

    /**
     * Checks for updates matching the Firebase Contract.
     * Backwards-compatible with callers expecting AppUpdateInfo?
     */
    suspend fun checkForUpdate(context: Context): AppUpdateInfo? {
        return when (val result = checkForUpdateResult(context)) {
            is UpdateCheckResult.UpdateAvailable -> result.info
            else -> null
        }
    }
}
