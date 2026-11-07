# 附近共享与音乐群组

本文对应 `feat/nearby-sharing` 的实现。服务端组件是可选的；客户端不装模组仍然可以正常进入装了模组的服务器。

## 功能

- **附近共享**：主机开启“Share my music”后，同维度、默认 32 格内的已装模组玩家可以听到主机的音乐。超出半径停止；距离越远音量越低。
- **音乐群组**：成员不受距离限制，跟随群组 host。群组优先于附近广播；同一首歌不会重复播放。群组 host 不会收到自己的远端播放。
- **歌词投影**：主机开启“Share my lyrics”后，附近/群组成员可以在主机头顶看到当前歌词行；查看方用“See nearby lyrics”控制。
- **回退顺序（网易云优先）**：
  1. **L1**：听众用自己的账号解析并下载。
  2. **L2**：听众直接下载主机共享的短期播放 URL。
  3. **L3**：可选的服务端中转。服务端用主机 URL 下载完整文件，再分块发给听众。默认关闭。

## 安装

- 客户端：把 `build/libs/dangomusicgtnh-<version>.jar` 放入 `mods/`。
- 服务端：同一个 JAR 放入服务端 `mods/`，启动后在 `config/dangomusicgtnh/server.cfg` 生成配置。
- 无模组客户端：可以正常进服和游玩；服务端只向完成 Hello 握手的客户端发数据。

## 客户端设置

播放器设置页 → **Multiplayer** 标签：

| 设置 | 默认 | 作用 |
|---|---|---|
| Share my music | 关 | 把当前音乐共享给附近玩家 |
| Hear nearby music | 开 | 播放附近玩家的音乐 |
| Nearby volume | 50% | 附近/群组音乐音量 |
| Share quality | 192k | 共享 URL 请求的音质（128/192/320k） |
| Share my lyrics | 关 | 共享当前歌词行 |
| See nearby lyrics | 开 | 显示主机头顶的歌词投影 |
| Hear group | 开 | 跟随群组 host |
| Share to group | 开 | 群组 host 把音乐共享给成员 |

## 服务端命令

```text
/dangomusic group create <name>
/dangomusic group join <name>
/dangomusic group leave
/dangomusic group info
/dangomusic status
/dangomusic reload          # OP
```

群组只存内存，服务器重启后解散。v1 只有 host-only：host 放什么，成员听什么。

## 服务端配置

`config/dangomusicgtnh/server.cfg`：

| 键 | 默认 | 说明 |
|---|---|---|
| enabled | true | 总开关 |
| radiusBlocks | 32 | 附近共享半径 |
| maxHostsPerListener | 4 | 每个听众最多同时收几个 host |
| maxGroupSize | 8 | 群组人数上限 |
| allowLyrics | true | 允许歌词共享 |
| relayEnabled | false | L3 服务端中转总开关 |
| relayMaxListeners | 2 | 同时中转的听众上限 |
| relayQuality | 1 | 0=128k, 1=192k, 2=320k |
| relayMaxMb | 16 | 单曲中转大小上限 |
| relayChunksPerTick | 2 | 每 tick 发送的 4 KiB 块数（20 tick/s） |
| relayAllowLocal | false | 测试用：允许 127.0.0.1 的 URL |

L3 带宽：2 块/tick × 4 KiB × 20 = 160 KiB/s 每路；默认最多 2 路 ≈ 320 KiB/s 服务端上行。调大 `relayChunksPerTick` 会提高速度，也会提高带宽。

## 网易云权益与 URL

- L1 使用听众自己的账号；没有 VIP 时可能只拿到试听或失败。
- L2 使用主机账号申请的短期 URL。客户端拿不到主机 Cookie/token，只拿到 URL；如果 URL 未绑 IP 且未过期，没有 VIP 的听众也能播完整歌曲。这等于借用主机的 VIP 权益，存在平台 ToS/账号共享风险。
- L3 由服务端下载同一个 URL，再分块给听众；风险更高，默认关闭。
- QQ/酷狗没有专门适配 L3；L1/L2 逻辑已存在，但未实测。

## 已知限制（v1）

- L3 目前只实现“服务端用主机 URL 下载”的 L3a；如果服务端 IP 被平台拒绝，L3b（主机上传音频）尚未实现。
- 完整文件下载后再 seek 播放，起播可能有几秒到几十秒延迟；不是实时流。
- 群组只有命令，没有独立 UI 页面。
- 远端歌词是行级，不做逐字 KTV/发光。
- 本地音乐与附近音乐各用一条 Java Sound line，系统混音；没有自动 ducking。
- 服务端只做长度/域名/数量上限；没有完整的速率限制和审计日志。

## 实测清单

1. 服务端装模组、客户端不装：能正常进服、游玩；服务端无异常。
2. 两个客户端：A 开 Share my music、B 开 Hear nearby；A 在半径内/外移动，B 的播放开始/停止、音量变化。
3. 歌词：A 开 Share my lyrics、B 开 See nearby lyrics；B 在 A 头顶看到当前行。
4. 群组：A `/dangomusic group create test`，B `join test`；两人远离，B 仍能听到 A；A 停止/退出后 B 停止。
5. VIP：A 登录网易云放 VIP 歌；B 不登录网易云；B 的 L1 失败后应走 L2；若 L2 失败，开 L3 再试。
6. 日志：客户端 `logs/fml-client-latest.log`、服务端 `logs/fml-server-latest.log` 中搜索 `DangoMusic`；不应出现 URL/token。
