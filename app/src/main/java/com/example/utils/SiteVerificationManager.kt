package com.example.utils

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object SiteVerificationManager {
    val verifiedSites = mutableStateListOf<String>()
    var isVerificationComplete by mutableStateOf(false)
    var isVerificationStarted by mutableStateOf(false)

    fun markSiteVerified(url: String) {
        if (!verifiedSites.contains(url)) {
            verifiedSites.add(url)
        }
    }
}