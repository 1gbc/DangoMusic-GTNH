# Release 草稿（未发布）

建议标题：**DangoMusic GTNH Edition · 公开测试发布准备**

状态：**Draft / Pre-release**。这份文件不是已发布公告，也不指示覆盖现有 5.38 资产。当前代码基线版本 0.1.0-dev.5.38；下次可分发构建的版本和校验值待正式打包时确定。许可证补全后的 JAR 不再等于原 5.38 二进制。

## 发布前状态

- [ ] 对应源码可供所有二进制接收者免费取得，包含必要依赖源码及构建材料。
- [ ] LGPL 解码库的修改、重建与替换路径已实际验证；完整许可和 NOTICE 已进入最终 JAR。
- [ ] L2/L3 分享路线的音乐内容及平台条款处理已确定。
- [ ] 若附 Apple 组件：完整文件/版本清单、.NET/WebView2 许可与最终 ZIP 敏感数据复检完成。
- [ ] 实际版本、标签、最终 JAR/组件校验值与测试记录已填好；完成 GTNH 游戏回归。

上述条件未完成前，保持草稿。详见 [RELEASE_AUDIT](RELEASE_AUDIT.md)。

## 项目来源

基于 [Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic) 的非官方 GTNH 移植，最初基线 [v1.7.3 / 1e82730](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c)。感谢原作者及本项目贡献者。本项目不代表原作者、GTNH 或音乐平台官方。

## 已有主要功能

- 网易云、QQ 音乐、酷狗的游戏播放器、搜索与歌单；账号/会员/地区权限仍由平台决定。
- 实际队列、随机/顺序/单曲循环，播放/暂停/停止、进度、音量、组合快捷键。
- 大歌词页和独立 3D 歌词、翻译/音译、KTV、滚动与抖动；灵动岛、卡片和封面进度条。
- 同服附近共享和听歌房、保存成员/当前房间、本地音乐接管；为实验功能，不承诺跨服/Firebase 或 Apple 音频共享。
- 非 Apple 本地队列记忆并暂停恢复、缓冲调节、网易云下一首预取。
- Apple 实验接入：游戏内控制和浏览，实际受保护音频由后台 WebView2 组件播放，尚非 Java 原生播放。功能/验证分项见 [README](../README.md)。

## 本次发布准备改动

- 重写中文主页，区分原作、移植与新增工作，补充准确依赖、安装、功能边界和贡献者来源。
- 保留原 GPL 和模板 MIT，补充下一次 JAR 打包所需 GPL、Apache 许可、NOTICE，校正元数据链接及 LGPL 重定位说明。
- 整理 Apple 当前安装说明，保留旧阶段记录；补充第三方许可、分发待办、About/Topics 和审查报告。
- 未新增 Mod 功能，未改变账号/配置格式，未删除原作者版权或改用其他许可证。

## 安装

面向 GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614。沿用整合包 Java 和 lwjgl3ify/LWJGL；编译目标 Java 8 不等于游戏推荐 Java。

1. 退出游戏并备份实例；从该次 Release 的 Assets 下载**普通安装 JAR**，不要用 `-dev`、`-sources` 或自动源码 ZIP。
2. 放入实例 `.minecraft/mods/`，移走同 Mod 旧 JAR，保留 `config/dangomusicgtnh/`，特别是 accounts 和个人设置。
3. 按 M 打开播放器，选择音源、登录并搜索。多人分享还需同版服务端 JAR。
4. Apple 如提供经过核对的配套组件，按 [INSTALL](INSTALL.md) 放到 `config/dangomusicgtnh/apple-player/`，保留 accounts/apple-webview；否则此发行包不提供开箱可用的 Apple 播放。

当前既有 [5.38 测试条目](https://github.com/1gbc/DangoMusic-GTNH/releases/tag/0.1.0-dev.5.38) 只有普通 JAR，不能冒称有 Apple ZIP。正式整理本草稿时，以实际上传的资产清单替换说明。

## 测试与已知限制

- 既有 5.38 CI 构建成功，历史记录有服务端冷启动；本次资源/文档检查结果见 [PUBLICATION_VALIDATION](PUBLICATION_VALIDATION.md)。
- Windows 既有游戏基线：Java 25.0.3 / lwjgl3ify 3.0.42 / LWJGL 3.4.3；本次未启动 GTNH 或执行双客户端游戏回归。
- Apple 真实账号曾完成出声、跳到 90 秒后继续、退出重启恢复；不等于全曲/全地区或长时间稳定性验收。推荐、完整媒体库、3D 歌词、异常恢复和一起听仍有未验收或未实现项。
- 当前 Apple 仅 Windows x64，需后台组件与 WebView2 Runtime；收藏写入、官方歌词、Java 均衡器、无损/空间音频不能按已有入口宣称支持。
- Linux/macOS、Java 8 游戏运行和纯 LWJGL 2 未完整测试。音乐平台接口、版权及订阅可能影响可用性。

## 源码与许可

审查基线：[ccec40f40ba654ecf19919ea472d90ae69f716e9](https://github.com/1gbc/DangoMusic-GTNH/tree/ccec40f40ba654ecf19919ea472d90ae69f716e9)，分支 port/gtnh-1.7.10。本次许可整理通过 docs/public-release PR 评审；最终发布须指向实际包含这些整理的发行提交/标签，不能仍把 ccec40f 作为新 JAR 的完整源码。

公开发包时，在本段加入实际发行源码归档和必要依赖源码的免费等效下载链接（GPL §6(d)），不要只给私有仓库地址。保留 [GPL-3.0](../LICENSE)、[第三方许可](../THIRD_PARTY_NOTICES.md) 和 [致谢](../CREDITS.md)。未完成这些材料前不发布本草稿。

反馈：[Issues](https://github.com/1gbc/DangoMusic-GTNH/issues/new/choose)；安全问题走 [SECURITY](../SECURITY.md)。不要上传账号资料、Cookie、令牌、私钥、签名地址或受保护音频。
