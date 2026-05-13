package com.example.mqttdashboard.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.devicePreferencesDataStore by preferencesDataStore(name = "device_preferences")

class DevicePreferencesRepository(
    private val context: Context
) {
    val selectedDevice: Flow<DeviceProfile> = context.devicePreferencesDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            DeviceProfile(
                id = preferences[Keys.DeviceId].orEmpty(),
                name = preferences[Keys.DeviceName].orEmpty()
            )
        }

    val savedDevices: Flow<List<DeviceProfile>> = context.devicePreferencesDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            parseDeviceList(preferences[Keys.DeviceListJson].orEmpty())
        }

    suspend fun saveSelectedDevice(deviceProfile: DeviceProfile) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[Keys.DeviceId] = deviceProfile.id
            preferences[Keys.DeviceName] = deviceProfile.name
        }
    }

    suspend fun saveDevices(devices: List<DeviceProfile>) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[Keys.DeviceListJson] = toDeviceListJson(devices)
        }
    }

    suspend fun saveDevicesAndSelection(devices: List<DeviceProfile>, selectedDevice: DeviceProfile) {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences[Keys.DeviceListJson] = toDeviceListJson(devices)
            preferences[Keys.DeviceId] = selectedDevice.id
            preferences[Keys.DeviceName] = selectedDevice.name
        }
    }

    fun buildDeviceListJson(devices: List<DeviceProfile>): String {
        return toDeviceListJson(devices)
    }

    suspend fun clearSelectedDevice() {
        context.devicePreferencesDataStore.edit { preferences ->
            preferences.remove(Keys.DeviceId)
            preferences.remove(Keys.DeviceName)
        }
    }

    private fun parseDeviceList(rawJson: String): List<DeviceProfile> {
        if (rawJson.isBlank()) {
            return emptyList()
        }

        val jsonArray = JSONArray(rawJson)
        val devices = mutableListOf<DeviceProfile>()
        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) {
                continue
            }
            devices += DeviceProfile(
                id = id,
                name = item.optString("name")
            )
        }
        return devices
    }

    private fun toDeviceListJson(devices: List<DeviceProfile>): String {
        val jsonArray = JSONArray()
        devices.forEach { device ->
            val jsonObject = JSONObject()
            jsonObject.put("id", device.id)
            jsonObject.put("name", device.name)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private object Keys {
        val DeviceId = stringPreferencesKey("selected_device_id")
        val DeviceName = stringPreferencesKey("selected_device_name")
        val DeviceListJson = stringPreferencesKey("device_list_json")
    }
}