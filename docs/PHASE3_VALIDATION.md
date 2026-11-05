# Phase 3 验证记录

目标是 GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614。已读取本机目标实例的组件、模组和既有日志。它们提供运行基线，本记录另列新 Mod 的验证结果。

| 检查 | 结果 |
|---|---|
| Gradle 9.7.1 分发包 | 官方 SHA-256 校验通过。Gradle daemon 已在 JDK 25.0.3 启动。 |
| Forge 1614 Java API 与目标字节码 | 通过单独的静态编译。使用实际 Forge/Minecraft 1.7.10 API 的 SRG 编译副本、MCP stable_12 核对调用名，JDK 25 以 `--release 8` 编译。入口 class major 52。 |
| 服务端代理隔离 | 通过独立类加载检查。在拒绝客户端、LWJGL 和 Java Sound 类的类加载器中执行共用入口和空代理 preInit。 |
| 配置的生成和重读 | 通过无界面检查。用 Forge 1614 Configuration，生成默认设置后写入覆盖值，重新构造时正确加载覆盖值。 |
| Custom Main Menu | 已检查本机 1.14.1 的字节码，实际 GuiCustom 走 GuiScreen 的点击入口，绘制路径接受普通 GuiButton。显示位置、交互、主题覆盖未实机验证。 |
| Java Sound 环境探针 | JDK 25.0.3 可打开 44.1 kHz / 16-bit 双声道输出，写入并排空 100 ms 静音 PCM 后正确关闭。探针未运行 Mod 的音频引擎。 |
| GTNHGradle build / reobf | 通过。Gradle 9.7.1、GTNHGradle 2.0.33，Java 25 daemon、Zulu JDK 8 编译，使用 Forge 1614 重混淆。模板 Spotless 脚本下载超时，Phase 3 构建使用 -PdisableSpotless=true。 |
| GTNH 启动、配置生成、进世界 | 在隔离的 GTNH 2.9.0、Java 25.0.3、LWJGL 3.4.3 实例通过。进入新建世界。后续重进该测试存档时发生 GTNH 物品注册表映射错误，成因未确认。 |
| 主菜单按钮 | 修正 Custom Main Menu 替换事件列表的问题后，实测成功打开基础屏幕并返回主菜单。 |
| M 键 | 已注册，实机验证未完成。 |
| Dedicated Server 及联机 | 未实测。独立类加载检查只验证共用入口的客户端类隔离。 |
| 三平台、音频、完整 UI、歌词、HUD | 待后续阶段。 |

SRG 编译副本和临时 JAR 存在于工作目录，仅用于静态检查。安装交付物须来自正式构建并完成实际验收。
