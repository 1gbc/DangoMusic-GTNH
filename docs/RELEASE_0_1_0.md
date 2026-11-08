# DangoMusic GTNH Edition 0.1.0

首个正式发行包，面向 **GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614**。基于原有 0.1.0-dev.5.38 功能与发布准备工作，保留现有音源、配置和听歌房；本次不新增播放功能。Apple Music 和多人共享仍有实验性范围，见下文。

## 来源与许可

本项目基于 [Sorihiko 的 DangoMusic](https://github.com/Sorihiko/DangoMusic) 移植开发，最初来源为上游 [v1.7.3 / 1e82730](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c)。这是**非官方 GTNH 移植版**，不代表原作者、GTNH 或音乐平台官方。感谢 Sorihiko、XiaoDaiLuo、1gbc、Alivn-chao 及其他实际贡献者，具体以提交历史为准。

项目沿用 GPL-3.0，第三方库/字体各保留原许可。JAR 已包含 GPL、LGPL、Apache、OFL 和适用 NOTICE；配套 Apple 组件附 WebView2 SDK LICENSE/NOTICE。

完整项目源码、构建和安装脚本、五项捆绑依赖的已发布源码在同页 **source-bundle.zip** 中免费提供。接收 JAR 的用户应有同等途径取得该源码包；私有仓库时 Release 只对有权限者可见，不能称为公众可下载。将来公开分发二进制时，同样公开提供源码包。

## 下载哪个文件

| 附件 | 用途 |
|---|---|
| `dangomusicgtnh-0.1.0.jar` | 玩家和可选服务端的普通安装包，放入 mods |
| `dangomusicgtnh-apple-player-0.1.0-windows-x64.zip` | Apple Music 可选播放组件，当前只支持 Windows x64 |
| `dangomusicgtnh-0.1.0-source-bundle.zip` | 项目源码、依赖源码、许可与解码库重建/替换工具，不是玩家安装包 |
| `SHA256SUMS.txt` | 上述附件的 SHA-256 校验值 |

GitHub 自动生成的 Source code ZIP 不包含本次附带的 dependency-sources，不能代替完整的源码附件。不要把开发 JAR、源码 ZIP 放入 mods。

## 主要功能

- 网易云、QQ 音乐、酷狗：游戏内登录、搜索、歌单和播放，能力受各平台实际接口、账号与版权限制。
- 液态玻璃播放器：顺序、一次打乱的随机队列、单曲循环；暂停、停止、上下首、跳转和音量；网易云喜欢列表内的心动模式。
- 大歌词页与独立 3D 歌词：多句滚动、翻译/音译、KTV 覆盖、当前行抖动、偏移和前奏/间奏/尾声提示，取决于歌曲实际数据。
- 灵动岛、歌曲卡片、封面进度条、Tab 避让、组合快捷键；停止后恢复玩家 ID、FPS、延迟。
- 同服附近共享与听歌房：需客户端与服务端均安装，听歌房在同一服务器跨距离同步；保留房间成员、回房和本地播放接管。
- 非 Apple 队列记忆并暂停恢复、音频缓冲调整、网易云下一首预取。

## 从测试版升级

1. 退出游戏并备份实例。沿用 GTNH 自带 Java、lwjgl3ify、LWJGL 和启动参数；已有 Windows 游戏基线为 Java 25.0.3 / lwjgl3ify 3.0.42 / LWJGL 3.4.3，不把 Java 8 编译目标当作运行推荐。
2. 将 `dangomusicgtnh-0.1.0.jar` 放入实例 `.minecraft/mods/`，移走旧的 DangoMusic GTNH JAR，只保留一份。
3. **保留 `config/dangomusicgtnh/`**，尤其 accounts、settings、续播和本地歌词，不需要为升级删除账号。
4. 进入世界默认按 M，选择音源、登录、搜索并播放。其他快捷键可在设置绑定。
5. 使用附近共享/听歌房时，服务端和参与客户端也更新到 0.1.0；服务端不安装 Apple 组件。

完整操作见 [安装指南](https://github.com/1gbc/DangoMusic-GTNH/blob/0.1.0/docs/INSTALL.md) 和 [联机说明](https://github.com/1gbc/DangoMusic-GTNH/blob/0.1.0/docs/NEARBY_SHARING.md)。

## Apple Music 可选安装与边界

1. 需要 Windows x64、[.NET 8 Desktop Runtime x64](https://dotnet.microsoft.com/en-us/download/dotnet/8.0) 和 [WebView2 Evergreen Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)。本次组件不捆绑这两个运行时。
2. 解压组件 ZIP，将 **apple-player 文件夹**放到 `config/dangomusicgtnh/`，最终存在 `config/dangomusicgtnh/apple-player/DangoApplePlayer.exe`；同级 DLL、配置及 licenses 一起保留。不要覆盖 accounts。
3. 在游戏选择 Apple Music，首次在官方 Apple 登录窗口亲自授权。有效会话可恢复，撤销或过期后仍需重新登录。
4. 搜索、媒体库、歌单和控制使用游戏界面；**实际受保护音频仍由后台 WebView2 组件播放**，不是仅登录组件或 Java 原生播放。窗口可隐藏，组件进程必须运行。

已有真实账号记录包含出声、跳到 90 秒后继续、退出重启后恢复授权；此次只重建和自检组件，未重复真实账号测试。最近播放真实接口曾返回 10 项。收藏修改、Apple 独立账号一起听未完成；歌词来自本地文件或严格匹配的网易云数据，不称为 Apple 官方歌词。无损、空间音频、Java 均衡器及全部音效不保证可用。

## 本次发行整理

- 正式版本号 0.1.0，固定标签与源码，保留旧测试版资产。
- 中文主页、来源/贡献者与非官方声明、环境/安装/功能分项说明同步更新。
- 补齐 JAR 内项目 GPL、Kotlin/annotations Apache、NOTICE 等材料；保留原字体和库版权，校正旧重定位说明。
- 源码包包含准确版本依赖源码及校验值，提供 LGPL 解码库编辑、重编译与替换完整 Mod 的脚本和步骤。
- Apple 可选附件采用框架依赖发布，只附必要组件与许可，不包含账号、浏览器缓存、完整运行时或音乐。

## 验证与已知限制

- 正常 Gradle 构建通过；LGPL 两库从源码重编译、使用替换仓库重建 Mod，通过重定位标记检查。
- 合成 MP3 解码输出 27,648 个 PCM 样本，Ogg packet/page 回环通过；这不是完整 Vorbis 歌曲或实际听感验收。
- Apple 组件 publish 成功，WebView2/关闭策略自检、两条无控制台 UTF-8 启动检查、10 项网页桥接回归通过；自检不证明真实账号/DRM 播放成功。
- 最终 JAR、组件 ZIP 和源码包核对许可、版本、文件清单与定向敏感数据扫描，校验值在 SHA256SUMS.txt。
- **本次未启动 GTNH，也未重做双客户端、真实账号播放、长期后台和全部地区回归。** 既有游戏/播放证据不是本轮新测试。Linux/macOS、Java 8 游戏运行、纯 LWJGL 2 未完整验收。
- 平台接口、订阅和地区版权影响可用性。歌曲、歌词、封面和商标权利不随代码 GPL 授予。共享仅用于你有相应授权的内容；L2 短期 URL 与默认关闭的 L3 中转存在独立平台条款风险，不宣传绕过会员或共享订阅权益。

源码标签：[0.1.0](https://github.com/1gbc/DangoMusic-GTNH/tree/0.1.0)，主开发分支 port/gtnh-1.7.10。详见 [更新记录](https://github.com/1gbc/DangoMusic-GTNH/blob/0.1.0/CHANGELOG.md)、[第三方许可](https://github.com/1gbc/DangoMusic-GTNH/blob/0.1.0/THIRD_PARTY_NOTICES.md)、[解码库替换说明](https://github.com/1gbc/DangoMusic-GTNH/blob/0.1.0/tools/release/README.md)。

反馈：[Issues](https://github.com/1gbc/DangoMusic-GTNH/issues/new/choose)；不要上传 accounts、Cookie、令牌、私钥、签名播放 URL、受保护音频或浏览器资料。
