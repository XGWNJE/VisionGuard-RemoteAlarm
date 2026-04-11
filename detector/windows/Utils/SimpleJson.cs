// ┌─────────────────────────────────────────────────────────┐
// │ SimpleJson.cs                                           │
// │ 角色：轻量 JSON 序列化帮助类（无外部依赖）               │
// │ 依赖：System.Web.Script.Serialization (System.Web.Ext.) │
// │ 对外 API：SimpleJson.ToJson(), SimpleJson.ParseType()   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace VisionGuard.Utils
{
    /// <summary>
    /// 轻量 JSON 序列化/反序列化，封装 JavaScriptSerializer，避免引入 Newtonsoft.Json。
    /// </summary>
    internal static class SimpleJson
    {
        private static readonly JavaScriptSerializer _js = new JavaScriptSerializer
        {
            MaxJsonLength = 10 * 1024 * 1024  // 10 MB 上限
        };

        /// <summary>将对象序列化为 JSON 字符串</summary>
        public static string ToJson(object obj) => _js.Serialize(obj);

        /// <summary>将 JSON 字符串反序列化为 Dictionary&lt;string, object&gt;</summary>
        public static Dictionary<string, object> ParseDict(string json)
        {
            try
            {
                return _js.Deserialize<Dictionary<string, object>>(json)
                       ?? new Dictionary<string, object>();
            }
            catch { return new Dictionary<string, object>(); }
        }

        /// <summary>安全获取 Dictionary 中的字符串值</summary>
        public static string GetString(Dictionary<string, object> d, string key, string fallback = "")
        {
            if (d != null && d.TryGetValue(key, out object v) && v != null)
                return v.ToString();
            return fallback;
        }
    }
}
