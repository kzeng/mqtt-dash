package com.example.mqttdashboard.data.mqtt

data class MqttBrokerConfig(
    val host: String = "118.31.36.131",
    val port: Int = 9001,
    val path: String = "/",
    val useTls: Boolean = false,
    val cleanSession: Boolean = true,
    val reconnectTimeoutMillis: Long = 5_000L,
    val subscriptionTopic: String = "#"
) {
    val serverUri: String
        get() {
            val scheme = if (useTls) "wss" else "ws"
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            return "$scheme://$host:$port$normalizedPath"
        }
}