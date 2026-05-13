package com.example.mqttdashboard.data.mqtt

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface NativeMqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    val incomingMessages: SharedFlow<MqttIncomingMessage>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false)
}