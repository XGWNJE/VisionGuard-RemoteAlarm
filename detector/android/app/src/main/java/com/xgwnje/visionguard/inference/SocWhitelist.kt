package com.xgwnje.visionguard.inference

import android.os.Build
import android.util.Log

/**
 * SoC 白名单检测。
 *
 * 根据设备硬件信息判断是否支持 640×640 高分辨率模型。
 * 覆盖高端 + 中高端 SoC，如骁龙 7/8 系列、天玑 8000/9000 系列、麒麟中高端、Exynos 中高端。
 */
object SocWhitelist {

    private const val TAG = "VG_SoC"
    private const val INPUT_SIZE_LOW = 320
    private const val INPUT_SIZE_HIGH = 640

    // 骁龙 8 Gen 系列关键字（高端 + 旧旗舰）
    private val SNAPDRAGON_8_KEYWORDS = listOf(
        "qcom",
        "sm8",      // sm8450, sm8550, sm8650 等
        "sm8150",   // 骁龙 855 / 855+ / 860
        "sm8250",   // 骁龙 865 / 865+ / 870
        "sm8350",   // 骁龙 888 / 888+
        "sdm8",     // sdm845, sdm865 等
        "lahaina",  // 骁龙 888
        "taro",     // 骁龙 8 Gen 1
        "kalama",   // 骁龙 8 Gen 2
        "pineapple" // 骁龙 8 Gen 3
    )

    // 骁龙 7 系列关键字（中高端）
    private val SNAPDRAGON_7_KEYWORDS = listOf(
        "sm7475",   // 骁龙 7+ Gen 2
        "sm7550",   // 骁龙 7 Gen 3
        "sm7675",   // 骁龙 7+ Gen 3
        "sm7635",   // 骁龙 7s Gen 2/3
        "sm7450",   // 骁龙 7 Gen 2
        "sdm7"      // 骁龙 7 系列旧型号
    )

    // 天玑 9000+ 系列关键字（高端）
    private val DIMENSITY_9_KEYWORDS = listOf(
        "mt698",    // 天玑 9000/9200
        "mt699"     // 天玑 9300+
    )

    // 天玑 8000/7000 系列关键字（中高端）
    private val DIMENSITY_8_KEYWORDS = listOf(
        "mt6895",   // 天玑 8000/8100
        "mt6983",   // 天玑 8200
        "mt6886",   // 天玑 7200
        "mt6878",   // 天玑 7300
        "mt6855"    // 天玑 7050/6020
    )

    // 麒麟高端系列关键字
    private val KIRIN_HIGH_KEYWORDS = listOf(
        "kirin9000",
        "kirin990",
        "kirin980"
    )

    // 麒麟中高端系列关键字
    private val KIRIN_MID_KEYWORDS = listOf(
        "kirin820",
        "kirin810",
        "kirin830",
        "kirin985",
        "kirin8000"
    )

    // Exynos 高端系列关键字
    private val EXYNOS_HIGH_KEYWORDS = listOf(
        "exynos2200",
        "exynos2400",
        "s5e"
    )

    // Exynos 中高端系列关键字
    private val EXYNOS_MID_KEYWORDS = listOf(
        "exynos1380",
        "exynos1480",
        "exynos1280",
        "exynos1330",
        "exynos1080"
    )

    private val ALL_KEYWORDS = SNAPDRAGON_8_KEYWORDS +
        SNAPDRAGON_7_KEYWORDS +
        DIMENSITY_9_KEYWORDS +
        DIMENSITY_8_KEYWORDS +
        KIRIN_HIGH_KEYWORDS +
        KIRIN_MID_KEYWORDS +
        EXYNOS_HIGH_KEYWORDS +
        EXYNOS_MID_KEYWORDS

    /**
     * 检测当前设备是否为高端 SoC。
     *
     * 检查 Build.HARDWARE、Build.BOARD、Build.SOC_MODEL（API 31+）
     * 是否匹配白名单中的关键字。
     *
     * @return true 表示高端 SoC，可使用 640×640 分辨率
     */
    fun isHighEndSoc(): Boolean {
        val hardwareInfo = collectHardwareInfo()
        Log.d(TAG, "Hardware info: $hardwareInfo")

        val lowerInfo = hardwareInfo.lowercase()

        for (keyword in ALL_KEYWORDS) {
            if (lowerInfo.contains(keyword)) {
                Log.i(TAG, "High-end SoC detected by keyword: $keyword")
                return true
            }
        }

        Log.i(TAG, "No high-end SoC detected, using default low resolution")
        return false
    }

    /**
     * 推荐输入分辨率。
     *
     * @return 高端 SoC → 640，其他 → 320
     */
    fun recommendInputSize(): Int {
        return if (isHighEndSoc()) INPUT_SIZE_HIGH else INPUT_SIZE_LOW
    }

    /**
     * 收集设备硬件信息字符串。
     */
    private fun collectHardwareInfo(): String {
        val info = StringBuilder()

        info.append(Build.HARDWARE).append(" ")
        info.append(Build.BOARD).append(" ")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                info.append(Build.SOC_MODEL).append(" ")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read SOC_MODEL", e)
            }
        }

        // 部分设备可通过 Build.PRODUCT / Build.DEVICE 提供额外信息
        info.append(Build.PRODUCT).append(" ")
        info.append(Build.DEVICE)

        return info.toString()
    }
}
