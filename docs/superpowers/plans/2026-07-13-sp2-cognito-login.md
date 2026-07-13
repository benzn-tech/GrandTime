# SP2 Cognito 登录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用真实 FieldSight 账号(Cognito prod 池)在 App 上用户名密码登录,加密存 refresh token 开机静默刷新,登录后回填 author_sub + 把媒体目录/文件名套上用户层,Sign out 可用。

**Architecture:** 裸 HTTP InitiateAuth(OkHttp,与 web cognito.js 同构,不引 Amplify);CognitoAuthManager 进程单例(GrandTimeApp 提供,Service+UI 共享)替换 StubAuthManager;TokenStore(EncryptedSharedPreferences)存 refreshToken/sub/displayName/userFolder;MediaStorage 目录段与文件名前缀由「当前媒体作用域」(AppState.mediaScope)驱动,登录切用户层、登出回 device。纯逻辑(JwtDecoder/结果映射/目录派生/回填 SQL/TokenStore 逻辑)JVM 单测,网络与 UI 真机验收。

**Tech Stack:** 既有栈 + OkHttp 4.12 + androidx.security:security-crypto 1.1.0-alpha06;无 AWS SDK。

Spec(唯一事实来源):`docs/superpowers/specs/2026-07-12-sp2-cognito-login-design.md`

## Global Constraints

- 工作分支:`feature/sp2-cognito-login`(自 main 切出;控制器创建)
- Cognito(2026-07-12 AWS 实测):池 `ap-southeast-2_q88pd6XXr`、client `4ratjdjonqm17tln6bs2761ci3`(无密钥,开 USER_PASSWORD_AUTH)、region `ap-southeast-2`;端点 `https://cognito-idp.ap-southeast-2.amazonaws.com/`,`Content-Type: application/x-amz-json-1.1`
- 登录 = InitiateAuth `AuthFlow=USER_PASSWORD_AUTH`;刷新 `REFRESH_TOKEN_AUTH`(不返回新 refresh token,沿用旧的)
- **不做移动端改密**:NEW_PASSWORD_REQUIRED → 登录页提示「Please set your password in the FieldSight web app first」,停在登录页,不应答挑战
- idToken 的 `sub` claim == users.cognito_sub == 回填 `capture_records.author_sub`;idToken 供 SP4 裸放 Authorization 头(本期仅暴露 freshIdToken 接口不调用)
- 登录后:媒体根段 `device` → `<用户名>_<user-id>`,迁移旧 device 文件 + 更新 DB 路径;新文件名前缀 `<用户名>_yyyyMMdd_HHmmss`;登出回 `device` + `VID_/AUD_/IMG_`
- CognitoAuthManager 单例经 GrandTimeApp 提供,Service 与 UI 共享(修 SP1 终审 M-5);删除 StubAuthManager
- 逻辑层 hardware/keymap/boot 不动;CaptureCore 不动
- gradle 前缀:`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`;真机 `-s F2S202503103054` + `export ANDROID_SERIAL=F2S202503103054`;adb `C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Dropbox 文件锁:重跑一次;持续 → BLOCKED
- 每 Task 一 commit,footer:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014TCKsgA4JjaWbbFD4zY7zh
```

---

### Task 1: 依赖 + INTERNET 权限 + BuildConfig 常量

**Files:**
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `gradle/libs.versions.toml` 增补**

`[versions]` 加:

```toml
okhttp = "4.12.0"
securityCrypto = "1.1.0-alpha06"
```

`[libraries]` 加:

```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
```

- [ ] **Step 2: `app/build.gradle.kts`**

dependencies 加:

```kotlin
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
```

`android { defaultConfig { ... } }` 内加 BuildConfig 常量,`android { buildFeatures { ... } }` 内确保 `buildConfig = true`:

```kotlin
    defaultConfig {
        // ... 既有 ...
        buildConfigField("String", "COGNITO_POOL_ID", "\"ap-southeast-2_q88pd6XXr\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"4ratjdjonqm17tln6bs2761ci3\"")
        buildConfigField("String", "COGNITO_REGION", "\"ap-southeast-2\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

- [ ] **Step 3: Manifest `<manifest>` 根下加**

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 4: 构建门**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL;`BuildConfig.COGNITO_POOL_ID` 可引用。

- [ ] **Step 5: Commit** — `git add -A gradle app && git commit -m "build: OkHttp + security-crypto deps, INTERNET perm, Cognito BuildConfig"`

---

### Task 2: JwtDecoder(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/JwtDecoder.kt`
- Test: `app/src/test/java/com/benzn/grandtime/auth/JwtDecoderTest.kt`

**Interfaces:**
- Produces: `data class JwtClaims(sub: String, email: String?, name: String?)`;`object JwtDecoder { fun decode(idToken: String): JwtClaims? }`——base64url 解 JWT payload 段取 sub/email/name(cognito:username 兜底);畸形/缺 sub 返回 null,不抛。

- [ ] **Step 1: 写失败测试 `JwtDecoderTest.kt`**

```kotlin
package com.benzn.grandtime.auth

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwtDecoderTest {

    private fun jwt(payloadJson: String): String {
        val header = b64("""{"alg":"RS256","typ":"JWT"}""")
        val payload = b64(payloadJson)
        return "$header.$payload.sigsigsig"
    }

    private fun b64(s: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun `decodes sub email name`() {
        val c = JwtDecoder.decode(jwt("""{"sub":"abc-123","email":"a@b.com","name":"Jane Doe"}"""))!!
        assertEquals("abc-123", c.sub)
        assertEquals("a@b.com", c.email)
        assertEquals("Jane Doe", c.name)
    }

    @Test
    fun `missing name and email are null but sub present`() {
        val c = JwtDecoder.decode(jwt("""{"sub":"only-sub"}"""))!!
        assertEquals("only-sub", c.sub)
        assertNull(c.email)
        assertNull(c.name)
    }

    @Test
    fun `no sub returns null`() {
        assertNull(JwtDecoder.decode(jwt("""{"email":"a@b.com"}""")))
    }

    @Test
    fun `malformed token returns null not crash`() {
        assertNull(JwtDecoder.decode("not.a.jwt"))
        assertNull(JwtDecoder.decode("garbage"))
        assertNull(JwtDecoder.decode(""))
    }
}
```

注:测试用 `java.util.Base64`(JVM);实现用同款 java.util.Base64.getUrlDecoder()(minSdk 33 无需 android.util.Base64,纯 JVM 可测)。删掉上面测试里未用的 `import android.util.Base64`。

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL(unresolved JwtDecoder)。

- [ ] **Step 3: 实现 `auth/JwtDecoder.kt`**

```kotlin
package com.benzn.grandtime.auth

import org.json.JSONObject
import java.util.Base64

data class JwtClaims(val sub: String, val email: String?, val name: String?)

/** 解 idToken payload 段(不验签——签名由 Cognito 颁发时保证;本地只读 claim)。纯 JVM。 */
object JwtDecoder {
    fun decode(idToken: String): JwtClaims? = try {
        val parts = idToken.split(".")
        if (parts.size < 2) return null
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        val json = JSONObject(payload)
        val sub = json.optString("sub").takeIf { it.isNotBlank() } ?: return null
        JwtClaims(
            sub = sub,
            email = json.optString("email").takeIf { it.isNotBlank() },
            name = json.optString("name").takeIf { it.isNotBlank() }
                ?: json.optString("cognito:username").takeIf { it.isNotBlank() },
        )
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(4)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: JWT payload decoder (sub/email/name)"`

---

### Task 3: 用户目录派生 + 媒体作用域(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/UserFolder.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt`(+mediaScope)、`app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt`(作用域感知)
- Test: `app/src/test/java/com/benzn/grandtime/auth/UserFolderTest.kt`、`app/src/test/java/com/benzn/grandtime/capture/MediaStorageScopeTest.kt`

**Interfaces:**
- Produces:
  - `data class MediaScope(folder: String, namePrefix: String?)`(folder="device" 或 "<user>_<id>";namePrefix=null 用 VID/AUD/IMG,否则用户名)
  - `object UserFolder { fun sanitize(name: String): String; fun derive(name: String?, email: String?, sub: String): MediaScope }`
  - `AppState.mediaScope: MutableStateFlow<MediaScope>`(默认 `MediaScope("device", null)`)
  - MediaStorage 构造增 `scopeProvider: () -> MediaScope = { MediaScope("device", null) }`;`mediaDir`/`newFile` 用之;companion `mediaSubdir(root, folder, kindDir)`

- [ ] **Step 1: 写失败测试**

`UserFolderTest.kt`:

```kotlin
package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFolderTest {
    @Test fun `sanitize keeps alnum collapses others lowercases`() {
        assertEquals("jane_doe", UserFolder.sanitize("Jane Doe"))
        assertEquals("a_b_com", UserFolder.sanitize("a@b.com"))
        assertEquals("john", UserFolder.sanitize("  John!!  "))
        assertEquals("user", UserFolder.sanitize("@@@"))
    }

    @Test fun `derive uses name then email then user, appends 8 sub chars`() {
        assertEquals(
            MediaScope("jane_doe_abc12345", "jane_doe"),
            UserFolder.derive("Jane Doe", "j@x.com", "abc12345-6789-xxxx"),
        )
        assertEquals(
            MediaScope("j_x_com_sub99999", "j_x_com"),
            UserFolder.derive(null, "j@x.com", "sub99999-0000"),
        )
        assertEquals(
            MediaScope("user_deadbeef", "user"),
            UserFolder.derive(null, null, "deadbeef-1111"),
        )
    }
}
```

`MediaStorageScopeTest.kt`:

```kotlin
package com.benzn.grandtime.capture

import com.benzn.grandtime.auth.MediaScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaStorageScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `logged-out scope uses device folder and kind prefix`() {
        val root = tmp.newFolder("r")
        val s = MediaStorage({ root }, scopeProvider = { MediaScope("device", null) })
        val f = s.newFile(MediaStorage.Kind.VIDEO, 0L)
        assertEquals(File(File(File(root, "FieldSight"), "device"), "video"), f.parentFile)
        assert(f.name.startsWith("VID_")) { f.name }
    }

    @Test fun `logged-in scope uses user folder and username prefix`() {
        val root = tmp.newFolder("r2")
        val s = MediaStorage({ root }, scopeProvider = { MediaScope("jane_abc12345", "jane") })
        val f = s.newFile(MediaStorage.Kind.VIDEO, 0L)
        assertEquals(File(File(File(root, "FieldSight"), "jane_abc12345"), "video"), f.parentFile)
        assert(f.name.startsWith("jane_")) { f.name }
    }
}
```

(既有 MediaStorageTest 若断言 `device`,保持——默认作用域仍 device;新增测试单独覆盖。)

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现**

`auth/UserFolder.kt`:

```kotlin
package com.benzn.grandtime.auth

/** 登录态媒体作用域:folder = 目录段;namePrefix = 文件名前缀(null → 用 kind 前缀 VID/AUD/IMG)。 */
data class MediaScope(val folder: String, val namePrefix: String?)

object UserFolder {
    /** 非字母数字折叠为单个下划线、小写、去首尾下划线;空 → "user"。 */
    fun sanitize(name: String): String {
        val s = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return s.ifBlank { "user" }
    }

    /** 用户名优先 name→email→"user";目录 = <username>_<sub 前 8 位字母数字>。 */
    fun derive(name: String?, email: String?, sub: String): MediaScope {
        val username = sanitize(name ?: email ?: "user")
        val subShort = sub.filter { it.isLetterOrDigit() }.take(8)
        return MediaScope("${username}_$subShort", username)
    }
}
```

`core/AppState.kt` 加(import `com.benzn.grandtime.auth.MediaScope`):

```kotlin
    /** 当前媒体作用域:登出=device/kind 前缀,登录=用户目录/用户名前缀。 */
    val mediaScope = MutableStateFlow(MediaScope("device", null))
```

`capture/MediaStorage.kt` 改:构造增 scopeProvider;mediaDir/newFile 用之;mediaSubdir 增 folder 参:

```kotlin
class MediaStorage(
    private val rootProvider: () -> File,
    private val scopeProvider: () -> com.benzn.grandtime.auth.MediaScope =
        { com.benzn.grandtime.auth.MediaScope("device", null) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Kind 不变
    fun mediaDir(kind: Kind): File =
        mediaSubdir(rootProvider(), scopeProvider().folder, kind.dir).apply { mkdirs() }

    fun newFile(kind: Kind, startMillis: Long = clock()): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMillis))
        val dir = mediaDir(kind)
        val prefix = scopeProvider().namePrefix ?: kind.prefix
        var candidate = File(dir, "${prefix}_$stamp.${kind.ext}")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${prefix}_${stamp}_$suffix.${kind.ext}")
            suffix++
        }
        return candidate
    }
    // hasFreeSpace 不变
    companion object {
        // publicRoot 不变
        fun mediaSubdir(root: File, folder: String, kindDir: String): File =
            File(File(File(root, "FieldSight"), folder), kindDir)
    }
}
```

**注意**:`mediaSubdir` 签名由 `(root, kindDir)` 变为 `(root, folder, kindDir)`——更新既有调用点:
`MediaMigrator.kt`(其 destDir 走 `mediaSubdir(newRoot, "device", kind)`)、`FilesScreen.kt scanDisk`
(改 `mediaSubdir(publicRoot, AppState.mediaScope.value.folder, kind)`)、既有 `MediaStorageTest` 里
若直接调 mediaSubdir 也补 "device" 参。

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(既有全绿 + 新增)。

- [ ] **Step 5: 构建** — `./gradlew assembleDebug`(确认所有 mediaSubdir 调用点已更新)。

- [ ] **Step 6: Commit** — `git add app/src && git commit -m "feat: media scope (user folder + name prefix), scope-aware MediaStorage"`

---

### Task 4: TokenStore(接口 + 加密实现,TDD 逻辑)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/TokenStore.kt`
- Test: `app/src/test/java/com/benzn/grandtime/auth/TokenStoreTest.kt`

**Interfaces:**
- Produces:
  - `interface TokenStore { fun save(session: PersistedSession); fun load(): PersistedSession?; fun clear() }`
  - `data class PersistedSession(refreshToken: String, sub: String, displayName: String, folder: String, namePrefix: String?)`
  - `class InMemoryTokenStore : TokenStore`(测试用)
  - `class EncryptedTokenStore(context) : TokenStore`(EncryptedSharedPreferences 实现,真机用)

- [ ] **Step 1: 写失败测试 `TokenStoreTest.kt`(测接口契约,用 InMemory 实现)**

```kotlin
package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStoreTest {
    private val sample = PersistedSession("rt-123", "sub-abc", "Jane Doe", "jane_subabc12", "jane")

    @Test fun `save then load roundtrip`() {
        val store = InMemoryTokenStore()
        assertNull(store.load())
        store.save(sample)
        assertEquals(sample, store.load())
    }

    @Test fun `clear removes session`() {
        val store = InMemoryTokenStore()
        store.save(sample)
        store.clear()
        assertNull(store.load())
    }

    @Test fun `null namePrefix survives roundtrip`() {
        val store = InMemoryTokenStore()
        val s = sample.copy(namePrefix = null, folder = "device")
        store.save(s)
        assertEquals(s, store.load())
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `auth/TokenStore.kt`**

```kotlin
package com.benzn.grandtime.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class PersistedSession(
    val refreshToken: String,
    val sub: String,
    val displayName: String,
    val folder: String,
    val namePrefix: String?,
)

interface TokenStore {
    fun save(session: PersistedSession)
    fun load(): PersistedSession?
    fun clear()
}

/** 测试/无 Keystore 环境用。 */
class InMemoryTokenStore : TokenStore {
    private var value: PersistedSession? = null
    override fun save(session: PersistedSession) { value = session }
    override fun load(): PersistedSession? = value
    override fun clear() { value = null }
}

/** 真机:EncryptedSharedPreferences(AES256-GCM MasterKey)。同步读写。 */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "fs_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(session: PersistedSession) {
        prefs.edit()
            .putString("rt", session.refreshToken)
            .putString("sub", session.sub)
            .putString("name", session.displayName)
            .putString("folder", session.folder)
            .putString("prefix", session.namePrefix)
            .apply()
    }

    override fun load(): PersistedSession? {
        val rt = prefs.getString("rt", null) ?: return null
        return PersistedSession(
            refreshToken = rt,
            sub = prefs.getString("sub", "") ?: "",
            displayName = prefs.getString("name", "") ?: "",
            folder = prefs.getString("folder", "device") ?: "device",
            namePrefix = prefs.getString("prefix", null),
        )
    }

    override fun clear() { prefs.edit().clear().apply() }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(3)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: TokenStore (encrypted + in-memory) for session persistence"`

---

### Task 5: CognitoClient(裸 HTTP,TDD 解析)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/CognitoClient.kt`
- Test: `app/src/test/java/com/benzn/grandtime/auth/CognitoClientTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface AuthOutcome { data class Tokens(idToken, refreshToken): AuthOutcome; data object NewPasswordRequired: AuthOutcome; data class Error(message: String): AuthOutcome }`
  - `class CognitoClient(poolClientId, region, http: (target: String, body: String) -> HttpResult)`——注入 HTTP 便于测;`suspend fun signIn(user, pass): AuthOutcome`、`suspend fun refresh(refreshToken): AuthOutcome`
  - `data class HttpResult(code: Int, body: String)`
  - companion `parseInitiateAuth(HttpResult): AuthOutcome`(纯函数,测这个)、`errorMessageFor(type: String): String`(异常 __type → 用户文案)

- [ ] **Step 1: 写失败测试 `CognitoClientTest.kt`(测纯解析)**

```kotlin
package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitoClientTest {

    @Test fun `success maps AuthenticationResult to Tokens`() {
        val body = """{"AuthenticationResult":{"IdToken":"idt","AccessToken":"act","RefreshToken":"rft"}}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(200, body))
        assertEquals(AuthOutcome.Tokens("idt", "rft"), r)
    }

    @Test fun `refresh success without RefreshToken keeps null-safe (id only)`() {
        val body = """{"AuthenticationResult":{"IdToken":"idt2","AccessToken":"act2"}}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(200, body))
        assertEquals(AuthOutcome.Tokens("idt2", null), r)
    }

    @Test fun `NEW_PASSWORD_REQUIRED challenge maps to NewPasswordRequired`() {
        val body = """{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"sess"}"""
        assertEquals(AuthOutcome.NewPasswordRequired, CognitoClient.parseInitiateAuth(HttpResult(200, body)))
    }

    @Test fun `NotAuthorized maps to friendly error`() {
        val body = """{"__type":"NotAuthorizedException","message":"Incorrect username or password."}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertEquals("Incorrect email or password", r.message)
    }

    @Test fun `UserNotFound also maps to same friendly error (no account enumeration)`() {
        val body = """{"__type":"UserNotFoundException","message":"User does not exist."}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertEquals("Incorrect email or password", r.message)
    }

    @Test fun `password reset required guides to web`() {
        val body = """{"__type":"PasswordResetRequiredException"}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertTrue(r.message.contains("web app"))
    }

    @Test fun `unknown error falls back to generic`() {
        val r = CognitoClient.parseInitiateAuth(HttpResult(500, "boom")) as AuthOutcome.Error
        assertTrue(r.message.isNotBlank())
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `auth/CognitoClient.kt`**

```kotlin
package com.benzn.grandtime.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

sealed interface AuthOutcome {
    data class Tokens(val idToken: String, val refreshToken: String?) : AuthOutcome
    data object NewPasswordRequired : AuthOutcome
    data class Error(val message: String) : AuthOutcome
}

/**
 * 裸 HTTP InitiateAuth,对齐 web cognito.js。http 可注入便于单测;默认走 OkHttp。
 */
class CognitoClient(
    private val clientId: String,
    private val region: String,
    private val http: (target: String, body: String) -> HttpResult = ::defaultHttp,
) {
    fun signIn(username: String, password: String): AuthOutcome {
        val body = JSONObject()
            .put("AuthFlow", "USER_PASSWORD_AUTH")
            .put("ClientId", clientId)
            .put("AuthParameters", JSONObject().put("USERNAME", username).put("PASSWORD", password))
            .toString()
        return runCatching { parseInitiateAuth(http("InitiateAuth", body)) }
            .getOrElse { AuthOutcome.Error("Network error — check your connection") }
    }

    fun refresh(refreshToken: String): AuthOutcome {
        val body = JSONObject()
            .put("AuthFlow", "REFRESH_TOKEN_AUTH")
            .put("ClientId", clientId)
            .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refreshToken))
            .toString()
        return runCatching { parseInitiateAuth(http("InitiateAuth", body)) }
            .getOrElse { AuthOutcome.Error("Network error — check your connection") }
    }

    private fun defaultHttp(target: String, body: String): HttpResult {
        val endpoint = "https://cognito-idp.$region.amazonaws.com/"
        val req = Request.Builder().url(endpoint)
            .header("Content-Type", "application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.$target")
            .post(body.toRequestBody("application/x-amz-json-1.1".toMediaType()))
            .build()
        OK_HTTP.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    companion object {
        private val OK_HTTP = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        fun parseInitiateAuth(result: HttpResult): AuthOutcome {
            val json = runCatching { JSONObject(result.body) }.getOrNull()
                ?: return AuthOutcome.Error("Network error — check your connection")
            json.optJSONObject("AuthenticationResult")?.let { ar ->
                val id = ar.optString("IdToken").takeIf { it.isNotBlank() }
                    ?: return AuthOutcome.Error("Login failed — please try again")
                return AuthOutcome.Tokens(id, ar.optString("RefreshToken").takeIf { it.isNotBlank() })
            }
            if (json.optString("ChallengeName") == "NEW_PASSWORD_REQUIRED") {
                return AuthOutcome.NewPasswordRequired
            }
            return AuthOutcome.Error(errorMessageFor(json.optString("__type")))
        }

        fun errorMessageFor(type: String): String = when {
            type.contains("NotAuthorized") || type.contains("UserNotFound") -> "Incorrect email or password"
            type.contains("PasswordResetRequired") -> "Password reset required — use the web app"
            type.contains("UserNotConfirmed") -> "Account not confirmed — use the web app"
            type.contains("TooManyRequests") -> "Too many attempts — try again later"
            else -> "Login failed — please try again"
        }
    }
}
```

(注:defaultHttp 的网络路径不进单测——测只覆盖 parseInitiateAuth/errorMessageFor 纯函数;网络在 Task 9 真机验。NEW_PASSWORD_REQUIRED 只识别不应答,符合无改密决策。)

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(7)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: CognitoClient bare-HTTP InitiateAuth + response parsing"`

---

### Task 6: AuthManager 接口扩展 + backfill DAO(TDD)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/auth/AuthManager.kt`、`app/src/main/java/com/benzn/grandtime/core/AppState.kt`(LoginState.authorSub)、`app/src/main/java/com/benzn/grandtime/db/CaptureRecordDao.kt`(backfillAuthorSub)
- Test: 追加到 `app/src/test/java/com/benzn/grandtime/db/FilesReconcilerTest.kt` 的 FakeDao(仅编译对齐)——backfill 纯 SQL 无单测,靠真机;此任务主要是接口/类型声明,构建即验证。

**Interfaces:**
- Produces:
  - `LoginState.LoggedIn(displayName: String, authorSub: String?)`(authorSub 可空;登出为 LoggedOut)
  - `sealed interface SignInResult { data object Success; data object NewPasswordRequired; data class Failure(message: String) }`
  - `AuthManager` 扩展:`suspend fun signIn(user, pass): SignInResult`、`suspend fun signOut()`、`suspend fun freshIdToken(): String?`(sealed 之外的三方法)
  - `CaptureRecordDao.backfillAuthorSub(sub)`:`UPDATE capture_records SET author_sub=:sub WHERE author_sub IS NULL`

- [ ] **Step 1: 改 `core/AppState.kt` 的 LoginState**

```kotlin
sealed interface LoginState {
    data object LoggedOut : LoginState
    data class LoggedIn(val displayName: String, val authorSub: String? = null) : LoginState
}
```

(`authorSub` 默认 null 保持既有 `LoggedIn("Test account")` 之类调用点仍编译——StubAuthManager 即将删,但 HomeScreen/SettingsScreen/StatusScreen 读的是 displayName,不受影响。)

- [ ] **Step 2: 改 `auth/AuthManager.kt`**

```kotlin
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
```

- [ ] **Step 3: 改 `db/CaptureRecordDao.kt` 加**

```kotlin
    @Query("UPDATE capture_records SET author_sub = :sub WHERE author_sub IS NULL")
    suspend fun backfillAuthorSub(sub: String)
```

- [ ] **Step 4: 构建**

```bash
./gradlew assembleDebug
```

Expected: 编译失败——StubAuthManager 未实现新方法。**本任务先让 StubAuthManager 临时实现**(下个任务删它):在 StubAuthManager 加最小实现使其编译:

```kotlin
    override suspend fun signIn(username: String, password: String): SignInResult = SignInResult.Success
    override suspend fun signOut() { _loginState.value = LoginState.LoggedOut }
    override suspend fun freshIdToken(): String? = null
```

再 `./gradlew assembleDebug && ./gradlew test` → 全绿(既有测试不受影响;LoginState.LoggedIn 新增可空参默认值兼容)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: AuthManager signIn/signOut/freshIdToken + SignInResult + backfill DAO + LoginState.authorSub"`

---

### Task 7: CognitoAuthManager(集成:登录/静默/登出 + 回填 + 目录迁移)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/CognitoAuthManager.kt`
- Delete: `app/src/main/java/com/benzn/grandtime/auth/StubAuthManager.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt`(提供单例)、`app/src/main/java/com/benzn/grandtime/service/CoreService.kt`(用共享单例)

**Interfaces:**
- Consumes: CognitoClient、TokenStore、JwtDecoder、UserFolder/MediaScope、CaptureRecordDao.backfillAuthorSub、MediaMigrator、AppState.{loginState,mediaScope}、BuildConfig
- Produces: `class CognitoAuthManager(client, tokenStore, dao, publicRoot: () -> File, scope: CoroutineScope) : AuthManager`;`GrandTimeApp.authManager`(全局单例);登录成功后 = 存 session + 设 loginState/mediaScope + 回填 + device→user 目录迁移。

- [ ] **Step 1: 实现 `auth/CognitoAuthManager.kt`**

```kotlin
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
            is AuthOutcome.Error -> true // 网络问题保留登录态;refresh 真失效由下次 freshIdToken/登录处理
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
            else -> null
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
```

需 `MediaMigrator` 加一个按「源/目标 folder」迁移的静态方法(现有实例迁的是 media/{kind};此处迁 FieldSight/<from>/{kind} → FieldSight/<to>/{kind}):在 `capture/MediaMigrator.kt` 加 companion:

```kotlin
    companion object {
        /** FieldSight/<fromFolder>/{kind} → FieldSight/<toFolder>/{kind},复用实例逻辑的 crash-safe/同名保护。 */
        fun migrateFolder(root: File, fromFolder: String, toFolder: String, onMoved: (String, File) -> Unit): Int {
            if (fromFolder == toFolder) return 0
            var moved = 0
            for (kind in listOf("video", "audio", "photo")) {
                val srcDir = MediaStorage.mediaSubdir(root, fromFolder, kind)
                val files = srcDir.listFiles()?.filter { it.isFile } ?: continue
                val destDir = MediaStorage.mediaSubdir(root, toFolder, kind).apply { mkdirs() }
                for (src in files) moved += moveOne(src, destDir, onMoved)
            }
            return moved
        }
    }
```

(把实例 migrate() 里的单文件移动逻辑抽成 private `moveOne(src, destDir, onMoved): Int`(0/1),实例与 companion 共用,保持 crash-safe 验证拷贝 + 跨卷同名后缀不变。)

- [ ] **Step 2: `GrandTimeApp.kt` 提供单例**(保留 Coil ImageLoaderFactory)

```kotlin
class GrandTimeApp : Application(), coil.ImageLoaderFactory {
    val authManager: CognitoAuthManager by lazy {
        CognitoAuthManager(
            client = CognitoClient(BuildConfig.COGNITO_CLIENT_ID, BuildConfig.COGNITO_REGION),
            tokenStore = EncryptedTokenStore(this),
            dao = CaptureDb.get(this).captureRecords(),
            publicRoot = { MediaStorage.publicRoot(this) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .components { add(coil.decode.VideoFrameDecoder.Factory()) }
        .build()
}
```

imports:`com.benzn.grandtime.auth.*`、`com.benzn.grandtime.capture.MediaStorage`、`com.benzn.grandtime.db.CaptureDb`、`kotlinx.coroutines.{CoroutineScope,SupervisorJob,Dispatchers}`。

- [ ] **Step 3: `CoreService.kt` 改用共享单例**

CoreService.kt:87 `val auth: AuthManager = StubAuthManager()` 改为:

```kotlin
        val auth: AuthManager = (application as GrandTimeApp).authManager
```

删 `import ...StubAuthManager`;加 `import com.benzn.grandtime.GrandTimeApp`。CaptureManager 的 MediaStorage 构造改为传 scopeProvider:
`MediaStorage({ MediaStorage.publicRoot(context) }, scopeProvider = { AppState.mediaScope.value })`。

- [ ] **Step 4: 删 StubAuthManager**

```bash
git rm app/src/main/java/com/benzn/grandtime/auth/StubAuthManager.kt
```

grep 确认无残留引用:`grep -rn StubAuthManager app/src` → 空。

- [ ] **Step 5: 构建 + 全量测试** — `./gradlew assembleDebug && ./gradlew test`(既有全绿;MediaMigrator 抽出 moveOne 后原 4 测试不变)。

- [ ] **Step 6: Commit** — `git add -A app/src && git commit -m "feat: CognitoAuthManager (signin/silent/signout + backfill + user-folder migration); shared singleton, drop stub"`

---

### Task 8: LoginScreen + MainActivity 登录门 + Sign out

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/LoginScreen.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`(未登录→LoginScreen)、`app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt`(Sign out 接线)

**Interfaces:**
- Consumes: `GrandTimeApp.authManager`、`AppState.loginState`、`SignInResult`、`LoginState`、AppTopBar/FsCard/theme
- Produces: `LoginScreen(onSignedIn: () -> Unit)`

- [ ] **Step 1: `ui/LoginScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.auth.SignInResult
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun submit() {
        if (loading) return
        error = null; loading = true
        scope.launch {
            when (val r = auth.signIn(email.trim(), password)) {
                SignInResult.Success -> onSignedIn()
                SignInResult.NewPasswordRequired -> {
                    error = "Please set your password in the FieldSight web app first"; loading = false
                }
                is SignInResult.Failure -> { error = r.message; loading = false }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = null, showBack = false, onBack = {}, serviceRunning = false)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Sign in", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") },
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") },
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() }, enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (loading) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                else Text("Sign in")
            }
        }
    }
}
```

- [ ] **Step 2: MainActivity 登录门**

MainScaffold 顶部读 loginState;未登录 → 只显 LoginScreen,不显主 Scaffold:

```kotlin
    val login by AppState.loginState.collectAsStateWithLifecycle()
    if (login is LoginState.LoggedOut) {
        LoginScreen(onSignedIn = { /* loginState 变化会自动重组进主界面 */ })
        return
    }
    // ... 既有 Scaffold ...
```

(onSignedIn 可留空——CognitoAuthManager 登录成功即置 AppState.loginState=LoggedIn,重组自动切走。)import `com.benzn.grandtime.core.LoginState`。

- [ ] **Step 3: SettingsScreen Sign out 接线**

Account 组的「Sign out」行由 disabled 变可点,onClick 走:

```kotlin
    val context = LocalContext.current
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    // Sign out 行:
    SettingRow("Sign out", null, enabled = true) { scope.launch { auth.signOut() } }
```

(登出后 AppState.loginState=LoggedOut,MainActivity 重组回 LoginScreen。)import GrandTimeApp。

- [ ] **Step 4: 构建 + 测试** — `./gradlew assembleDebug && ./gradlew test`。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: LoginScreen, MainActivity login gate, Settings sign out"`

---

### Task 9: 真机验收 + tag

**Files:** 无新文件(缺陷回对应 Task 修)

设备 F2S202503103054。`./gradlew installDebug`;需真实 FieldSight 账号(用户自备)。逐项(证据 logcat/截图/DB 查/文件列)。**登录需真账号——若无账号或密码,标记该项 BLOCKED_ON_USER,不猜测凭据。**

- [ ] **1 首登**:全新装 → 打开弹 LoginScreen → 输真实账号 → 登录成功进 Home,显示真实 email/name(非「Test account」)。截图。
- [ ] **2 持久**:`adb shell am force-stop` → 重开 → 直接进待命(silentLogin 用存的 refresh),无需重输。
- [ ] **3 重启**:`adb reboot` → 开机自启 + 静默登录 → 通知/Home 显示已登录身份。
- [ ] **4 回填**:登录前先广播录一段(author_sub 空)→ 登录 → `adb exec-out run-as com.benzn.grandtime`(或 sqlite 不可用时看 Diagnostics)查 capture_records.author_sub 被回填为真 sub。
- [ ] **5 用户目录**:登录后录一段 → 文件落 `/sdcard/FieldSight/<用户名>_<id>/video/<用户名>_*.mp4`;登录前的 device 文件已迁移过去、Files 页不丢。`adb shell ls /sdcard/FieldSight/`。
- [ ] **6 Sign out**:Settings→Sign out → 回 LoginScreen;物理键仍能录(落回 device/、author_sub 空)。
- [ ] **7 错误路径**:错密码 → "Incorrect email or password";飞行模式登录 → 网络错误文案;(测试账号若 FORCE_CHANGE_PASSWORD)→ "set your password in the FieldSight web app"。
- [ ] **收尾**:`./gradlew test` 全绿;`git tag sp2-accepted`;报告逐项 expected vs actual。

---

## 验证总览

| 层次 | 手段 | 覆盖 |
|---|---|---|
| JVM 单测 | `./gradlew test` | JwtDecoder、UserFolder、MediaStorage 作用域、TokenStore 逻辑、CognitoClient 解析/错误映射 + 既有零回归 |
| 真机 | Task 9 七项 | 真账号登录/持久/开机/回填/用户目录迁移/登出/错误 |

## 风险与缓解

- **无真实测试凭据** → Task 9 需用户提供账号;登录相关项标 BLOCKED_ON_USER,不猜凭据
- **EncryptedSharedPreferences 首次初始化慢/ROM Keystore 异常** → EncryptedTokenStore 若抛异常,GrandTimeApp 单例 lazy 内 runCatching 兜底降级 InMemoryTokenStore(实施时可加,记报告)
- **silentLogin 离线** → 保留登录态(用存的身份),仅 idToken 刷新失败;不误登出
- **目录迁移与新录并发** → 迁移一次性 IO;复用 SP3c crash-safe/跨卷同名保护;新录用当前作用域
- **Dropbox 文件锁** → 重跑
