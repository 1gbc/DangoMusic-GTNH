# Apple Music Windows 组件

当前安装与组件分发状态见 [INSTALL](../../docs/INSTALL.md#apple-music-可选组件)，依赖许可见 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md#apple-播放组件)。0.1.0 提供 framework-dependent Windows x64 组件 ZIP，需另装 .NET 8 Desktop Runtime 与 WebView2，见发行说明。

下文保留各阶段历史记录；其中“当前”“待验证”等字样仅描述该阶段。现状以以上指南和 5.36/5.37 验证记录为准，不按旧版安装文件名操作。

# 5.33 更新：关闭窗口保留登录，首页和媒体库状态修复

右上角 × 和 Alt+F4 改为隐藏组件，不再结束会话。游戏退出时组件才正常回收；重新打开游戏播放器会尝试复用固定 Default 配置中的有效登录资料。请保留 accounts/apple-webview，不要删除或迁移登录资料。Apple 撤销或过期的会话仍需重新授权，不能承诺永久免登录。

登录状态增加只读媒体库权限探测，修复 SDK 标志未就绪时游戏始终未登录的问题。首页提供“热门歌曲”“媒体库歌曲”和“刷新媒体库”；目录搜索/热门歌曲与个人媒体库权限分开处理。首次恢复需要等待数秒，媒体库失败会显示权限/接口/网络状态。

请同时替换 5.33 JAR 和此组件。关闭原游戏后覆盖 apple-player，保留 accounts。进入 Apple Music 后先等状态恢复，再测试搜索、热门歌曲、媒体库与个人歌单。关闭组件主窗口后再次打开不应重复登录。跨游戏重启恢复与真实媒体库读取仍需用户验证。

以下为此前版本的阶段记录，当前以本段为准。

# 5.32 更新：直接在游戏内登录与浏览

点击 Apple Music 账号入口会自动启动组件并尝试弹出官方授权；明确已授权后自动收起窗口。回到游戏左侧搜索歌曲，或打开“媒体库歌曲”/个人歌单，点击歌曲或播放全部。队列和三种播放模式由游戏管理，无需复制配对链接、浏览器扩展或 Node。

同时替换 5.32 JAR 和本组件文件夹，保留现有 accounts 登录资料。新浏览接口和自动授权效果仍待真实账号实测；若自动弹窗失败，可在组件内点官方登录。搜索最多100项、媒体库和单歌单最多前500个资源。

完整说明见源码 docs/APPLE_IN_GAME_LIBRARY.md。以下5.31说明作为此前安装记录，当前以本段为准。

# 内嵌 Apple Music 播放组件 · 5.31 实验版

目标：不再需要保持 Chrome/Edge 标签页或安装扩展。Mod 启动并管理一个使用 Windows WebView2 的播放组件，组件负责官方网页的账号与受保护播放，游戏负责播放界面和控制。

**这不是把 Apple 受保护音频交给网易云的 Java 解码器。** 实际声音由 Mod 管理的 WebView2 组件输出；浏览器内核仍在后台运行。尚未验证真实 Apple 登录、DRM 完整播放和隐藏窗口后的连续播放。已通过编译、组件启动自检、游戏端原生配对和旧桥接协议回归。5.29 的 Chrome 实测不能代替这个新环境的测试。

## 安装

1. 退出游戏。旧 Node 控制台输入 `stop`、`quit`；旧浏览器标签页停止 Apple Music 播放。
2. 将 `dangomusicgtnh-0.1.0-dev.5.31.jar` 替换实例 mods 中的旧版本。
3. 将组件 ZIP 中 **apple-player** 文件夹整体放入实例的 `.minecraft/config/dangomusicgtnh/`，最终应存在：
   `config/dangomusicgtnh/apple-player/DangoApplePlayer.exe`。
4. 需要 Windows x64、Microsoft Edge WebView2 Evergreen Runtime，以及 [.NET 8 Desktop Runtime x64](https://dotnet.microsoft.com/en-us/download/dotnet/8.0)。开发机器已检测到这两个运行环境。无需安装 .NET SDK、Node 或浏览器扩展。
5. 启动游戏，按 M，选 Apple Music，点击账号入口 → **启动内嵌播放器**。组件由游戏自动配对，不需要复制链接。
6. 在组件自己的官方 Apple 页面登录。它使用独立本机资料目录，因此第一次需要重新登录，不会读取 Chrome 的 Cookie。登录资料保存在 `config/dangomusicgtnh/accounts/apple-webview/`，不要上传。
7. 首次测试请在组件窗口选歌并确认能播放，再点击 **隐藏窗口，回到游戏**，用游戏底栏和快捷键控制。需要登录或诊断时点击游戏里的 **显示登录窗口**。

组件是附属进程，运行时无需外部浏览器窗口。游戏退出/崩溃后，组件检查父进程并退出；连接失效也会尝试暂停后退出。关闭组件主窗口会结束播放，隐藏窗口按钮则保留后台播放。只关闭自己创建的组件，不关闭用户浏览器。

## 本次必须验证

- 在组件内登录，播放超过三分钟的歌曲；持续超过试听时长，并跳到 120 秒后继续出声，核对游戏显示的完整时长。
- 隐藏组件后运行至少两分钟，确认声音、真实进度和游戏暂停/继续、切歌、跳转、音量、停止正常。
- 游戏点击停止后灵动岛恢复空闲；切回网易云不双播。
- 游戏退出后该组件自动退出，再进游戏可复用组件自己的登录资料。
- 授权弹窗、无订阅、无版权、组件关闭、网络中断有合理反馈。

若只能试听、登录失败或隐藏后停止，需要如实反馈。不能通过关闭 DRM 检查或导出解密音频解决。

## 当前范围

5.30 优先验证播放方式变更。游戏内搜索、媒体库、收藏、推荐、独立播放队列、Apple 歌词和 Apple 一起听仍未实现，选歌暂在组件内完成。后续应在内嵌环境验证通过后，通过受限 API 适配器把这些入口接到现有网易云式游戏界面。

## 构建与依赖

使用 .NET 8 SDK：`dotnet publish tools/apple-native-player/AppleNativePlayer.csproj -c Release -o <输出目录>`。依赖锁定 Microsoft.Web.WebView2 1.0.4314.35（依赖许可与再分发条款以包内 LICENSE/NOTICE 为准）。官方 NuGet TLS 连接失败时，开发阶段从华为 NuGet 镜像取得同版包，随后使用 `dotnet nuget verify --all` 验证 Microsoft 作者签名与 NuGet.org 仓库签名通过。未修改依赖二进制。

发布组件需同时保留 Microsoft WebView2 包的 LICENSE.txt、NOTICE.txt 和项目 GPL-3.0 许可证。构建缓存、登录资料、私钥、配对凭据和音频不得提交。

`DangoApplePlayer.exe --self-test` 只检查参数拒绝和 WebView2 Runtime 是否可加载，不登录或播放歌曲；退出码 0 代表自检通过，不能证明 DRM 完整播放。

官方参考：[WebView2 WinForms](https://learn.microsoft.com/en-us/microsoft-edge/webview2/get-started/winforms)、[MusicKit](https://developer.apple.com/musickit/)。

补充验证记录：新增的原生组件进程异常输入检查被自动审批拒绝（仅返回 blocked by policy），未执行。最终包只通过编译和通信协议检查；先前 --self-test 返回 0，但不覆盖本次后续生命周期改动。需用户手动验证。

## 5.31 启动修复

5.30 无控制台启动时，设置 Console.InputEncoding 会抛异常，窗口出现前即退出。5.31 改用原始标准输入流读取 UTF-8，Python 两条无界面回归和与游戏一致的 Java ProcessBuilder 管道测试通过。不再覆盖启动错误，异常退出会显示阶段码（110 输入流、111 解析、112 字段、113 Windows Forms 初始化、114 父进程字段、115 窗口创建）。请同时更新 JAR 和组件文件。此前异常输入检查未执行的缺口，现已用只发送无效测试数据、不会打开登录窗口的回归补充。完整 DRM 播放仍待验证。
