# 发行源码与 LGPL 解码库替换

发行页的 `dangomusicgtnh-0.1.0-source-bundle.zip` 包含完整项目源码、构建/安装说明、五项随 JAR 打包的依赖源码、校验值与许可材料。它与 Mod JAR 在同一 Release 下免费提供，接收 JAR 的用户应同时能够下载此包；不要求另付费用。仓库若仍私有，这次 Release 仅向有权限者提供，不称为公开下载。将来向公众分发 JAR 时，也必须给公众等效源码下载。

源码包不是玩家安装包。普通玩家只需 JAR，可选安装 Apple 组件。

## 修改和重新构建

项目构建：JDK 25 运行 Gradle 9.7.1，JDK 8 编译工具链；首次还需联网获取 Forge/MCP/构建工具。不要把整个 Gradle 缓存或 Minecraft 二进制包当作项目源码分发。

下面示例从解压后的源码根目录运行；将路径换成自己电脑上的绝对路径。Python 3.9+，不需要额外 Python 包。

```text
python tools/release/rebuild_codecs.py --sources dependency-sources --output codec-rebuild --jdk /path/to/jdk8
```

第一次解压 JOrbis/Jogg 0.0.17 和 JLayer 1.0.1 的准确源码，包括 JLayer 所需 `.ser` 表。编辑 `codec-rebuild/editable-sources/` 下对应 Java 文件，再执行同一命令即可重新编译。已有源码不会被覆盖，库原始版权、许可与源文件头必须保留。

随后构建使用修改后库的完整 Mod：

```text
./gradlew build -I tools/release/codec-rebuild.init.gradle -Ddango.codecRepo=/absolute/path/codec-rebuild/maven -Porg.gradle.java.installations.paths=/path/to/jdk8
```

Windows 使用 `gradlew.bat`。产物在 `build/libs/`；退出游戏，备份旧 JAR，使用普通安装 JAR 替换 `mods/` 中旧文件。不要同时保留两份 Mod。重定位规则仍由根 `build.gradle.kts` 提供，不需要手动改依赖包名。

`--probe` 可为两库各加一个 `DangoCodecRebuildProbe.value()`，用于证明修改后的类确实进入最终 `shadow.javazoom` / `shadow.jcraft` 包；它不是游戏功能，也不用于正式发行包。删除 probe 源文件后，应使用新的 output 目录重新构建，以免保留旧 class。

无需签名或厂商密钥才能安装替换 JAR。本项目不禁止为调试这些库的修改而进行必要逆向工程。

## Apple 组件

组件源码在 `tools/apple-native-player`，适配器 JS 在 `tools/apple-web-bridge/extension`。本次组件使用 framework-dependent 发布，不捆绑 .NET 或完整 WebView2 Runtime。用户另装 .NET 8 Desktop Runtime x64 与 WebView2 Evergreen Runtime；包内保留微软 SDK LICENSE/NOTICE 及项目 GPL。

## 验证边界

源码重建、重定位检查和合成数据的解码器检查不代表在 GTNH 里完整听完歌曲。实际本次执行的结果见 `docs/RELEASE_0_1_0.md`，不把未进行的游戏内测试写成通过。
