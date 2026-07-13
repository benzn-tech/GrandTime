# SP2 Cognito 真登录设计:替换 StubAuthManager

日期:2026-07-12
状态:已与用户逐节确认
前置:主干 / UI 换装 / SP3a 采集(含抓帧)已合并 main

## 1. 背景与目标

登录目前是桩(StubAuthManager 恒返回「Test account」)。SP2 换成真登录:用用户自己
FieldSight 账号(Cognito prod 池,已 2026-07-12 实测)在 App 上登录,一次登录永久有效
(除非 Sign out),开机静默刷新;登录成功后 idToken 的 `sub` 回填本地无主 capture_records。

**实测确认的接口事实**(账号 509194952652):
- 用户池 `ap-southeast-2_q88pd6XXr`(`fieldsight-users`),区域 ap-southeast-2
- App client `4ratjdjonqm17tln6bs2761ci3`(`fieldsight-web-client`),**无密钥**,
  开 `ALLOW_USER_PASSWORD_AUTH` + REFRESH + SRP
- idToken 的 `sub` claim == users.cognito_sub == 要回填的 capture_records.author_sub

**已确认决策**:
- 范围 = **仅用户名密码登录**;**首登改密(NEW_PASSWORD_REQUIRED)不在移动端做**——移动端
  小键盘输密码不便,改密属网页端 + 内部 SOP(网页端建号并设好密码后,移动端才登录)。
  App 碰到 NEW_PASSWORD_REQUIRED 时提示「Please set your password in the FieldSight
  web app first」并停在登录页,不弹改密。忘密码/自助注册同样排除(引导网页端)
- 登录入口 = 未登录才弹登录页;登过一次永久直进待命(采集不依赖登录,低摩擦)
- 登录时回填本地所有 author_sub 为空的 capture_records
- 技术 = 裸 HTTP InitiateAuth(OkHttp),与 web 端 cognito.js 同构;不引 Amplify
- refresh token 加密保存(EncryptedSharedPreferences)
- 池/client/region 作为 BuildConfig 常量(默认上述 prod 值),留可配置口子

**SP3c 衔接(2026-07-13 增补)——登录后套用户层目录**:
- SP3c 已落地设备级目录 `<公共根>/FieldSight/device/{video,audio,photo}`,文件名 `VID_/AUD_/IMG__yyyyMMdd_HHmmss`
- 登录成功后:①媒体根目录段由 `device` 换成 `<用户名>_<user-id>`(用户名=idToken name/email claim 清洗,user-id=sub 短标识);②把 `FieldSight/device/*` 一次性迁移到用户目录(复用 SP3a MediaMigrator 的 crash-safe/跨卷保护逻辑)+ 更新 DB filePath;③新文件名前缀由 `VID_/…` 换成 `<用户名>_yyyyMMdd_HHmmss`(用户可读)
- 单一改点:`MediaStorage.mediaSubdir(root, kindDir)` 的 `device` 段改为读「当前用户目录」(登录态从 TokenStore 取,登出回 `device`);SP3c 已把该路径集中到此一处
- 登出后新录仍落 `device`(无主),下次登录再套用户层

## 2. 架构

```
LoginScreen (Compose)──┐
                       ├─▶ CognitoAuthManager : AuthManager  (替换 StubAuthManager)
Home/Settings 读 ──────┘        │  loginState: StateFlow<LoginState>
                                │  suspend signIn(user, pass): SignInResult
                                │  suspend silentLogin(): Boolean   (开机/启动调)
                                │  suspend signOut()
                                ├─ CognitoClient        裸 HTTP InitiateAuth/RespondToAuthChallenge/RefreshToken
                                ├─ TokenStore           EncryptedSharedPreferences(refreshToken/sub/displayName)
                                ├─ JwtDecoder           解 idToken payload 取 sub / email / name(纯函数,可单测)
                                ├─ RecordOwnerBackfiller dao.backfillAuthorSub(sub)  (登录成功回填)
                                └─ UserFolderMigrator     FieldSight/device/* → FieldSight/<user>_<id>/* + DB 路径更新(复用 MediaMigrator)
```

`AuthManager` 接口沿用 SP1(loginState + silentLogin),**新增** signIn/signOut 与
`SignInResult`(Success / NewPasswordRequired / Failure(message))。**NewPasswordRequired
不触发改密流**——仅用于让登录页显示「去网页端设密码」提示,不返回 session、不做后续挑战。
CognitoAuthManager 单例(经 Application 或简单 locator 提供),Service 与 UI 共享同一实例
(修 SP1 终审 M-5 遗留)。`LoginState.LoggedIn(displayName)` 不变;新增 `authorSub` 供
上传/回填用(放 LoginState.LoggedIn 或 AuthManager 属性)。

### CognitoClient(裸 HTTP,对齐 cognito.js)
- endpoint `https://cognito-idp.ap-southeast-2.amazonaws.com/`,`Content-Type: application/x-amz-json-1.1`
- `signIn`:`X-Amz-Target: AWSCognitoIdentityProviderService.InitiateAuth`,body
  `{AuthFlow:"USER_PASSWORD_AUTH", ClientId, AuthParameters:{USERNAME,PASSWORD}}`
  → 返回 AuthenticationResult(IdToken/AccessToken/RefreshToken) 或 ChallengeName=NEW_PASSWORD_REQUIRED
- ChallengeName=NEW_PASSWORD_REQUIRED → 映射为 `SignInResult.NewPasswordRequired`,登录页提示去网页端设密码;
  **不调 RespondToAuthChallenge、不在 App 内改密**
- `refresh`:`InitiateAuth` `REFRESH_TOKEN_AUTH`,`AuthParameters:{REFRESH_TOKEN}`
  (注:刷新不返回新 refresh token,沿用旧的)
- 纯 OkHttp,JSON 手拼/org.json 解析;无 AWS SDK

### TokenStore(EncryptedSharedPreferences)
存 refreshToken、sub、displayName(email 或 name claim);idToken 内存持有不落盘(短命,
按需刷新)。AndroidX security-crypto,MasterKey AES256-GCM。

## 3. 数据流

**首次登录**:
```
未登录打开 App → LoginScreen → 输入用户名密码 → CognitoAuthManager.signIn
  → CognitoClient InitiateAuth
     ├─ Success → JwtDecoder 取 sub/name → TokenStore 存 refreshToken+sub+name
     │           → RecordOwnerBackfiller.backfill(sub) → loginState=LoggedIn(name, sub)
     │           → UserFolderMigrator 迁 device→<用户名>_<id> → 关登录页回 Home
     └─ NewPasswordRequired → 登录页显示「Please set your password in the FieldSight
                              web app first」,停在登录页(不改密、不建会话)
```

**开机/启动静默登录**:
```
CoreService 启动 / MainActivity onCreate → CognitoAuthManager.silentLogin()
  → TokenStore 有 refreshToken?
     ├─ 有 → CognitoClient refresh → 成功: loginState=LoggedIn(存的 name, sub)
     │       └─ 失败(refresh 过期/撤销)→ 清 TokenStore → loginState=LoggedOut(需重登)
     └─ 无 → loginState=LoggedOut
```

**Sign out**(Settings):清 TokenStore + loginState=LoggedOut;不撤销云端 token(本地登出即可);
下次打开 App 弹登录页。**采集不受登录影响**——登出后物理键照常录,只是 author_sub 又为空。

**回填**:`CaptureRecordDao.backfillAuthorSub(sub)` = `UPDATE capture_records SET author_sub=:sub
WHERE author_sub IS NULL`。登录成功即调一次。

## 4. UI 与错误处理

### LoginScreen(新一级流,未登录时替代整个 Scaffold 内容)
FieldSight 品牌顶栏(wordmark)+ 居中卡:Email 输入、Password 输入(可见切换)、
「Sign in」黄色 CTA(48dp)、错误行(error 色)。加载态按钮转圈禁用。
**无 App 内改密弹层**——NEW_PASSWORD_REQUIRED 走错误行提示引导网页端。

### 错误处理(用户可见文案)
- `NotAuthorizedException`(密码错)→ "Incorrect email or password"
- `UserNotFoundException` → "Incorrect email or password"(不泄露账号是否存在)
- `PasswordResetRequiredException` → "Password reset required — use the web app"(忘密码排除范围,引导网页)
- `NEW_PASSWORD_REQUIRED` 挑战 → "Please set your password in the FieldSight web app first"(不改密)
- 网络失败 → "Network error — check your connection"
- **刷新失败降级**:silentLogin refresh 失败 → 静默转 LoggedOut,不打断采集,Home 显示
  "Signed out — tap to sign in";不弹错误 toast(开机时用户可能没在看)
- 所有失败不崩溃;登录页可重试

### 与采集/上传的边界
- 采集全程不依赖 loginState(spec SP3a 已如此);author_sub 登录后才有值
- idToken 供 SP4 上传用:CognitoAuthManager 提供 `suspend freshIdToken(): String?`
  (内存有效则直接返回,过期则 refresh),SP4 拿它裸放 Authorization 头。本期仅暴露接口不调用

## 5. 工程结构变化

```
auth/AuthManager.kt          扩展接口(+signIn/signOut/freshIdToken/SignInResult;无 completeNewPassword)
auth/CognitoAuthManager.kt   新增(替换 StubAuthManager 的注入点);登录成功触发回填 + 用户目录迁移
auth/CognitoClient.kt        新增:裸 HTTP(OkHttp);NEW_PASSWORD_REQUIRED 仅识别不应答
auth/TokenStore.kt           新增:EncryptedSharedPreferences 封装(refreshToken/sub/displayName/userFolder)
auth/JwtDecoder.kt           新增:解 idToken payload(纯函数,JVM 可单测)
auth/StubAuthManager.kt      删除(注入点改为 CognitoAuthManager;历史提交里可查)
capture/UserFolderMigrator.kt 新增或复用 MediaMigrator:device→<用户名>_<id> 迁移 + DB 路径更新
capture/MediaStorage.kt      mediaSubdir 的目录段由「当前用户目录」提供(登录态取,登出=device)
core/AppState.kt             LoginState.LoggedIn 增 authorSub 字段
db/CaptureRecordDao.kt       +backfillAuthorSub(sub)
service/CoreService.kt       silentLogin 调用点改为经共享 CognitoAuthManager(实例来源统一)
ui/LoginScreen.kt            新增
ui/MainActivity.kt           未登录 → LoginScreen;已登录 → 现有 Scaffold
ui/SettingsScreen.kt         Account 段 Sign out 由「灰」变可用,接 signOut
GrandTimeApp.kt              提供 CognitoAuthManager 单例(Service+UI 共享)
gradle/libs.versions.toml    +okhttp 4.12、+androidx.security:security-crypto 1.1.0-alpha06
app/build.gradle.kts         BuildConfig 常量:COGNITO_POOL_ID/CLIENT_ID/REGION
AndroidManifest.xml          +INTERNET 权限(实测当前未声明,采集全本地不需要;登录首次需要)
```

逻辑层 hardware/keymap/capture(除 dao 加一个方法)/boot 不动。

## 6. 测试与验收

**JVM 单测**:JwtDecoder(解真实 idToken 结构取 sub/email;畸形 token 不崩返回 null)、
SignInResult 解析(注入假 CognitoClient:Success / NewPasswordRequired / 各异常映射到文案)、
backfill SQL(假 DAO:只更新 null 行)、TokenStore 逻辑(可注入的抽象层,避开 Android Keystore)。

**真机验收**(F2S202503103054,SP2 完成门):
1. 全新安装 → 打开 App 弹登录页 → 用真实 FieldSight 账号登录成功 → 进 Home 显示真实身份
   (email/name),不再是「Test account」
2. 杀进程重开 → 直接进待命(silentLogin 用存的 refresh 成功),无需重新输密码
3. 重启设备 → 开机自启 + 静默登录 → 通知/Home 显示已登录身份
4. 登录前先录一段 → 登录 → 查 DB:该 capture_records 行 author_sub 被回填为真实 sub
   (`adb run-as sqlite3 … SELECT author_sub …`)
5. Settings → Sign out → 回登录页;物理键仍能录(author_sub 空)
6. 错误路径:错密码 → "Incorrect email or password";飞行模式登录 → 网络错误文案
7. 登录成功 → 媒体根变为 `FieldSight/<用户名>_<id>/`,旧 `device/` 文件迁移过去、DB 路径更新、
   Files 页不丢;新录文件名前缀为 `<用户名>_`
8. (若测试账号处于 FORCE_CHANGE_PASSWORD/NEW_PASSWORD_REQUIRED)登录页显示「去网页端设密码」
   提示,不弹改密、停在登录页

**不做**:忘密码/邮箱验证码重置、自助注册、Hosted-UI/OAuth、TEST 池、多账号切换、
生物识别解锁、token 撤销(云端 GlobalSignOut)。

## 7. 范围外(后续子项目)

SP4 上传用 freshIdToken 裸放 Authorization 头 + 项目(site_slug)选择;忘密码自助;
生物识别;TEST 环境切换 UI。
