package com.xgwnje.visionguard_android.data.repository

// ┌─────────────────────────────────────────────────────────┐
// │ SettingsRepository.kt                                   │
// │ 角色：DataStore 持久化封装                              │
// │ 持久化：deviceId（唯一标识）、cooldown、confidence、targets │
// │ serverUrl / apiKey 已移至 AppConstants 硬编码             │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vg_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID   = stringPreferencesKey("device_id")
        val COOLDOWN    = intPreferencesKey("cooldown")    // 秒，默认 5
        val CONFIDENCE  = floatPreferencesKey("confidence") // 0.0-1.0，默认 0.45
        val TARGETS     = stringPreferencesKey("targets")  // 逗号分隔的 COCO 类名
    }

    companion object {
        const val DEFAULT_COOLDOWN = 5
        const val DEFAULT_CONFIDENCE = 0.45f
        const val DEFAULT_TARGETS = "person"
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

    /** 读取 cooldown（秒） */
    val cooldownFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.COOLDOWN] ?: DEFAULT_COOLDOWN
    }

    /** 读取 confidence（0.0-1.0） */
    val confidenceFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONFIDENCE] ?: DEFAULT_CONFIDENCE
    }

    /** 读取监控目标（COCO 类名，逗号分隔） */
    val targetsFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TARGETS] ?: DEFAULT_TARGETS
    }

    suspend fun setCooldown(v: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.COOLDOWN] = v }
    }

    suspend fun setConfidence(v: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.CONFIDENCE] = v }
    }

    suspend fun setTargets(v: String) {
        context.dataStore.edit { prefs -> prefs[Keys.TARGETS] = v }
    }

    /** 同步读取当前值（供立即使用） */
    suspend fun getCooldown(): Int = cooldownFlow.first()
    suspend fun getConfidence(): Float = confidenceFlow.first()
    suspend fun getTargets(): String = targetsFlow.first()
}
