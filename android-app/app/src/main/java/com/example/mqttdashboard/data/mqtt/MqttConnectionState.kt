package com.example.mqttdashboard.data.mqtt

sealed interface MqttConnectionState {
    data object Idle : MqttConnectionState
    data object Connecting : MqttConnectionState
    data object Connected : MqttConnectionState
    data class Disconnected(val cause: String? = null) : MqttConnectionState
    data class Failed(val cause: String) : MqttConnectionState
}