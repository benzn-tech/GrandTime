# SP4b 移动端上传 + 项目选择 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** GrandTime 登录后选当前工地 → 录制打 site 标签 → WorkManager 队列把视频/音频/照片直传到已上线的 FieldSight recordings 端点(wdsgobb7b0),实时 + 离线重试;顺带修长按录制键(#80)与 refresh 失效/网络区分(#82)。

**Architecture:** SP4a 后端已上线(recordings 表 + `POST /api/org/recordings/upload-url` 预签名 PUT + `/complete`,在 `https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api`)。移动端:SiteStore(DataStore)存当前工地 → 每条 capture_records 打 siteId → 录制完成入 WorkManager 队列 → freshIdToken → upload-url → PUT S3 → complete → 置 uploadStatus。

**Tech Stack:** Kotlin/Compose/Room 2.6/CameraX/OkHttp 4.12(已有)+ 新增 androidx.work(WorkManager)。

设计依据:`docs/superpowers/specs/2026-07-13-sp4-upload-project-selection-design.md` §5。

## Global Constraints

- **org API base**:`https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api`(= SP2 移动端已连的 fieldsight-test 栈);认证:`freshIdToken()` 拿 idToken,**裸放 `Authorization` 头**(不带 "Bearer")。
- **端点契约(SP4a 已上线)**:`POST {base}/org/recordings/upload-url` body `{kind, clientUuid, siteId?, fileName, contentType, startedAt, endedAt?, durationS?, sizeBytes?, resolution?, codec?}` → `{recordingId, uploadUrl, s3Key}`;`PUT uploadUrl`(头 `Content-Type` 与请求的 contentType 一致);`POST {base}/org/recordings/{id}/complete` body `{sizeBytes?}` → `{ok:true}`。**注意路径前缀是 `/org/recordings/...`**(org API 在 `/api/org/{proxy+}`)。clientUuid = capture_records.id(幂等)。
- **contentType 映射**:video→`video/mp4`,audio→`audio/*`(实际录音容器,見 AudioRecorder;若 wav 用 `audio/wav`),photo→`image/jpeg`。
- **site**:GET `{base}/org/sites`(ACL 过滤,返回 `[{id,slug,name,...}]`)+ GET `{base}/org/me`(返回可选 `site_ids`/`scope`);选中工地存 DataStore 的 `{id,slug,name}`;每条录制打 `siteId`(UUID);未选=null 允许上传。
- **JVM 可单测**(TDD 严格红绿):CognitoClient/#82 的 AuthOutcome、RecordingsApiClient 解析、SitesApiClient 解析、SiteStore(真临时 DataStore,仿 KeyMapStoreTest)。**非 JVM(构建+真机验)**:Room 迁移/DAO、WorkManager Worker、Compose UI、CaptureManager 接线。
- **HTTP 客户端**:无共享 OkHttpClient;新客户端仿 CognitoClient——构造注入 `http: ((String,String)->HttpResult)?` 便于测,自建 `OkHttpClient.Builder()`。REST/JSON(非 AWS-JSON-1.1,无 X-Amz-Target)。
- **依赖**:WorkManager 走版本目录(`libs.versions.toml` 加 `work` + `androidx-work-runtime-ktx`,`build.gradle.kts` 加 `implementation(libs.androidx.work.runtime.ktx)`)。
- **键位**:**无 `TOGGLE_VIDEO` 枚举**——`(HardKey.VIDEO, LONG)` 重映到既有 `KeyAction.START_STOP_VIDEO`(已在 handledActions,自动路由,无需改 dispatcher)。
- Room:`CaptureDb` 现 `version=1` `exportSchema=false` 零迁移先例;本计划写首个迁移 1→2 加 `siteId`,`.addMigrations()` 挂上。

---

### Task 1(#82): refresh 区分 auth 失效 vs 网络错误

**Files:** Modify `auth/CognitoClient.kt`、`auth/CognitoAuthManager.kt`;Test 追加 `auth/CognitoClientTest.kt`。

**Interfaces:**
- Produces:`AuthOutcome` 新增 `data object AuthInvalid : AuthOutcome`;`CognitoClient.parseInitiateAuth` 对 NotAuthorized/UserNotFound/token 失效返回 `AuthInvalid`(网络异常仍 `Error`);`silentLogin`/`freshIdToken` 据此:AuthInvalid → 登出(applyLoggedOut + 清 session),网络 Error → 保留登录态。

- [ ] **Step 1: 加失败测试**(CognitoClientTest.kt)

```kotlin
@Test fun `refresh NotAuthorized maps to AuthInvalid not Error`() {
    val body = """{"__type":"NotAuthorizedException","message":"Refresh Token has expired."}"""
    assertEquals(AuthOutcome.AuthInvalid, CognitoClient.parseInitiateAuth(HttpResult(400, body)))
}

@Test fun `refresh malformed or network body stays Error`() {
    val r = CognitoClient.parseInitiateAuth(HttpResult(200, "boom")) as AuthOutcome.Error
    assertTrue(r.message.isNotBlank())
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL(AuthInvalid 不存在)。

- [ ] **Step 3: 实现**

`AuthOutcome` 加:`data object AuthInvalid : AuthOutcome`(sealed interface 内)。
`parseInitiateAuth`:在 `errorMessageFor` 分类前,若 `type.contains("NotAuthorized") || type.contains("UserNotFound") || type.contains("PasswordResetRequired") || type.contains("UserNotConfirmed")` → 返回 `AuthOutcome.AuthInvalid`;其余走原 `Error(errorMessageFor(type))`。(注:此四类原本经 errorMessageFor 映文案;现登录路径 signIn 仍需要文案——故 signIn 保留旧行为:signIn 里对 AuthInvalid 映射回 `SignInResult.Failure(errorMessageFor(type))`。为不破坏 signIn,单独在 signIn 分支处理 AuthInvalid → Failure("Incorrect email or password" 或对应文案);refresh 分支才据 AuthInvalid 登出。)

`CognitoAuthManager.silentLogin` 的 when 增 `AuthOutcome.AuthInvalid -> { tokenStore.clear(); idTokenCache = null; applyLoggedOut(); false }`;`AuthOutcome.Error -> true`(网络,保留)。
`freshIdToken` 的 when:`AuthOutcome.Tokens -> ...`;`AuthOutcome.AuthInvalid -> { tokenStore.clear(); idTokenCache = null; applyLoggedOut(); null }`;`else -> null`(网络,session 保留)。
`signIn` 的 when 增 `AuthOutcome.AuthInvalid -> SignInResult.Failure("Incorrect email or password")`(保持无账号枚举文案)。

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test`。
- [ ] **Step 5: Commit** — `feat: distinguish auth-invalid vs network in refresh (#82)`

---

### Task 2: 依赖 WorkManager + ORG_API_BASE_URL BuildConfig

**Files:** Modify `gradle/libs.versions.toml`、`app/build.gradle.kts`。

- [ ] **Step 1: libs.versions.toml** 加 `[versions] work = "2.10.0"` + `[libraries] androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }`。
- [ ] **Step 2: build.gradle.kts** dependencies 加 `implementation(libs.androidx.work.runtime.ktx)`;defaultConfig 加 `buildConfigField("String", "ORG_API_BASE_URL", "\"https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")`。
- [ ] **Step 3: 构建** — `./gradlew assembleDebug`(确认依赖解析 + BuildConfig 生成)。
- [ ] **Step 4: Commit** — `build: add WorkManager + ORG_API_BASE_URL`

---

### Task 3: capture_records 加 siteId + Room 迁移 1→2 + DAO 方法

**Files:** Modify `db/CaptureRecord.kt`、`db/CaptureDb.kt`、`db/CaptureRecordDao.kt`。构建+真机验(Room 非 JVM 单测)。

- [ ] **Step 1: 实体** `CaptureRecord` 加 `val siteId: String? = null`(在 siteSlug 旁)。
- [ ] **Step 2: DAO** 加(仿既有 @Query 风格):
```kotlin
    @Query("UPDATE capture_records SET siteId = :siteId WHERE id = :id")
    suspend fun setSiteId(id: String, siteId: String?)

    @Query("UPDATE capture_records SET uploadStatus = :status WHERE id = :id")
    suspend fun markUploadStatus(id: String, status: String)

    @Query("SELECT * FROM capture_records WHERE uploadStatus IN (:statuses) AND missing = 0")
    suspend fun listByUploadStatus(statuses: List<String>): List<CaptureRecord>
```
- [ ] **Step 3: 迁移** `CaptureDb`:`version = 2`;companion 加
```kotlin
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE capture_records ADD COLUMN siteId TEXT")
        }
    }
```
`Room.databaseBuilder(...)` 链加 `.addMigrations(MIGRATION_1_2)`。imports:`androidx.room.migration.Migration`、`androidx.sqlite.db.SupportSQLiteDatabase`。
- [ ] **Step 4: 构建** — `./gradlew assembleDebug`。(真机验:老库升级不丢数据——T9 覆盖。)
- [ ] **Step 5: Commit** — `feat(db): capture_records.siteId + migration 1->2 + upload DAO methods`

---

### Task 4: RecordingsApiClient(TDD 解析)

**Files:** Create `net/RecordingsApiClient.kt`、Test `net/RecordingsApiClientTest.kt`。

**Interfaces:**
- Produces:`class RecordingsApiClient(baseUrl, http: ((method,url,authHeader,body)->HttpResult)? = null)`;`fun uploadUrl(idToken, req: UploadUrlReq): UploadUrlResult`、`fun putFile(uploadUrl, contentType, file): Boolean`、`fun complete(idToken, recordingId, sizeBytes): Boolean`;`data class UploadUrlReq(...)`、`sealed interface UploadUrlResult { data class Ok(recordingId, uploadUrl, s3Key); data object AuthExpired; data class Error(msg) }`;companion 纯解析 `parseUploadUrl(HttpResult): UploadUrlResult`。

- [ ] **Step 1: 失败测试**(RecordingsApiClientTest.kt)——测纯解析:
```kotlin
@Test fun `parse success`() {
    val b = """{"recordingId":"r1","uploadUrl":"https://s3/x","s3Key":"users/a/video/2026-07-13/x.mp4"}"""
    val r = RecordingsApiClient.parseUploadUrl(HttpResult(200, b)) as RecordingsApiClient.UploadUrlResult.Ok
    assertEquals("r1", r.recordingId); assertEquals("https://s3/x", r.uploadUrl)
}
@Test fun `401 maps to AuthExpired`() {
    assertEquals(RecordingsApiClient.UploadUrlResult.AuthExpired,
        RecordingsApiClient.parseUploadUrl(HttpResult(401, "")))
}
@Test fun `4xx5xx maps to Error`() {
    assertTrue(RecordingsApiClient.parseUploadUrl(HttpResult(500, "boom"))
        is RecordingsApiClient.UploadUrlResult.Error)
}
```
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**(HttpResult 复用 auth 包的;或本包自定义同名。用 org.json 解析;parseUploadUrl:code==401→AuthExpired,200 且有 recordingId→Ok,否则 Error。默认 http 用 OkHttp POST JSON,`Authorization` 裸 idToken 头;putFile 用 OkHttp PUT `RequestBody` from File + Content-Type;complete POST。网络异常 → Error("network")。)
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit** — `feat(net): RecordingsApiClient (upload-url/put/complete) + parse tests`

---

### Task 5: SiteStore(DataStore,TDD)+ SitesApiClient(TDD)+ AppState.selectedSite

**Files:** Create `core/SiteStore.kt`、`net/SitesApiClient.kt`;Modify `core/AppState.kt`;Test `core/SiteStoreTest.kt`、`net/SitesApiClientTest.kt`。

**Interfaces:**
- Produces:`data class SelectedSite(id, slug, name)`;`AppState.selectedSite: MutableStateFlow<SelectedSite?>`;`class SiteStore(dataStore)`:`val site: Flow<SelectedSite?>`、`suspend fun set(SelectedSite?)`;`SitesApiClient.parseSites(HttpResult): List<SiteOption>`(SiteOption(id,slug,name))。

- [ ] **Step 1: 失败测试**——SiteStoreTest(仿 KeyMapStoreTest 真临时 DataStore):set→site Flow 读回一致;null 清空。SitesApiClientTest:`parseSites` 解析 `{"sites":[{"id":"u1","slug":"north","name":"North Wharf"}]}` → 1 项 id/slug/name 对。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现** SiteStore(3 个 stringPreferencesKey site_id/slug/name,仿 SettingsStore);SitesApiClient(GET `{base}/org/sites` 裸 idToken 头,parseSites 读 `sites[]`);AppState 加 `selectedSite = MutableStateFlow<SelectedSite?>(null)`。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit** — `feat: SiteStore + SitesApiClient + AppState.selectedSite`

---

### Task 6: UploadWorker + CaptureManager 接线(打 siteId + 入队)

**Files:** Create `upload/UploadWorker.kt`、`upload/UploadEnqueuer.kt`;Modify `capture/CaptureManager.kt`、`GrandTimeApp.kt`(WorkManager 初始化如需)。构建+真机验。

**Interfaces:**
- Produces:`interface UploadEnqueuer { fun enqueue(recordId: String) }`;`class WorkManagerUploadEnqueuer(context): UploadEnqueuer`(建唯一 OneTimeWorkRequest,NetworkType.CONNECTED + 指数退避,inputData=recordId);`UploadWorker(CoroutineWorker)`:读 record → freshIdToken(null 且 loginState=LoggedOut → Result.failure;null 网络 → Result.retry)→ markUploadStatus(uploading)→ uploadUrl → putFile → complete → markUploadStatus(uploaded)返回 success;任一失败 → markUploadStatus(failed)+ Result.retry(auth 过期 → failure)。

- [ ] **Step 1**:CaptureManager 构造注入 `uploadEnqueuer: UploadEnqueuer`(仿 notify/probe 注入 lambda 风格,便于测)。在 4 个 insert/finalize 点(视频段 finalize 回调 CaptureManager.kt:~220、photo success ~320、frame-grab ~397、audio stopAudio finalize ~438):(a) 构造 CaptureRecord 时传 `siteId = AppState.selectedSite.value?.id`;(b) 成功落盘后 `uploadEnqueuer.enqueue(recordId)`。
- [ ] **Step 2**:UploadWorker 实现(用 GrandTimeApp.authManager.freshIdToken()、RecordingsApiClient、CaptureDb dao)。GrandTimeApp 如需 `Configuration.Provider` 初始化 WorkManager(默认自动初始化即可,除非移除了 default initializer)。
- [ ] **Step 3**:CoreService 构造 CaptureManager 处传 `WorkManagerUploadEnqueuer(applicationContext)`。
- [ ] **Step 4: 构建 + 既有测试** — `./gradlew assembleDebug test`(CaptureManager 若有注入点破坏既有 FakeDao 测试需修;新增可选 UploadWorker 逻辑纯函数抽取可 JVM 测)。
- [ ] **Step 5: Commit** — `feat(upload): WorkManager upload queue + CaptureManager enqueue + siteId stamp`

---

### Task 7: Home 工地选择器 + Files 上传状态角标

**Files:** Modify `ui/HomeScreen.kt`、`ui/FilesScreen.kt`;可能 Create `ui/SitePickerDialog.kt`。构建+真机验。

- [ ] **Step 1**:Home 状态卡「Signed in as X」下加一行 `Site: <name|未选>`,可点 → 弹 SitePickerDialog(拉 SitesApiClient.parseSites via GET /org/sites,列出可选工地,选中 → SiteStore.set + AppState.selectedSite 更新)。登录后首次进入若未选工地,提示。state 用 `AppState.selectedSite.collectAsStateWithLifecycle()`。
- [ ] **Step 2**:FilesScreen 的 MediaCell 加上传状态角标(仿 duration badge,`.align(Alignment.TopEnd)`):`when(record.uploadStatus)` pending→灰点/uploaded→绿勾/failed→红叹号;failed 点击 → 重新入队(UploadEnqueuer.enqueue(record.id))。
- [ ] **Step 3: 构建** — `./gradlew assembleDebug`。
- [ ] **Step 4: Commit** — `feat(ui): Home site picker + Files upload-status badges`

---

### Task 8: 键位修复(长按录制,解 #80)

**Files:** Modify `keymap/KeyMapping.kt`;若删 TOGGLE_VIDEO_UPLOAD 再改 `keymap/KeyAction.kt` + `ui/Labels.kt`(+ 先 grep `KeyBindingsScreen.kt`)。构建。

- [ ] **Step 1**:`KeyMapping.DEFAULTS` 的 `(HardKey.VIDEO to PressType.LONG) to KeyAction.TOGGLE_VIDEO_UPLOAD` 改为 `KeyAction.START_STOP_VIDEO`。
- [ ] **Step 2**:决定 TOGGLE_VIDEO_UPLOAD 去留——**保留枚举但不绑定**(最小改动,Labels 的 when 分支留着无害),或删除(则删 KeyAction 该值 + Labels 该 when 分支 + 查 KeyBindingsScreen 的 `KeyAction.entries` 用法)。**推荐保留不绑定**,减少面。
- [ ] **Step 3**:既有 KeyMappingTest 若断言 `(VIDEO,LONG)→TOGGLE_VIDEO_UPLOAD` 需改为 START_STOP_VIDEO。`./gradlew test`。
- [ ] **Step 4: 构建 + Commit** — `fix(keymap): long-press VIDEO = start/stop record (#80)`

---

### Task 9: 真机 E2E 验收(需用户账号 + 已上线后端)

**Files:** 无(验收)。

- [ ] 真机装 APK,登录 → Home 选工地(拉 GET /org/sites)→ 录一段视频 + 拍照 → 观察 Files 角标 pending→uploaded;adb/后端核:后端 recordings 表出现行、site_id = 所选、uploaded_at 非空;S3 `users/{name}/video|pictures/{date}/` 出现文件。
- [ ] 断网录制 → 联网后自动补传(WorkManager 重试)。
- [ ] 长按视频键能起停录制(#80 解)。
- [ ] Room 迁移:覆盖旧版本升级(装旧 APK 有数据 → 升新 APK → 数据在、siteId 列加上)。
- [ ] 登出后 idToken 失效路径(#82):refresh 真失效 → 回登录页(而非卡"已登录无 token")。

---

## 交付后
- E2E 通过 → SP4 完整完成(后端已 live + 移动端上传闭环)。合并 GrandTime feature 分支到 main。
- 关联加固:SP4a #83(后端 s3_key 冲突→409);移动端可选「仅 WiFi 上传」开关(本期不做)。
