> **历史发布准备记录**：本文记录 2026-11-08 的预审/草稿状态。正式 0.1.0 的资产、后续验证与仍有的限制以 [RELEASE_0_1_0](RELEASE_0_1_0.md) 为准；本次保留原审查结果供追溯。

# 发布准备验证

日期：2026-11-08；审查基线 ccec40f，文档分支 docs/public-release。只验证本次文档、许可资源和元数据，不新增功能测试。

## 本轮执行结果

| 检查 | 结果与范围 |
|---|---|
| 最新版本与资产 | 已核对上游 main、移植起点、本仓库 main 分支/标签、Release API 与实际 5.38 JAR；版本和原资产哈希见 [RELEASE_AUDIT](RELEASE_AUDIT.md) |
| Mod 构建 | 本机 Gradle 9.7.1 + JDK 25 运行 / JDK 8 toolchain，`build --offline` 退出码 0；新增许可与 mcmod.info 资源均进入产物 |
| JAR 内容 | 检查 GPL、NOTICE、Kotlin/annotations Apache、模板 MIT、原解码器/ZXing/OFL 文件；GPL/OFL 与源文件一致；元数据保留 Sorihiko、使用本仓库 URL；实际 Kotlin 字节码版本为 2.2.21 |
| 依赖 | `:core:dependencies --configuration runtimeClasspath --offline` 成功，核对 JOrbis 0.0.17、JLayer 1.0.1、ZXing 3.3.3、Kotlin 2.2.21、annotations 13.0 |
| GitHub Markdown | 20 个新增/修改 Markdown 通过 GitHub GFM API 渲染；HTML 标题、表格、details、图片元素有效；本地 README 预览目视核对排版与三枚真实静态徽章，不是 GitHub 正式页截图 |
| 本地链接与锚点 | 检查 95 处相对路径/页内跳转，无缺失；Markdown API 不返回 heading id，锚点另按标题与 GitHub slug 规则核对 |
| 外部链接 | 37 个非本仓库外链在初查/重试后均返回 HTTP 200，包括徽章和上游固定提交。两条另测 GNU 正文链接被站点拒绝（403）；保留官方地址，以已实读的本地完整 GPL/LGPL 文本核对条款，不声称本轮成功打开 GNU 页面 |
| 私有仓库链接 | 已用授权 API 核对 repo/分支、提交、标签、Release 及资产、CI、贡献者；公开网页未登录 404 是权限限制，新文档相对链接按本地分支检查，不虚称已合入默认分支 |
| 许可副本 | 根 GPL 和模板 MIT 保持原文；字体与上游基线相同；WebView2 LICENSE/NOTICE 与本机准确版本 NuGet 包逐字节一致；库 LGPL 正文未改，仅修正 JLayer 导言 |
| 敏感资料 | 原有 tracked/可达历史与原 5.38 JAR 的定向扫描见审查报告；提交前再检查待提交文件与本次 JAR，未发现规则覆盖的凭据或音乐文件，不作为完整安全审计 |
| 独立复核 | 单独只读复核未发现阻断性文档错误；指出当前联机文档仍提及已移除的房间开关，已改为实际房间状态控件，并保留“设置-多人”入口 |

本轮重建产物只用于打包验证，不上传、不替换现有 5.38，不作为正式安装包交付；许可资源变化后须在下一次发包分配版本并记录新哈希。

## 未执行与边界

没有启动 GTNH 2.9.0，没有 Apple 真实账号重测、双客户端听歌房或歌词目视回归，没有重建 Apple/.NET 组件，没有 LGPL 修改后重新链接播放验收。旧文档的真实播放记录和 CI 成功不冒充本轮游戏测试。

没有改变仓库可见性、About/Topics 或现有 Release，没有上传新二进制，没有发布正式 Release。可评审的发布正文在 [RELEASE_DRAFT](RELEASE_DRAFT.md)；未解决的公开分发条件见 [RELEASE_AUDIT](RELEASE_AUDIT.md)。
