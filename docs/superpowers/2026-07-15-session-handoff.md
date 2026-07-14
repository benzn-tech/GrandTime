# Session Handoff — 2026-07-15(SP-Capture2-P2 上线 → P3 待执行)

> 新 session 从这里接手。TL;DR:**SP-Capture2-P2 已完成上线**;**SP-Capture2-P3(GPS 水印+gps_track)已设计+计划、未实施**——直接对 P3 plan 跑 subagent-driven-development,Task 1 先真机定水印方向。

## 0. 立即上手(Resume here)

1. 读 `CLAUDE.md`(项目/工作流/真机注意)。
2. 读 P3 spec:`docs/superpowers/specs/2026-07-15-sp-capture2-p3-watermark-gps-design.md`。
3. 读 P3 plan:`docs/superpowers/plans/2026-07-15-sp-capture2-p3-watermark-gps.md`(14 任务,完整代码,无占位)。
4. 用 `superpowers:subagent-driven-development` 执行该 plan。新建分支 `feature/sp-capture2-p3`(从 `main` = origin/main)。
5. **Task 1 = GL 水印方向真机探针**:必须真机(用户上手 F2SP)定标 `WM_ROTATION_DEG`,再接后续。
6. **Task 12 在后端仓** `C:/Users/camil/Dropbox/fieldsight-pipeline`(gps_track 入库),合 `develop` 走 CI/CD 部署 fieldsight-test。
7. 全过后 `finishing-a-development-branch`:合 main + tag `sp-capture2-p3-accepted` + push。

## 1. 本 session 走过的路径

### SP-Capture2-P2(相机层重写)—— 已完成上线
- **动机**:CameraX 1.4 够不着设备的 `c2.mtk.hevc.encoder`,且录像中拍照只有 480P 双绑能用;要 HEVC + 4:3/16:9 + 全分辨率录像中拍照。
- **P1 探针(先做过,已并入 main)**:真机枚举 —— 传感器 5MP 4:3、HW 编码器硬上限 1920×1088、`c2.mtk.hevc.encoder` 存在、三流(编码器+JPEG+预览)并发 OK、录像中拍满 5MP OK、`setLocation` 元数据可写、**无 0.5x 超广角**(FOV 实测 cam0 52°H)。见 `docs/superpowers/plans/.capture2-p1-findings.md`(gitignored 本地)。
- **P2 执行(SDD,10 计划任务 + 每任务审查)**:新建 `capture/camera2/`(VideoSizeSelector/SegmentRecorder/GlRecordPipeline/Camera2Pipeline),重写 CaptureManager+TorchController 到 Camera2Pipeline,**删除整个 frame-grab 抓帧子系统**(Camera2 原生全分辨率录像中拍照),预览换 SurfaceView,加 4:3/16:9 设置,**移除 CameraX**(5 依赖+4 文件)。
- **Fable 5 整支终审抓出并修复**:
  - **Critical C1**:新管线录**无声视频**(旧 CameraX 有麦克风音频)→ 加 AAC 音频轨(AudioRecord→AAC MediaCodec→同一 muxer 第二轨),**真机验证 mp4 = hevc 视频 + AAC 音频**。
  - **5 Important(I1-I5)**:录像中改设置畸变(锁 activeSpec)、相机死亡泄漏/卡死(invalidateOnCameraLost+onCameraLost 通知)、连按拍照协程泄漏(单发守卫+超时)、起录失败清理、降采样丢 EXIF(保 orientation)。
  - **再审补漏**:音频启动失败降纯视频、AAC configure 守卫、AudioRecord.stop 先于 join、SegmentRecorder.stop CAS 幂等、起录失败 await release。
- **真机 T10 验收 PASS**(用户上手):hevc 1440×1080 4:3 + AAC 音频 + 录像中满 5MP 2592×1944 照片;预览/红灯/快照闪现确认。
- **上线**:20 提交 ff 合 `main`,tag **`sp-capture2-p2-accepted`**,push GitHub,分支已删。106 单测绿。

### SP-Capture2-P3(GPS 水印)—— 已 brainstorm + spec + plan,未实施
- 用户决策:**视频+照片都烧水印**;GPS 用 **LocationManager**(无 GMS);**水印 3 行(用户名/时间/经纬度),第 4 行地址栏预留**(Geocoder 反查依赖网络、麻烦,推后);**gps_track 全链路**(移动端采集+上传+后端 recordings.gps_track 入库,跨仓);水印开关默认开;**单 plan**,不拆子阶段。
- spec + plan 已写、已 push(见 §0)。

## 2. 当前 git 状态

- `main` @ `96061cd`(= origin/main),分支 `main`,无活跃 feature 分支。
- Tags:`sp-capture2-p2-accepted`(P2 验收),另有 subproject-1/ui-reskin/sp3a/sp3c 等历史 tag。
- 后端仓 `fieldsight-pipeline`:org API 在 `develop`(领先 main),移动端连 fieldsight-test 栈。

## 3. SP-Capture2-P3 待实施内容(14 任务概览)

plan 有每任务完整代码。顺序与依赖:

| # | 任务 | 类型 | 关键点 |
|---|---|---|---|
| T1 | GlRecordPipeline 水印叠加层 + 方向定标 | 真机 | 2D alpha 着色器叠底部;`WM_ROTATION_DEG` 默认 90,**真机探针定标**(setOrientationHint 下方向)。**先定生死。** |
| T2 | WatermarkContent(4 行组装,地址栏保留) | TDD | 纯;3 行(address 传 null);`Watermark.build` 留 address 参数向前兼容。 |
| T3 | 水印开关设置 watermarkEnabled(默认开) | TDD | SettingsStore。 |
| T4 | CaptureRecord.gpsTrack 列 + Room 迁移 2→3 | 迁移 | ADD COLUMN gpsTrack TEXT。 |
| T5 | WatermarkRenderer(内容→Bitmap) | 真机 | Canvas 半透明黑条+白字描边;视频/照片共用。 |
| T6 | Camera2Pipeline.setWatermarkBitmap 透传 + 段起点 location | 真机 | 转发 gl;startSegment location 传真值。 |
| T7 | GpsTracker(LocationManager GPS+轨迹) | 真机 | **不做 Geocoder**;latestFix + snapshotTrackJson。 |
| T8 | CaptureManager 编排(GPS 起停+~1Hz 刷水印+段起点+gps_track 存 DB) | 真机 | 水印定时器;address=null。 |
| T9 | 照片水印(Canvas 叠 JPEG,保 EXIF) | 真机 | takePhoto 落盘后叠。 |
| T10 | 定位权限(manifest + MainActivity required) | — | ACCESS_FINE_LOCATION。 |
| T11 | SettingsScreen 水印开关行 | — | RadioDialog<Boolean>。 |
| T12 | **后端** gps_track 入库(fieldsight-pipeline) | TDD | mark_uploaded 加 gps_track(Jsonb);complete_recording 取 body.gpsTrack;pytest。合 develop 部署。 |
| T13 | 移动端上传 gps_track(RecordingsApiClient.complete + UploadWorker) | TDD | complete 加 gpsTrack 参数。 |
| T14 | 真机端到端验收 + tag + 删探针 | 真机 | 3 行水印方向/可读/走时、照片水印、setLocation 起点、gps_track 入库、无定位降级、P2 回归。tag `sp-capture2-p3-accepted`。 |

## 4. 已知 / 追踪但未排期(不阻塞 P3)

**P2.5 小加固(终审 Minor,列 P3 或单独收尾)**:
- 编码器 `GlRecordPipeline` 无 EGL/GL 错误检查(config 失败=黑视频不易诊断)。
- 分段 drain 错误不上报:`SegmentRecorder` drain 静默 break → 截断文件按成功 finalize 并上传(证据完整性,值得修)。
- `eglPresentationTimeANDROID` 未设 → A/V 时戳为墙钟近似(P3 动 drawFrame 时可顺手加)。
- 瞬时起停零帧段(0 字节 mp4 行 finalize)。
- 拍照超时后 late-JPEG 占 ImageReader 槽。
- `onFinalized` error 分支缺 `sounds.stopRecording()`。
- pipeline 字段级 race(CAS 已挡危险部分,残留 double-notify 良性)。

**功能推后**:
- 水印**地址反查**(Geocoder,lat/lon→街道)——`Watermark.build` 已留 address 参数,日后接个 Geocoder 传值即可。
- **SP-Ask**(语音问 FieldSight RAG,后端已就绪;注意 armeabi 32 位)——用户明确推后。

**用户侧非阻塞待办**:手电/回放画质/Files 应用可见性/提示音的人工确认。

## 5. 真机 / 环境注意(新 session 务必知道)

- `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`。
- Dropbox 构建锁 → gradle 报 "Could not delete" 重跑一次即过。
- 设备常横屏(rotation 1/3),UI 自动化动态取 bounds;`adb pull` 用 `MSYS_NO_PATHCONV=1` + `//sdcard`;掉线 `adb reconnect`。
- 媒体验证用本机 ffprobe/ffmpeg(WinGet Gyan.FFmpeg)。
- 物理键=lolaage 广播,真机录制/验收要用户上手 F2SP;媒体落 `/sdcard/FieldSight/<用户目录>/{video,audio,photo}/`。
- 后端 T12 在 `fieldsight-pipeline` 仓,合 `develop` 触发 CI/CD 部署 fieldsight-test。
