package com.example.mqttdashboard.data.mqtt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PahoNativeMqttRepository(
    private val brokerConfig: MqttBrokerConfig = MqttBrokerConfig()
) : NativeMqttRepository {
    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Idle)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<MqttIncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<MqttIncomingMessage> = _incomingMessages.asSharedFlow()

    private var mqttClient: MqttAsyncClient? = null

    override suspend fun connect() {
        if (mqttClient?.isConnected == true) {
            return
        }

        _connectionState.value = MqttConnectionState.Connecting
        val client = buildClient().also { client ->
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (reconnect) {
                        _connectionState.value = MqttConnectionState.Connecting
                        resubscribeAfterReconnect(client)
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    _connectionState.value = MqttConnectionState.Disconnected(cause?.message)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) {
                        return
                    }
                    val payload = message.payload?.toString(StandardCharsets.UTF_8).orEmpty()
                    _incomingMessages.tryEmit(MqttIncomingMessage(topic = topic, payload = payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                }
            })
        }

        try {
            client.connectAwait(buildConnectOptions())
            client.subscribeAwait(brokerConfig.subscriptionTopic, 1)
            mqttClient = client
            _connectionState.value = MqttConnectionState.Connected
        } catch (error: Throwable) {
            _connectionState.value = MqttConnectionState.Failed(error.message ?: "MQTT connect failed")
            runCatching { client.close() }
            throw error
        }
    }

    override suspend fun disconnect() {
        val client = mqttClient ?: return
        runCatching {
            if (client.isConnected) {
                client.disconnectAwait()
            }
            client.close()
        }
        mqttClient = null
        _connectionState.value = MqttConnectionState.Disconnected()
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retained: Boolean) {
        val client = mqttClient ?: throw IllegalStateException("MQTT client is not connected")
        if (!client.isConnected) {
            throw IllegalStateException("MQTT client is not connected")
        }

        val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
            this.qos = qos
            isRetained = retained
        }
        client.publishAwait(topic, message)
    }

    private fun buildClient(): MqttAsyncClient {
        return MqttAsyncClient(
            brokerConfig.serverUri,
            "mqtt_dash_native_${UUID.randomUUID().toString().take(8)}",
            MemoryPersistence()
        )
    }

    private fun buildConnectOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = brokerConfig.cleanSession
            connectionTimeout = 5
            keepAliveInterval = 30
        }
    }

    private fun resubscribeAfterReconnect(client: MqttAsyncClient) {
        client.subscribe(brokerConfig.subscriptionTopic, 1, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                _connectionState.value = MqttConnectionState.Connected
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                _connectionState.value = MqttConnectionState.Failed(
                    exception?.message ?: "MQTT resubscribe failed"
                )
            }
        })
    }
}

private suspend fun MqttAsyncClient.connectAwait(options: MqttConnectOptions) {
    suspendCancellableCoroutine { continuation ->
        connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception ?: IllegalStateException("MQTT connect failed"))
                }
            }
        })
    }
}

private suspend fun MqttAsyncClient.subscribeAwait(topic: String, qos: Int) {
    suspendCancellableCoroutine { continuation ->
        subscribe(topic, qos, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception ?: IllegalStateException("MQTT subscribe failed"))
                }
            }
        })
    }
}

private suspend fun MqttAsyncClient.disconnectAwait() {
    suspendCancellableCoroutine { continuation ->
        disconnect(null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception ?: IllegalStateException("MQTT disconnect failed"))
                }
            }
        })
    }
}

private suspend fun MqttAsyncClient.publishAwait(topic: String, message: MqttMessage) {
    suspendCancellableCoroutine { continuation ->
        publish(topic, message, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception ?: IllegalStateException("MQTT publish failed"))
                }
            }
        })
    }
}