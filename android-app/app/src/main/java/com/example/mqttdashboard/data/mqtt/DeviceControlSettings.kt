package com.example.mqttdashboard.data.mqtt

data class DeviceControlSettings(
    val targetTemperature: String = "",
    val targetHumidity: String = "",
    val controlTemperatureDeviation: String = "",
    val controlHumidityDeviation: String = "",
    val controlTemperatureTime: String = "",
    val controlHumidityTime: String = "",
    val heatingTime: String = "",
    val humidificationTime: String = "",
    val defrostTime: String = "",
    val stopTime: String = ""
) {
    val isComplete: Boolean
        get() = listOf(
            targetTemperature,
            targetHumidity,
            controlTemperatureDeviation,
            controlHumidityDeviation,
            controlTemperatureTime,
            controlHumidityTime,
            heatingTime,
            humidificationTime,
            defrostTime,
            stopTime
        ).all { it.isNotBlank() }

    val hasValues: Boolean
        get() = listOf(
            targetTemperature,
            targetHumidity,
            controlTemperatureDeviation,
            controlHumidityDeviation,
            controlTemperatureTime,
            controlHumidityTime,
            heatingTime,
            humidificationTime,
            defrostTime,
            stopTime
        ).any { it.isNotBlank() }
}