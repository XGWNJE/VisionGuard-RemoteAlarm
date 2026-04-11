package com.xgwnje.visionguard_android.ui.viewmodel

// ┌─────────────────────────────────────────────────────────┐
// │ AlertViewModel.kt                                       │
// │ 角色：报警列表 ViewModel，桥接 Service ↔ AlertListScreen  │
// │ 依赖：AlertForegroundService（通过 Application 单例访问） │
// │ 对外：alerts StateFlow, clearAlerts()                    │
// └─────────────────────────────────────────────────────────┘

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.service.AlertForegroundService
import kotlinx.coroutines.flow.StateFlow

class AlertViewModel(private val service: AlertForegroundService) : ViewModel() {

    val alerts: StateFlow<List<AlertMessage>> = service.alerts

    fun clearAlerts() = service.clearAlerts()

    class Factory(private val service: AlertForegroundService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlertViewModel(service) as T
    }
}
