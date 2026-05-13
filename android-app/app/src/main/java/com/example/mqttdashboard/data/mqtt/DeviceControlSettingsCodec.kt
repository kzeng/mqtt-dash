package com.example.mqttdashboard.data.mqtt

object DeviceControlSettingsCodec {
    private const val REQUEST_SETTINGS_COMMAND = "5503F900AA"
    private const val SETTINGS_RESPONSE_PREFIX = "551bf900"

    fun buildReadCommand(): String = REQUEST_SETTINGS_COMMAND

    fun buildWriteCommand(settings: DeviceControlSettings): String? {
        if (!settings.isComplete) {
            return null
        }

        return buildString {
            append("551BDA00")
            appendScaled(settings.targetTemperature)
            appendScaled(settings.targetHumidity)
            appendScaled(settings.controlTemperatureDeviation)
            appendScaled(settings.controlHumidityDeviation)
            appendWhole(settings.controlTemperatureTime)
            appendWhole(settings.controlHumidityTime)
            appendWhole(settings.heatingTime)
            appendWhole(settings.humidificationTime)
            appendWhole(settings.defrostTime)
            appendWhole(settings.stopTime)
            append("00000000")
            append("AA")
        }
    }

    fun parseReadResponse(payload: String): DeviceControlSettings? {
        val normalized = payload.lowercase().replace(" ", "")
        if (!normalized.startsWith(SETTINGS_RESPONSE_PREFIX) || !normalized.endsWith("aa")) {
            return null
        }

        val bytes = normalized.substring(8, normalized.length - 2).chunked(2)
        if (bytes.size < 20) {
            return null
        }

        fun decodeScaled(index: Int): String {
            val raw = (bytes[index].toInt(16) shl 8) or bytes[index + 1].toInt(16)
            return String.format("%.1f", raw / 10.0)
        }

        fun decodeWhole(index: Int): String {
            val raw = (bytes[index].toInt(16) shl 8) or bytes[index + 1].toInt(16)
            return raw.toString()
        }

        return DeviceControlSettings(
            targetTemperature = decodeScaled(0),
            targetHumidity = decodeScaled(2),
            controlTemperatureDeviation = decodeScaled(4),
            controlHumidityDeviation = decodeScaled(6),
            controlTemperatureTime = decodeWhole(8),
            controlHumidityTime = decodeWhole(10),
            heatingTime = decodeWhole(12),
            humidificationTime = decodeWhole(14),
            defrostTime = decodeWhole(16),
            stopTime = decodeWhole(18)
        )
    }

    private fun StringBuilder.appendScaled(value: String) {
        val scaled = (value.toDoubleOrNull()?.times(10))?.toInt() ?: throw IllegalArgumentException("Invalid decimal value")
        append(scaled.toString(16).padStart(4, '0'))
    }

    private fun StringBuilder.appendWhole(value: String) {
        val whole = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer value")
        append(whole.toString(16).padStart(4, '0'))
    }
}