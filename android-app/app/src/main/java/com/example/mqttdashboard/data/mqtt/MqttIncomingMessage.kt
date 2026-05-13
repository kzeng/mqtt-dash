package com.example.mqttdashboard.data.mqtt

data class MqttIncomingMessage(
    val topic: String,
    val payload: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)