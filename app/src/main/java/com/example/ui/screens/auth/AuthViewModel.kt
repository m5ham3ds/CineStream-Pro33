package com.example.ui.screens.auth

import android.net.Uri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.repository.AuthRepository
import com.example.data.repository.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuthRepository
    
    val currentUser: StateFlow<User?> = repository.currentUserFlow
    
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        init {
        repository.auth.addAuthStateListener { auth ->
            viewModelScope.launch {
                val currentAuth = auth.currentUser
                if (currentAuth != null) {
                    if (repository.currentUserFlow.value == null || repository.currentUserFlow.value?.uid != currentAuth.uid) {
                        repository.getCurrentUser()
            if (repository.auth.currentUser != null) {
                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
            }
                    }
                } else {
                    repository.getCurrentUser()
            if (repository.auth.currentUser != null) {
                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
            }
                }
            }
        }
    }

    fun checkCurrentUser() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getCurrentUser()
            if (repository.auth.currentUser != null) {
                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
            }
            _isLoading.value = false
        }
    }

    fun resetError() {
        _authError.value = null
    }

    fun handleGoogleSignIn(idToken: String, email: String?, displayName: String?, photoUrl: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.signInWithCredential(credential).await() }
                val firebaseUser = authResult.user
                
                if (firebaseUser != null) {
                    try {
                        val snapshot = kotlinx.coroutines.withTimeout(15000) { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(firebaseUser.uid).get().await() }
                        if (snapshot.exists()) {
                            val user = User.fromDocument(snapshot)
                            repository.currentUserFlow.value = user
                            com.example.data.repository.UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                            com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                        } else {
                            val generatedUsername = try { repository.generateUniqueUsername(email?.substringBefore("@") ?: "user") } catch(e:Exception) { "user_" + firebaseUser.uid.take(5) }
                            val newUser = User(
                                uid = firebaseUser.uid,
                                email = email ?: firebaseUser.email ?: "",
                                firstName = displayName?.substringBefore(" ") ?: "",
                                lastName = displayName?.substringAfter(" ", "") ?: "",
                                username = generatedUsername,
                                photoUrl = photoUrl ?: firebaseUser.photoUrl?.toString() ?: ""
                            )
                            kotlinx.coroutines.withTimeoutOrNull(15000) { repository.saveUser(newUser) }
                            repository.currentUserFlow.value = newUser
                            com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                        }
                    } catch (e: Exception) {
                        repository.auth.signOut()
                        _authError.value = getApplication<Application>().getString(R.string.network_error_load_profile)
                        repository.currentUserFlow.value = null
                    }
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: getApplication<Application>().getString(R.string.auth_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }


    fun signInWithEmail(email: String, pass: String) {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            _authError.value = getApplication<android.app.Application>().getString(com.example.R.string.no_internet_check_connection)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.signInWithEmailAndPassword(email, pass).await() }
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    try {
                        val snapshot = kotlinx.coroutines.withTimeout(15000) { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(firebaseUser.uid).get().await() }
                        if (snapshot.exists()) {
                            val user = User.fromDocument(snapshot)
                            repository.currentUserFlow.value = user
                            com.example.data.repository.UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                            com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                        } else {
                            // User document missing? Rare, but create it.
                            val generatedUsername = try { repository.generateUniqueUsername(email.substringBefore("@")) } catch(e:Exception) { "user_" + firebaseUser.uid.take(5) }
                            val user = User(
                                uid = firebaseUser.uid,
                                email = email,
                                firstName = "",
                                lastName = "",
                                username = generatedUsername,
                                photoUrl = ""
                            )
                            kotlinx.coroutines.withTimeoutOrNull(15000) { repository.saveUser(user) }
                            repository.currentUserFlow.value = user
                        }
                    } catch (e: Exception) {
                        repository.auth.signOut()
                        _authError.value = getApplication<Application>().getString(R.string.network_error_load_profile)
                        repository.currentUserFlow.value = null
                    }
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: getApplication<Application>().getString(R.string.login_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signUpWithEmail(email: String, pass: String) {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            _authError.value = getApplication<android.app.Application>().getString(com.example.R.string.no_internet_check_connection)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.createUserWithEmailAndPassword(email, pass).await() }
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    val generatedUsername = try { repository.generateUniqueUsername(email.substringBefore("@")) } catch(e:Exception) { "user_" + firebaseUser.uid.take(5) }
                    val newUser = User(
                        uid = firebaseUser.uid,
                        email = email,
                        firstName = "",
                        lastName = "",
                        username = generatedUsername,
                        photoUrl = ""
                    )
                    try {
                        kotlinx.coroutines.withTimeout(15000) { repository.saveUser(newUser) }
                    } catch (e: Exception) {}
                    repository.currentUserFlow.value = newUser
                            com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: getApplication<Application>().getString(R.string.signup_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.auth.sendPasswordResetEmail(email).await()
                _authError.value = getApplication<Application>().getString(R.string.password_reset_sent)
            } catch (e: Exception) {
                _authError.value = e.message ?: getApplication<Application>().getString(R.string.failed_send_reset_email)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProfile(firstName: String, lastName: String, username: String, isProfilePublic: Boolean = true, photoUri: Uri? = null, onComplete: (Boolean, String?) -> Unit) {
        val safeUsername = username.lowercase().replace(" ", "").trim()
        viewModelScope.launch {
            _isLoading.value = true
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData == null) {
                onComplete(false, getApplication<Application>().getString(R.string.user_not_found))
                _isLoading.value = false
                return@launch
            }
            
            try {
                if (username != currentUserData.username) {
                    val isTaken = repository.isUsernameTaken(safeUsername, currentUserData.uid)
                    if (isTaken) {
                        onComplete(false, getApplication<Application>().getString(R.string.username_already_taken))
                        _isLoading.value = false
                        return@launch
                    }
                }
                
                var finalPhotoUrl = currentUserData.photoUrl
                if (photoUri != null) {
                    val uploadedUrl = repository.uploadProfilePicture(currentUserData.uid, photoUri)
                    if (uploadedUrl != null) {
                        finalPhotoUrl = uploadedUrl
                    } else {
                        onComplete(false, getApplication<Application>().getString(R.string.failed_upload_image))
                        _isLoading.value = false
                        return@launch
                    }
                }
                
                val updatedUser = currentUserData.copy(
                    firstName = firstName,
                    lastName = lastName,
                    username = safeUsername,
                    photoUrl = finalPhotoUrl,
                    isProfilePublic = isProfilePublic
                )
                kotlinx.coroutines.withTimeout(15000) { repository.saveUser(updatedUser) }
                
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, getApplication<Application>().getString(R.string.failed_save_server))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateBio(newBio: String) {
        val safeBio = newBio.take(100)
        viewModelScope.launch {
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData != null) {
                val updatedUser = currentUserData.copy(bio = safeBio)
                repository.currentUserFlow.value = updatedUser
                try {
                    kotlinx.coroutines.withTimeout(10000) { repository.saveUser(updatedUser) }
                } catch (e: Exception) {}
            }
        }
    }

    fun updateProfilePictureDirect(photoUri: Uri?, avatarUrl: String? = null, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData == null) {
                _isLoading.value = false
                onComplete(false)
                return@launch
            }
            try {
                var finalUrl = avatarUrl ?: currentUserData.photoUrl
                if (photoUri != null) {
                    val uploadedUrl = repository.uploadProfilePicture(currentUserData.uid, photoUri)
                    if (uploadedUrl != null) {
                        finalUrl = uploadedUrl
                    }
                }
                val updatedUser = currentUserData.copy(photoUrl = finalUrl)
                repository.currentUserFlow.value = updatedUser
                kotlinx.coroutines.withTimeout(10000) { repository.saveUser(updatedUser) }
                onComplete(true)
            } catch (e: Exception) {
                onComplete(false)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, onComplete: (Boolean, String?) -> Unit) {
        val user = repository.auth.currentUser
        if (user == null) {
            onComplete(false, getApplication<Application>().getString(R.string.no_logged_in_user))
            return
        }
        if (user.email.isNullOrBlank()) {
            onComplete(false, getApplication<Application>().getString(R.string.account_no_email))
            return
        }
        if (newPassword.length < 6) {
            onComplete(false, getApplication<Application>().getString(R.string.password_min_length))
            return
        }

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(user.email!!, currentPassword)
                user.reauthenticate(credential).await()
                user.updatePassword(newPassword).await()
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.localizedMessage ?: getApplication<Application>().getString(R.string.failed_update_password))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signOut(onFailure: ((String) -> Unit)? = null): Boolean {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            val errorMsg = getApplication<android.app.Application>().getString(com.example.R.string.cannot_logout_offline)
            _authError.value = errorMsg
            onFailure?.invoke(errorMsg)
            return false
        }
        val oldUid = repository.auth.currentUser?.uid
        repository.auth.signOut()
        viewModelScope.launch { 
            if (!oldUid.isNullOrBlank()) {
                try {
                    com.example.data.notification.FcmTokenManager.getInstance(getApplication()).onUserSignedOut(oldUid)
                } catch (_: Exception) {}
            }
            try {
                com.example.data.repository.NotificationPreferencesRepository(getApplication()).resetToDefaults()
            } catch (_: Exception) {}
            com.example.data.sync.CloudSyncManager(getApplication()).clearLocalData()
            repository.getCurrentUser()
            if (repository.auth.currentUser != null) {
                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
            } 
        }
        return true
    }
}