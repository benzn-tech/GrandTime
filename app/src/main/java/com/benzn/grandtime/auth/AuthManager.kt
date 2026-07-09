package com.benzn.grandtime.auth

import com.benzn.grandtime.core.LoginState
import kotlinx.coroutines.flow.StateFlow

interface AuthManager {
    val loginState: StateFlow<LoginState>
    suspend fun silentLogin(): Boolean
}
