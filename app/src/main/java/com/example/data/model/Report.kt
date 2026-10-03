package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Report(
    val id: String = "",
    val reportId: String = id,
    val userId: String = "",
    val userEmail: String = "",
    val type: String = "content",
    val targetType: String = "movie",
    val targetId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "pending",
    val resolutionNotes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val resolvedBy: String? = null,
    // Compatibility fields
    val reason: String = title,
    val details: String = description,
    val contentId: String = targetId,
    val contentType: String = targetType
) {
    fun toFirestoreMap(): Map<String, Any?> {
        val finalId = id.ifBlank { reportId }
        val finalTargetId = targetId.ifBlank { contentId }
        val finalTargetType = targetType.ifBlank { contentType }
        val finalTitle = title.ifBlank { reason }
        val finalDesc = description.ifBlank { details }

        return mapOf(
            "id" to finalId,
            "reportId" to finalId,
            "userId" to userId,
            "userEmail" to userEmail,
            "type" to type,
            "targetType" to finalTargetType,
            "targetId" to finalTargetId,
            "title" to finalTitle,
            "description" to finalDesc,
            "status" to "pending",
            "resolutionNotes" to resolutionNotes,
            "createdAt" to Timestamp.now(),
            "resolvedAt" to null,
            "resolvedBy" to null,
            // Legacy aliases for backwards compatibility
            "reason" to finalTitle,
            "details" to finalDesc,
            "contentId" to finalTargetId,
            "contentType" to finalTargetType
        )
    }

    companion object {
        fun fromDocument(doc: DocumentSnapshot): Report {
            val id = doc.getString("id") ?: doc.getString("reportId") ?: doc.id
            val created = when (val c = doc.get("createdAt")) {
                is Timestamp -> c.toDate().time
                is Number -> c.toLong()
                else -> 0L
            }
            val resolved = when (val r = doc.get("resolvedAt")) {
                is Timestamp -> r.toDate().time
                is Number -> r.toLong()
                else -> null
            }
            val targetType = doc.getString("targetType") ?: doc.getString("contentType") ?: "movie"
            val targetId = doc.getString("targetId") ?: doc.getString("contentId") ?: ""
            val title = doc.getString("title") ?: doc.getString("reason") ?: ""
            val desc = doc.getString("description") ?: doc.getString("details") ?: ""

            return Report(
                id = id,
                reportId = id,
                userId = doc.getString("userId") ?: "",
                userEmail = doc.getString("userEmail") ?: "",
                type = doc.getString("type") ?: "content",
                targetType = targetType,
                targetId = targetId,
                title = title,
                description = desc,
                status = doc.getString("status") ?: "pending",
                resolutionNotes = doc.getString("resolutionNotes") ?: "",
                createdAt = created,
                resolvedAt = resolved,
                resolvedBy = doc.getString("resolvedBy"),
                reason = title,
                details = desc,
                contentId = targetId,
                contentType = targetType
            )
        }
    }
}
