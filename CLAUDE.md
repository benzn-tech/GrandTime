# GrandTime — Claude 项目指南

F2SP 执法终端上的现场作业记录仪原生 Android App(Kotlin/Compose),替代商用 SMART-PTT(com.corget),是 FieldSight 的移动客户端。包名 `com.benzn.grandtime`,GitHub `benzn-tech/GrandTime`,Kotlin 2.1 / AGP 8.7 / minSdk=targetSdk 33 / compileSdk 35。

**沟通**:中文回复;汇报用 已完成/如何/影响 格式。

## 当前状态(2026-07-31)

- **prod 线上 = 0.5.8**(versionCode 12,签名 release,tag `v0.5.8`,连客户湖 `fieldsight-data-509194952652`)。dev 版桌面标签叫 **devfieldsight**(见记忆 [[grandtime-dev-label-convention]])。
- **已上线到 prod**(按时间):SP1-4 采集/登录/上传、SP3b 灯语、SP-Capture2-P2/P3(Camera2+GL+水印+GPS)、录像中拍照预览黑修复、**视频 Pause/Resume + 息屏省电暂停**、**音频 Pause/Resume**(音频键:空闲长按=调音量、录音中短按=暂停、长按=结束、空闲短按=开始)、**P0 chunk-session**(文件名 `_sid{32hex}_c{NNNN}`+session open/close+≤2min 快报告链路)、**30s 滚动分段 + 文件页/首页按"整段录制"归一**(UI 归一非物理合并)、**release 签名**(keystore.jks + keystore.properties 均 gitignored,**必须备份**,口令见早期会话)。
- **✅ 已上线:扫码登录 v2(QR terminal sign-in,2026-07-31)** —— 三端全上 prod 并**真机验证登录成功**:后端 redeem(PR #175/#180 → main)、web(PR #141 → main,Amplify)、移动(PR #2 → main,本版)。v1 的 Cognito CUSTOM_AUTH 路线被 prod 池"选择式登录"卡死,已弃用改 refresh-token 交接。**两个部署期真坑**:①in-VPC org-api 无 DynamoDB VPC 端点→redeem 挂死 504(已加网关端点 `vpce-01233d5b756ffefcb`);②`consumed` 是 DynamoDB **保留字**,消费用的 `update_item` 未加 `#c` 别名→每次兑换静默 401(已修 + 加日志 + 测试桩防回归)。详见下方"扫码登录 v2"节 + 记忆 [[qr-login-feature]]。**待办**:负路径真机验证(过期码/随机码)、解共享池遗留 `LambdaConfig`(惰性无害)。
- `main` 与 origin 同步。GrandTime 无活跃 feature 分支(v1 分支 `feat/qr-login-mobile` 已推 origin,PR #1 开着但已过时——v2 会另起分支)。

## 扫码登录 v2(QR terminal sign-in)—— 交接(2026-07-31)

**目标(不变)**:F2SP 终端扫 web 出示的一次性二维码 → 终端拿到**自己的** Cognito token 登录,免在硬键盘打字。**跨 3 仓**(fieldsight-pipeline 后端 / fieldsight-ui web / GrandTime 移动)。

### 为什么从 v1 转 v2(关键 context)
- **v1(Cognito CUSTOM_AUTH)**:12 个 SDD 任务全建完+Opus 终审+**已部署到 prod**(码表+3 触发器+挂共享池 LambdaConfig+client 加 ALLOW_CUSTOM_AUTH)。但**登录跑不通** —— prod 池 `ap-southeast-2_q88pd6XXr` 是 **ESSENTIALS 层"选择式登录"**(`Policies.SignInPolicy.AllowedFirstAuthFactors=["PASSWORD"]`),Cognito 这套新模型**没有 custom-auth 因子**(合法值只有 SOFTWARE_TOKEN/SMS_OTP/EMAIL_OTP/EMAIL_MAGIC_LINK/WEB_AUTHN/PASSWORD);`initiate-auth CUSTOM_AUTH` 在进触发器**之前**就被拒;SignInPolicy 在 ESSENTIALS 层**删不掉**(update-user-pool 省略它也留着)。
- **决定性发现**:池里**只有一个 app client**(`4ratjdjonqm17tln6bs2761ci3`,web+移动**共用**,不轮换)→ v1"web 和移动 client 不同"的前提是**错的**。
- **v2(refresh-token 交接)**:因同一 client,给终端一个 refresh token 走 `REFRESH_TOKEN_AUTH` 即可登录(**不受 SignInPolicy 管**)。流:web 出码时把自己的 refresh token 存后端(绑一次性码)→ 终端扫码 → **未认证 `POST /api/org/auth/qr/redeem`** 返回 token → 终端 `REFRESH_TOKEN_AUTH` → 登录。**凭据不进 QR**(QR 只带 90s 单次码)。终端与 web 共享会话血缘(已接受)。
- **v2 设计 spec**:`docs/superpowers/specs/2026-07-31-qr-login-refresh-handoff-design.md`(supersede v1 的 `2026-07-30-qr-login-design.md`)。

### v2 进度
- **后端 v2:完成并推送** —— 分支 `feat/qr-login-v2-backend`(@`3c9e0e5`,off `origin/develop`),已推 origin(**未 PR、未部署**)。4 个编码任务 + Opus 终审"Ready to merge: Yes" + fix wave;测试 1343 绿。计划 `fieldsight-pipeline/docs/superpowers/plans/2026-07-31-qr-login-v2-backend.md`,ledger `.superpowers/sdd/2026-07-31-qr-login-v2-backend/progress.md`。内容:create 存 refreshToken · 公开 redeem 端点(在 `lambda_handler` 里 `get_connection()` **之前**处理,pre-auth+pre-DB、原子单次、恶意输入→通用 401、不记日志)· 模板加公开路由(`Auth:{Authorizer:NONE}`)/删 3 触发器/留码表 · 删 `lambda_qr_auth.py`。
- **web v2 + 移动 v2:计划已写,未实现**。web 计划 `.../plans/2026-07-31-qr-login-v2-web.md`(2 编码任务:create 带上 web 的 refresh token、payload 去 `u` 变 `{v:2,c,env}`)。移动计划 `docs/superpowers/plans/2026-07-31-qr-login-v2-mobile.md`(4 任务:`QrLoginPayload` v2、`redeemQrCode(code)` + 删 custom-auth 那套、`signInWithQrCode(code)`=redeem→已有的 `refresh()`→persistAndEnter、扫码器)。这俩是在 v1 分支代码上改 → 从 v1 分支(`feat/qr-login-web`/`feat/qr-login-mobile`)切 v2 分支。

### 新会话怎么接续
1. 读记忆 [[qr-login-feature]] + 上面两份 v2 计划。
2. 用 **Subagent-Driven**(SDD)执行,顺序 **web → 移动**(后端已完)。web/移动纯代码,不需 gated 步骤;真机验收才需后端 redeem 上线。
3. **部署(用户跑,Task 5)**:后端走 **hotfix-off-main**(注意 `origin/develop` 比 `main` 领先 ~25 提交,别 develop→main 全量发)→ deploy-prod(审批门)→ **解开共享池 LambdaConfig 回 `{}`**(`describe→merge→update` 保全字段 + 改后全量 diff 验证 + 回滚备份 `deploy-backup/pool.json`)→ CLI 演练。web 走 Amplify、移动出 release APK 装 F2SP。

### 部署/AWS 坑(实测)
- 变更类 aws/gh 命令被 **Claude Code auto 模式分类器拦截** → 需 `dangerouslyDisableSandbox:true` 或用户 `!` 亲跑;AWS 用自定义 `aws login` 重认证,会话 ~1 小时过期。
- 改共享 prod 池/client 一律 `describe→merge→update` + **改后全量 diff 确认只有目标字段变**(v1 挂触发器就是这么安全落地的);`update-user-pool` 省略字段会重置。
- 加新 CFN 资源要连查部署角色 `github-actions-fieldsight-deploy` IAM(v1 已补 `dynamodb:UpdateTimeToLive`;v2 只删资源不加类型,无需再补)。

## 架构(采集核心 = Camera2 管线)

`capture/camera2/`(P2):`Camera2Pipeline`(门面:CameraDevice + CaptureSession **固定 2 路输出**[GL 相机 SurfaceTexture + JPEG ImageReader])、`GlRecordPipeline`(相机 OES 纹理 → GL 画到编码器面+预览面)、`SegmentRecorder`(HEVC→AVC 降级 + AAC 音频轨,muxerLock 串行双轨)、`VideoSizeSelector`(选码尺寸 ≤1920×1088)。**核心不变式**:预览挂摘/分段起停/拍照/手电**都不重配相机会话**(GL 目标切换,故不中断录像)。
`capture/`:`CaptureManager`(编排:状态机+DB+上传+灯+GPS)、`CaptureCore`(纯状态机)、`AudioRecorder`、`MediaStorage`。
其它:`auth/`(Cognito 裸 HTTP)、`upload/`(WorkManager)、`db/`(Room)、`net/`(RecordingsApiClient/SitesApiClient)、`ui/`(Compose)、`core/AppState`+`SettingsStore`。

## 开发/真机工作流(重要)

- **构建**:`JAVA_HOME` 未设 → 用 Android Studio 自带 JDK:Git Bash `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` 再 `./gradlew`。
- **Dropbox 构建锁**:仓库在 Dropbox 里,gradle 偶发 `java.io.IOException: Could not delete '...build...'` —— **重跑一次即过**,非真失败。
- **adb**:`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`;真机 `F2S202503103054`。设备**常处横屏**(rotation 1/3,坐标系 640×480 变),UI 自动化用 `uiautomator dump` 动态取控件 bounds 别硬编坐标;掉线用 `adb reconnect`/重启 server;`adb pull` 用 `MSYS_NO_PATHCONV=1 ... //sdcard/...`。物理键是 lolaage 广播(F2spActionParser),真机验收需用户上手。
- **媒体验证**:本机有 ffmpeg/ffprobe(`$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_*/...bin`),用来验录制 mp4 编码/分辨率/音轨、抽帧看画面。
- **测试**:`./gradlew testProdDebugUnitTest`(现 163 绿)。相机/GL/GPS 类无 JVM 单测,靠真机验证。构建按 flavor 分:`assembleProdDebug`/`assembleDevDebug`。
- **权限**:CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS 走 `MainActivity` 的 RequestMultiplePermissions;MANAGE_EXTERNAL_STORAGE/SYSTEM_ALERT_WINDOW 走 Settings intent。

## 发版给用户装机(每一条都因为踩过才写)

**从哪里构建。** **绝不用 `Dropbox/GrandTime`** —— 那个检出经常停在别的 session 的在途分支上
(实测见过 `feat/device-identity-phase2` 领先 main 五个提交)。在那里构建 = 把别人没做完的
工作装到客户设备上;切它的分支 = 毁掉别人的工作现场。用专用 worktree:

```bash
cd C:/Users/camil/Dropbox/GrandTime-multidevice      # 分支 build-main
git fetch origin && git merge --ff-only origin/main
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleProdRelease
```

新建 worktree 要**手动拷 `local.properties`**(被 gitignore),否则 Gradle 报 "SDK location not found"。

**flavor 决定录音进哪个后端,与 git 分支无关(编译期烧死)。**

| flavor | 包名 | 桌面名 | 网关 | 落到 |
|---|---|---|---|---|
| prod | `com.benzn.grandtime` | **FieldSight** | `ys94qy2tk0` | prod 桶 + `fieldsight` 库 |
| dev | `com.benzn.grandtime.dev` | **devfieldsight** | `wdsgobb7b0` | test 桶 + `fieldsight_test` 库 |

「App 录了但 prod 网页空」几乎总是装了 dev 版。**验法是 grep APK 的 dex 找 `ys94qy2tk0`,
别信文件名。**

**版本号:每次出机都同时 bump `versionCode` 和 `versionName`**(`app/build.gradle.kts`)。
`0.5.9`/`13` 曾带着三份不同的代码出机三次,导致「这台设备上跑的是哪一版」**无法回答** ——
只能把 APK 拉下来 grep dex 里的类名。`versionCode` 是 Android 装机时比较的(整数 +1),
`versionName` 是人在设置里读的。

**装机:永远覆盖,不要卸载。** 两个 flavor 都用**同一张 release 证书**
(`CN=GrandTime, O=benzn-tech`),所以 `-r` 直接覆盖,**登录和录音都保留**
(`firstInstallTime` 不变就是覆盖成功的证据):

```bash
ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r fieldsight-PROD-<sha>.apk
```

卸载会丢登录(录音在外部存储、不丢),所以是最后手段。**只有签名真的不一致才需要卸载,
而且要先查再说** —— 拉设备上的 APK 用 `apksigner verify --print-certs` 比对 SHA-256。
**不要不查就抛「换签名要卸载」的警告**:证书已经很久没变过,一句无根据的警告白白多一步,
还会教会用户忽略警告。

**APK 要发布到用户拿包的地方**:`C:/Users/camil/Dropbox/fieldsight-dev-apk/`,
命名 `fieldsight-{PROD|dev}-{git 短哈希}.apk`,并更新 `README.txt` 说明这版改了什么、验了什么。
直接从 `app/build/outputs/...` 装机「能用」,但会让那个文件夹留着**过期的包且看不出过期** ——
下一个从那里装的人静默拿到旧代码。

**🔴 dev 版平时必须卸载,只在真做两台设备测试时装。** F2SP 的实体键是 ROM 广播
(`lolaage.*`),**每个装了的 app 都会收到**:两个 flavor 同时在,按一下键**两个都开始录**,
一个进 prod 一个进 test,而两边的 Files 界面像到足以让人对着错的那个操作。dev 版还有自己
独立的数据库,没登 test 账号时列表全是 Waiting —— 看起来像「上传坏了」。

**识别这个症状**:用户说 Files「拆散了」或「之前传好的又变成 Waiting」时,
**先查前台是哪个包**(`adb logcat | grep "Changing focus"`),再去动分组代码。

## 硬约束

- **全 Android framework,不引原生库/新 Gradle 依赖**(设备 ABI 仅 armeabi 32 位)。
- **无 Google Play Services 保证**(MediaTek):GPS 用 `LocationManager` 不用 FusedLocation。
- **开发产物一律英文**(代码注释/提交信息/技术文档,2026-07-15 起用户指定;存量中文注释不回改);用户可见文案英文;与用户的对话交流用中文。
- 视频硬编上限 **1920×1088**;4:3=1440×1080 / 16:9=1920×1080;HEVC 默认降 H.264。
- 不反编译/抄厂商代码(只取接口事实,干净重写);SMART-PTT 只禁用(`pm disable`,恢复 `pm enable com.corget`)。

## Superpowers 流程

每个子项目:`brainstorming`(→ spec `docs/superpowers/specs/`)→ `writing-plans`(→ plan `docs/superpowers/plans/`)→ `subagent-driven-development`(逐任务:实现子代理 + 审查子代理 + 修复循环;终审用 Fable 5)→ 真机验收 → `finishing-a-development-branch`(合 main + tag + push)。SDD 台账 `.superpowers/sdd/progress.md`(gitignored)。**逐组件真机验证**(P1/P2 那样先探针定生死)。

## 后端(跨仓)

FieldSight 后端在 `C:/Users/camil/Dropbox/fieldsight-pipeline`(Python/psycopg3/SAM,Aurora PG16)。移动端现按 flavor 分连不同栈:**prod flavor(默认/出货)** 连 prod org gateway `ys94qy2tk0`,湖 `fieldsight-data-509194952652`;**dev flavor** 连 fieldsight-test 栈(org API `wdsgobb7b0`,桶 `fieldsight-data-test-509194952652`)。CI/CD:合 `develop` → sam deploy fieldsight-test(自动迁移);合 `main` → prod(审批门)。`recordings` 表(迁移 0009)含 `gps_track jsonb` 列(P3 用)。
