package com.xgwnje.visionguard.data.repository

// ┌─────────────────────────────────────────────────────────┐
// │ SettingsRepository.kt                                   │
// │ 角色：DataStore 持久化封装                              │
// │ 持久化：deviceId（唯一标识）、cooldown、confidence、targets、selectedModel │
// │ serverUrl / apiKey 已移至 AppConstants 硬编码             │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xgwnje.visionguard.data.model.MaskRegion
import com.xgwnje.visionguard.data.model.MonitorConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vg_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID           = stringPreferencesKey("device_id")
        val COOLDOWN            = intPreferencesKey("cooldown")               // 秒，默认 5
        val CONFIDENCE          = floatPreferencesKey("confidence")           // 0.0-1.0，默认 0.45
        val TARGETS             = stringPreferencesKey("targets")             // 逗号分隔的 COCO 类名
        val SELECTED_MODEL      = stringPreferencesKey("selected_model")      // yolo26n / yolo26s
        val TARGET_SAMPLING_RATE = intPreferencesKey("target_sampling_rate")  // 次/秒，默认 3
        val USE_HIGH_RESOLUTION = booleanPreferencesKey("use_high_resolution") // 640x640，默认 false
        val MASK_REGIONS        = stringPreferencesKey("mask_regions")         // JSON 数组，默认空
        val DIGITAL_ZOOM        = floatPreferencesKey("digital_zoom")          // 默认 1.0f
        val DEVICE_NAME         = stringPreferencesKey("device_name")          // 自定义设备名
    }

    companion object {
        const val DEFAULT_COOLDOWN = 5
        const val DEFAULT_CONFIDENCE = 0.45f
        const val DEFAULT_TARGETS = "person"
        const val DEFAULT_MODEL = "yolo26n"
        const val DEFAULT_TARGET_SAMPLING_RATE = 3
        const val DEFAULT_USE_HIGH_RESOLUTION = false
        const val DEFAULT_DIGITAL_ZOOM = 1.0f
        const val DEFAULT_DEVICE_NAME = "Android-Detector"

        private val gson = Gson()
        private val maskRegionType = object : TypeToken<List<MaskRegion>>() {}.type
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

    /** 读取选择的模型（yolo26n / yolo26s） */
    val selectedModelFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_MODEL] ?: DEFAULT_MODEL
    }

    /** 读取目标采样率（次/秒） */
    val targetSamplingRateFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_SAMPLING_RATE] ?: DEFAULT_TARGET_SAMPLING_RATE
    }

    /** 读取是否启用高分辨率（640x640） */
    val useHighResolutionFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.USE_HIGH_RESOLUTION] ?: DEFAULT_USE_HIGH_RESOLUTION
    }

    /** 读取遮罩区域列表（JSON 反序列化） */
    val maskRegionsFlow: Flow<List<MaskRegion>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.MASK_REGIONS] ?: "[]"
        try {
            gson.fromJson<List<MaskRegion>>(json, maskRegionType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 读取数码裁切倍率 */
    val digitalZoomFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIGITAL_ZOOM] ?: DEFAULT_DIGITAL_ZOOM
    }

    /** 读取自定义设备名 */
    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_NAME] ?: DEFAULT_DEVICE_NAME
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

    suspend fun setSelectedModel(v: String) {
        context.dataStore.edit { prefs -> prefs[Keys.SELECTED_MODEL] = v }
    }

    suspend fun setTargetSamplingRate(v: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.TARGET_SAMPLING_RATE] = v }
    }

    suspend fun setUseHighResolution(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.USE_HIGH_RESOLUTION] = v }
    }

    suspend fun setMaskRegions(v: List<MaskRegion>) {
        val json = gson.toJson(v)
        context.dataStore.edit { prefs -> prefs[Keys.MASK_REGIONS] = json }
    }

    suspend fun setDigitalZoom(v: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.DIGITAL_ZOOM] = v }
    }

    /** 批量保存 MonitorConfig（原子写入，减少文件 IO 次数） */
    suspend fun saveMonitorConfig(config: MonitorConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIDENCE] = config.confidence
            prefs[Keys.COOLDOWN] = (config.cooldownMs / 1000).toInt()
            prefs[Keys.TARGETS] = config.targets.joinToString(",")
            prefs[Keys.SELECTED_MODEL] = config.modelName
            prefs[Keys.TARGET_SAMPLING_RATE] = config.targetSamplingRate
            prefs[Keys.USE_HIGH_RESOLUTION] = config.useHighResolution
            prefs[Keys.MASK_REGIONS] = gson.toJson(config.maskRegions)
            prefs[Keys.DIGITAL_ZOOM] = config.digitalZoom
        }
    }

    /** 同步读取当前值（供立即使用） */
    suspend fun getCooldown(): Int = cooldownFlow.first()
    suspend fun getConfidence(): Float = confidenceFlow.first()
    suspend fun getTargets(): String = targetsFlow.first()
    suspend fun getSelectedModel(): String = selectedModelFlow.first()
    suspend fun getTargetSamplingRate(): Int = targetSamplingRateFlow.first()
    suspend fun getUseHighResolution(): Boolean = useHighResolutionFlow.first()
    suspend fun getMaskRegions(): List<MaskRegion> = maskRegionsFlow.first()
    suspend fun getDigitalZoom(): Float = digitalZoomFlow.first()

    suspend fun setDeviceName(v: String) {
        context.dataStore.edit { prefs -> prefs[Keys.DEVICE_NAME] = v }
    }

    suspend fun getDeviceName(): String = deviceNameFlow.first()
}
