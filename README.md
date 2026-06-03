# 短信验证码助手 / SMS OTP Helper

验证码助手是一个 LSPosed 模块，用于从短信或短信通知中提取验证码，并通过当前输入法自动填入。验证码会临时写入剪贴板，成功填入或过期后清理。

![应用首页](docs/images/app-home.jpg)

## Beta 版说明

- 当前版本：`1.0-beta.1`
- 下载 APK：[sms-otp-helper-v1.0-beta.1.apk](releases/sms-otp-helper-v1.0-beta.1.apk)
- 需要 Android 10+。
- 需要在 LSPosed 中勾选短信 App 和当前输入法 App。
- 安装或升级后建议重启手机，或至少强行停止短信 App 与当前输入法进程。

## 基本流程

1. 短信或通知出现验证码。
2. 模块提取 4-8 位数字验证码。
3. 验证码进入临时剪贴板，90 秒后失效。
4. 当前输入法检测到临时验证码后自动填入。
5. 填入成功后清空临时剪贴板，避免重复输入。

## 发布包

Beta APK：

[`releases/sms-otp-helper-v1.0-beta.1.apk`](releases/sms-otp-helper-v1.0-beta.1.apk)

SHA256：

`0EA49D1FAAEB8C4129589E020DB546B00BD414B3529B027B84E323E4BE5DC75D`
