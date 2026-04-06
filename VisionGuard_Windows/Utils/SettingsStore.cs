// ┌─────────────────────────────────────────────────────────┐
// │ SettingsStore.cs                                        │
// │ 角色：轻量 key=value 持久化，存储到 settings.ini        │
// │ 线程：仅在 UI 线程调用 Load/Save，无需加锁              │
// │ 对外 API：Load(), Save(), GetInt/Bool/String/StringList │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.IO;

namespace VisionGuard.Utils
{
    /// <summary>
    /// 轻量 key=value 持久化，存储到 %AppData%\VisionGuard\settings.ini。
    /// 格式：每行 key=value，#开头为注释，忽略空行。
    /// 线程安全：仅在 UI 线程调用 Load/Save，无需加锁。
    /// </summary>
    internal static class SettingsStore
    {
        private static readonly string FilePath = Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory,
            "settings.ini");

        private static Dictionary<string, string> _data = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        // ── 读取 ─────────────────────────────────────────────────────

        public static void Load()
        {
            _data.Clear();
            if (!File.Exists(FilePath)) return;
            try
            {
                foreach (var line in File.ReadAllLines(FilePath))
                {
                    if (string.IsNullOrWhiteSpace(line) || line.StartsWith("#")) continue;
                    int idx = line.IndexOf('=');
                    if (idx <= 0) continue;
                    string key = line.Substring(0, idx).Trim();
                    string val = line.Substring(idx + 1).Trim();
                    _data[key] = val;
                }
            }
            catch { /* 读取失败静默，使用默认值 */ }
        }

        // ── 写入 ─────────────────────────────────────────────────────

        public static void Save()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
                var lines = new List<string> { "# VisionGuard 用户设置（自动生成，可手动编辑）" };
                foreach (var kv in _data)
                    lines.Add($"{kv.Key}={kv.Value}");
                File.WriteAllLines(FilePath, lines);
            }
            catch { /* 写入失败静默 */ }
        }

        // ── 类型化访问 ───────────────────────────────────────────────

        public static int GetInt(string key, int defaultValue)
        {
            if (_data.TryGetValue(key, out string s) && int.TryParse(s, out int v)) return v;
            return defaultValue;
        }

        public static bool GetBool(string key, bool defaultValue)
        {
            if (_data.TryGetValue(key, out string s) && bool.TryParse(s, out bool v)) return v;
            return defaultValue;
        }

        public static string GetString(string key, string defaultValue)
        {
            return _data.TryGetValue(key, out string s) ? s : defaultValue;
        }

        /// <summary>
        /// 读取逗号分隔的字符串列表，空字符串返回空集合。
        /// </summary>
        public static HashSet<string> GetStringList(string key)
        {
            var result = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (_data.TryGetValue(key, out string s) && !string.IsNullOrWhiteSpace(s))
            {
                foreach (var item in s.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries))
                {
                    result.Add(item.Trim());
                }
            }
            return result;
        }

        public static void Set(string key, int value)    => _data[key] = value.ToString();
        public static void Set(string key, bool value)   => _data[key] = value.ToString();
        public static void Set(string key, string value) => _data[key] = value ?? string.Empty;
    }
}
