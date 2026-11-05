# DangoMusic GTNH Edition

Minecraft 1.7.10 / Forge 10.13.4.1614 的客户端移植工程，基于 [DangoMusic v1.7.3](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c)。当前版本为 Phase 5–6 开发版，接入了原版播放器界面和歌词、HUD 渲染入口。首次启动默认简体中文，已保存的界面语言可在播放器设置里切换。运行状态见阶段验证记录。

- [源码审计](docs/PORTING_AUDIT.md)
- [移植方案](docs/PORTING_PLAN.md)
- [Phase 3 验证](docs/PHASE3_VALIDATION.md)
- [Phase 4 验证](docs/PHASE4_VALIDATION.md)
- [Phase 5–6 验证](docs/PHASE5_VALIDATION.md)

## 编译

使用 JDK 25 运行 Gradle。编译需 JDK 8 toolchain。若 Foojay 下载超时，预装 JDK 8 后通过 Gradle 的 `org.gradle.java.installations.paths` 指定目录。Windows 下运行 `gradlew.bat build`。JAR 输出至 `build/libs`，带 `-dev`、`-dev-preshadow` 或 `-sources` 后缀的 JAR 用于开发。

## 隔离测试

使用 GTNH 2.9.0 的副本，将重混淆 JAR 放入其 `mods`。从主菜单左下角的 DangoMusic 按钮进入，或在世界中按 M。从侧栏切换网易云、QQ 音乐和酷狗。输入关键词后回车，点选曲目播放。账号凭据保存于测试实例的 `config/dangomusicgtnh/accounts`，播放器与 HUD 设置保存在 `settings.json`，主菜单入口开关在 `settings.cfg`。

## 许可

项目沿用 GPL-3.0，见 LICENSE。构建模板的许可见 LICENSE-build-template。解码与二维码依赖的许可证位于 `core/src/main/resources/META-INF/licenses`。公开分发前仍需完成 LGPL 库的对应源码及可替换分发核对。
