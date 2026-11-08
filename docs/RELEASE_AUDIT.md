# 公开发布前审查记录

审查日期：2026-11-08。结论是基于下述版本和范围的发布准备检查，不是对未检查内容的“完全合规”认证。此次没有修改项目 GPL 许可证，没有发布 Release 或改变仓库可见性。

## 固定基线与实读材料

| 对象 | 核对结果 |
|---|---|
| 上游默认分支 | [Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic)，`main`，HEAD [`09c23ff6232359842a779f44ee2453bb1a2c4e9a`](https://github.com/Sorihiko/DangoMusic/tree/09c23ff6232359842a779f44ee2453bb1a2c4e9a) |
| 移植起点 | [上游 v1.7.3 / `1e82730e6cbf19301e33cc6901f210bcffe9ea2c`](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c)，不把最新上游当成已合入代码 |
| 本仓库最新开发源码 | 默认分支 `port/gtnh-1.7.10`，[`ccec40f40ba654ecf19919ea472d90ae69f716e9`](https://github.com/1gbc/DangoMusic-GTNH/tree/ccec40f40ba654ecf19919ea472d90ae69f716e9)，5.38 标签也指向该提交 |
| 仓库与现有发布 | 本次 API 核对时为 private；5.38 是非草稿 prerelease，只有一个 4,237,897 字节 JAR，无 Apple 组件。5.29–5.33 条目没有资产；不将空条目推荐为下载 |
| 已有 CI | [主分支 Build 成功](https://github.com/1gbc/DangoMusic-GTNH/actions/runs/42448041586)；只证明该工作流构建成功，不是游戏实测 |

实际阅读：上游固定 HEAD 的 [LICENSE](https://github.com/Sorihiko/DangoMusic/blob/09c23ff6232359842a779f44ee2453bb1a2c4e9a/LICENSE)、[README](https://github.com/Sorihiko/DangoMusic/blob/09c23ff6232359842a779f44ee2453bb1a2c4e9a/README.md)、`common/ui/src/main/resources/META-INF/licenses/` 的组件说明及许可、字体许可；本仓库 LICENSE、构建模板许可、构建脚本、元数据、字体和资源、Java/Kotlin/C#/JS 源码、贡献/安全/验证文档；实际 Maven 解码器源码与 WebView2 NuGet 许可。

本仓库 LICENSE 与上游 LICENSE 在统一行尾后文本一致；四个 TTF 的 Git blob 与移植起点逐一一致，字体 OFL 文本亦一致。没有导入最新上游 Logo 或游戏截图。

## 简明结果

“已满足”只适用于该行具体事项；“需要修改”包含本 PR 已修正和仍待发布流程处理的项，明确分别说明。

| 状态 | 事项与依据 | 处理结果 / 下一步 |
|---|---|---|
| 已满足 | 上游实际授予 GPL v3；§2、§4、§5、§6 允许在相应条件下修改、移植、传播源码及对象码/JAR | 不是禁止移植或禁止编译发布的许可证；必须同时满足署名、许可、修改标记及对应源码条件 |
| 已满足 | 根 LICENSE 原样保留；构建模板 MIT 声明保留 | 未擅自更换为 MIT、闭源许可或添加“禁止商用”等额外限制 |
| 需要修改 → 本 PR 已修正 | 原 README 过时且来源不够突出；`mcmod.info` 只指向上游，容易混淆维护主体。GPL §4、§5(a)(b) 要求保留声明并标明修改 | README/CREDITS 显著说明原作、移植基线、非官方关系；更新日志标明本次修改和日期；元数据保留 Sorihiko 并增加移植维护者及正确仓库链接 |
| 需要修改 → 本 PR 已修正源码打包材料 | 现有 5.38 JAR 只有解码器/ZXing 许可和字体 OFL，没有项目 GPL 正文、Kotlin/annotations 独立说明 | 添加随下次构建进入 JAR 的 GPL、模板 MIT、Kotlin/annotations Apache、NOTICE/组件清单。不会追溯修改 GitHub 现有 5.38 资产 |
| 需要修改 → 发布前未完成 | GPL §1、§6(d)：网络分发对象码须提供等效途径取得完整 Corresponding Source；当前私有仓库不能作为无权限公众的源码入口 | 最终 JAR 对应提交固定后，提供完整源码、构建/安装脚本及需要的依赖源码；发布页明确其下载位置。仓库公开状态由维护者另行决定，本任务不代改 |
| 需要修改 → 发布前未完成 | JOrbis/Jogg 和 JLayer 源文件含 Library GPL v2-or-later，现有上游许可副本为 LGPL 2.1；LGPL §4、§6 要求相应源码、通知及合适的重新链接/替换路径 | 已取得准确解码器源码与校验值、修正旧包名及“完全未修改”措辞；尚未完成用户修改库后重建替换/实际播放验证和完整源码发行包，不把仅有 LICENSE 当作完成 |
| 已满足（所查字体副本） | Inter OFL 1.1 条件 1–5：保留版权和许可，修改名受 RFN 约束，字体本身仍用 OFL | 字体和许可与上游基线一致、字体内部版权保留，所读许可头未声明 RFN；没有本次改字体或另售字体。原始裁剪工具链未重建，不推断存在额外授权 |
| 需要修改 → 本 PR 已补材料，最终包待确认 | Apache 2.0 §4 与 WebView2 SDK BSD 条件要求保留适用版权、LICENSE、NOTICE，不能暗示背书 | 补齐 Kotlin/annotations 说明及 NuGet 1.0.4314.35 原始 LICENSE/NOTICE；组件最终打包时仍须携带，Gradle wrapper 许可单列 |
| 尚待确认 | 自包含 .NET / WebView2 Runtime 不是 WebView2 SDK 同一个发行物 | 当前 `.csproj` 默认 framework-dependent，本地曾有 self-contained 测试包；尚无最终 ZIP/运行库逐文件清单，不能认定全部配套组件已审完。建议 Runtime 由微软安装，若捆绑需另核其条款及 .NET notices |
| 已满足（页面表述） | 代码许可不等于名称/Logo/商标授权；GPL §7 允许的标记义务不等于取得商标许可 | 显著标“非官方”，不导入上游 Logo，不声称任何官方背书。未单独取得名称商标授权，不把未见商标条款当成已获得授权 |
| 需要修改 → 分发政策待确认 | `NearbyAudioPlayer` 与 [联机文档](NEARBY_SHARING.md) 有 L2 主机 URL、L3 服务端下载中转，涉及歌曲内容与平台权益，独立于 GPL | L3 默认关闭但 L2 存在。不能将这种能力宣传为绕过会员/共享会员权益；在公开推广相关路线前核对平台条款、内容授权，或另立任务限制为听众自己授权的曲目同步。本次未改变功能行为 |
| 已满足（有限扫描） | 当前跟踪文件、可达历史及 5.38 JAR 的下述定向检查没有发现高置信私钥/令牌或音乐文件 | 扫描范围和例外见下节，不覆盖 GitHub Actions secrets、未知外部网盘、不可达对象或未来发布包 |

GPL 正文条款：[GPL v3](https://www.gnu.org/licenses/gpl-3.0.html)（本仓库 LICENSE 同文）；库条款：[LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)。保留原作及各依赖的版权/无担保说明；GPL §5(d) 的交互界面法律通知应按上游实际界面和条款适用条件另核，不能仅因 README 有许可证就认定任何 UI 义务自动满足。

## 敏感内容与发布资产检查

- 扫描前本仓库 256 个 tracked 文件和 `git rev-list --objects --all` 可达的 570 个历史 blob。定向规则为私钥 PEM 头、GitHub token 格式、三段 JWT、硬编码长 MUSIC_U 值；只记录路径/规则，不在报告输出候选凭据。
- 无高置信匹配；音频/私钥、账号目录、运行日志路径检查无真实数据命中。源代码包路径里的 `config/ConfigManager.kt` 是配置实现，不是用户配置泄漏；JAR 中同名 class 同理。
- tracked 二进制是四个 Inter 字体及 Gradle wrapper JAR；没有跟踪歌曲、EXE、DLL 或组件 ZIP。仓库的 `build/`、组件 `bin/obj`、游戏配置和日志属于忽略目录，不能据此保证人工复制的 ZIP 一定干净。
- 下载并查看既有 5.38 Release JAR：SHA-256 `3ca60730a48fd824b994fadf3e5ec1227072e6cc6cd43d517d1a2e4ed1f07310`。资源列表无音频/账号/运行日志，含 1,029 个重定位 Kotlin 路径项；许可缺项见上表。这不是本 PR 重新构建产物的哈希。
- 不是通用高熵扫描或完整安全审计；不能排除其他格式、加密内容、不可达历史对象、未获取的远端引用、托管服务配置和仓库外资料。正式发布时必须重新扫描**最终** JAR、源码包和组件 ZIP，禁止带入 WebView2 profile、账号资料、浏览器缓存、日志和签名播放 URL。

## 功能及环境声明的依据

| 主页声明 | 核对依据 |
|---|---|
| 版本、MC、Forge | 根 `build.gradle.kts`、`gradle.properties`、`DangoMusicGtnh.java`、Release API |
| JDK 25 / 编译 8 / 构建版本 | `.java-version`、`core/build.gradle.kts`、wrapper properties、`settings.gradle.kts`、CI |
| Windows Java 25 / lwjgl3ify / LWJGL 基线 | [最初源码审计的本机日志记录](PORTING_AUDIT.md#本机基线与风险)；不是本轮新游戏测试 |
| 官方 GTNH 的多 Java 包 | [GTNH 官方下载历史](https://www.gtnewhorizons.com/version-history/)；不能据此宣称本 Mod 全组合兼容 |
| 队列、停止、快捷键、HUD | `MusicPlayer.kt`、`PlayerKeyBinding.kt`、`LegacyKeyBindings.kt`、模块与设置页面、CHANGELOG |
| 多人服务端可选 | `acceptableRemoteVersions="*"`、CommonProxy/共享协议、[当前联机文档](NEARBY_SHARING.md)；不同版本仍需实测 |
| Apple 非登录专用组件 | `tools/apple-native-player/Program.cs` 的 WebView2 与官网宿主、`AppleWebPlayer.kt`、`AppleMusicSource.kt`、适配器 JS |
| Apple 搜索/推荐/歌词及验证边界 | [5.34](APPLE_5_34_LOCAL.md)、[5.36](APPLE_5_36_LOCAL.md)、[5.37](APPLE_5_37_LOCAL.md)、`AppleLyrics.kt` 与实际接口代码 |
| 缓存和续播路径 | `DangoMusic.kt`、`MusicPlayer.kt`、`ResumeStore.kt`、`CoverCache.kt`、`AppleWebPlayer.kt` |

## 正式分发前必须完成

1. 固定最终发行提交和版本；许可补全会改变 JAR，因此不能把本 PR 构建直接冒充原 5.38，需后续正式打包任务分配版本和校验值。
2. 完整、公开可取得的对应源码与 LGPL 修改后重建/替换验证。未经完成，不把现有 JAR 标成已经过全部许可验收。
3. Apple 若作为发行附件，逐文件盘点 SDK、实际 .NET 运行库和相关许可，并放入相同发行包；没有最终 ZIP 前仅发布源码/说明。
4. 核对 L2/L3 平台条款与内容授权，确定公开发行政策。代码许可证不代替音乐版权或账号服务条款。
5. 补充真实 GTNH 截图及游戏回归，尤其 Apple、双客户端房间、歌词、切源与停止；本次不虚构这类证据。

本次实际文档/构建验证结果另见 [PUBLICATION_VALIDATION](PUBLICATION_VALIDATION.md)，[Release 草稿](RELEASE_DRAFT.md) 在上述事项完成前保持草稿。
