using System;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace VisionGuard.Utils
{
    public static class NtpSync
    {
        private static long _offsetMs;
        private static bool _synced;

        public static long OffsetMs => _offsetMs;
        public static bool IsSynced => _synced;

        public static DateTime UtcNow => DateTime.UtcNow.AddMilliseconds(_offsetMs);

        public static async Task SyncAsync()
        {
            string[] servers = { "ntp.aliyun.com", "cn.pool.ntp.org", "ntp.tencent.com" };
            foreach (var server in servers)
            {
                try
                {
                    long offset = await QueryOffset(server);
                    _offsetMs = offset;
                    _synced = true;
                    LogManager.StaticInfo($"[NTP] 同步成功 server={server} offset={offset}ms");
                    return;
                }
                catch (Exception ex)
                {
                    LogManager.StaticWarn($"[NTP] {server} 失败: {ex.Message}");
                }
            }
            LogManager.StaticWarn("[NTP] 所有服务器均失败，使用本地时钟");
        }

        private static async Task<long> QueryOffset(string server)
        {
            const int NTP_PORT = 123;
            var ntpData = new byte[48];
            ntpData[0] = 0x1B; // LI=0, VN=3, Mode=3 (client)

            using (var udp = new UdpClient())
            {
                udp.Client.ReceiveTimeout = 3000;
                udp.Client.SendTimeout = 3000;

                var ep = new IPEndPoint(Dns.GetHostAddresses(server)[0], NTP_PORT);
                long t1 = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                await udp.SendAsync(ntpData, ntpData.Length, ep);
                var result = await udp.ReceiveAsync();
                long t4 = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

                byte[] data = result.Buffer;

                // NTP 时间戳 (1900-01-01) → Unix epoch 偏移
                const long NTP_EPOCH_DIFF = 2208988800L;

                ulong rxSec = ((ulong)data[32] << 24) | ((ulong)data[33] << 16) |
                              ((ulong)data[34] << 8) | data[35];
                ulong rxFrac = ((ulong)data[36] << 24) | ((ulong)data[37] << 16) |
                               ((ulong)data[38] << 8) | data[39];
                ulong txSec = ((ulong)data[40] << 24) | ((ulong)data[41] << 16) |
                              ((ulong)data[42] << 8) | data[43];
                ulong txFrac = ((ulong)data[44] << 24) | ((ulong)data[45] << 16) |
                               ((ulong)data[46] << 8) | data[47];

                long t2 = ((long)rxSec - NTP_EPOCH_DIFF) * 1000 + (long)(rxFrac * 1000 / 0x100000000L);
                long t3 = ((long)txSec - NTP_EPOCH_DIFF) * 1000 + (long)(txFrac * 1000 / 0x100000000L);

                // offset = ((t2-t1) + (t3-t4)) / 2
                long offset = ((t2 - t1) + (t3 - t4)) / 2;
                return offset;
            }
        }
    }
}
