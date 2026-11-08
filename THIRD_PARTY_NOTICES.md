# 第三方组件、资源与分发说明

本项目代码沿用上游 [GPL-3.0](LICENSE)，不将第三方字体、库、微软组件、游戏或音乐内容重新声明为 GPL。下表基于 `ccec40f` 的源码、Gradle 解析结果和实际 5.38 JAR；后续依赖升级须复核。本次补充材料日期：2026-11-08。0.1.0 未改变这些依赖版本；正式发行的后续验证与源码包见 [发行说明](docs/RELEASE_0_1_0.md)。

## Mod JAR 中的内容

| 内容 / 版本 | 来源与许可 | 本仓库材料 / 注意事项 |
|---|---|---|
| DangoMusic 与移植代码 | [Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic)，GPL v3 | [LICENSE](LICENSE)、[CREDITS](CREDITS.md)、[CHANGELOG](CHANGELOG.md)；下次构建附 `META-INF/LICENSE-DangoMusic-GPL-3.0.txt` |
| Inter 四个裁剪字体 | [Inter](https://github.com/rsms/inter)，SIL OFL 1.1；Copyright (c) 2016 The Inter Project Authors | [原始字体许可](src/main/resources/assets/dangomusicgtnh/fonts/Inter-LICENSE.txt)；本地字体与移植上游基线 blob 一致，保留字体内版权。所核对许可头未声明 Reserved Font Name；字体仍用 OFL，不作为独立字体商品出售 |
| JOrbis / Jogg 0.0.17 | [JCraft](https://github.com/ymnk/jorbis)，源文件声明 GNU Library GPL v2 或以后版本 | [LGPL 2.1 副本](core/src/main/resources/META-INF/licenses/JOrbis-LGPL-2.1.txt)；该副本不意味着源文件只有 2.1。Copyright (C) 2000 ymnk, JCraft, Inc.；保留源文件其他版权 |
| JLayer 1.0.1 | [Maven 源码](https://repo.maven.apache.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1-sources.jar)，源文件含 Library GPL v2 或以后版本声明；上游附 LGPL 2.1 文本 | [LGPL 副本](core/src/main/resources/META-INF/licenses/JLayer-LGPL-2.1.txt)，JavaZOOM 与各源文件作者版权保留，不缩减为单一新版权人 |
| ZXing core 3.3.3 | [ZXing](https://github.com/zxing/zxing/tree/zxing-3.3.3)，Apache 2.0 | [Apache 副本](core/src/main/resources/META-INF/licenses/ZXing-Apache-2.0.txt)，保留源码版权及适用 NOTICE |
| Kotlin stdlib 2.2.21 | [JetBrains Kotlin v2.2.21](https://github.com/JetBrains/kotlin/tree/v2.2.21)，Apache 2.0 | [LICENSE](src/main/resources/META-INF/licenses/Kotlin-Apache-2.0.txt)、[原发行版 NOTICE](src/main/resources/META-INF/licenses/Kotlin-NOTICE.txt)；NOTICE 原文提及 compiler 是原发行版措辞，本 Mod 不打包 Kotlin 编译器 |
| JetBrains annotations 13.0 | [该版本 POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom)，Apache 2.0 | [Apache 副本](src/main/resources/META-INF/licenses/Annotations-Apache-2.0.txt)及 [JAR 内组件清单](src/main/resources/META-INF/NOTICE-DangoMusic.txt) |

`build.gradle.kts` 将 javazoom、com.jcraft、ZXing、Kotlin 和 annotations 重定位至 `me.sorihiko.dangomusicgtnh.shadow.*`。这是字节码包名修改，不能把分发副本称为完全未修改。项目的 `core/src/main/java/com/jcraft/jorbis/DangoVorbis.java` 是 VorbisFile 子类适配代码，也须连同构建脚本提供。

### LGPL 对应源码与替换路径

当前不依赖“放一份 LGPL 文本就足够”的假设，也不在本次任务擅自转用 GPL 重许可库。正式分发前应完成一条经验证的合规路径：

1. 保留库作者、LGPL 和无担保通知，提供准确版本的库源码；不能仅给项目首页。
2. 把完整项目源码、构建脚本、所需非系统依赖源码与版本清单随发布一起提供，或提供满足 GPL §6(d) 的等效、免费且无需私有仓库权限的下载途径。
3. 验证修改 JLayer/JOrbis 后重建、重定位并替换整个安装 JAR 的流程，让用户确实可以修改这些库；若选择 LGPL §6 的重新链接方式，应交付其需要的材料。另一方案是改为可替换的外部库，但需另开功能/构建任务评估兼容性。
4. 不增加禁止为调试库修改而逆向工程的条款。保留上游项目和库的版权，不据此承诺平台音乐可下载。

已核对的 Maven 对应源码（不是最终完整 Corresponding Source 发布包）：

| 源码文件 | SHA-256 |
|---|---|
| [jorbis-0.0.17-sources.jar](https://repo.maven.apache.org/maven2/org/jcraft/jorbis/0.0.17/jorbis-0.0.17-sources.jar) | `1643dd368b9c160276caf8d1f6a8c0aae43ca5bf49b53348a2a01623641708e5` |
| [jlayer-1.0.1-sources.jar](https://repo.maven.apache.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1-sources.jar) | `ecde410fc8940ab5d8d5a1d5c585870a3a194f4001701e66a27b8dd8cb7b75ce` |

0.1.0 已从两库准确源码重新编译、加入标记后通过替换仓库构建完整 Mod，并验证重定位标记、合成 MP3 解码与 Ogg 容器回环。没有把这些检查称为实际游戏播放或完整 Vorbis 歌曲验收。步骤见 [替换工具说明](tools/release/README.md)。更多来源见 Kotlin 的固定标签、ZXing 固定标签和 annotations POM 的 SCM；公开二进制前仍需收集完整对应源码，GitHub 自动源码 ZIP 或单独 `-sources.jar` 不自动满足整个组合产物的要求。

## Apple 播放组件

项目自有 C#/JS 代码随本仓库源码提供；它与 Mod 分进程运行。0.1.0 附件为已列明文件清单的 framework-dependent Windows x64 组件，不含 .NET 或完整 WebView2 Runtime；不将以下 SDK 条款扩展到所有微软产品。

- **Microsoft.Web.WebView2 1.0.4314.35 SDK**：本机 NuGet 包 `LICENSE.txt` 是带 Microsoft 非背书条件的 BSD 风格许可，允许满足通知条件的源/二进制再分发；其 `NOTICE.txt` 列出其他材料。原样副本在 [third-party/webview2](third-party/webview2/)，发布组件必须同时携带两者。源码 `.csproj` 的版本与这些副本对应。
- **WebView2 Evergreen Runtime**：与 SDK 不同，属于微软单独提供和更新的浏览器运行时，遵循自己的条款。当前文档让用户从[微软入口](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)安装，不把完整 Runtime 再打进 ZIP。分发方式见[微软文档](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution)。
- **.NET 8 / Windows Desktop Runtime**：框架依赖包要求用户安装；自包含包会携带实际运行库，必须基于该次 publish 的 `.deps.json`、运行库版本与文件清单收集对应 LICENSE 和 ThirdPartyNotices，不能只放 WebView2 SDK 许可。本次没有重新构建或打包组件。
- Apple 官方网页、MusicKit 脚本与受保护内容不作为项目源码或静态资源再分发。账号资料、浏览器缓存及诊断日志不进入组件 ZIP。0.1.0 清单与文件校验位于组件 ZIP 内，未包含账号、浏览器缓存或音频。

## 构建与宿主提供的内容

- `LICENSE-build-template` 为原构建模板的 MIT 许可（Copyright (c) 2021 Johann Bernhardt），原样保留；它不把整个 Mod 改成 MIT。
- Gradle wrapper 9.7.1：Apache 2.0；[Gradle 源码与许可](https://github.com/gradle/gradle/tree/v9.7.1)。wrapper 属于源码构建材料，不随 Mod JAR 分发；仓库保留其许可副本于 [third-party/gradle](third-party/gradle/)。其中 NOTICE 是完整 Gradle 发行版的原始文件，不表示其列出的全部组件都已进入本仓库或 Mod JAR。
- Gson、Log4j API 为 compileOnly；Forge、Minecraft、LWJGL、lwjgl3ify 等由宿主提供，本 Mod 不再次打包或重新许可这些组件。GTNHGradle、Kotlin Gradle 插件等为构建工具，不把其全部传递依赖误列为玩家安装前置。
- 系统 CJK 字体在运行时使用；本仓库只捆绑上表四个 Inter 字体，没有随包复制系统字体。

## 图片、图标、音乐与商标

当前源码资源中没有独立 Logo/截图图片文件或歌曲音频；二维码、封面由运行时生成/获取，部分图标由代码绘制。上游现有 `docs/logo.png` 与截图未被本次拷贝。运行时下载的封面、歌词、歌曲仍属相应权利人，不能因为代码 GPL 就打包传播。音乐共享的授权风险另见 [RELEASE_AUDIT](docs/RELEASE_AUDIT.md)。
