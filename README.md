# mpkg转换

把 Wallpaper Engine（壁纸引擎）导出的 **.mpkg / .pkg** 壁纸，一键提取成 **MP4 视频**和**图片**的安卓应用。
纯 Kotlin + Jetpack Compose 实现，壁纸在本地解析，不上传任何文件。

> 仓库名说明：GitHub 只允许 ASCII 仓库名，故仓库为 `mpkg-convert`，应用名为 **mpkg转换**。

## 它能处理什么

Wallpaper Engine 的壁纸在包里有两种形态，本应用全部支持：

**视频**
1. **直接的 `.mp4` 条目**（视频类壁纸）；
2. **封装在视频纹理 `.tex` 里的 MP4**（场景类壁纸「发送至移动设备 → 导出 .mpkg」后，视频以 TEXB0004 视频纹理存储）。

**图片**
3. **静态壁纸纹理 `.tex`**（RGBA8888 / R8 / RG88 原始像素，或 DXT1/DXT3/DXT5 压缩）→ 解码后输出 PNG；
4. **`.tex` 内直接存放的编码图片**（容器 imageFormat 声明为 PNG/JPEG 等）→ 原样输出对应格式文件；
5. **包内独立的图片条目**（.png/.jpg/.gif/.webp 等素材）→ 原样提取。

提取出的视频字节与包内完全一致，**不重新编码、不压缩画质**；静态纹理解码同样无损（PNG 输出）。

应用会对所有图片候选**智能打分**（像素面积 × 格式系数 × 路径惩罚），并通过灰度/纯色/极端宽高比质量门，把真正的**完整分辨率主图**标注为「★ 主图」排在最前，避免误取预览小图、UI 提示框、遮罩或粒子拖尾；其余图片也全部保留，可自行选择。

> 注意：纯代码 / 网页类壁纸，以及没有经过「导出 .mpkg」渲染的原始场景工程，内部不含现成 MP4 或纹理图片，这类文件无法提取（应用会明确提示）。

## 使用流程

1. 在电脑端 Wallpaper Engine 中，右键目标壁纸 → **发送至移动设备 → 导出 .mpkg**（建议选「高性能 / 保持原始宽高比 / 60 帧」）；
2. 把导出的 .mpkg 通过微信、网盘或数据线传到手机；
3. 打开本应用 → **选择壁纸文件** → 自动扫描提取；
4. 默认情况下，视频保存到「影片 / Movies」、图片保存到「图片 / Pictures」目录下的 **mpkg转换** 文件夹；也可在「设置」中**自定义导出文件夹**，均可直接分享。
5. 每次成功提取都会写入「历史」页：图片显示缩略图、点击可全屏查看；视频点击后会弹出「用哪个应用打开」，选择播放器即可观看。

## 技术说明

- 语言：Kotlin 2.0.20，JVM target 17
- UI：Jetpack Compose（Material 3）
- 解包核心：`app/src/main/java/com/doubao/repkg/core/`
  - `BinaryIO.kt`：小端读取 + 可寻址数据源（文件 / 内存两种实现）
  - `PkgReader.kt`：PKGV#### / PKGM#### 容器解析
  - `TexAssetDecoder.kt`：TEXV0005 纹理统一解码（视频 + 静态图片），含轻量 `peek`
  - `ImageRanker.kt`：主图智能打分与质量门（移植自社区 dsh-mpkg-wallpaper 插件）
  - `Dxt.kt`：S3TC（DXT1/3/5）块解压，移植自 RePKG（MIT，源自 LibSquish）
  - `TexVideoExtractor.kt`：视频纹理提取（保留兼容入口）
  - `Extractor.kt`：高层编排（peek → 打分 → 视频/图片双通道）
- LZ4：极少数视频 mipmap 使用原始 LZ4 block 压缩，通过 `org.lz4:lz4-java` 解压（`AndroidLz4.kt`）
- 导出位置：默认走 MediaStore（Android 10+，无需权限）/ 公共目录（Android 9-）；自定义文件夹使用 SAF `ACTION_OPEN_DOCUMENT_TREE` + 持久化授权，通过 `androidx.documentfile` 写入（`ExportSettings.kt`）
- 历史记录：内部存储 JSON（`HistoryStore.kt`，org.json，无 Room 依赖），缩略图存内部存储；图片应用内全屏查看，视频 `ACTION_VIEW` 调用外部播放器
- 图片加载：`ImageLoader.kt` 后台线程解码 + LRU 缓存 + 按需降采样（历史列表/预览卡片/全屏查看共用）
- 视频封面：`VideoThumbnail.kt` 用 `MediaMetadataRetriever` 抽第 1 秒帧作缩略图
- ETC2 解码：优先 GLES 3.0 离屏硬解（`Etc2Decoder.kt`），失败自动回退纯 CPU 解码（`core/Etc2CpuDecoder.kt`，带 60 组测试向量）
- **联网说明**：唯一的网络行为是「检查更新」——GET 一次静态 `update.json`
  （不带参数、不带设备标识、不上传任何数据）。可在「设置 → 检查更新」中关闭自动检查。
  更新地址与开源地址集中配置在 `UpdateConfig.kt`
- 图片编码：解码出的 RGBA 像素用 Android 系统 Bitmap 编码为 PNG，无需三方库

核心解包逻辑参考并移植自开源项目：

- RePKG：https://github.com/notscuffed/repkg
- RePKG.Neo：https://github.com/masterLazy/RePKG.Neo

## 编译

需要 JDK 17 与 Android SDK（compileSdk 34）。

- Android Studio：直接打开本目录，等待 Gradle 同步后运行；
- 命令行：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew test
```

## 版本

- v1.0.0：PKGV 桌面包 MP4 提取
- v1.1.0：修复 PKGM 手机包（.mpkg）识别，动态/静态壁纸均可解析
- v1.2.0：新增静态壁纸图片提取（纹理解码 + 原样图片），视频/图片双通道
- v1.3.0：主图智能识别（修复取到预览小图、画质问题）、自定义导出文件夹、历史记录（图片全屏查看 / 视频选择应用打开）
- v1.6.0：
  - **修复 ETC2 原图导出上下颠倒**（GLES 渲染到 FBO 后 `glReadPixels` 回读本身已补偿方向，着色器里多余的 V 翻转导致倒立）
  - **历史记录滑动不再卡顿**（缩略图/全屏图改为后台线程异步解码 + 内存 LRU 缓存 + 按需降采样）
  - **导出的视频有封面**（`MediaMetadataRetriever` 抽取视频帧作缩略图与卡片预览）
  - **整体更流畅**（.tex 头部只读 4KB 前缀而非整个条目；缩略图/卡片图降采样后再编码；解析进度节流）
  - 新增**纯 CPU ETC2 解码回退**（GLES 离屏不可用时自动接管），并附 60 组测试向量单元测试
- v1.7.0：
  - **新增「检查更新」**：启动时自动检查（默认开、12 小时最多一次），发现新版本弹窗提示，
    支持「立即更新 / 稍后提醒（24h）/ 忽略此版本」，以及 `force` / `minSupportedVersionCode` **强制更新**
  - 设置页新增**「检查更新」卡片**（点击检查最新版本 + 自动检查开关 + 上次检查时间）
  - 设置页**「关于」卡片**新增**开源地址**（点击打开）
  - 更新清单是一个静态 `update.json`：只做一次 GET，不带参数、不上传任何数据（新增 `INTERNET` 权限仅用于此）
  - 下载地址支持 **GitHub Release 资产直链**或**网盘/对象存储链接**
  - 应用名统一为 **mpkg转换**：桌面名、关于页、导出文件夹（`Movies|Pictures/mpkg转换`）
  - 附 `UpdatePolicyTest` / `UpdateCheckerTest`（纯 JVM 单测，不联网）

## 许可

本项目以 **MIT License** 开源，见 [LICENSE](LICENSE)。

核心解包逻辑（容器解析、纹理解码）参考并移植自下列 MIT 开源项目，版权归其原作者所有：

- [RePKG](https://github.com/notscuffed/repkg) — `core/Dxt.kt`（S3TC 解压，源自 LibSquish）
- [RePKG.Neo](https://github.com/masterLazy/RePKG.Neo) — 容器格式参考

## 声明

壁纸内容版权归原作者所有，本工具仅供个人学习与收藏使用，请勿用于商业用途或二次分发付费内容。

