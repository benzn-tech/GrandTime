# SP-Capture2-P3 设计文档:GPS 水印 + 轨迹

**日期**:2026-07-15
**状态**:设计已口头评审通过,待用户书面审阅
**前置**:SP-Capture2-P2(Camera2+GL+MediaCodec 管线,已合并 main,tag `sp-capture2-p2-accepted`)。本阶段在 P2 管线上加水印与 GPS。

## 1. 目标

给现场作业记录仪的**视频与照片烧录 GPS 水印**,并把**逐秒 GPS 轨迹上传后端**,补全证据链的时空信息:
1. **视频水印**:相机帧上逐帧叠 4 行文字(用户名 / 时间戳 / 纬经度 / 街道地址),GL 渲染。
2. **照片水印**:JPEG 落盘前 Canvas 叠同样 4 行。
3. **GPS 采集**:`LocationManager`(GPS_PROVIDER)取定位;段起点写 mp4 `setLocation` 元数据;逐秒轨迹 `gps_track` 传后端 `recordings` 表(SP4a 已备 `gps_track jsonb` 列)。
4. **水印开关**:设置项,默认开。

**一个 plan(不拆子阶段)**,内含一个前置真机探针(GL 水印方向)先定生死。

## 2. 现状与约束

- **P2 管线**(`capture/camera2/`):`GlRecordPipeline` 相机 OES 纹理 → `drawFrame` 画到编码器面 + 预览面;`Camera2Pipeline` 门面;`SegmentRecorder`(`prepare(..., location)` 已接受起点 GPS,P2 传 null);`Camera2Pipeline.takePhoto` 出满 5MP JPEG bytes 落盘;`downscaleJpeg`(CaptureManager,含 EXIF orientation 保留 helper)。
- **后端**:`recordings` 表(迁移 0009)有 `gps_track jsonb` 列;`POST /api/org/recordings/{id}/complete` 端点(`repositories/recordings.py` + `lambda_org_api.py`);`RecordingsApiClient.complete`(移动端)。CI/CD:合 develop → sam deploy fieldsight-test。
- **设备**:F2SP MediaTek,**无 Google Play Services 保证** → GPS 用框架 `LocationManager`,不用 FusedLocation。ABI armeabi → 全 framework,不引原生库/新依赖。
- **编码方向**:编码帧为传感器方向(横),`setOrientationHint(90)` 播放时旋正 → **水印必须画在"旋转后为底部水平"的位置**(§7 风险,探针先验)。

## 3. 必须保住的现有功能(回归即失败)

P2 全部功能:HEVC/AVC、4:3/16:9、分段滚动、录像中拍照、预览挂摘不断录、息屏后台、手电、**音频轨**、SP3b 灯语、SP4 上传+siteId、照片精度降采样。水印/GPS 为**增量叠加**,不改这些路径的既有行为(水印关时与 P2 完全一致)。

## 4. 架构

```
GpsTracker(LocationManager, GPS_PROVIDER ~1s)
  ├─ latestFix: (lat, lon, time)                    → SegmentRecorder.setLocation(段起点)
  ├─ track: List<{t, lat, lon}>(录制期间累积)       → complete 上传 → 后端 recordings.gps_track
  └─ address: String?(Geocoder 反查 latestFix,异步、缓存、离线=null)
WatermarkText = 4 行:
  用户名(AppState.loginState 的 displayName)
  YYYY-MM-DD HH:mm:ss(逐秒)
  lat, lon(latestFix,如 "-36.8499, 174.7600";无定位="定位中…")
  街道地址(address,无=省略该行)
视频水印(开):GlRecordPipeline 加"文字纹理"层
  ├─ 文字画到 ARGB Bitmap(Canvas)→ 上传 GL 纹理(内容变才重画重传,~1Hz)
  └─ drawFrame:①画相机 OES 纹理(现有)②带 alpha 的 2D 着色器叠文字纹理于底部
照片水印(开):Camera2Pipeline.takePhoto 出 JPEG → CaptureManager 落盘前
  解码 Bitmap → Canvas 叠 4 行 → 重压 JPEG(保 EXIF orientation,复用 P2 helper)
水印开关(SettingsStore.watermark,默认 on):关 → 视频不加文字层、照片不叠、逻辑同 P2
```

- **组件边界**:
  - `GpsTracker`(新,`capture/`):`start()/stop()`、`latestFix`、`snapshotTrack(): List`、`address`。只依赖 `LocationManager`/`Geocoder`,可独立理解。
  - `WatermarkRenderer`(新,`capture/camera2/`):纯函数把 `WatermarkContent`(4 行数据)画到 `Bitmap`(Canvas);GL 层与照片层共用同一画法,保证视频/照片水印一致。
  - `GlRecordPipeline`:加 `setWatermarkBitmap(Bitmap?)`(null=不叠);内部第二纹理 + 2D 着色器。
  - `Camera2Pipeline`:`startSegment` 传 `location`(GpsTracker.latestFix);`setWatermarkBitmap` 透传给 GL;`takePhoto` 返回 bytes 由 CaptureManager 叠照片水印。
  - `CaptureManager`:编排——录制起停开关 GpsTracker;~1Hz 定时更新 WatermarkContent → 重画 Bitmap → `pipeline.setWatermarkBitmap`;照片落盘叠水印;段起点 location;complete 传 gps_track。
  - 后端 `recordings.py` `complete_recording`:接收可选 `gps_track` → 写列。

## 5. GPS 采集与权限

- `LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000ms, 0m, listener)`;无 GPS 定位时水印显"定位中…"、`setLocation` 跳过、track 空。
- 权限 `ACCESS_FINE_LOCATION`:manifest 声明 + 权限向导(SP3c 的向导)加一项;未授权 → 录制照常,水印 GPS 行显"无定位权限",不阻断录制。
- Geocoder 反查:`Geocoder.getFromLocation`(异步/后台线程),成功缓存地址;移动超阈值(如 50m)才重查;离线/失败 → address=null(省略地址行)。
- `gps_track` 格式:`[{"t": <epoch_ms>, "lat": <double>, "lon": <double>}, …]`,录制期间每秒 append,段/会话结束随 complete 上传。

## 6. 设置项 + 后端

| 设置 | 值 | 默认 |
|---|---|---|
| 视频/照片水印 | 开 / 关 | **开** |

后端(fieldsight-pipeline):`complete_recording` 请求体加可选 `gps_track`(数组);`repositories/recordings.py` 的 complete 写 `gps_track` 列(jsonb);无则不写。移动端 `RecordingsApiClient.complete` 加 `gpsTrack` 参数。CI/CD 合 develop → fieldsight-test。**S3 键/既有 complete 契约不变,仅加一个可选字段。**

## 7. 风险 / 注意

- **★水印方向(最大风险)**:编码帧传感器方向 + `setOrientationHint` 旋转,水印在 GL 空间画的位置要保证"播放旋正后位于底部且水平"。**plan 第一个任务=真机探针**:GL 叠一行测试水印,录一段,拉回确认方向/位置对,再正式接。撞坑早暴露。
- **性能**:文字 Bitmap 只在内容变(秒/GPS/地址)时重画重传纹理,不是每帧;GL 每帧多画一个小 quad,开销小。
- **照片水印**:走解码-叠-重压,复用 P2 `downscaleJpeg` 的 EXIF 保留;与精度降采样同一次 IO 完成,避免二次读写。
- **无 GMS**:LocationManager 框架方案,不引依赖。Geocoder 同框架(在线才有结果)。
- **回归**:水印关 = P2 行为;水印开也不动分段/预览/息屏/音频路径。真机对照 P2 验收项 + 新增(水印方向可读、照片带水印、gps_track 入库、setLocation 起点)。
- **P2.5 顺带**(可选,非阻塞):P3 动 GlRecordPipeline/drawFrame 时,可顺手补编码器 EGL 错误检查、`eglPresentationTimeANDROID` 精确 A/V 时戳(见 P2 计划附录)。

## 8. 交付

单 plan + SDD:①GL 水印方向真机探针 → ②WatermarkRenderer(纯,可测)→ ③GpsTracker + 权限 → ④GlRecordPipeline 文字层 + Camera2Pipeline 透传 → ⑤照片水印 → ⑥CaptureManager 编排(GPS 起停、~1Hz 刷、段起点、照片叠)→ ⑦设置开关 + UI → ⑧后端 gps_track(跨仓)+ 移动端上传 → ⑨真机端到端验收 + tag。关联既有决策:#69(GPS 水印)由本阶段落地;补 [[fieldsight-recording-site-attribution-gap]] 的时空维度。
