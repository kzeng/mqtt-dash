package com.example.mqttdashboard.nativeui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.mqttdashboard.MainActivity
import com.example.mqttdashboard.R
import com.example.mqttdashboard.data.device.DevicePreferencesRepository
import com.example.mqttdashboard.data.device.DeviceProfile
import com.example.mqttdashboard.data.mqtt.ChannelSnapshot
import com.example.mqttdashboard.data.mqtt.DeviceControlSettings
import com.example.mqttdashboard.data.mqtt.DeviceTelemetry
import com.example.mqttdashboard.data.mqtt.MqttConnectionState
import com.example.mqttdashboard.data.mqtt.PahoNativeMqttRepository
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.IOException
import kotlinx.coroutines.launch

private const val DEVICE_LIMIT = 6

private enum class NativeHomeTab {
    Dashboard,
    Control,
    Device,
    Debug,
    About
}

class NativeShellActivity : ComponentActivity() {
    private lateinit var repository: DevicePreferencesRepository
    private lateinit var viewModel: NativeShellViewModel
    private var returnResultToCaller: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DevicePreferencesRepository(applicationContext)
        returnResultToCaller = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false)
        viewModel = ViewModelProvider(
            this,
            NativeShellViewModelFactory(
                devicePreferencesRepository = repository,
                nativeMqttRepository = PahoNativeMqttRepository()
            )
        )[NativeShellViewModel::class.java]
        setContent {
            MaterialTheme {
                NativeShellScreen(
                    repository = repository,
                    mqttViewModel = viewModel,
                    onComplete = { selectedDevice, devices ->
                        persistDeviceSelection(selectedDevice, devices)
                    },
                    onOpenWebDashboard = {
                        openWebDashboard()
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
                        Card(modifier = Modifier.fillMaxWidth()) {
    }

    private fun persistDeviceSelection(selectedDevice: DeviceProfile, devices: List<DeviceProfile>) {
        lifecycleScope.launch {
            repository.saveDevicesAndSelection(devices, selectedDevice)
            if (returnResultToCaller) {
                val result = Intent().apply {
                    putExtra(EXTRA_SELECTED_DEVICE_ID, selectedDevice.id)
                    putExtra(EXTRA_SELECTED_DEVICE_NAME, selectedDevice.name)
                    putExtra(EXTRA_DEVICE_LIST_JSON, repository.buildDeviceListJson(devices))
                }
                setResult(Activity.RESULT_OK, result)
                finish()
            } else {
                Toast.makeText(this@NativeShellActivity, "设备已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openWebDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_SELECTED_DEVICE_ID = "selected_device_id"
        const val EXTRA_SELECTED_DEVICE_NAME = "selected_device_name"
        const val EXTRA_DEVICE_LIST_JSON = "device_list_json"
        const val EXTRA_RETURN_RESULT = "return_result"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeShellScreen(
    repository: DevicePreferencesRepository,
    mqttViewModel: NativeShellViewModel,
    onComplete: (DeviceProfile, List<DeviceProfile>) -> Unit,
    onOpenWebDashboard: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedDevices by repository.savedDevices.collectAsState(initial = emptyList())
    val selectedDevice by repository.selectedDevice.collectAsState(initial = DeviceProfile())
    val mqttUiState by mqttViewModel.mqttUiState.collectAsState()
    var deviceId by remember(selectedDevice.id) { mutableStateOf(selectedDevice.id) }
    var deviceName by remember(selectedDevice.name) { mutableStateOf(selectedDevice.name) }
    var controlSettings by remember { mutableStateOf(DeviceControlSettings()) }
    var customCommand by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(NativeHomeTab.Dashboard) }
    var message by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let {
            deviceId = it
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        val decoded = decodeQrFromUri(context, uri)
        if (decoded.isNullOrBlank()) {
            message = "未识别到二维码，请更换清晰图片重试"
        } else {
            deviceId = decoded
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScanner(scanLauncher)
        } else {
            message = "未授予相机权限，无法扫码"
        }
    }

    LaunchedEffect(message) {
        val toastMessage = message ?: return@LaunchedEffect
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        message = null
    }

    LaunchedEffect(mqttUiState.controlSettings) {
        if (mqttUiState.controlSettings.hasValues) {
            controlSettings = mqttUiState.controlSettings
        }
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        bottomBar = {
            NavigationBar {
                NativeHomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            when (tab) {
                                NativeHomeTab.Dashboard -> Icon(Icons.Filled.Home, contentDescription = "首页")
                                NativeHomeTab.Control -> Icon(Icons.Filled.Tune, contentDescription = "控制")
                                NativeHomeTab.Device -> Icon(Icons.Filled.Link, contentDescription = "连接")
                                NativeHomeTab.Debug -> Icon(Icons.Filled.BugReport, contentDescription = "调试")
                                NativeHomeTab.About -> Icon(Icons.Filled.Info, contentDescription = "关于")
                            }
                        },
                        label = {
                            Text(
                                text = when (tab) {
                                    NativeHomeTab.Dashboard -> "首页"
                                    NativeHomeTab.Control -> "控制"
                                    NativeHomeTab.Device -> "连接"
                                    NativeHomeTab.Debug -> "调试"
                                    NativeHomeTab.About -> "关于"
                                }
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NativeShellContent(
            innerPadding = innerPadding,
            currentTab = currentTab,
            deviceId = deviceId,
            deviceName = deviceName,
            savedDevices = savedDevices,
            mqttUiState = mqttUiState,
            temperatureHistory = mqttUiState.temperatureHistory,
            humidityHistory = mqttUiState.humidityHistory,
            onOpenWebDashboard = onOpenWebDashboard,
            onDeviceIdChange = { deviceId = it },
            onDeviceNameChange = { deviceName = it },
            onScanClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchScanner(scanLauncher)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onPickImageClick = {
                imagePickerLauncher.launch("image/*")
            },
            onSaveClick = {
                val normalizedId = deviceId.trim()
                val normalizedName = deviceName.trim()
                if (normalizedId.isBlank()) {
                    message = "请先填写或扫码录入设备 ID"
                } else {
                    val updatedDevices = savedDevices.toMutableList()
                    val existingIndex = updatedDevices.indexOfFirst { it.id == normalizedId }
                    if (existingIndex >= 0) {
                        updatedDevices[existingIndex] = DeviceProfile(normalizedId, normalizedName)
                    } else {
                        if (updatedDevices.size >= DEVICE_LIMIT) {
                            message = "设备列表已满，最多只能添加6个设备"
                            return@NativeShellContent
                        }
                        updatedDevices.add(DeviceProfile(normalizedId, normalizedName))
                    }
                    onComplete(DeviceProfile(normalizedId, normalizedName), updatedDevices)
                }
            },
            onUseDevice = { device ->
                onComplete(device, savedDevices)
            },
            onEditDevice = { device ->
                deviceId = device.id
                deviceName = device.name
            },
            onDeleteDevice = { device ->
                coroutineScope.launch {
                    val updatedDevices = savedDevices.filterNot { it.id == device.id }
                    repository.saveDevices(updatedDevices)
                    if (selectedDevice.id == device.id) {
                        val nextSelected = updatedDevices.firstOrNull() ?: DeviceProfile()
                        if (nextSelected.id.isBlank()) {
                            repository.clearSelectedDevice()
                            deviceId = ""
                            deviceName = ""
                        } else {
                            repository.saveSelectedDevice(nextSelected)
                            deviceId = nextSelected.id
                            deviceName = nextSelected.name
                        }
                    }
                }
            },
            onReconnectClick = {
                mqttViewModel.connect()
            },
            onDisconnectClick = {
                mqttViewModel.disconnect()
            },
            onToggleRunStopClick = {
                mqttViewModel.toggleRunStop()
            },
            onRebootDeviceClick = {
                mqttViewModel.rebootDevice()
            },
            onRebootEspClick = {
                mqttViewModel.rebootEsp8266()
            },
            controlSettings = controlSettings,
            onControlSettingsChange = {
                controlSettings = it
            },
            onRequestControlSettingsClick = {
                mqttViewModel.requestControlSettings()
            },
            onSaveControlSettingsClick = {
                mqttViewModel.saveControlSettings(controlSettings)
            },
            customCommand = customCommand,
            onCustomCommandChange = {
                customCommand = it
            },
            onSendCustomCommandClick = {
                mqttViewModel.sendCustomCommand(customCommand)
            }
        )
    }
}

@Composable
private fun NativeShellContent(
    innerPadding: PaddingValues,
    currentTab: NativeHomeTab,
    deviceId: String,
    deviceName: String,
    savedDevices: List<DeviceProfile>,
    mqttUiState: NativeShellMqttUiState,
    temperatureHistory: List<Float>,
    humidityHistory: List<Float>,
    onOpenWebDashboard: () -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onPickImageClick: () -> Unit,
    onSaveClick: () -> Unit,
    onUseDevice: (DeviceProfile) -> Unit,
    onEditDevice: (DeviceProfile) -> Unit,
    onDeleteDevice: (DeviceProfile) -> Unit,
    onReconnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onToggleRunStopClick: () -> Unit,
    onRebootDeviceClick: () -> Unit,
    onRebootEspClick: () -> Unit,
    controlSettings: DeviceControlSettings,
    onControlSettingsChange: (DeviceControlSettings) -> Unit,
    onRequestControlSettingsClick: () -> Unit,
    onSaveControlSettingsClick: () -> Unit,
    customCommand: String,
    onCustomCommandChange: (String) -> Unit,
    onSendCustomCommandClick: () -> Unit
) {
    when (currentTab) {
        NativeHomeTab.Dashboard -> DashboardTabContent(
            innerPadding = innerPadding,
            mqttUiState = mqttUiState,
            temperatureHistory = temperatureHistory,
            humidityHistory = humidityHistory
        )

        NativeHomeTab.Control -> ControlTabContent(
            innerPadding = innerPadding,
            mqttUiState = mqttUiState,
            controlSettings = controlSettings,
            onControlSettingsChange = onControlSettingsChange,
            onRequestControlSettingsClick = onRequestControlSettingsClick,
            onSaveControlSettingsClick = onSaveControlSettingsClick,
            onReconnectClick = onReconnectClick,
            onDisconnectClick = onDisconnectClick,
            onToggleRunStopClick = onToggleRunStopClick,
            onRebootDeviceClick = onRebootDeviceClick,
            onRebootEspClick = onRebootEspClick
        )

        NativeHomeTab.Device -> DeviceTabContent(
            innerPadding = innerPadding,
            deviceId = deviceId,
            deviceName = deviceName,
            savedDevices = savedDevices,
            onDeviceIdChange = onDeviceIdChange,
            onDeviceNameChange = onDeviceNameChange,
            onScanClick = onScanClick,
            onPickImageClick = onPickImageClick,
            onSaveClick = onSaveClick,
            onUseDevice = onUseDevice,
            onEditDevice = onEditDevice,
            onDeleteDevice = onDeleteDevice
        )

        NativeHomeTab.Debug -> DebugTabContent(
            innerPadding = innerPadding,
            mqttUiState = mqttUiState,
            customCommand = customCommand,
            onCustomCommandChange = onCustomCommandChange,
            onSendCustomCommandClick = onSendCustomCommandClick,
            onOpenWebDashboard = onOpenWebDashboard
        )

        NativeHomeTab.About -> AboutTabContent(innerPadding = innerPadding)
    }
}

@Composable
private fun DashboardTabContent(
    innerPadding: PaddingValues,
    mqttUiState: NativeShellMqttUiState,
    temperatureHistory: List<Float>,
    humidityHistory: List<Float>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DashboardHeroCard(mqttUiState = mqttUiState)
        }
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mqttUiState.telemetry.hasSnapshot) {
                        DashboardSummaryGrid(telemetry = mqttUiState.telemetry)
                        DashboardDetailGrid(telemetry = mqttUiState.telemetry)
                        TrendSection(
                            temperatureHistory = temperatureHistory,
                            humidityHistory = humidityHistory
                        )
                        ChannelSection(telemetry = mqttUiState.telemetry)
                        TelemetrySection(telemetry = mqttUiState.telemetry)
                    } else {
                        WaitingTelemetryState()
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingTelemetryState() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "等待设备上报实时数据...", style = MaterialTheme.typography.bodyLarge)
        CircularProgressIndicator(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(18.dp),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ControlTabContent(
    innerPadding: PaddingValues,
    mqttUiState: NativeShellMqttUiState,
    controlSettings: DeviceControlSettings,
    onControlSettingsChange: (DeviceControlSettings) -> Unit,
    onRequestControlSettingsClick: () -> Unit,
    onSaveControlSettingsClick: () -> Unit,
    onReconnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onToggleRunStopClick: () -> Unit,
    onRebootDeviceClick: () -> Unit,
    onRebootEspClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onReconnectClick, modifier = Modifier.weight(1f)) {
                            Text("连接")
                        }
                        OutlinedButton(onClick = onDisconnectClick, modifier = Modifier.weight(1f)) {
                            Text("断开")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onToggleRunStopClick, modifier = Modifier.weight(1f)) {
                            Text("运行/停止")
                        }
                        OutlinedButton(onClick = onRebootDeviceClick, modifier = Modifier.weight(1f)) {
                            Text("重启仪表")
                        }
                    }
                    OutlinedButton(onClick = onRebootEspClick, modifier = Modifier.fillMaxWidth()) {
                        Text("重启ESP8266")
                    }
                    if (mqttUiState.commandStatus.isNotBlank()) {
                        Text(text = "命令状态: ${mqttUiState.commandStatus}")
                    }
                }
            }
        }
        item {
            ControlSettingsCard(
                settings = controlSettings,
                commandStatus = mqttUiState.commandStatus,
                onSettingsChange = onControlSettingsChange,
                onRequestClick = onRequestControlSettingsClick,
                onSaveClick = onSaveControlSettingsClick
            )
        }
    }
}

@Composable
private fun DeviceTabContent(
    innerPadding: PaddingValues,
    deviceId: String,
    deviceName: String,
    savedDevices: List<DeviceProfile>,
    onDeviceIdChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onPickImageClick: () -> Unit,
    onSaveClick: () -> Unit,
    onUseDevice: (DeviceProfile) -> Unit,
    onEditDevice: (DeviceProfile) -> Unit,
    onDeleteDevice: (DeviceProfile) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = onDeviceIdChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("仪表ID") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = onDeviceNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onScanClick, modifier = Modifier.weight(1f)) {
                            Text("扫码录入")
                        }
                        OutlinedButton(onClick = onPickImageClick, modifier = Modifier.weight(1f)) {
                            Text("相册识别")
                        }
                    }
                    Button(onClick = onSaveClick, modifier = Modifier.fillMaxWidth()) {
                        Text("保存并使用")
                    }
                }
            }
        }
        item {
            SectionLabel(title = "已保存设备")
        }
        items(savedDevices, key = { it.id }) { device ->
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = device.name.ifBlank { "未命名设备" }, style = MaterialTheme.typography.titleMedium)
                    Text(text = device.id, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onUseDevice(device) }, modifier = Modifier.weight(1f)) {
                            Text("使用")
                        }
                        OutlinedButton(onClick = { onEditDevice(device) }, modifier = Modifier.weight(1f)) {
                            Text("编辑")
                        }
                        OutlinedButton(onClick = { onDeleteDevice(device) }, modifier = Modifier.weight(1f)) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTabContent(
    innerPadding: PaddingValues,
    mqttUiState: NativeShellMqttUiState,
    customCommand: String,
    onCustomCommandChange: (String) -> Unit,
    onSendCustomCommandClick: () -> Unit,
    onOpenWebDashboard: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DebugCommandCard(
                customCommand = customCommand,
                responseTopic = mqttUiState.responseTopic,
                responsePayload = mqttUiState.responsePayload,
                commandStatus = mqttUiState.commandStatus,
                onCustomCommandChange = onCustomCommandChange,
                onSendClick = onSendCustomCommandClick
            )
        }
        item {
            LegacyFallbackCard(onOpenWebDashboard = onOpenWebDashboard)
        }
    }
}

@Composable
private fun AboutTabContent(innerPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AboutCard()
        }
    }
}

@Composable
private fun DashboardHeroCard(mqttUiState: NativeShellMqttUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "温湿度控制记录仪客户端", style = MaterialTheme.typography.titleLarge)
            Text(text = "当前设备: ${mqttUiState.selectedDevice.id.ifBlank { "未选择" }}")
            Text(text = "连接状态: ${mqttConnectionLabel(mqttUiState.connectionState)}")
        }
    }
}

@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "应用 Logo",
                    modifier = Modifier.size(72.dp)
                )
            }
            Text(
                text = "温湿度控制记录仪客户端",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "V3.0.0",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "武汉兴达森科技有限公司 ©2026",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TelemetrySection(telemetry: DeviceTelemetry) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "空调模块: ${telemetry.airConditionerState}")
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun DashboardSummaryGrid(telemetry: DeviceTelemetry) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryTile(label = "温度", value = telemetry.temperature, modifier = Modifier.weight(1f))
        SummaryTile(label = "湿度", value = telemetry.humidity, modifier = Modifier.weight(1f))
        SummaryTile(
            label = "仪表",
            value = telemetry.running?.let { if (it) "运行" else "停止" } ?: "-",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DashboardDetailGrid(telemetry: DeviceTelemetry) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryTile(
                label = "目标温度",
                value = formatTargetWithDeviation(
                    target = telemetry.targetTemperature,
                    deviation = telemetry.temperatureDeviation
                ),
                modifier = Modifier.weight(1f)
            )
            SummaryTile(
                label = "目标湿度",
                value = formatTargetWithDeviation(
                    target = telemetry.targetHumidity,
                    deviation = telemetry.humidityDeviation
                ),
                modifier = Modifier.weight(1f)
            )
            SummaryTile(
                label = "设备时间",
                value = telemetry.deviceTime.ifBlank { "-" },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryTile(
                label = "控制模式",
                value = telemetry.controlMode,
                modifier = Modifier.weight(1f)
            )
            SummaryTile(
                label = "控制状态",
                value = telemetry.controlStatus,
                modifier = Modifier.weight(1f)
            )
            SummaryTile(
                label = "Wi-Fi",
                value = telemetry.wifiQuality?.let { "$it%" } ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun TrendSection(temperatureHistory: List<Float>, humidityHistory: List<Float>) {
    if (temperatureHistory.isEmpty() && humidityHistory.isEmpty()) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "最近趋势", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TrendCard(label = "温度", values = temperatureHistory, modifier = Modifier.weight(1f))
            TrendCard(label = "湿度", values = humidityHistory, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrendCard(label: String, values: List<Float>, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            SparklineChart(values = values)
            Text(
                text = values.lastOrNull()?.let { String.format("最新 %.1f", it) } ?: "暂无数据",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SparklineChart(values: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        if (values.size < 2) {
            return@Canvas
        }

        val minValue = values.minOrNull() ?: return@Canvas
        val maxValue = values.maxOrNull() ?: return@Canvas
        val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)

        values.zipWithNext().forEachIndexed { index, (startValue, endValue) ->
            val start = Offset(
                x = index * stepX,
                y = size.height - (((startValue - minValue) / range) * size.height)
            )
            val end = Offset(
                x = (index + 1) * stepX,
                y = size.height - (((endValue - minValue) / range) * size.height)
            )
            drawLine(
                color = lineColor,
                start = start,
                end = end,
                strokeWidth = 6f
            )
        }
    }
}

@Composable
private fun ChannelSection(telemetry: DeviceTelemetry) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "通道状态", style = MaterialTheme.typography.titleMedium)
        telemetry.channels.chunked(2).forEach { rowChannels ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowChannels.forEach { channel ->
                    ChannelCard(channel = channel, modifier = Modifier.weight(1f))
                }
                if (rowChannels.size == 1) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: ChannelSnapshot, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${channel.label} ${if (channel.active) "●" else "○"}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(text = "温度 ${channel.temperature}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "湿度 ${channel.humidity}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LegacyFallbackCard(onOpenWebDashboard: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "经典版本工具", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "仅在原生页尚未覆盖的少量功能需要时使用，默认操作请留在当前原生界面。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onOpenWebDashboard, modifier = Modifier.fillMaxWidth()) {
                Text("打开经典版本")
            }
        }
    }
}

@Composable
private fun MetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = leftLabel, style = MaterialTheme.typography.labelMedium)
            Text(text = leftValue, style = MaterialTheme.typography.bodyLarge)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = rightLabel, style = MaterialTheme.typography.labelMedium)
            Text(text = rightValue, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatTargetWithDeviation(target: String, deviation: String): String {
    val normalizedTarget = target.ifBlank { "-" }
    val normalizedDeviation = deviation.ifBlank { "-" }
    return if (normalizedTarget == "-" && normalizedDeviation == "-") {
        "-"
    } else {
        "$normalizedTarget ± $normalizedDeviation"
    }
}

@Composable
private fun ControlSettingsCard(
    settings: DeviceControlSettings,
    commandStatus: String,
    onSettingsChange: (DeviceControlSettings) -> Unit,
    onRequestClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "控制参数", style = MaterialTheme.typography.titleMedium)
            ParameterRow(
                leftLabel = "目标温度",
                leftValue = settings.targetTemperature,
                onLeftChange = { onSettingsChange(settings.copy(targetTemperature = it)) },
                rightLabel = "目标湿度",
                rightValue = settings.targetHumidity,
                onRightChange = { onSettingsChange(settings.copy(targetHumidity = it)) }
            )
            ParameterRow(
                leftLabel = "温度回差",
                leftValue = settings.controlTemperatureDeviation,
                onLeftChange = { onSettingsChange(settings.copy(controlTemperatureDeviation = it)) },
                rightLabel = "湿度回差",
                rightValue = settings.controlHumidityDeviation,
                onRightChange = { onSettingsChange(settings.copy(controlHumidityDeviation = it)) }
            )
            ParameterRow(
                leftLabel = "温控时间",
                leftValue = settings.controlTemperatureTime,
                onLeftChange = { onSettingsChange(settings.copy(controlTemperatureTime = it)) },
                rightLabel = "湿控时间",
                rightValue = settings.controlHumidityTime,
                onRightChange = { onSettingsChange(settings.copy(controlHumidityTime = it)) }
            )
            ParameterRow(
                leftLabel = "加热时间",
                leftValue = settings.heatingTime,
                onLeftChange = { onSettingsChange(settings.copy(heatingTime = it)) },
                rightLabel = "加湿时间",
                rightValue = settings.humidificationTime,
                onRightChange = { onSettingsChange(settings.copy(humidificationTime = it)) }
            )
            ParameterRow(
                leftLabel = "化霜时间",
                leftValue = settings.defrostTime,
                onLeftChange = { onSettingsChange(settings.copy(defrostTime = it)) },
                rightLabel = "停止时间",
                rightValue = settings.stopTime,
                onRightChange = { onSettingsChange(settings.copy(stopTime = it)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRequestClick, modifier = Modifier.weight(1f)) {
                    Text("读取参数")
                }
                Button(onClick = onSaveClick, modifier = Modifier.weight(1f)) {
                    Text("保存参数")
                }
            }
            if (commandStatus.isNotBlank()) {
                Text(text = "参数状态: $commandStatus")
            }
        }
    }
}

@Composable
private fun ParameterRow(
    leftLabel: String,
    leftValue: String,
    onLeftChange: (String) -> Unit,
    rightLabel: String,
    rightValue: String,
    onRightChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = leftValue,
            onValueChange = onLeftChange,
            modifier = Modifier.weight(1f),
            label = { Text(leftLabel) },
            singleLine = true
        )
        OutlinedTextField(
            value = rightValue,
            onValueChange = onRightChange,
            modifier = Modifier.weight(1f),
            label = { Text(rightLabel) },
            singleLine = true
        )
    }
}

@Composable
private fun DebugCommandCard(
    customCommand: String,
    responseTopic: String,
    responsePayload: String,
    commandStatus: String,
    onCustomCommandChange: (String) -> Unit,
    onSendClick: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "调试指令", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = customCommand,
                onValueChange = onCustomCommandChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("指令") },
                placeholder = { Text("输入指令") },
                singleLine = true
            )
            Button(onClick = onSendClick, modifier = Modifier.fillMaxWidth()) {
                Text("发送指令")
            }
            if (commandStatus.isNotBlank()) {
                Text(text = "调试状态: $commandStatus")
            }
            Text(text = "响应主题: ${responseTopic.ifBlank { "-" }}")
            OutlinedTextField(
                value = responsePayload,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("响应信息") },
                placeholder = { Text("响应信息将显示在这里") },
                minLines = 6,
                readOnly = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NativeShellPreview() {
    MaterialTheme {
        NativeShellContent(
            innerPadding = PaddingValues(0.dp),
            currentTab = NativeHomeTab.Dashboard,
            deviceId = "11:22:33:44:55:66",
            deviceName = "示例设备",
            savedDevices = listOf(DeviceProfile("11:22:33:44:55:66", "示例设备")),
            mqttUiState = NativeShellMqttUiState(
                selectedDevice = DeviceProfile("11:22:33:44:55:66", "示例设备"),
                connectionState = MqttConnectionState.Connected,
                telemetry = DeviceTelemetry(
                    deviceTime = "11:28",
                    running = true,
                    temperature = "23.4",
                    humidity = "65.0",
                    targetTemperature = "24.0",
                    targetHumidity = "60.0",
                    temperatureDeviation = "1.0",
                    humidityDeviation = "3.0",
                    controlMode = "自动 / 节能",
                    controlStatus = "制冷",
                    airConditionerState = "运行中",
                    wifiQuality = 88,
                    lastTopic = "dev/11:22:33:44:55:66/info"
                ),
                temperatureHistory = listOf(21.3f, 21.8f, 22.1f, 22.7f, 23.0f, 23.4f),
                humidityHistory = listOf(70.0f, 68.5f, 67.3f, 66.2f, 65.8f, 65.0f)
            ),
            temperatureHistory = listOf(21.3f, 21.8f, 22.1f, 22.7f, 23.0f, 23.4f),
            humidityHistory = listOf(70.0f, 68.5f, 67.3f, 66.2f, 65.8f, 65.0f),
            onOpenWebDashboard = {},
            onDeviceIdChange = {},
            onDeviceNameChange = {},
            onScanClick = {},
            onPickImageClick = {},
            onSaveClick = {},
            onUseDevice = {},
            onEditDevice = {},
            onDeleteDevice = {},
            onReconnectClick = {},
            onDisconnectClick = {},
            onToggleRunStopClick = {},
            onRebootDeviceClick = {},
            onRebootEspClick = {},
            controlSettings = DeviceControlSettings(
                targetTemperature = "24.0",
                targetHumidity = "60.0",
                controlTemperatureDeviation = "1.0",
                controlHumidityDeviation = "3.0",
                controlTemperatureTime = "3",
                controlHumidityTime = "3",
                heatingTime = "40",
                humidificationTime = "50",
                defrostTime = "3",
                stopTime = "40"
            ),
            onControlSettingsChange = {},
            onRequestControlSettingsClick = {},
            onSaveControlSettingsClick = {},
            customCommand = "5503F900AA",
            onCustomCommandChange = {},
            onSendCustomCommandClick = {}
        )
    }
}

private fun mqttConnectionLabel(state: MqttConnectionState): String {
    return when (state) {
        MqttConnectionState.Idle -> "未连接"
        MqttConnectionState.Connecting -> "连接中"
        MqttConnectionState.Connected -> "已连接"
        is MqttConnectionState.Disconnected -> "已断开"
        is MqttConnectionState.Failed -> "连接失败"
    }
}

private fun launchScanner(scanLauncher: androidx.activity.result.ActivityResultLauncher<ScanOptions>) {
    val options = ScanOptions()
    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    options.setPrompt("将设备二维码放入取景框内")
    options.setBeepEnabled(false)
    options.setOrientationLocked(false)
    scanLauncher.launch(options)
}

private fun decodeQrFromUri(context: android.content.Context, uri: Uri): String? {
    return try {
        val bitmap = loadBitmapFromUri(context, uri) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binaryBitmap).text
    } catch (_: NotFoundException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val maxEdge = maxOf(width, height)
            if (maxEdge > 1600) {
                val scale = 1600f / maxEdge
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMutableRequired(false)
        }
    } else {
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}