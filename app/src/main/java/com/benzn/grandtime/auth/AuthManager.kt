package com.benzn.grandtime.auth

import com.benzn.grandtime.core.LoginState
import kotlinx.coroutines.flow.StateFlow

sealed interface SignInResult {
    data object Success : SignInResult
    data object NewPasswordRequired : SignInResult
    data class Failure(val message: String) : SignInResult
}

interface AuthManager {
    val loginState: StateFlow<LoginState>
    suspend fun silentLogin(): Boolean
    suspend fun signIn(username: String, password: String): SignInResult
    suspend fun signOut()
    /** SP4 上传用:内存 idToken 有效则返回,过期则刷新;失败返回 null。本期不调用。 */
    suspend fun freshIdToken(): String?
}
