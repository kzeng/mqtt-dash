package com.example.mqttdashboard.data.mqtt

class DeviceTopicRouter(
    private val deviceId: String
) {
    val infoTopic: String = "dev/$deviceId/info"
    val stateTopic: String = "dev/$deviceId/state"
    val controlTopic: String = "dev/$deviceId/ctrl"
    val commonCommandTopic: String = "dev/$deviceId/common_command"

    fun isForSelectedDevice(topic: String): Boolean {
        return topic == infoTopic ||
            topic == stateTopic ||
            topic == controlTopic ||
            topic == commonCommandTopic
    }
}