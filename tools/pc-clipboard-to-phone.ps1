param(
    [string]$PhoneDir = "/sdcard/Download/SMS",
    [string]$Hotkey = "Ctrl+Alt+V",
    [string]$FilePrefix = "pc-clipboard"
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($adb) {
        return $adb.Source
    }
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        return $sdkAdb
    }
    throw "找不到 adb。请先安装 Android platform-tools，并把 adb 加到 PATH。"
}

function Convert-Hotkey {
    param([string]$Value)

    $modifiers = 0
    $keyName = ""
    foreach ($part in $Value.Split("+")) {
        $token = $part.Trim()
        switch -Regex ($token) {
            "^(Ctrl|Control)$" { $modifiers = $modifiers -bor 0x0002; continue }
            "^Alt$" { $modifiers = $modifiers -bor 0x0001; continue }
            "^Shift$" { $modifiers = $modifiers -bor 0x0004; continue }
            "^Win(dows)?$" { $modifiers = $modifiers -bor 0x0008; continue }
            default { $keyName = $token.ToUpperInvariant() }
        }
    }
    if ([string]::IsNullOrWhiteSpace($keyName) -or $keyName.Length -ne 1) {
        throw "快捷键格式暂时支持类似 Ctrl+Alt+V、Ctrl+Shift+C。"
    }
    return @{ Modifiers = $modifiers; Key = [byte][char]$keyName }
}

function Push-ClipboardToPhone {
    param(
        [string]$AdbPath,
        [string]$TargetDir,
        [string]$Prefix
    )

    $text = Get-Clipboard -Raw
    if ([string]::IsNullOrEmpty($text)) {
        Write-Host "电脑剪贴板为空。"
        return
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $tempFile = Join-Path $env:TEMP "$Prefix-$timestamp.txt"
    [System.IO.File]::WriteAllText($tempFile, $text, [System.Text.UTF8Encoding]::new($false))

    & $AdbPath shell "mkdir -p '$TargetDir'" | Out-Null
    & $AdbPath push $tempFile "$TargetDir/$Prefix-$timestamp.txt" | Out-Null
    Remove-Item -LiteralPath $tempFile -Force
    Write-Host "已写入手机：$TargetDir/$Prefix-$timestamp.txt"
}

$adbPath = Find-Adb
$parsedHotkey = Convert-Hotkey $Hotkey

Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class HotkeyNative {
    [DllImport("user32.dll")]
    public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll")]
    public static extern sbyte GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [StructLayout(LayoutKind.Sequential)]
    public struct MSG {
        public IntPtr hwnd;
        public uint message;
        public UIntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int pt_x;
        public int pt_y;
    }
}
"@

$hotkeyId = 1001
$wmHotkey = 0x0312

if (-not [HotkeyNative]::RegisterHotKey([IntPtr]::Zero, $hotkeyId, [uint32]$parsedHotkey.Modifiers, [uint32]$parsedHotkey.Key)) {
    throw "注册快捷键失败：$Hotkey。可能已经被其他程序占用。"
}

Write-Host "已启动。快捷键：$Hotkey"
Write-Host "目标目录：$PhoneDir"
Write-Host "退出：关闭此 PowerShell 窗口或按 Ctrl+C"

try {
    $msg = New-Object HotkeyNative+MSG
    while ([HotkeyNative]::GetMessage([ref]$msg, [IntPtr]::Zero, 0, 0) -ne 0) {
        if ($msg.message -eq $wmHotkey -and $msg.wParam.ToUInt32() -eq $hotkeyId) {
            Push-ClipboardToPhone -AdbPath $adbPath -TargetDir $PhoneDir -Prefix $FilePrefix
        }
    }
} finally {
    [HotkeyNative]::UnregisterHotKey([IntPtr]::Zero, $hotkeyId) | Out-Null
}
