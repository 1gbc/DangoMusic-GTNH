<h1 align="center">DangoMusic GTNH Edition</h1>
<p align="center">在 GT New Horizons 里，听音乐、看歌词、和朋友分享正在播放的歌。</p>

<p align="center">
<a href="https://github.com/1gbc/DangoMusic-GTNH/releases"><img alt="Development version" src="https://img.shields.io/badge/version-0.1.0--dev.5.38-orange"></a>
<a href="https://www.gtnewhorizons.com/version-history/"><img alt="Target GTNH" src="https://img.shields.io/badge/target-GTNH%202.9.0-blue"></a>
<a href="LICENSE"><img alt="Project license GPL v3" src="https://img.shields.io/badge/license-GPL--3.0-green"></a>
</p>

<p align="center">
<a href="#功能概览">功能</a> · <a href="#运行环境">环境</a> · <a href="#下载与安装">安装</a> · <a href="#快速上手">上手</a> · <a href="CHANGELOG.md">更新记录</a> · <a href="docs/RELEASE_AUDIT.md">发布审查</a>
</p>

## 项目与来源

本项目基于 **[Sorihiko 的 DangoMusic](https://github.com/Sorihiko/DangoMusic)** 移植开发，起点为上游 [v1.7.3 / `1e82730`](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c)，目标为 **Minecraft 1.7.10 / Forge / GTNH 2.9.0**。

**这是非官方 GTNH 移植版本。** 本项目独立维护，不代表 Sorihiko、GTNH 团队或各音乐平台，也未声称获得其官方背书。上游原作的播放器、多音源、液态玻璃界面、3D 歌词和 HUD 是本项目的基础；1.7.10 平台适配、GTNH 联机共享与听歌房、歌词和交互调整、Apple Music 实验接入等工作见本仓库[更新记录](CHANGELOG.md)。上游其他 Minecraft 版本的功能和安装包不等于本移植版的兼容范围。

感谢 Sorihiko 和本项目实际贡献者。完整来源与第三方说明见 [CREDITS](CREDITS.md) 和 [第三方许可](THIRD_PARTY_NOTICES.md)。

> 当前仍为开发测试版。`5.38` 已有测试 JAR，尚未完成全部公开分发准备；对应源码公开可获取、LGPL 依赖源码与替换验证、配套组件许可清单等剩余条件见[审查报告](docs/RELEASE_AUDIT.md)。本页面不代表正式发布公告。

## 功能概览

以下状态以 `0.1.0-dev.5.38` 源码及已有记录为准。**已实现**表示存在实际逻辑；**已实测**只指所链接记录中的具体场景，不能推及全部歌曲、平台或环境。本次文档整理没有新增游戏实测。

| 功能 | 当前范围 | 状态与依据 |
|---|---|---|
| 网易云 / QQ 音乐 / 酷狗 | 登录、搜索、歌单、曲目信息与播放；能力受平台及账号权限限制 | 已实现；[移植验证](docs/PHASE5_VALIDATION.md)，登录和接口需持续回归 |
| 播放器与队列 | 播放/暂停、停止、上下首、跳转、音量；顺序、一次打乱的随机队列、单曲循环；网易云喜欢列表的心动模式 | 已实现；[历史更新与验证](CHANGELOG.md) |
| 歌词 | 大歌词页、独立设置的 3D 歌词、多句滚动、翻译/音译、KTV 覆盖、当前行抖动、偏移及前奏/间奏/尾声提示 | 已实现；缺少逐字时间时不能保证精确逐字覆盖 |
| HUD 与快捷键 | 歌曲卡片、灵动岛、封面进度条、位置设置、Tab 玩家列表避让、组合键与停止快捷键 | 已实现；停止后显示玩家 ID、FPS、延迟 |
| 附近共享 | 同服同维度附近播放与歌词投影，默认 32 格 | 实验性；服务端和参与客户端均须安装，见[联机说明](docs/NEARBY_SHARING.md) |
| 听歌房（音乐群组） | 同服跨距离、邀请、多房间成员关系、单个当前房间、服务端保存、回房与本地音乐接管 | 已实现，待多客户端完整回归；[5.38 记录](CHANGELOG.md)；不宣称支持上游 Firebase 跨服房间 |
| 续播与缓冲 | 非 Apple 队列下次启动恢复为暂停；可调缓冲、网易云下一首预取 | 已实现；5.38 构建与服务端冷启动记录，听感待回归 |

### Apple Music：实验性、单独说明

**游戏内操作，后台组件实际播放。** Windows WebView2 组件既处理官方登录，也承载受保护播放；它并非只负责登录验证。尚未实现由 Mod 的 Java 解码器直接播放 Apple 完整音频。窗口可以隐藏，组件进程必须运行。

| 能力 | 已有内容 | 验证边界 |
|---|---|---|
| 登录与恢复 | 官方账号弹窗、本机独立资料、关闭主窗口隐藏、恢复有效会话 | 真实账号退出重启恢复有记录；过期/撤销后须重新授权，不是扫码登录 |
| 完整播放与控制 | 游戏队列、暂停/继续、停止、跳转、音量；完整播放权限校验 | 已实测出声及跳到 90 秒后继续播放；不是全曲、全地区、长期稳定性验收 |
| 搜索与媒体库 | 目录搜索、个人歌曲和歌单、热门、最近播放、推荐卡片 | 已接入；最近播放真实接口返回 10 项；收藏修改未实现，全部推荐类型未完整验收 |
| 歌词与 KTV | 本地 LRC/YRC/TTML 或严格匹配的网易云歌词，复用游戏显示 | 解析/时钟回归通过；非 Apple 官方歌词，3D 游戏内效果待完整目视验收 |
| 音质与音效 | 以官方网页实际能力为准 | 不保证无损、Hi-Res、空间音频、Java 均衡器或全部淡入淡出效果 |
| 一起听 | 未完成独立账号的 Apple 同步播放路线 | 开发中；不提供 Apple 音频 URL 转发或 DRM 音频下载 |

证据：[5.36 真实播放记录](docs/APPLE_5_36_LOCAL.md)、[5.37 时钟与最近播放记录](docs/APPLE_5_37_LOCAL.md)、[当前安装指南](docs/INSTALL.md)。现有 `5.38` Release 只有 Mod JAR，**没有可供普通玩家下载的配套 Apple 组件**；仅装 JAR 不会启用 Apple 完整播放。

## 运行环境

| 项目 | 玩家需要什么 |
|---|---|
| 游戏与整合包 | GTNH **2.9.0** / Minecraft **1.7.10** / Forge **10.13.4.1614**；不用于 Fabric、NeoForge 或其他 MC 版本 |
| Java 与图形环境 | 沿用 GTNH 对应发行包的 Java 和启动参数。已有 Windows 基线：**Java 25.0.3 + lwjgl3ify 3.0.42 + LWJGL 3.4.3**；保留整合包依赖，不自行混装 LWJGL |
| 渲染与声音 | 可用 OpenGL 与系统声音设备；平滑中文字体使用系统字体回退。精简 Java 环境未经完整验证 |
| Apple 可选组件 | 目前仅 Windows x64；另需 WebView2 Evergreen Runtime。是否另装 .NET 8 Desktop Runtime 取决于组件包，见[安装指南](docs/INSTALL.md) |
| 联机可选组件 | 单人只装客户端；附近共享、听歌房需要服务器及参与客户端均安装 |

GTNH 官方提供不同 Java 发行包，见[官方下载历史](https://www.gtnewhorizons.com/version-history/)和 [lwjgl3ify 安装说明](https://github.com/GTNewHorizons/lwjgl3ify/blob/master/README.MD)。Mod 没有声明 lwjgl3ify 的 Forge 硬依赖，但当前验证基线使用它；纯 LWJGL 2、Java 8 游戏运行、Linux/macOS 客户端尚未完整验收。**编译目标 Java 8 不是玩家推荐运行版本。**

<details>
<summary>开发者构建环境</summary>

使用 **JDK 25 运行 Gradle**、**JDK 8 编译 toolchain**，Gradle wrapper **9.7.1**、GTNHGradle **2.0.33**。执行 `./gradlew build`，Windows 执行 `gradlew.bat build`；首次构建需联网获取依赖。产物在 `build/libs/`。完整步骤和 PR 规范见 [CONTRIBUTING](CONTRIBUTING.md)。

</details>

## 下载与安装

1. 前往 [Releases](https://github.com/1gbc/DangoMusic-GTNH/releases)。核对测试版说明及未完成的分发条件。当前有资产的版本为 [0.1.0-dev.5.38](https://github.com/1gbc/DangoMusic-GTNH/releases/tag/0.1.0-dev.5.38)。仓库未公开时需要访问权限。
2. 玩家安装包为 **`dangomusicgtnh-0.1.0-dev.5.38.jar`**。不要安装 `-dev.jar`、`-dev-preshadow.jar`、`-sources.jar`，不要把 Source code ZIP 当作 Mod。
3. 退出游戏并备份实例，把 JAR 放进该实例 **`.minecraft/mods/`**，移走旧版同 Mod JAR，只保留一份。
4. **保留 `.minecraft/config/dangomusicgtnh/`**，尤其 `accounts/`、个人设置、续播与本地歌词。升级不需要删除账号。
5. 进入世界按 **M**。Apple 及服务端安装见[完整指南](docs/INSTALL.md)，附近共享与听歌房见[联机说明](docs/NEARBY_SHARING.md)。

目前没有稳定发行版；缺少 JAR 资产的历史条目不可安装。公开发布前须完成[分发检查](docs/RELEASE_AUDIT.md)，CI 成功不等于游戏兼容性保证。

## 快速上手

1. 默认 **M** 打开播放器，在左下角选音源并进入账号入口。会员、订阅和地区权限由平台决定。
2. 搜索歌曲或打开歌单，点击曲目或“播放全部”。底栏控制播放、停止、队列、模式、音量和进度。
3. **设置 → 快捷键**可录制按键或组合键；也可在 Minecraft 控制设置修改 DangoMusic 分类。除打开播放器的 M 外，播放/暂停、上下首、停止、音量和歌词默认未绑定，避免整合包冲突。
4. 从当前播放区进入大歌词页；世界内 3D 歌词和 HUD 从设置启用。两套歌词设置独立，翻译、音译和 KTV 依赖实际歌词数据。
5. 同服服务器已安装 Mod 时，在 **设置 → 多人**启用分享/收听，或从侧栏进入 **听歌房**。

## 截图、限制与反馈

尚未整理可公开的 GTNH 截图，不使用上游其他 MC 版本图片冒充。需要补充：播放器首页/搜索、世界内灵动岛和多行 KTV、大歌词页、双客户端共享/听歌房。截图先隐藏账号、服务器地址和聊天隐私。

- 平台接口可能变化；登录和播放受订阅、版权及网络限制。选择最高音质不等于实际返回该档位。
- 音乐、封面、歌词与商标属于各自权利人。代码开源许可不授予内容分发权，短期 URL 和服务端中转另有平台条款风险，见[审查报告](docs/RELEASE_AUDIT.md)。
- 配置在 `.minecraft/config/dangomusicgtnh/`。普通音源下载的音频、预取与封面主要在内存；Apple WebView2 保存自己的登录资料和浏览器缓存。见[目录说明](docs/INSTALL.md#配置与缓存位置)。
- [提交问题](https://github.com/1gbc/DangoMusic-GTNH/issues/new/choose)时提供版本、音源、复现步骤和脱敏日志。**不要上传 accounts、Cookie、令牌、私钥、签名地址或浏览器资料。** 安全问题按 [SECURITY](SECURITY.md) 使用私密渠道。

---

[更新记录](CHANGELOG.md) · [贡献指南](CONTRIBUTING.md) · [致谢](CREDITS.md) · [第三方许可](THIRD_PARTY_NOTICES.md) · [GPL-3.0](LICENSE) · [发布审查](docs/RELEASE_AUDIT.md)
