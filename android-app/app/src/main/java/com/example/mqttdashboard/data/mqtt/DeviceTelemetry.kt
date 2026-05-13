package com.example.mqttdashboard.data.mqtt

data class ChannelSnapshot(
    val label: String,
    val temperature: String = "-",
    val humidity: String = "-",
    val active: Boolean = false
)

data class DeviceTelemetry(
    val deviceTime: String = "",
    val running: Boolean? = null,
    val temperature: String = "-",
    val humidity: String = "-",
    val targetTemperature: String = "-",
    val targetHumidity: String = "-",
    val temperatureDeviation: String = "-",
    val humidityDeviation: String = "-",
    val controlMode: String = "-",
    val controlStatus: String = "-",
    val airConditionerState: String = "-",
    val wifiQuality: Int? = null,
    val channels: List<ChannelSnapshot> = List(4) { index -> ChannelSnapshot(label = "CH${index + 1}") },
    val lastTopic: String = "",
    val lastPayload: String = ""
) {
    val hasSnapshot: Boolean
        get() = deviceTime.isNotBlank() || running != null || temperature != "-" || humidity != "-"
}