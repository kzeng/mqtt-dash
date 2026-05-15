MQTT-DASH
=========

## 项目简介

当前仓库已收口为原生 Android 实现。

应用使用 Kotlin/Compose 构建原生界面，通过 MQTT over WebSocket 连接设备，提供设备管理、实时遥测、控制参数读取与保存、调试指令发送、二维码扫码录入等能力。

## 开发与构建环境

| 项目 | 版本/路径 |
|------|-----------|
| 操作系统 | Windows 11 |
| Java | JDK 17 |
| Android SDK | `C:\Work\Apps\android-sdk` |
| Gradle | 8.8 |
| Android Gradle Plugin (AGP) | 8.3.0 |
| 目标 Android 版本 | 13 (API 34) |
| 最低 SDK | 21 (Android 5.0) |
| 构建工具 | Gradle (./gradlew assembleDebug) |

## 项目结构

```
mqtt-dash/
├── android-app/         # Android 项目
│   └── app/
│       └── src/main/
│           ├── java/com/example/mqttdashboard/
│           │   ├── nativeui/  # Compose 页面与 ViewModel
│           │   └── data/      # MQTT、设备配置、协议解析
│           ├── res/           # Android 资源
│           └── AndroidManifest.xml
├── test/
│   └── test-messages.py  # MQTT 消息发布测试脚本
└── README.md
```

## 原生架构说明

### `nativeui/`

`nativeui/` 负责原生界面、交互状态与页面编排。

- `NativeShellActivity.kt`
	- 应用启动 Activity。
	- 使用 Jetpack Compose 组织底部导航与五个主标签页：`Dashboard`、`Control`、`Device`、`Debug`、`About`。
	- 负责扫码、相册识别二维码、设备编辑保存等直接 UI 交互。
- `NativeShellViewModel.kt`
	- 聚合 MQTT 连接状态、遥测数据、历史曲线、控制参数、调试响应等 UI 所需状态。
	- 负责发送控制指令、读取参数、保存参数、发送自定义命令。
	- 负责把 MQTT 入站消息按当前选中设备过滤后，转换成 Compose 可消费的 `NativeShellMqttUiState`。

### `data/device/`

`data/device/` 负责设备列表与当前设备选择的持久化。

- `DeviceProfile.kt`
	- 定义设备模型，包含 `id` 和 `name`。
- `DevicePreferencesRepository.kt`
	- 基于 Jetpack DataStore 保存当前选中设备和设备列表。
	- 对外暴露两个核心流：
		- `selectedDevice`：当前选中设备。
		- `savedDevices`：已保存设备列表。
	- 提供 `saveSelectedDevice()`、`saveDevices()`、`saveDevicesAndSelection()`、`clearSelectedDevice()` 等接口。

### `data/mqtt/`

`data/mqtt/` 负责 MQTT 连接、Topic 路由、协议编解码与遥测解析。

- `PahoNativeMqttRepository.kt`
	- 封装 Paho MQTT Android/WebSocket 客户端。
	- 对外暴露：
		- `connectionState`：当前连接状态。
		- `incomingMessages`：收到的原始 MQTT 消息流。
	- 负责连接、订阅、重连恢复订阅、发布消息。
- `DeviceTopicRouter.kt`
	- 根据当前设备 ID 生成四个核心 Topic：
		- `dev/{id}/info`
		- `dev/{id}/state`
		- `dev/{id}/ctrl`
		- `dev/{id}/common_command`
- `DeviceTelemetryParser.kt`
	- 负责解析 `info` 与 `state` 主题消息，产出界面展示用的遥测数据。
- `DeviceControlSettingsCodec.kt`
	- 负责控制参数读写协议编解码。
	- 读取参数命令：`5503F900AA`
	- 写入参数命令前缀：`551BDA00...AA`
	- 读取参数响应前缀：`551bf900...aa`

## 设备选择与持久化流程

设备选择链路以 `DevicePreferencesRepository` 为中心。

1. `NativeShellActivity` 启动后创建 `DevicePreferencesRepository` 与 `NativeShellViewModel`。
2. `NativeShellScreen` 订阅 `savedDevices` 和 `selectedDevice` 两个 DataStore 流。
3. 用户在 `Device` 标签页中可以手动输入设备 ID、扫码录入、从相册识别二维码，或从已保存列表中直接选用设备。
4. 当用户保存或切换设备时，界面会调用 `saveDevicesAndSelection()` 或 `saveSelectedDevice()`，把设备列表和当前设备写入 DataStore。
5. `NativeShellViewModel.observeSelectedDevice()` 监听到设备变化后，会立即重置：
	 - 遥测缓存
	 - 历史曲线
	 - 控制参数
	 - 调试响应
6. 后续所有 MQTT 消息过滤和控制命令发送，都会基于新的 `selectedDevice.id` 继续执行。

这样做的好处是，设备选择既能跨启动持久保存，也能在切换时立即清理旧设备状态，避免界面混入上一台设备的数据。

## MQTT 连接与订阅流转

原生 MQTT 链路由 `PahoNativeMqttRepository` 和 `NativeShellViewModel` 协同完成。

1. `NativeShellViewModel` 初始化时调用 `connect()`。
2. `PahoNativeMqttRepository.connect()` 创建 `MqttAsyncClient`，设置 Paho 回调，并把连接状态置为 `Connecting`。
3. 连接成功后，仓库先订阅配置中的 `subscriptionTopic`，当前默认值是 `#`，订阅完成后才把状态置为 `Connected`。
4. 自动重连发生时，仓库会先把状态切回 `Connecting`，重新订阅成功后再恢复为 `Connected`。
5. `NativeShellViewModel.observeConnectionState()` 持续把仓库连接状态映射到 Compose 状态树。
6. `NativeShellViewModel.observeIncomingMessages()` 会对收到的消息做一次当前设备过滤：
	 - 只处理 `info`、`state`、`ctrl`、`common_command` 四类 Topic。
	 - 不属于当前选中设备的消息直接忽略。
7. `info` 消息会更新首页遥测和温湿度历史曲线。
8. `state` 消息会补充设备运行态字段。
9. `common_command` 消息会尝试走 `DeviceControlSettingsCodec.parseReadResponse()`，如果成功则更新控制参数表单和调试响应区域。

## 控制参数读取流转

控制参数读取已经按“减少第一次丢响应”的目标做过专门处理。

1. 用户在 `Control` 标签页点击“读取参数”。
2. `NativeShellViewModel.requestControlSettings()` 组装读命令 `5503F900AA`。
3. ViewModel 在发送前会检查：
	 - 当前是否已选择设备。
	 - MQTT 状态是否为 `Connected`。
4. 条件通过后，命令发布到 `dev/{id}/ctrl`。
5. 为了应对设备响应过快、重连后订阅刚恢复等临界时序，ViewModel 会为本次读取建立一个 request id，并在 1.5 秒后做一次静默自动补发。
6. 如果在补发前已经从 `dev/{id}/common_command` 收到合法的 `551bf900...aa` 响应，补发任务会被取消。
7. 解析成功后，UI 状态里的 `controlSettings` 会被更新，页面显示“参数已加载”。

这套机制主要用于解决此前常见的现象：第一次点击“读取参数”时偶发无数据，第二次点击才能拿到响应。

## 构建命令

```bash
cd android-app
./gradlew assembleDebug   # 生成 Debug APK
./gradlew clean           # 清理构建
```

## APK 输出

Debug APK 位于：
```
android-app/app/build/outputs/apk/debug/app-debug.apk
```

当前已验证 `clean assembleDebug` 会将 APK 稳定输出到以上目录。

## 主要功能

- MQTT WebSocket 连接（ws://118.31.36.131:9001）
- 原生仪表盘首页与历史曲线
- 控制参数读取、保存与状态回显
- 设备管理、扫码录入、相册识别二维码
- 调试命令发送、重启仪表、重启 ESP8266
- 空调状态与 WiFi 信号强度显示

## 注意事项

- 使用 `network_security_config.xml` 允许明文 `ws://` 连接
- 当前已移除经典 WebView/HTML 版本入口，Android 包仅保留原生实现
- 原生 MQTT 在自动重连后会先恢复订阅，再允许控制指令继续发送，避免第一次读取参数时响应被订阅时序吞掉
- 如果再次发现 APK 没有出现在 `android-app/app/build/outputs/apk/debug/app-debug.apk`，优先重新执行 `./gradlew clean assembleDebug` 并检查本地 SDK/JDK 配置