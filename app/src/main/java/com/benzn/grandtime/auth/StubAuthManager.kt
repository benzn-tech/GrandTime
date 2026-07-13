package com.benzn.grandtime.auth

import com.benzn.grandtime.core.LoginState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 子项目1 打桩:恒登录成功。子项目2 换 Cognito 实现,接口不变。 */
class StubAuthManager : AuthManager {
    private val _loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    override val loginState: StateFlow<LoginState> = _loginState

    override suspend fun silentLogin(): Boolean {
        _loginState.value = LoginState.LoggedIn("Test account")
        return true
    }

    override suspend fun signIn(username: String, password: String): SignInResult = SignInResult.Success
    override suspend fun signOut() { _loginState.value = LoginState.LoggedOut }
    override suspend fun freshIdToken(): String? = null
}
