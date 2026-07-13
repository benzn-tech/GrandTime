package com.benzn.grandtime.auth

import com.benzn.grandtime.capture.MediaMigrator
import com.benzn.grandtime.capture.MediaStorage
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.db.CaptureRecordDao
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CognitoAuthManager(
    private val client: CognitoClient,
    private val tokenStore: TokenStore,
    private val dao: CaptureRecordDao,
    private val publicRoot: () -> File,
    private val scope: CoroutineScope,
) : AuthManager {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    override val loginState: StateFlow<LoginState> = _loginState

    @Volatile private var idTokenCache: String? = null

    override suspend fun silentLogin(): Boolean {
        val session = tokenStore.load() ?: run {
            applyLoggedOut(); return false
        }
        // 已有持久身份:先按存的用户目录/身份进入登录态(离线也能显示身份 + 落用户目录)
        applyLoggedIn(session)
        // 尽力刷新 idToken(供上传);失败不改变登录态(refresh 过期才登出)
        return when (val r = withContext(Dispatchers.IO) { client.refresh(session.refreshToken) }) {
            is AuthOutcome.Tokens -> { idTokenCache = r.idToken; true }
            is AuthOutcome.Error -> true // 网络问题保留登录态
            AuthOutcome.AuthInvalid -> { // refresh token 真失效 → 登出
                tokenStore.clear(); idTokenCache = null; applyLoggedOut(); false
            }
            AuthOutcome.NewPasswordRequired -> true
        }
    }

    override suspend fun signIn(username: String, password: String): SignInResult {
        return when (val r = withContext(Dispatchers.IO) { client.signIn(username, password) }) {
            is AuthOutcome.Tokens -> {
                val claims = r.idToken.let { JwtDecoder.decode(it) }
                    ?: return SignInResult.Failure("Login failed — please try again")
                val refresh = r.refreshToken
                    ?: return SignInResult.Failure("Login failed — please try again")
                val mediaScope = UserFolder.derive(claims.name, claims.email, claims.sub)
                val displayName = claims.name ?: claims.email ?: mediaScope.namePrefix ?: "User"
                idTokenCache = r.idToken
                tokenStore.save(
                    PersistedSession(refresh, claims.sub, displayName, mediaScope.folder, mediaScope.namePrefix)
                )
                onLoggedIn(claims.sub, displayName, mediaScope)
                SignInResult.Success
            }
            AuthOutcome.NewPasswordRequired -> SignInResult.NewPasswordRequired
            is AuthOutcome.Error -> SignInResult.Failure(r.message)
            // Unreachable via client.signIn() (it uses parseInitiateAuth directly, which never
            // returns AuthInvalid — only refresh() does). Branch exists only for sealed-interface
            // exhaustiveness; kept account-enumeration-safe if it were ever reached.
            AuthOutcome.AuthInvalid -> SignInResult.Failure("Incorrect email or password")
        }
    }

    override suspend fun signOut() {
        tokenStore.clear()
        idTokenCache = null
        applyLoggedOut()
    }

    override suspend fun freshIdToken(): String? {
        idTokenCache?.let { return it }
        val session = tokenStore.load() ?: return null
        return when (val r = withContext(Dispatchers.IO) { client.refresh(session.refreshToken) }) {
            is AuthOutcome.Tokens -> r.idToken.also { idTokenCache = it }
            AuthOutcome.AuthInvalid -> { // refresh token 真失效 → 登出
                tokenStore.clear(); idTokenCache = null; applyLoggedOut(); null
            }
            else -> null // 网络 Error / NewPasswordRequired:保留登录态,session 不动
        }
    }

    // 登录成功首次:设状态 + 回填 + 迁移旧 device 目录到用户目录
    private fun onLoggedIn(sub: String, displayName: String, mediaScope: MediaScope) {
        _loginState.value = LoginState.LoggedIn(displayName, sub)
        AppState.loginState.value = _loginState.value
        AppState.mediaScope.value = mediaScope
        scope.launch(Dispatchers.IO) {
            runCatching { dao.backfillAuthorSub(sub) }
            runCatching {
                val root = publicRoot()
                MediaMigrator.migrateFolder(
                    root = root, fromFolder = "device", toFolder = mediaScope.folder,
                ) { oldPath, newFile -> scope.launch { dao.updatePath(oldPath, newFile.absolutePath) } }
            }
        }
    }

    // silentLogin 恢复:设状态(不重复迁移——迁移只在真登录时做一次;此处目录已是用户目录)
    private fun applyLoggedIn(session: PersistedSession) {
        _loginState.value = LoginState.LoggedIn(session.displayName, session.sub)
        AppState.loginState.value = _loginState.value
        AppState.mediaScope.value = MediaScope(session.folder, session.namePrefix)
    }

    private fun applyLoggedOut() {
        _loginState.value = LoginState.LoggedOut
        AppState.loginState.value = LoginState.LoggedOut
        AppState.mediaScope.value = MediaScope("device", null)
    }
}
