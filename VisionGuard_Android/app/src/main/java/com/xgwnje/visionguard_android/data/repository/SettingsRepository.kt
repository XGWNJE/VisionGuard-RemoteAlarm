package com.xgwnje.visionguard_android.data.repository

// ┌─────────────────────────────────────────────────────────┐
// │ SettingsRepository.kt                                   │
// │ 角色：DataStore 持久化封装，只存 deviceId（唯一标识）      │
// │ serverUrl / apiKey 已移至 AppConstants 硬编码             │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vg_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    /** 读取设备 ID（首次启动时自动生成并持久化） */
    val deviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_ID] ?: ""
    }

    /** 确保 deviceId 存在（首次启动生成），返回最终值 */
    suspend fun ensureDeviceId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrEmpty()) {
                prefs[Keys.DEVICE_ID] = UUID.randomUUID().toString()
            }
            id = prefs[Keys.DEVICE_ID] ?: ""
        }
        return id
    }
}
