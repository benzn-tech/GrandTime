# SP-Capture2 设计文档:Camera2+MediaCodec 录制核心重写

**日期**:2026-07-14
**状态**:设计已口头评审通过,待用户书面审阅

## 1. 目标

把录制核心从 CameraX 重写为 **Camera2 + MediaCodec** 自控管线,一次改造解锁三样现在做不到的能力,并修掉一个既有限制:
1. **可配置宽高比 / 满传感器广角**:Camera2 精确选传感器输出尺寸(4:3 满传感器吃满 120° 广角,或 16:9)。
2. **HEVC(H.265)直驱编码**:MediaCodec 配 `video/hevc`(设备有 `c2.mtk.hevc.encoder`,CameraX 够不着)。同画质文件小 ~40-50%。
3. **GPS 水印烧录**:自控编码器输入 Surface → OpenGL 逐帧叠水印。
4. **(修既有限制)各分辨率都能"录像中拍照"**:Camera2 用独立 ImageReader 与编码器 Surface 并行,消除现在"只有 480P 双绑才能录像中拍照、720P/1080P 不行"的坑。

## 2. 现状与约束

- **现有 CameraX 管线**(SP3a/SP3c,`capture/CameraSession.kt` 等):Video(Recorder/VideoCapture)+Image(ImageCapture)+Preview 用例;480P 才双绑(录像中拍照),720P/1080P 只绑 Video;Preview 可挂/摘不断录;照片按目标像素选 4:3 档 H.264/AVC 1920×1080 默认。
- **硬件**(见 [[grandtime-f2sp-device-facts]]):MediaTek MT6768、Android 13、传感器原生 4:3、`c2.mtk.hevc.encoder` 存在、**ABI 仅 armeabi(32 位)**——但本重写全用 Android framework(Camera2/MediaCodec/MediaMuxer/OpenGL ES),**无需任何原生库**,不受 32 位约束。
- **后端**:VAD lambda 会从 HEVC/H265 生成 H.264 `web_video/` 预览,故 **HEVC 上传能在网页端播**(无兼容问题)。

## 3. 必须保住的现有功能(回归即失败)

分段视频滚动、**录像中拍照**(改进为全分辨率可用)、Preview 前台挂/摘不中断录像、**息屏后台录制**(OverlayGuard 维持进程可见 + FGS camera|microphone)、手电、录音(AudioRecorder)、物理键映射、**SP3b 物理灯语**(录像红/录音黄/待机蓝,由 captureState 驱动)、SP4 上传 + 打 siteId、系统提示音、Files 缩略图/上传角标。

**收缩改造面**:重写限定在相机层(`CameraSession`/`VideoRecorder`/`PhotoTaker` → 新的 Camera2 实现);`CaptureManager` 的编排(键动作、DB、上传、灯、状态机)接口尽量不变,把回归风险关在相机层内。

## 4. 架构

```
Camera2 CameraDevice → CaptureSession(3 路输出 Surface):
  ├─ Preview Surface(前台可见时;息屏时摘除,录像不断)
  ├─ 编码器输入 Surface(经 OpenGL:相机帧 + 水印叠加 → MediaCodec)
  └─ 拍照 ImageReader(JPEG,录像中并行取证拍照,全分辨率)

视频:相机帧 → [OpenGL GLES 渲染:相机纹理 + 水印文字层] → MediaCodec(video/hevc 优先,
      不可用降 video/avc)→ MediaMuxer(setLocation 写起点 GPS)→ 分段 mp4。
音频:AudioRecord → MediaCodec(AAC)→ 同一 muxer(或既有 AudioRecorder 路径,择一,P2 定)。
拍照:ImageReader onImageAvailable → JPEG 落盘(同现有 PhotoTaker 契约)。
```

- **宽高比**:开录时按设置(4:3/16:9)从 `StreamConfigurationMap` 选最匹配的相机输出尺寸 + 对应编码尺寸;Preview 的 TextureView/SurfaceView 按同比 FIT_CENTER 显示。
- **HEVC 降级**:`MediaCodecList` 查 `video/hevc` 编码器,能建就用;建失败/不支持 → `video/avc`。
- **水印(P3)**:GLES 把相机外部纹理画到编码器 Surface,再在其上画一个文字层(用户名/时间戳/GPS,3 行,底部),逐帧刷新时间与 GPS。水印开关默认开。
- **GPS**:FusedLocation/LocationManager 采集;muxer.setLocation(起点 fix);逐秒轨迹存 `gps_track` 传后端 recordings 表(SP4 已备列)。

## 5. 设置项(SettingsStore 扩展)

| 设置 | 值 | 默认 |
|---|---|---|
| 录制宽高比 | 4:3 / 16:9 | 4:3(满传感器广角) |
| 视频编码 | 自动(HEVC 优先,不可用降 H.264) | 自动 |
| 视频水印 | 开 / 关 | 开 |

(视频清晰度、照片精度、分段时长、实时上传等既有设置保留。)

## 6. 分阶段交付(降风险;每阶段独立 plan + 真机验收)

**P1 — 真机可行性探针(先行,像 SP3b 那样先定生死)**
- 枚举后置 Camera2 `StreamConfigurationMap` 的可用尺寸(video/JPEG/preview)+ 各宽高比最大档。
- 建 `video/hevc` MediaCodec + Camera2 会话,录一段短片 → MediaMuxer 出 mp4 → 拉回真机/电脑确认能播、码率/尺寸对。
- 验证"编码器 Surface + 拍照 ImageReader 并行"在本机不冲突。
- 验证 setLocation 元数据写入。
- 产出:可行性结论 + 真实尺寸表 + HEVC 档参数,直接喂 P2 的 plan。**若某项撞硬件坑,早暴露、早调方案。**

**P2 — 管线对等 + HEVC + 宽高比**
- 用 Camera2+MediaCodec 重写相机层,达 §3 全部现有功能对等(分段/预览/息屏/录像中拍照/手电/音频/灯语/上传)。
- 接入 HEVC(降级 H.264)+ 宽高比设置。真机逐项验收(对照 SP3c 九项 + 新增全分辨率录像中拍照)。

**P3 — 水印 + GPS**
- OpenGL 逐帧水印(用户名+时间戳+GPS 三行,底部)+ 水印开关。
- GPS 采集 + muxer.setLocation + gps_track 传后端。真机验证水印可读、GPS 准。

## 7. 风险 / 注意

- **最大风险=回归**:CameraX 那套 480P 双绑、预览挂摘、息屏、分段是反复调过的。Camera2 更底层、更多样板;靠 P1 探针 + P2 逐项对照 SP3c 验收 + 相机层封装 兜底。
- **设备特异**:MediaTek HAL 的并发流/尺寸限制真机才知道——P1 探针必做。
- **音频合流**:是把音频也走 MediaCodec+同一 muxer,还是保留现有 AudioRecorder 独立路径,P2 按真机结果定(优先复用现有可用路径)。
- **不新增原生库**(armeabi 约束下全走 framework)。
- 每阶段都在特性分支 + SDD + 真机验收,过了再合 main 推 GitHub。

## 8. 交付拆分
- **SP-Capture2-P1**(探针):独立小 plan,真机跑,产出尺寸/HEVC 结论。
- **SP-Capture2-P2**(管线):独立 plan + SDD,相机层重写,真机对等验收。
- **SP-Capture2-P3**(水印+GPS):独立 plan + SDD,真机验收。
- 关联既有决策:#70(宽高比)由本项目"设置项 4:3/16:9"落地;#68(HEVC)、#69(水印)并入。
