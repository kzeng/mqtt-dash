package com.example.mqttdashboard.data.mqtt

object DeviceTelemetryParser {
    fun parseInfoPayload(payload: String, previous: DeviceTelemetry = DeviceTelemetry()): DeviceTelemetry? {
        val bytes = payload.hexToBytes() ?: return null
        if (bytes.size <= 104) {
            return null
        }

        val hasSensor = bytes[87] != 0
        val airConditionerFlag = bytes[104]
        val temperatureProtection = when (bytes[102]) {
            2 -> "制冷保护"
            1 -> "加热保护"
            else -> ""
        }
        val statusFlags = bytes[103]
        val controlActions = buildList {
            if ((statusFlags and 0b0000_0001) != 0) add("加热")
            if ((statusFlags and 0b0000_0010) != 0) add("制冷")
            if ((statusFlags and 0b0000_0100) != 0) add("化霜")
            if ((statusFlags and 0b0000_1000) != 0) add("加湿")
            if ((statusFlags and 0b0001_0000) != 0) add("抽湿")
        }

        val controlMode = if (!hasSensor) {
            "-"
        } else {
            val mode = if (bytes[100] == 1) "循环" else "自动"
            val policy = if (bytes[101] == 1) "节能" else ""
            listOf(mode, policy).filter { it.isNotBlank() }.joinToString(" / ")
        }

        val controlStatus = if (!hasSensor) {
            "-"
        } else {
            listOf(temperatureProtection, controlActions.joinToString(" / "))
                .filter { it.isNotBlank() }
                .joinToString(" / ")
                .ifBlank { "待机" }
        }

        val channels = List(4) { index ->
            val channelStart = 16 + (index * 18)
            ChannelSnapshot(
                label = "CH${index + 1}",
                temperature = decodeChannelScaled(bytes, channelStart),
                humidity = decodeChannelScaled(bytes, channelStart + 2),
                active = airConditionerFlag != 0 && index == 0
            )
        }

        return previous.copy(
            deviceTime = formatTime(bytes[13], bytes[14]),
            running = bytes[5] == 1,
            temperature = decodeScaled(bytes, 88),
            humidity = decodeScaled(bytes, 90),
            targetTemperature = decodeScaled(bytes, 92),
            temperatureDeviation = decodeScaled(bytes, 94),
            targetHumidity = decodeScaled(bytes, 96),
            humidityDeviation = decodeScaled(bytes, 98),
            controlMode = controlMode,
            controlStatus = controlStatus,
            airConditionerState = if (airConditionerFlag == 1) "运行中" else "关闭",
            wifiQuality = bytes.last(),
            channels = channels,
            lastPayload = payload
        )
    }

    fun parseStatePayload(payload: String, previous: DeviceTelemetry = DeviceTelemetry()): DeviceTelemetry? {
        val bytes = payload.hexToBytes() ?: return null
        if (bytes.size <= 3) {
            return null
        }

        return previous.copy(
            running = bytes[3] == 1,
            lastPayload = payload
        )
    }

    private fun decodeScaled(bytes: List<Int>, startIndex: Int): String {
        if (startIndex + 1 >= bytes.size) {
            return "-"
        }
        val raw = (bytes[startIndex] shl 8) or bytes[startIndex + 1]
        return String.format("%.1f", raw / 10.0)
    }

    private fun decodeChannelScaled(bytes: List<Int>, startIndex: Int): String {
        val decoded = decodeScaled(bytes, startIndex)
        return if (decoded == "0.0") "-" else decoded
    }

    private fun formatTime(hour: Int, minute: Int): String {
        return String.format("%02d:%02d", hour, minute)
    }

    private fun String.hexToBytes(): List<Int>? {
        val normalized = replace(" ", "").trim()
        if (normalized.length < 2 || normalized.length % 2 != 0) {
            return null
        }

        return normalized.chunked(2).map { chunk ->
            chunk.toIntOrNull(16) ?: return null
        }
    }
}