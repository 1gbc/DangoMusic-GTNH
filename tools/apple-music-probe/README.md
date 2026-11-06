# Windows 上验证 Apple Music

这是 DangoMusic 的独立验证程序。终端模拟 Mod 的播放控制端，浏览器运行 Apple 官方 MusicKit。使用 Java JDK 17 或更新版本编译，以及 Node.js 18 或更新版本生成开发者令牌。

## 1. 准备开发者配置

1. 使用加入 Apple Developer Program 的账号进入 [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list)。在 Identifiers 创建用于 MusicKit 的 Media ID。
2. 在 Keys 创建启用了 Media Services 的密钥，关联刚建的 Media ID，下载 `.p8` 文件。保存到项目以外的位置，并记录 Key ID。
3. 在开发者账号的 Membership details 找到 Team ID。Team ID 和 Key ID 用于下面的命令。Apple Music 订阅账号用于浏览器中授权，两类配置各有用途。
4. 将下面命令中的 ID 和路径换成实际值。签名脚本在本机读取私钥，令牌有效一小时。命令直接把结果保存到当前 PowerShell 的环境变量中。

官方参考：[创建 Media ID 和私钥](https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key) · [开发者令牌](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens)

## 2. 启动

从项目根目录进入 `tools/apple-music-probe`，运行：

```powershell
$probeClasses = Join-Path $env:TEMP ('dango-am-probe-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $probeClasses | Out-Null
javac --release 8 -d $probeClasses .\AppleMusicProbe.java
if ($LASTEXITCODE -ne 0) { throw '验证程序编译失败' }
try {
    $env:DANGO_AM_DEVELOPER_TOKEN = node .\sign-token.cjs TEAMID1234 KEYID12345 'C:\安全目录\AuthKey_KEYID12345.p8'
    if ($LASTEXITCODE -ne 0) { throw '开发者令牌签名失败' }
    java -cp $probeClasses AppleMusicProbe (Get-Location).Path
} finally {
    Remove-Item Env:DANGO_AM_DEVELOPER_TOKEN
}
```

浏览器会打开一张本机配对页。点击「通过 Apple 授权」，在 Apple 的弹窗中登录并同意。授权后回到配对页点「读取媒体库歌曲」。终端输入 `status`，预期显示 `authorized=1` 和读取到的歌曲数。测试账号需要有可读取的媒体库条目。

## 3. 播放检查

1. 选一首标有「目录 ID」且时长超过两分钟的歌。在终端输入 `load 目录ID`，等网页提示加载完成后，点网页的播放按钮。保留标签页。
2. 在终端输入 `status`，确认 `positionMs` 随时间推进，`durationMs` 对应歌曲全长。播放超过预览时长，并试 `seek 90`，确认能从歌曲 90 秒附近继续出声。
3. 依次测试 `pause`、`play` 和 `stop`。每次等前一项生效后再发下一条命令。停止应让进度归零，随后应保持静音。再试曲目仍在加载时输入 `stop`。
4. 测试 `logout` 或直接关闭标签页。退出原型用 `quit`，保留的标签页会在本机会话失效后尝试停止播放。需要重新配对时，重启验证程序。
5. 如遇失败，记录浏览器名称、失败的步骤和网页上显示的错误。请勿发送开发者令牌、私钥、Apple 账号密码或网络抓包。

若 Apple 弹窗能打开但授权返回失败，需要继续验证回环网页来源。令牌脚本为这个随机端口的本机测试省略了可选的来源限制，正式部署需要重新确认来源与签发策略。
