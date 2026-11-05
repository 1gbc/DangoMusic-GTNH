# DangoMusic GTNH Edition 移植方案

## 目标与工具链

以本机 ShatteredPrism 的 GTNH 2.9.0 正式版为第一验收基线。目标为 Minecraft 1.7.10 / Forge 10.13.4.1614，正式实机使用 Java 25.0.3 + lwjgl3ify 3.0.42 / LWJGL 3.4.3。2.9.x 其他发行版须分开验证。目标日志和模块审计见 PORTING_AUDIT.md。

从 [ExampleMod 的已审查提交](https://github.com/GTNewHorizons/ExampleMod1.7.10/tree/bf0843f5b29153ad76866d8390916b2e5b559e05) 复用构建约定及 wrapper。Gradle 9.7.1 daemon 使用 JDK 25，Forge 工程采用 GTNHGradle 2.0.33 / RetroFuturaGradle、MCP stable_12。Java 桥选择普通 Java 8 语言模式，由 GTNH convention 选择 Java 8 编译 toolchain。移入 Kotlin 后使用 2.3.21、JVM 8 目标，并单独限制 JDK API。最终检查自有类 major version 52、依赖字节码、重混淆及安装用 JAR；字节码兼容不能替代 Java 25 + lwjgl3ify 下的实际测试。Java 8 作为开发基线及可选兼容分支。实际构建和启动结果以构建记录为准。

标识使用 `dangomusicgtnh`，包名使用 `me.sorihiko.dangomusicgtnh`，作为 GPL 派生项目保留上游归属。配置、会话、缓存分别放在 `config/dangomusicgtnh/settings.*`、`accounts/` 和 `cache/`。首次启动不读原版会话。源码在独立 Git 分支，保留上游提交和移植变更记录。

## 实现边界

- **Forge 入口**。只让 1.7.10 的 `@Mod`、`@SidedProxy` 和空服务端代理可见于共用入口。使用 FML 的远端版本通配设置，实际用未安装该 Mod 的 GTNH 服务器验证握手。仅客户端代理注册键位、屏幕、Tick、HUD 和世界事件。主菜单兼顾 GuiMainMenu 和实际 Custom Main Menu。无方块、实体、服务端任务、频道或同步包。用反射和类加载测试确认服务端路径不加载 Kotlin 业务、客户端 GUI、GL 或音频类。
- **业务及 Provider**。保留 MusicSourceApi 的职责，网易云、QQ、酷狗分别实现统一搜索、详情、播放授权、封面、歌词、歌手、专辑、推荐、排行、用户歌单和二维码生命周期。曲目身份以平台和原生 ID 确定，平台字段留在适配器。区分超时、解析失败、空结果、过期、地区限制、账号无权限和试听。仅使用当前账号被平台授予的音源。先用公开搜索与曲库请求验证协议，再由有权限的测试账号验证各扫码与会员路径。
- **网络和持久化**。由客户端受限工作队列执行。设置连接、读取和响应大小限制，合并并发同键请求，对幂等暂时错误限次退避。会话切换、退出登录和关界面时取消轮询，并用 generation 阻止过期结果回写。播放地址按服务端有效期处理，认证缓存按账号隔离。用原子替换保存设置和会话，日志只记平台、操作、错误分类及状态码。
- **本地播放**。保留 MP3、Vorbis、FLAC 及 MusicPlayer 队列行为，首选 Java Sound PCM，避免接管游戏 OpenAL 上下文。一个输出生命周期由单个播放 worker 管理，控制命令不在客户端线程等待 I/O。切歌、暂停、seek、设备异常或关闭后取消旧任务，关闭流、decoder 和音频行。按已呈现的帧计算歌词时钟，校验暂停、缓冲、变采样率、seek 后的时间连续性。用真实解码样本和设备断开回归。
- **播放器 GUI**。移植共用 MusicScreen 和布局、Spring、Theme、GlassRenderContext 对应实现，重做 1.7.10 屏幕生命周期、键鼠、滚动和输入。保留原版液态玻璃、导航、列表、封面墙、播放栏、歌手和专辑。封面后台处理，纹理创建与销毁调度至渲染线程。按上游 docs 截图在相同布局下检查视觉和交互。
- **HUD 与歌词**。复用 Song Card、灵动岛、TextFlash 和 LyricsModule，1.7.10 侧通过 overlay 事件和 WorldLast 适配。插值相机变换、控制世界遮挡和发光合成，用 try/finally 恢复纹理单元、FBO、深度、颜色、混合、着色器及矩阵。核对 Angelica 实际绘制时序，再决定是否需要兼容入口。字体回归包括简体中文、混排和系统字体缺失。
- **第三方打包**。使用宿主 Gson / Log4j API 和 LWJGL。Kotlin、ZXing 和解码库按已验证的依赖范围打包和隔离，关掉会误删反射入口的依赖裁剪，保留许可证、版权声明及合法可替换的 LGPL 库分发方案。分发前检查 多版本 class 条目和额外 module-info 未进入旧 ASM 扫描路径，并为各库记录对应源码版本。

## 阶段门槛

| 阶段 | 验证与交付 |
|---|---|
| 1–2 审计及方案 | 核对上游提交、实际 GTNH 实例和依赖。提交两份 Markdown。 |
| 3 独立骨架 | 原生 Mod 元数据、代理、配置、M 键和主菜单入口、基础屏幕。先编译、检查重混淆，再到隔离的 GTNH 测试实例启动、打开界面、进入测试世界。 |
| 4 基础播放 | 三家依次完成匿名、扫码、曲库、授权、解码及播放控制。将网络失败、无权限与试听纳入实测，测试高频切歌、关闭、音量及失效会话。 |
| 5 完整界面 | 覆盖推荐、排行、歌单、搜索、队列、列表/封面墙、歌手、专辑、登录、设置，核对无账号、空列表、回退与错误显示。 |
| 6 歌词与 HUD | 核验有字级歌词和仅行级歌词的歌曲、快进/暂停同步、世界遮挡和粒度、效果设置、HUD 定位和缩放持久化。 |
| 7 兼容性 | GTNH 2.9.0 + Angelica 光影开关、资源重载、窗口和全屏、GUI 缩放及其他 HUD/键位。用正常 GTNH 专服实际连通验证，确认物理服务端误安装时不会初始化客户端组件。 |
| 8 发布 | 可安装重混淆 JAR、对应源代码及构建工程、默认配置、安装/编译说明、兼容性报告、完成清单及维护说明。实际账号、设备或服务器不可用的验收项标记未验证。 |

GTNH 的现有实例用于读版本。实机验证使用隔离实例和测试世界。最终发布条件包含成功联机和真实平台验证。账号测试由持有人在界面扫描二维码完成，测试资料仅记录脱敏状态。

参考 [GTNH 开发文档](https://www.gtnewhorizons.com/GTNH-Dev-Doc/)、[GTNHGradle](https://github.com/GTNewHorizons/GTNHGradle)、[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 及 [GTNH 2.9.0 发布页](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/releases/tag/2.9.0)。

