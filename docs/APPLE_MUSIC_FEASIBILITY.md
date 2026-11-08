> **历史阶段记录**：本文保留当时实现和测试边界，不是当前安装/能力承诺。5.38 当前安装以 [INSTALL](INSTALL.md) 为准，完整能力与限制见 [README](../README.md#apple-music实验性单独说明)。

# Apple Music 接入评估

基线为 `0.1.0-dev.5.19`（`6e60c2e`），目标为 Windows 上的 GTNH 2.9.0 / Forge 1.7.10。以下结论来自源码和官方接口核对。尚未使用真实 Apple Music 账户执行授权和播放。

## 扫码授权与桌面浏览器

Apple Music 个性化请求需要 Music User Token。Apple 的网页流程由 MusicKit JS 的 `authorize()` 在发起授权的网页中完成。Apple 官方公开文档里未找到能让手机扫设备码、再由 Windows 实例轮询或接收授权的 Apple Music 接口。Sign in with Apple 的登录结果也不能用于访问 Apple Music 媒体库。二维码可以打开页面，但如果在手机上完成授权，会话仍由手机上的网页管理。Windows 端能否取得可用的播放会话，需要独立验证。

自建跨设备中转会增加公网服务和令牌处理，即使成功取得媒体库授权，也仍需验证 Windows 网页能否建立完整播放会话。当前没有这样的中转服务或验证结果。

建议先验证同机浏览器方案。用户点击游戏内「连接 Apple Music」，Mod 打开默认浏览器，在 Apple 的授权界面登录并同意访问。授权成功后，游戏自动刷新账号状态。如果浏览器要求首次播放由用户交互触发，在网页上提供一次启用播放的按钮，之后由游戏控制。

### 安装、启动与退出

- 优先把桥接网页随 JAR 打包，使用已安装的浏览器加载 Apple 官方 MusicKit JS。安装时只更换 Mod，另行配置通过 Media Services 密钥签发的短期开发者令牌。签名私钥保留在本机签名工具或受控签发服务。
- Mod 仅在打开 Apple Music 时启动本机回环服务，绑定 `127.0.0.1`，不监听局域网。页面通过一次性配对值建立会话，校验来源及会话标识，限制接口与请求类型。Apple 用户令牌由 MusicKit 网页管理，游戏收到授权状态、浏览数据和播放状态。
- 浏览器标签页在播放期间保持打开。停止命令应同时清空游戏状态并使待处理的浏览器播放命令失效。关闭网页、断开连接、退出账号或退出游戏时，回收对应会话。网页也应通过会话心跳超时自行停止播放。
- 验证浏览器来源、Apple 签发的令牌与网页来源是否匹配，并实测 Windows 上的授权弹窗、受保护播放和浏览器交互限制。如果本机网页来源不能走通，先确认可信 HTTPS 页面方案与部署方式。

官方参考：[MusicKit](https://developer.apple.com/musickit/) · [网页授权](https://developer.apple.com/musickit/web/?path=%2Fstory%2Fuser-authorization--page) · [用户令牌](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit) · [开发者令牌](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens) · [Apple 工程师对两类授权的说明](https://developer.apple.com/forums/thread/703942)

## 功能对照

| 功能 | 当前入口与接入方式 | 能力和验收 |
| --- | --- | --- |
| 音源与账户 | 在 `MusicSources`、`Sidebar` 和登录入口新增 Apple Music；以独立会话管理 Apple Music | 手机扫码回传缺少已找到的官方接口；同机浏览器授权待验证。检查授权、取消、超时、重新连接、登出及过期状态 |
| 搜索、歌曲、封面、专辑、歌单和歌手 | 通过 `MusicSourceApi` 复用列表 UI，用 Apple Music API 获取目录资源 | 官方有对应目录接口；实测地区、分页和无结果。保存资源类型与原始 ID，区分媒体库 ID 与目录 ID |
| 个人媒体库、收藏、推荐 | 扩展 `Library` 中的音源能力判断，使用授权后的 `/v1/me` 接口 | 读取媒体库、推荐和添加收藏有公开接口；双向收藏状态、取消收藏及上传歌曲的可播性分别验证。网易云心动模式继续走网易云 |
| 完整播放与预览 | `MusicPlayer` 复用队列，增加控制 MusicKit 的播放后端。现有下载和解码流程继续处理原有音源 | MusicKit Web 提供网页播放。Windows 上待验证完整歌曲、暂停、跳转、停止、进度和播完事件。预览单独标识，不能计入完整播放验收 |
| 顺序、随机、单曲循环、停止 | 由游戏持有队列与模式；浏览器播放当前一首。将游戏的 `generation` 规则延伸到异步浏览器命令 | 用 A/B/C 队列检查实际顺序与显示，切换模式应保留当前曲目和进度。模拟延迟播放响应、快速切歌和退出，验证旧会话无法继续出声 |
| 音量、淡入淡出、音质和音效 | `PlayerBar` 根据后端能力显示选项。现有 PCM 淡化、均衡与环境音效依赖 Mod 自己解码 | 测试 MusicKit 音量控制后再决定能否实现渐变。暂未确认可选无损档位或游戏音效所需的 PCM 接口。浏览器负责播放，不导出受保护音频，也不落盘完整歌曲 |
| 灵动岛、卡片、快捷键和 Tab 避让 | 保持由 `MusicPlayer` 暴露曲目和状态，让浏览器回传实际进度、时长和缓冲状态 | 检查切换音源后只有一路出声，停止后立即显示玩家 ID、FPS 与延迟，组合键和 Tab 避让照常工作 |
| 大歌词页与 3D 歌词 | `MusicScreen` 和 `LyricsPage` 当前限制为网易云；`LyricsModule` 已通过音源适配器读取歌词 | Apple 的公开歌曲字段可标记 `hasLyrics`，未找到公开接口返回可用的歌词原文和时间轴。大歌词页可显示曲目信息和暂无歌词。若将来有合法歌词来源，按数据分别开放同步、翻译、音译和 KTV，保持两处设置独立 |

接口参考：[Apple Music API](https://developer.apple.com/documentation/applemusicapi) · [请求和资源 ID](https://developer.apple.com/documentation/applemusicapi/handling-requests-and-responses) · [媒体库歌曲字段](https://developer.apple.com/documentation/applemusicapi/librarysongs/attributes-data.dictionary)

## 下一步验证顺序

1. 确认可接受同机浏览器授权及播放期间保留标签页。准备有效的 Apple Music 开发者令牌，以及由测试者在浏览器内亲自登录的有效订阅账号。无需提交密码、私钥或用户令牌。
2. 在 Windows 上实测授权、读取媒体库、完整播放，并验证游戏侧命令与播放进度回传。记录浏览器、网页来源和需要的交互。用曲目时长与预览区分，验证能播放超过预览时长并跳转到歌曲后段。
3. 最小流程成立后再接入通用播放队列和 UI，验证切源、停止、超时、地区限制、未订阅、断网、网页关闭和会话失效；在 GTNH 2.9.0 回归现有网易云、QQ 音乐和酷狗。
4. 通过验收后打包 JAR，写清浏览器与令牌配置、已实现功能和限制，提交版本标签和更新说明。

## Windows 原型

已准备[独立验证程序和令牌生成步骤](../tools/apple-music-probe/README.md)。终端模拟游戏侧，先验证浏览器链路，再决定怎样接入 Mod。
