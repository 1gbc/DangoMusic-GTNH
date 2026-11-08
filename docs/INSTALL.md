# 安装、更新与本地资料

当前适用正式发行包 `0.1.0`，功能沿用 dev.5.38。测试边界和本次资产清单见 [发行说明](RELEASE_0_1_0.md)，历史许可证审查见 [RELEASE_AUDIT](RELEASE_AUDIT.md)。

## 玩家安装

GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614。沿用整合包 Java、lwjgl3ify、LWJGL 与启动参数。已有 Windows 基线是 Java 25.0.3、lwjgl3ify 3.0.42、LWJGL 3.4.3；其他组合不视为已通过兼容认证。

1. 退出游戏，备份实例和配置。
2. 打开 [Releases](https://github.com/1gbc/DangoMusic-GTNH/releases)。下载 [0.1.0](https://github.com/1gbc/DangoMusic-GTNH/releases/tag/0.1.0) 的 `dangomusicgtnh-0.1.0.jar`，用同页 SHA256SUMS.txt 核对文件。
3. 放入实际启动实例的 `.minecraft/mods/`，移走旧版同 Mod JAR，只保留一份。不要安装开发包、源码包或 GitHub 自动源码 ZIP。
4. 保留 `config/dangomusicgtnh/`，不覆盖账号资料。进入世界默认按 M。

## 附近共享与听歌房

- 本机听歌：客户端安装即可，远端服务器可以不装。
- 同服分享：服务端也装**同版本普通 JAR**，参与者客户端均须安装。服务器不需要 Apple 组件、图形界面或声音设备。
- 服务端通过握手识别客户端；未装 Mod 的玩家可加入，但不能共享。允许进服不代表不同 Mod 版本已完整兼容。
- 附近共享默认同维度 32 格；听歌房在同服跨距离收听。不宣称支持上游 Firebase 的任意跨服房间。
- 服务端配置 `config/dangomusicgtnh/server.cfg`，房间数据 `groups.json`。操作见 [NEARBY_SHARING](NEARBY_SHARING.md)。

L1 使用听众自己账号；L2 转交主机短期 URL；L3 可选服务端下载中转，默认关闭。L2/L3 不因代码开源就获得音乐分发许可，公开推广前须处理平台条款和权利问题。Apple 未接入这种音频 URL 分享路线。

## Apple Music 可选组件

### 当前分发状态

0.1.0 同页提供 `dangomusicgtnh-apple-player-0.1.0-windows-x64.zip`，与该版 JAR 配套。此附件为框架依赖发布，**不捆绑 .NET 或完整 WebView2 Runtime**，需要另装以下微软运行时。旧 5.38 只有 JAR，不能用旧条目拼装本次组件。

### 组件工作方式

游戏负责搜索、列表、队列、歌词显示与控制；Windows 后台 WebView2 组件负责官方会话及受保护音频播放。它不是仅登录工具，结束进程会中断播放。无需常开 Chrome/Edge 标签页或 Node 控制台，但后台浏览器内核仍须运行。

已有真实账号验证包含实际出声、跳至 90 秒后继续出声、正常退出重启后恢复授权；没有完整覆盖全曲、所有地区、长期后台和全部异常恢复。证据见 [5.36](APPLE_5_36_LOCAL.md) 和 [5.37](APPLE_5_37_LOCAL.md)。

### 安装配套组件

1. 当前仅 Windows x64，需要 [WebView2 Evergreen Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)，由微软单独安装更新。
2. 确认组件包类型：自包含包附带 .NET；框架依赖包（项目默认 `SelfContained=false`）另需 [.NET 8 Desktop Runtime x64](https://dotnet.microsoft.com/en-us/download/dotnet/8.0)。以包内运行配置和发布说明为准，不将本地自包含测试包的结论套用于所有构建。
3. 退出游戏，将 **apple-player 文件夹**放入实例 `config/dangomusicgtnh/`，最终路径为：

   ```text
   .minecraft/config/dangomusicgtnh/apple-player/DangoApplePlayer.exe
   ```

   同级 DLL、运行配置和许可证一起保留，不多套一层 ZIP 文件夹。只更新 `apple-player`，保留 `accounts`。
4. 按 M 选择 Apple Music 并进入账号入口。组件自动启动和本机配对；首次在官方 Apple 弹窗亲自登录。没有验证过跨设备扫码登录。
5. 在游戏搜索、打开媒体库/歌单并播放。主窗口 × / Alt+F4 隐藏组件，游戏退出时结束组件。Apple 会话有效时可恢复，撤销或过期仍须重新登录。

需要相应 Apple Music 订阅及地区播放权限。不提取或分发解密音频，不保证无损、空间音频或网易云 Java 音效可用于 Apple。

### 开发构建

另装 .NET 8 SDK：

```powershell
dotnet publish tools/apple-native-player/AppleNativePlayer.csproj -c Release -r win-x64 --self-contained false -o out/apple-player
```

这是框架依赖包。自包含发布须显式使用 `--self-contained true` 并核对随包 .NET LICENSE / ThirdPartyNotices。WebView2 SDK 锁定 `1.0.4314.35`；发布前按[第三方许可](../THIRD_PARTY_NOTICES.md)准备许可、对应源码与完整文件清单。`dotnet publish` 成功不代表材料齐备。

## 配置与缓存位置

以下路径相对于实际实例 `.minecraft/`：

| 路径 / 数据 | 用途 |
|---|---|
| `config/dangomusicgtnh/settings.json` | 模块、歌词、HUD 与快捷键设置，升级保留 |
| `config/dangomusicgtnh/settings.cfg` | Forge 侧初始音量，升级保留 |
| `config/dangomusicgtnh/accounts/` | 平台会话与设备资料，含敏感内容，禁止上传 |
| `config/dangomusicgtnh/accounts/apple-webview/` | Apple 独立登录资料、浏览器缓存和存储，不删除或上传 |
| `config/dangomusicgtnh/lyrics/apple/` | 合法取得的本地歌词与 `.offset`，见 [导入格式](APPLE_5_34_LOCAL.md#导入歌词) |
| `config/dangomusicgtnh/resume-queue.json` / `resume-state.json` | 非 Apple 队列和进度；主动停止后不续播，可能包含收听历史 |
| `config/dangomusicgtnh/apple-player/` | 组件与诊断状态，不是歌曲下载目录 |
| `config/dangomusicgtnh/groups.json`（服务端） | 房间、成员和当前房间，备份但不随公共发布包分发 |

普通音源 `MusicPlayer` / `NearbyAudioPlayer` 使用下载到内存的音频字节，下一首预取也在内存；`CoverCache` 保存内存图片和纹理。当前没有统一的“已下载歌曲文件夹”。Apple WebView2 缓存由内核管理，不是可导出的音乐文件，不应随组件打包。

反馈只提供必要的脱敏日志、诊断状态与复现步骤，不上传整个 config、账号目录或实例。安全问题按 [SECURITY](../SECURITY.md) 处理。
