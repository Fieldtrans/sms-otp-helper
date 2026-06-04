using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace SmsOtpHelper.Win
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new TrayApp());
        }
    }

    internal sealed class TrayApp : Form
    {
        private const int HotkeyId = 1001;
        private const int WmHotkey = 0x0312;
        private readonly NotifyIcon notifyIcon;
        private readonly System.Windows.Forms.Timer pollTimer;
        private readonly AppConfig config;
        private string lastOtpPayload = "";

        public TrayApp()
        {
            Text = "短信验证码助手";
            ShowInTaskbar = false;
            WindowState = FormWindowState.Minimized;

            config = AppConfig.Load();
            notifyIcon = new NotifyIcon();
            notifyIcon.Icon = SystemIcons.Application;
            notifyIcon.Text = "短信验证码助手";
            notifyIcon.Visible = true;
            notifyIcon.ContextMenuStrip = BuildMenu();
            notifyIcon.DoubleClick += delegate { ShowSettings(); };

            pollTimer = new System.Windows.Forms.Timer();
            pollTimer.Tick += delegate { PullLatestOtp(false); };
            ApplyConfig();

            BeginInvoke(new Action(delegate
            {
                Hide();
                notifyIcon.ShowBalloonTip(1500, "短信验证码助手", "已在托盘运行", ToolTipIcon.Info);
                PullLatestOtp(false);
            }));
        }

        protected override void SetVisibleCore(bool value)
        {
            base.SetVisibleCore(false);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WmHotkey && m.WParam.ToInt32() == HotkeyId)
            {
                PushClipboardToPhone();
                return;
            }
            base.WndProc(ref m);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                NativeMethods.UnregisterHotKey(Handle, HotkeyId);
                pollTimer.Dispose();
                notifyIcon.Dispose();
            }
            base.Dispose(disposing);
        }

        private ContextMenuStrip BuildMenu()
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("立即读取手机验证码", null, delegate { PullLatestOtp(true); });
            menu.Items.Add("电脑剪贴板写入手机", null, delegate { PushClipboardToPhone(); });
            menu.Items.Add("检测手机连接", null, delegate { CheckDevice(true); });
            menu.Items.Add("设置", null, delegate { ShowSettings(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate { CloseApp(); });
            return menu;
        }

        private void ApplyConfig()
        {
            pollTimer.Stop();
            if (config.AutoPullOtp)
            {
                pollTimer.Interval = Math.Max(2, config.PollSeconds) * 1000;
                pollTimer.Start();
            }

            NativeMethods.UnregisterHotKey(Handle, HotkeyId);
            Hotkey parsed;
            if (Hotkey.TryParse(config.PushHotkey, out parsed))
            {
                NativeMethods.RegisterHotKey(Handle, HotkeyId, parsed.Modifiers, parsed.Key);
            }
        }

        private void PullLatestOtp(bool showResult)
        {
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    string output = RunAdb("shell cat " + ShellQuote(config.PhoneDir + "/latest_otp.txt"));
                    string payload = NormalizeAdbText(output);
                    if (payload.Length == 0)
                    {
                        if (showResult) Toast("手机目录里还没有验证码文件。");
                        return;
                    }

                    string code = payload.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)[0].Trim();
                    if (code.Length == 0)
                    {
                        if (showResult) Toast("验证码文件为空。");
                        return;
                    }

                    if (!showResult && payload == lastOtpPayload)
                    {
                        return;
                    }
                    lastOtpPayload = payload;
                    SetClipboard(code);
                    Toast("已复制手机验证码：" + code);
                }
                catch (Exception ex)
                {
                    if (showResult) Toast("读取失败：" + ex.Message);
                }
            });
        }

        private void PushClipboardToPhone()
        {
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    string text = GetClipboard();
                    if (text.Length == 0)
                    {
                        Toast("电脑剪贴板为空。");
                        return;
                    }

                    string tempFile = Path.Combine(Path.GetTempPath(), "sms-otp-pc-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt");
                    File.WriteAllText(tempFile, text, new UTF8Encoding(false));
                    string fileName = "pc-clipboard-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt";
                    RunAdb("shell mkdir -p " + ShellQuote(config.PhoneDir));
                    RunAdb("push " + Quote(tempFile) + " " + RemoteArg(config.PhoneDir + "/" + fileName));
                    try { File.Delete(tempFile); } catch { }
                    Toast("已写入手机：" + fileName);
                }
                catch (Exception ex)
                {
                    Toast("写入失败：" + ex.Message);
                }
            });
        }

        private bool CheckDevice(bool showResult)
        {
            try
            {
                string output = RunAdb("devices");
                bool ok = output.IndexOf("\tdevice", StringComparison.OrdinalIgnoreCase) >= 0;
                if (showResult)
                {
                    Toast(ok ? "手机已连接并授权 ADB。" : "未检测到已授权手机。");
                }
                return ok;
            }
            catch (Exception ex)
            {
                if (showResult) Toast("检测失败：" + ex.Message);
                return false;
            }
        }

        private void ShowSettings()
        {
            using (SettingsDialog dialog = new SettingsDialog(config))
            {
                if (dialog.ShowDialog() == DialogResult.OK)
                {
                    config.Save();
                    ApplyConfig();
                    Toast("设置已保存。");
                }
            }
        }

        private void CloseApp()
        {
            notifyIcon.Visible = false;
            Application.Exit();
        }

        private string RunAdb(string arguments)
        {
            string adb = config.ResolveAdbPath();
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = adb;
            startInfo.Arguments = arguments;
            startInfo.UseShellExecute = false;
            startInfo.RedirectStandardOutput = true;
            startInfo.RedirectStandardError = true;
            startInfo.CreateNoWindow = true;
            Process process = Process.Start(startInfo);
            string stdout = process.StandardOutput.ReadToEnd();
            string stderr = process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                throw new InvalidOperationException(stderr.Trim().Length == 0 ? stdout.Trim() : stderr.Trim());
            }
            return stdout;
        }

        private void Toast(string message)
        {
            BeginInvoke(new Action(delegate
            {
                notifyIcon.ShowBalloonTip(1800, "短信验证码助手", message, ToolTipIcon.Info);
            }));
        }

        private static string GetClipboard()
        {
            string result = "";
            Thread thread = new Thread(new ThreadStart(delegate
            {
                if (Clipboard.ContainsText())
                {
                    result = Clipboard.GetText();
                }
            }));
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
            return result == null ? "" : result;
        }

        private static void SetClipboard(string text)
        {
            Thread thread = new Thread(new ThreadStart(delegate { Clipboard.SetText(text); }));
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
        }

        private static string NormalizeAdbText(string text)
        {
            if (text == null) return "";
            text = text.Replace("\0", "").Trim();
            if (text.IndexOf("No such file", StringComparison.OrdinalIgnoreCase) >= 0) return "";
            return text;
        }

        private static string Quote(string value)
        {
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private static string ShellQuote(string value)
        {
            return "'" + value.Replace("'", "'\\''") + "'";
        }

        private static string RemoteArg(string value)
        {
            return Quote(value);
        }
    }

    internal sealed class SettingsDialog : Form
    {
        private readonly AppConfig config;
        private readonly TextBox adbPathBox = new TextBox();
        private readonly TextBox phoneDirBox = new TextBox();
        private readonly TextBox hotkeyBox = new TextBox();
        private readonly TextBox pollSecondsBox = new TextBox();
        private readonly CheckBox autoPullBox = new CheckBox();

        public SettingsDialog(AppConfig config)
        {
            this.config = config;
            Text = "设置";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(430, 270);

            AddLabel("ADB 路径", 16, 20);
            adbPathBox.SetBounds(120, 16, 290, 24);
            adbPathBox.Text = config.AdbPath;
            Controls.Add(adbPathBox);

            AddLabel("手机目录", 16, 58);
            phoneDirBox.SetBounds(120, 54, 290, 24);
            phoneDirBox.Text = config.PhoneDir;
            Controls.Add(phoneDirBox);

            AddLabel("写入快捷键", 16, 96);
            hotkeyBox.SetBounds(120, 92, 150, 24);
            hotkeyBox.Text = config.PushHotkey;
            Controls.Add(hotkeyBox);

            autoPullBox.SetBounds(120, 130, 260, 24);
            autoPullBox.Text = "自动读取手机验证码到电脑剪贴板";
            autoPullBox.Checked = config.AutoPullOtp;
            Controls.Add(autoPullBox);

            AddLabel("轮询秒数", 16, 166);
            pollSecondsBox.SetBounds(120, 162, 80, 24);
            pollSecondsBox.Text = config.PollSeconds.ToString();
            Controls.Add(pollSecondsBox);

            Label hint = new Label();
            hint.SetBounds(16, 202, 400, 32);
            hint.Text = "手机端需在右上角设置里开启“导出给电脑”。";
            hint.ForeColor = Color.DimGray;
            Controls.Add(hint);

            Button ok = new Button();
            ok.Text = "保存";
            ok.SetBounds(240, 232, 80, 28);
            ok.Click += delegate { SaveAndClose(); };
            Controls.Add(ok);

            Button cancel = new Button();
            cancel.Text = "取消";
            cancel.SetBounds(330, 232, 80, 28);
            cancel.Click += delegate { DialogResult = DialogResult.Cancel; Close(); };
            Controls.Add(cancel);
        }

        private void AddLabel(string text, int x, int y)
        {
            Label label = new Label();
            label.Text = text;
            label.SetBounds(x, y + 4, 100, 22);
            Controls.Add(label);
        }

        private void SaveAndClose()
        {
            int pollSeconds;
            if (!int.TryParse(pollSecondsBox.Text.Trim(), out pollSeconds))
            {
                pollSeconds = 5;
            }
            config.AdbPath = adbPathBox.Text.Trim();
            config.PhoneDir = phoneDirBox.Text.Trim();
            config.PushHotkey = hotkeyBox.Text.Trim();
            config.AutoPullOtp = autoPullBox.Checked;
            config.PollSeconds = Math.Max(2, Math.Min(60, pollSeconds));
            DialogResult = DialogResult.OK;
            Close();
        }
    }

    internal sealed class AppConfig
    {
        private static readonly string ConfigPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "SmsOtpHelper.Win.ini");
        public string AdbPath = "";
        public string PhoneDir = "/sdcard/Download/SMS";
        public string PushHotkey = "Ctrl+Alt+V";
        public bool AutoPullOtp = true;
        public int PollSeconds = 5;

        public static AppConfig Load()
        {
            AppConfig config = new AppConfig();
            if (!File.Exists(ConfigPath)) return config;
            foreach (string line in File.ReadAllLines(ConfigPath, Encoding.UTF8))
            {
                int index = line.IndexOf('=');
                if (index <= 0) continue;
                string key = line.Substring(0, index).Trim();
                string value = line.Substring(index + 1).Trim();
                if (key == "AdbPath") config.AdbPath = value;
                else if (key == "PhoneDir") config.PhoneDir = value;
                else if (key == "PushHotkey") config.PushHotkey = value;
                else if (key == "AutoPullOtp") config.AutoPullOtp = value == "true";
                else if (key == "PollSeconds")
                {
                    int seconds;
                    if (int.TryParse(value, out seconds)) config.PollSeconds = seconds;
                }
            }
            return config;
        }

        public void Save()
        {
            StringBuilder builder = new StringBuilder();
            builder.AppendLine("AdbPath=" + AdbPath);
            builder.AppendLine("PhoneDir=" + PhoneDir);
            builder.AppendLine("PushHotkey=" + PushHotkey);
            builder.AppendLine("AutoPullOtp=" + (AutoPullOtp ? "true" : "false"));
            builder.AppendLine("PollSeconds=" + PollSeconds);
            File.WriteAllText(ConfigPath, builder.ToString(), Encoding.UTF8);
        }

        public string ResolveAdbPath()
        {
            if (AdbPath.Length > 0 && File.Exists(AdbPath)) return AdbPath;
            string local = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android\\Sdk\\platform-tools\\adb.exe");
            if (File.Exists(local)) return local;
            return "adb.exe";
        }
    }

    internal struct Hotkey
    {
        public uint Modifiers;
        public uint Key;

        public static bool TryParse(string value, out Hotkey hotkey)
        {
            hotkey = new Hotkey();
            if (string.IsNullOrEmpty(value)) return false;
            string key = "";
            string[] parts = value.Split('+');
            foreach (string raw in parts)
            {
                string part = raw.Trim().ToUpperInvariant();
                if (part == "CTRL" || part == "CONTROL") hotkey.Modifiers |= 0x0002;
                else if (part == "ALT") hotkey.Modifiers |= 0x0001;
                else if (part == "SHIFT") hotkey.Modifiers |= 0x0004;
                else if (part == "WIN" || part == "WINDOWS") hotkey.Modifiers |= 0x0008;
                else key = part;
            }
            if (key.Length != 1) return false;
            hotkey.Key = (uint)key[0];
            return true;
        }
    }

    internal static class NativeMethods
    {
        [DllImport("user32.dll")]
        public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
    }
}
