# Melodist

> 第三方 Android 音乐客户端 · 手机 + Android TV 双端 · Jetpack Compose

[![Release](https://img.shields.io/github/v/release/BakInt/melodist?label=release)](https://github.com/BakInt/melodist/releases)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#下载与安装)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.5-7F52FF?logo=kotlin&logoColor=white)](#技术栈)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#技术栈)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 简介

本仓库是开源项目 [Melodist](https://github.com/Viemean/melodist)（MIT 协议）的个人二开分支。应用通过**公开网络接口**提供在线音乐播放与检索，同时支持本地音乐；包含手机端与 Android TV 端两种形态，共享同一套核心模块。

- **手机端**（`app-mobile`）：标准版 + 系统媒体控制中心适配版
- **电视端**（`app-tv`）：Leanback 焦点导航、手机远控被控端、屏保歌词
- **共享层**（`core-*`）：接口、播放、数据与 UI 组件双端复用

本应用自身不提供、不存储任何音视频媒体资源，所有内容均来自公开网络接口或本地文件，仅供个人学习研究使用。

> 本项目为独立开发的开源分支，与任何音乐服务提供商、设备厂商均无关联，也未获得其授权或认可；文中出现的第三方产品名称与商标归各自权利人所有，仅用于客观描述兼容性。

## 功能特性

### 播放与音质

- 在线播放、搜索、每日推荐、猜你喜欢、相似歌曲、个性电台
- 五档音质（标准 / 高码率 / 无损 / 高解析 / 母带级），按账号权限自动回退
- 空间音频与多声道音效（取决于设备解码器）
- 睡眠定时器（手机 + TV 双端）
- 本地音乐扫描与播放

### 歌词

- 逐行歌词 + 翻译；逐字歌词平滑扫光（时间轴解码 + 实时高亮）
- 歌词页常亮
- 手机：桌面悬浮歌词窗（本地歌词优先、在线歌词兜底 + 本地缓存）
- TV：屏保实时歌词

### 账号

- 扫码登录 / 第三方账号授权 / 唤起已安装客户端一键确认
- 登录状态长期保活（`SessionKeeper` + 周期续签）
- 支持导入已有客户端凭据，凭据状态与会员信息解耦

### 曲库与社交

- 收藏（歌单 / 专辑 / 单曲）、听歌排行、听歌日记、评论
- 一起听（MQTT 5.0）
- 听歌识曲（ACR，CameraX + ZXing）

### 手机端体验

- 搜索联想（包含式歌名）+ 歌词搜索
- 搜索结果行尾收藏按钮 + 收藏数（短格式：`128` / `9k` / `15万+` / `999万+`）
- 收藏页双 Tab（自建 / 收藏）+ 内容分栏（收藏的歌单 / 收藏的专辑）
- 设置页 5 个分栏独立折叠（默认收起）
- 底部标签文字开关；抽屉覆盖式跳转（返回回到抽屉）
- 本地缓存限额（GB，FIFO 淘汰 + 播放保护）
- 播放页副歌标记、页面状态保持（`SaveableStateHolder`）
- 下载（含元数据与封面）、深度链接、远程控制端

### 电视端

- Leanback 焦点导航、Baseline Profile 加速启动
- 手机远控被控端（WebSocket + 流代理）
- 屏保实时歌词

## 与上游的关系

本分支基于上游 **v1.7.5** 发布点（`5260f18`）二开，仅选择性并入 v1.8.0 的部分提交，因此：

- 上游 v1.8.0 之后的改动**不一定**包含在本仓库中；
- 本仓库的提交历史与上游分叉，合并上游更新时可能需要手工解决冲突；
- 公开接口与协议随时可能变更，本仓库不保证长期可用。

本分支相对上游的主要改动（按提交主题）：

- 播放核心稳定性、音质回退链与睡眠定时器
- 逐字歌词（时间轴解码 + 平滑扫光）与歌词界面常亮
- 下载 / 更新 / 串流 / 连接安全加固，登录票据持久化
- 手机端体验修复、搜索联想与歌词搜索、收藏页改造、设置分栏折叠、悬浮歌词窗
- 电视端体验修复、手机远控被控端完善
- 登录续期链路对齐上游 v1.8.0（票据层与续签顺序）

## 屏幕截图

| 主页 | 播放界面 |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="320" alt="主页" /> | <img src="docs/screenshots/player.jpg" width="320" alt="播放界面" /> |

## 下载与安装

成品 APK 见 [Releases](https://github.com/BakInt/melodist/releases)。

| 文件 | 适用设备 | 说明 |
| :--- | :--- | :--- |
| `melodist-mobile-1.7.5-666.apk` | 手机 `arm64-v8a` | 标准版，可与其它客户端共存 |
| `melodist-mobile-originos-1.7.5-666.apk` | 手机 `arm64-v8a` | 系统媒体控制中心适配版，**与同包名应用不能共存，安装前需先卸载** |
| `melodist-tv-1.7.5-666.apk` | TV `arm64-v8a` / `armeabi-v7a` | 电视端，遥控器操作 |

安装说明：

1. 全部 APK 为 **debug 签名**，安装时需允许「安装未知来源应用」。
2. 若设备上已有同包名的应用，需先卸载，否则会提示签名冲突。
3. 电视端可通过 U 盘安装，或 `adb install melodist-tv-1.7.5-666.apk`。
4. 首次启动需登录账号，可用音质与曲库范围取决于账号本身权限。

## 从源码构建

环境要求：**JDK 21**、Android SDK Platform 37 + Build-Tools、Gradle Wrapper（随仓库提供）。

```bash
# 手机端：标准版 + 系统媒体控制中心适配版
./gradlew :app-mobile:assembleStandardRelease :app-mobile:assembleOriginOsRelease

# 电视端
./gradlew :app-tv:assembleRelease
```

- **签名**：release 构建需要 `app-tv/release.jks` 以及环境变量 `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；缺省时回退为 debug 签名（本仓库发布的 APK 即 debug 签名）。
- **Windows 路径限制**：AGP 不接受非 ASCII 工程路径，且 `aidl.exe` 在中文路径下会生成 GBK 编码的依赖文件导致解析失败。工程放在中文目录时，请先用 `subst X: <工程目录>` 映射盘符，再在映射盘内执行 Gradle。
- **单元测试与静态检查**：`./gradlew test`（JUnit 6）、`./gradlew detekt`、`./gradlew ktlintCheck`。

## 项目结构

```
melodist/
├── core-model/      数据模型
├── core-api/        接口层（协议、加解密、登录）
├── core-data/       仓库、本地存储、设置
├── core-playback/   播放引擎（Media3 ExoPlayer）
├── core-connect/    设备互联（WebSocket + 流代理）
├── core-ui/         共享 Compose 组件与主题
├── app-mobile/      手机端（standard / originOs 两个 flavor）
├── app-tv/          电视端
├── benchmark-mobile/
└── benchmark-tv/    Baseline Profile 生成
```

## 技术栈

Kotlin 2.5 · Jetpack Compose（BOM 2026.09，Material 3）· Media3 ExoPlayer · OkHttp 5 · kotlinx.serialization · Coroutines / Flow · Coil 3 · Java-WebSocket · CameraX + ZXing · WorkManager · Leanback（`tv-material`）· Baseline Profiles

`minSdk 26`（Android 8.0）/ `targetSdk 37`。

## 已知限制

- 发布的 APK 为 debug 签名；应用内更新功能当前关闭。
- 手机端仅提供 `arm64-v8a`；电视端同时提供 `armeabi-v7a`。
- 空间音频与多声道音效需要设备具备对应解码器才会显示。
- 音质上限、曲库范围与部分功能受账号权限限制。
- 适配版使用与官方客户端相同的包名，无法与其共存，且可能被系统或应用商店识别。
- 依赖公开接口，上游协议变更可能导致功能失效。

## 免责声明

1. 本项目仅供个人学习研究，严禁用于任何商业目的或非法牟利。
2. 本应用自身不提供、不存储任何音视频媒体资源。在线播放与检索服务均通过公开网络接口或本地存储提供，相关媒体资源的知识产权归原作者或版权方所有。
3. 本项目与任何音乐服务提供商、设备厂商均无关联，未获其授权、认可或赞助。项目文档与界面中出现的第三方名称、商标、产品标识归各自权利人所有，仅用于客观描述兼容性与技术实现。
4. 用户使用本软件所产生的版权争议由使用者自行承担，开发者不对因使用本软件造成的任何直接或间接后果承担法律责任。

## 致谢

- [Melodist](https://github.com/Viemean/melodist)：本项目的上游，MIT 协议开源
- 上游所有贡献者，以及相关接口社区的研究与文档

## 开源协议

本项目基于 [MIT License](LICENSE) 许可协议开源。
