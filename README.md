# 短信验证码助手 / SMS OTP Helper

短信验证码助手是一个 LSPosed 模块，用于从短信或短信通知中提取验证码，并通过当前输入法自动填入。验证码会临时写入剪贴板，并在生成 90 秒后清理。

![应用首页](docs/images/app-home.jpg)

## 正式版说明

- 当前 Android 版本：`1.2.0`
- 当前 Windows 辅助程序版本：`1.2.1`
- 下载 APK：[sms-otp-helper-v1.2.0.apk](releases/sms-otp-helper-v1.2.0.apk)
- 下载 Windows 辅助程序：[SmsOtpHelper.Win-v1.2.1.exe](releases/SmsOtpHelper.Win-v1.2.1.exe)
- 需要 Android 10+。
- 需要在 LSPosed 中勾选短信 App 和当前输入法 App。
- 安装或升级后建议重启手机，或至少强行停止短信 App 与当前输入法进程。
- 设置里可以开启“半自动填入”，默认保留验证码最后 2 位不填。例如 `123456` 会自动输入 `1234`。
- 设置里可以关闭收到验证码时的 Toast 提示，或调整提示秒数。
- 设置里可以开启“导出给电脑”，验证码会写入手机 `Download/SMS/latest_otp.txt`，供 Windows 辅助程序读取。

## 基本流程

1. 短信或通知出现验证码。
2. 模块提取 4-8 位数字或常见字母数字验证码。
3. 验证码进入临时剪贴板，90 秒后失效。
4. 当前输入法检测到临时验证码后自动填入。
5. 填入成功后只标记为已填入，避免重复输入；临时剪贴板仍按生成时间 90 秒后清理。

## Windows 辅助程序

`SmsOtpHelper.Win.exe` 是托盘程序，需要电脑安装 `adb`，手机开启 USB 调试并授权。

- 自动读取手机 `Download/SMS/latest_otp.txt`，有新验证码时复制到电脑剪贴板。
- 默认快捷键 `Ctrl+Alt+V`：把电脑当前剪贴板写入手机 `Download/SMS` 目录。
- USB 连接并授权 ADB 后，可自动尝试打开手机文件传输模式，也可在托盘菜单手动触发。
- 双击托盘图标或右键“设置”可以修改 ADB 路径、手机目录、快捷键和轮询秒数。

## 发布包

正式版 APK：

[`releases/sms-otp-helper-v1.2.0.apk`](releases/sms-otp-helper-v1.2.0.apk)

Windows 辅助程序：

[`releases/SmsOtpHelper.Win-v1.2.1.exe`](releases/SmsOtpHelper.Win-v1.2.1.exe)

历史版本：

[`releases/SmsOtpHelper.Win-v1.2.0.exe`](releases/SmsOtpHelper.Win-v1.2.0.exe)

[`releases/sms-otp-helper-v1.1.2.apk`](releases/sms-otp-helper-v1.1.2.apk)

[`releases/sms-otp-helper-v1.1.1.apk`](releases/sms-otp-helper-v1.1.1.apk)

[`releases/sms-otp-helper-v1.1.0.apk`](releases/sms-otp-helper-v1.1.0.apk)

APK SHA256：

`7D65F90607F76568834F09AC8B410A956CEF6AB55CE59B757EBA0FCFFFCCBE10`

Windows exe SHA256：

`786F4B3BE841AAAF36299EABBAA520348B2B564E88E52D5C3C6C1302A66F272D`
