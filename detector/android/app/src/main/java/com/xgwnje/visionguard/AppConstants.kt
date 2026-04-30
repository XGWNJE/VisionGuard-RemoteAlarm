package com.xgwnje.visionguard

// ┌─────────────────────────────────────────────────────────┐
// │ AppConstants.kt                                         │
// │ 角色：全局硬编码常量                                      │
// │ 修改方法：直接改此文件后重新编译 APK                       │
// └─────────────────────────────────────────────────────────┘

object AppConstants {
    /** 服务器地址（不含末尾斜杠） */
    const val SERVER_URL = "http://216.36.111.208:3000"

    /** API 密钥（与服务器 .env 中 API_KEY 一致） */
    const val API_KEY = "XG-VisionGuard-2024"
}
