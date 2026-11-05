# Phase 3 验证记录

目标是 GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614。已读取本机目标实例的组件、模组和既有日志。它们提供运行基线，本记录另列新 Mod 的验证结果。

| 检查 | 结果 |
|---|---|
| Gradle 9.7.1 分发包 | 官方 SHA-256 校验通过。Gradle daemon 已在 JDK 25.0.3 启动。 |
| Forge 1614 Java API 与目标字节码 | 通过单独的静态编译。使用实际 Forge/Minecraft 1.7.10 API 的 SRG 编译副本、MCP stable_12 核对调用名，JDK 25 以 `--release 8` 编译。入口 class major 52。 |
| 服务端代理隔离 | 通过独立类加载检查。在拒绝客户端、LWJGL 和 Java Sound 类的类加载器中执行共用入口和空代理 preInit。 |
| 配置的生成和重读 | 通过无界面检查。用 Forge 1614 Configuration，生成默认设置后写入覆盖值，重新构造时正确加载覆盖值。 |
| Custom Main Menu | 已检查本机 1.14.1 的字节码，实际 GuiCustom 走 GuiScreen 的点击入口，绘制路径接受普通 GuiButton。显示位置、交互、主题覆盖未实机验证。 |
| GTNHGradle build / reobf | 进行中。 |
| GTNH 启动、进世界、M 键、主菜单按钮、配置保存 | 未实测。原 GTNH 会话在运行，可用内存不足以稳妥地并行启动另一套完整实例。 |
| Dedicated Server 及联机 | 未实测。独立类加载检查只验证共用入口的客户端类隔离。 |
| 三平台、音频、完整 UI、歌词、HUD | 待后续阶段。 |

SRG 编译副本和临时 JAR 存在于工作目录，仅用于静态检查。安装交付物须来自正式构建并完成实际验收。
