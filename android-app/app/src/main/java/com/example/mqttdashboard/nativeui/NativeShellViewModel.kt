package com.example.mqttdashboard.nativeui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mqttdashboard.data.device.DevicePreferencesRepository
import com.example.mqttdashboard.data.device.DeviceProfile
import com.example.mqttdashboard.data.mqtt.DeviceControlSettings
import com.example.mqttdashboard.data.mqtt.DeviceControlSettingsCodec
import com.example.mqttdashboard.data.mqtt.DeviceTelemetry
import com.example.mqttdashboard.data.mqtt.DeviceTelemetryParser
import com.example.mqttdashboard.data.mqtt.DeviceTopicRouter
import com.example.mqttdashboard.data.mqtt.MqttConnectionState
import com.example.mqttdashboard.data.mqtt.MqttIncomingMessage
import com.example.mqttdashboard.data.mqtt.NativeMqttRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class NativeShellMqttUiState(
    val selectedDevice: DeviceProfile = DeviceProfile(),
    val connectionState: MqttConnectionState = MqttConnectionState.Idle,
    val latestMessage: MqttIncomingMessage? = null,
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val temperatureHistory: List<Float> = emptyList(),
    val humidityHistory: List<Float> = emptyList(),
    val controlSettings: DeviceControlSettings = DeviceControlSettings(),
    val commandStatus: String = "",
    val commandStatusEventId: Long = 0L,
    val responseTopic: String = "",
    val responsePayload: String = ""
)

class NativeShellViewModel(
    private val devicePreferencesRepository: DevicePreferencesRepository,
    private val nativeMqttRepository: NativeMqttRepository
) : ViewModel() {
    private val _mqttUiState = MutableStateFlow(NativeShellMqttUiState())
    val mqttUiState: StateFlow<NativeShellMqttUiState> = _mqttUiState.asStateFlow()
    private var pendingControlSettingsRequestId: Long = 0L
    private var controlSettingsRetryJob: Job? = null

    init {
        observeSelectedDevice()
        observeConnectionState()
        observeIncomingMessages()
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            runCatching { nativeMqttRepository.connect() }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { nativeMqttRepository.disconnect() }
        }
    }

    fun toggleRunStop() {
        sendControlCommand(payload = "1", successMessage = "已发送 运行/停止 指令")
    }

    fun rebootDevice() {
        sendControlCommand(payload = "0", successMessage = "已发送 重启仪表 指令")
    }

    fun rebootEsp8266() {
        sendControlCommand(payload = "2", successMessage = "已发送 重启ESP8266 指令")
    }

    fun requestControlSettings() {
        val requestId = pendingControlSettingsRequestId + 1
        pendingControlSettingsRequestId = requestId
        controlSettingsRetryJob?.cancel()
        sendControlCommand(
            payload = DeviceControlSettingsCodec.buildReadCommand(),
            successMessage = "已发送 读取参数 指令",
            onSuccess = { topic, payload ->
                scheduleControlSettingsRetry(
                    requestId = requestId,
                    topic = topic,
                    payload = payload
                )
            }
        )
    }

    fun saveControlSettings(settings: DeviceControlSettings) {
        val payload = try {
            DeviceControlSettingsCodec.buildWriteCommand(settings)
        } catch (_: IllegalArgumentException) {
            null
        }

        if (payload == null) {
            updateCommandStatus("请先填写完整且合法的参数")
            return
        }

        _mqttUiState.value = _mqttUiState.value.copy(controlSettings = settings)
        sendControlCommand(payload = payload, successMessage = "已发送 保存参数 指令")
    }

    fun sendCustomCommand(rawCommand: String) {
        val command = rawCommand.replace("\\s+".toRegex(), "").uppercase()
        if (command.length < 4 || command.length % 2 != 0 || !command.startsWith("55") || !command.endsWith("AA")) {
            updateCommandStatus("命令格式错误！")
            return
        }

        sendControlCommand(
            payload = command,
            successMessage = "通信指令已下发: $command",
            clearResponse = true
        )
    }

    private fun observeSelectedDevice() {
        viewModelScope.launch {
            devicePreferencesRepository.selectedDevice.collectLatest { device ->
                controlSettingsRetryJob?.cancel()
                pendingControlSettingsRequestId = 0L
                _mqttUiState.value = _mqttUiState.value.copy(
                    selectedDevice = device,
                    telemetry = DeviceTelemetry(),
                    temperatureHistory = emptyList(),
                    humidityHistory = emptyList(),
                    controlSettings = DeviceControlSettings(),
                    commandStatus = "",
                    responseTopic = "",
                    responsePayload = ""
                )
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            nativeMqttRepository.connectionState.collectLatest { state ->
                _mqttUiState.value = _mqttUiState.value.copy(connectionState = state)
            }
        }
    }

    private fun observeIncomingMessages() {
        viewModelScope.launch {
            nativeMqttRepository.incomingMessages.collectLatest { message ->
                val selectedDeviceId = _mqttUiState.value.selectedDevice.id
                if (selectedDeviceId.isBlank()) {
                    _mqttUiState.value = _mqttUiState.value.copy(latestMessage = message)
                    return@collectLatest
                }

                val topicRouter = DeviceTopicRouter(selectedDeviceId)
                if (topicRouter.isForSelectedDevice(message.topic)) {
                    val parsedControlSettings = if (message.topic == topicRouter.commonCommandTopic) {
                        DeviceControlSettingsCodec.parseReadResponse(message.payload)
                    } else {
                        null
                    }
                    if (parsedControlSettings != null) {
                        pendingControlSettingsRequestId = 0L
                        controlSettingsRetryJob?.cancel()
                    }

                    val previousTelemetry = _mqttUiState.value.telemetry
                    val nextTelemetry = when (message.topic) {
                        topicRouter.infoTopic -> DeviceTelemetryParser.parseInfoPayload(message.payload, previousTelemetry)
                        topicRouter.stateTopic -> DeviceTelemetryParser.parseStatePayload(message.payload, previousTelemetry)
                        else -> previousTelemetry
                    }?.copy(lastTopic = message.topic) ?: previousTelemetry.copy(
                        lastTopic = message.topic,
                        lastPayload = message.payload
                    )

                    val nextTemperatureHistory = if (message.topic == topicRouter.infoTopic) {
                        appendHistorySample(_mqttUiState.value.temperatureHistory, nextTelemetry.temperature.toFloatOrNull())
                    } else {
                        _mqttUiState.value.temperatureHistory
                    }

                    val nextHumidityHistory = if (message.topic == topicRouter.infoTopic) {
                        appendHistorySample(_mqttUiState.value.humidityHistory, nextTelemetry.humidity.toFloatOrNull())
                    } else {
                        _mqttUiState.value.humidityHistory
                    }

                    _mqttUiState.value = _mqttUiState.value.copy(
                        latestMessage = message,
                        telemetry = nextTelemetry,
                        temperatureHistory = nextTemperatureHistory,
                        humidityHistory = nextHumidityHistory,
                        controlSettings = parsedControlSettings ?: _mqttUiState.value.controlSettings,
                        commandStatus = when {
                            parsedControlSettings != null -> "参数已加载"
                            else -> _mqttUiState.value.commandStatus
                        },
                        responseTopic = message.topic,
                        responsePayload = message.payload.uppercase()
                    )
                }
            }
        }
    }

    override fun onCleared() {
        controlSettingsRetryJob?.cancel()
        viewModelScope.launch {
            runCatching { nativeMqttRepository.disconnect() }
        }
        super.onCleared()
    }

    private fun sendControlCommand(
        payload: String,
        successMessage: String,
        clearResponse: Boolean = false,
        onSuccess: ((topic: String, payload: String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val currentState = _mqttUiState.value
            val selectedDeviceId = currentState.selectedDevice.id
            if (selectedDeviceId.isBlank()) {
                updateCommandStatus("请先选择设备")
                return@launch
            }

            if (currentState.connectionState != MqttConnectionState.Connected) {
                updateCommandStatus("MQTT 连接或订阅尚未就绪，请稍后重试")
                return@launch
            }

            val topic = DeviceTopicRouter(selectedDeviceId).controlTopic
            val result = runCatching {
                nativeMqttRepository.publish(topic = topic, payload = payload)
            }
            _mqttUiState.value = currentState.copy(
                commandStatus = result.fold(
                    onSuccess = { successMessage },
                    onFailure = { error -> error.message ?: "指令发送失败" }
                ),
                commandStatusEventId = currentState.commandStatusEventId + 1,
                responseTopic = if (clearResponse) "" else currentState.responseTopic,
                responsePayload = if (clearResponse) "" else currentState.responsePayload
            )
            if (result.isSuccess) {
                onSuccess?.invoke(topic, payload)
            }
        }
    }

    private fun scheduleControlSettingsRetry(requestId: Long, topic: String, payload: String) {
        controlSettingsRetryJob = viewModelScope.launch {
            delay(1500)
            if (pendingControlSettingsRequestId != requestId) {
                return@launch
            }
            if (_mqttUiState.value.connectionState != MqttConnectionState.Connected) {
                return@launch
            }
            runCatching {
                nativeMqttRepository.publish(topic = topic, payload = payload)
            }
        }
    }

    private fun updateCommandStatus(status: String) {
        val currentState = _mqttUiState.value
        _mqttUiState.value = currentState.copy(
            commandStatus = status,
            commandStatusEventId = currentState.commandStatusEventId + 1
        )
    }

    private fun appendHistorySample(history: List<Float>, value: Float?): List<Float> {
        if (value == null) {
            return history
        }
        return (history + value).takeLast(12)
    }
}

class NativeShellViewModelFactory(
    private val devicePreferencesRepository: DevicePreferencesRepository,
    private val nativeMqttRepository: NativeMqttRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NativeShellViewModel::class.java)) {
            return NativeShellViewModel(devicePreferencesRepository, nativeMqttRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}