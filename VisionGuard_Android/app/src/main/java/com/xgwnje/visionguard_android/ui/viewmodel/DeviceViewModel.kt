package com.xgwnje.visionguard_android.ui.viewmodel

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceViewModel.kt                                      │
// │ 角色：设备列表 ViewModel，桥接 Service ↔ DeviceListScreen│
// │ 对外：devices StateFlow, sendCommand(), commandAck Flow  │
// └─────────────────────────────────────────────────────────┘

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.service.AlertForegroundService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DeviceViewModel(private val service: AlertForegroundService) : ViewModel() {

    val devices: StateFlow<List<DeviceInfo>> = service.devices
        .map { list ->
            list.sortedWith(
                compareByDescending<DeviceInfo> { it.online }
                    .thenBy { it.deviceName }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val commandAck: SharedFlow<Pair<String, Boolean>> = service.commandAck

    fun sendCommand(targetDeviceId: String, command: String) {
        service.sendCommand(targetDeviceId, command)
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String) {
        service.sendSetConfig(targetDeviceId, key, value)
    }

    class Factory(private val service: AlertForegroundService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DeviceViewModel(service) as T
    }
}
