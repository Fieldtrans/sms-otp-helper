# 短信验证码助手 / SMS OTP Helper

短信验证码助手是一个 LSPosed 模块，用于从短信或短信通知中提取验证码，并通过当前输入法自动填入。验证码会临时写入剪贴板，并在生成 90 秒后清理。

![应用首页](docs/images/app-home.jpg)

## 正式版说明

- 当前版本：`1.1.0`
- 下载 APK：[sms-otp-helper-v1.1.0.apk](releases/sms-otp-helper-v1.1.0.apk)
- 需要 Android 10+。
- 需要在 LSPosed 中勾选短信 App 和当前输入法 App。
- Android 13+ 需要授予通知权限，收到验证码时会显示“验证码已就绪”提示。
- 安装或升级后建议重启手机，或至少强行停止短信 App 与当前输入法进程。
- 设置里可以开启“半自动填入”，默认保留验证码最后 2 位不填。例如 `123456` 会自动输入 `1234`。

## 基本流程

1. 短信或通知出现验证码。
2. 模块提取 4-8 位数字或常见字母数字验证码。
3. 验证码进入临时剪贴板，90 秒后失效。
4. 当前输入法检测到临时验证码后自动填入。
5. 填入成功后只标记为已填入，避免重复输入；临时剪贴板仍按生成时间 90 秒后清理。

## 电脑辅助脚本

普通 Android App 不能可靠地自动把 USB 模式切到“文件传输”，这个动作需要系统或特权权限。需要把电脑剪贴板内容写到手机目录时，可以使用 `tools/pc-clipboard-to-phone.ps1`：

```powershell
powershell -ExecutionPolicy Bypass -File tools\pc-clipboard-to-phone.ps1 -PhoneDir "/sdcard/Download/SMS" -Hotkey "Ctrl+Alt+V"
```

前提：电脑安装 `adb`，手机开启 USB 调试并连接电脑。脚本启动后按设置的快捷键，会把电脑当前剪贴板内容保存成文本文件并推送到手机指定目录。

## 发布包

正式版 APK：

[`releases/sms-otp-helper-v1.1.0.apk`](releases/sms-otp-helper-v1.1.0.apk)

历史版本：

[`releases/sms-otp-helper-v1.0.0.apk`](releases/sms-otp-helper-v1.0.0.apk)

SHA256：

`8565CE3BDE2E7FB01CAEB194D5C9F0564F4C834303E2CC156943579061A59E39`
