package dev.mrwick.redline.ui.settings

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.notifications.AutoReplyController
import dev.mrwick.redline.ui.theme.GixxerTokens
import kotlin.math.roundToInt

@Composable
internal fun SpeedWarningSection(vm: SettingsViewModel) {
    val limit by vm.speedLimitKmh.collectAsStateWithLifecycle()
    SettingsSection("Speed warning") {
        Text(
            "Sends your limit to the cluster, which warns when you exceed it (as in Ride Connect). " +
                "The cluster draws its own warning.",
            style = MaterialTheme.typography.bodySmall,
        )
        AlertSwitchRow("Warn above a speed", limit > 0) { on ->
            vm.setSpeedLimitKmh(if (on) Settings.DEFAULT_SPEED_LIMIT_KMH else 0)
        }
        if (limit > 0) {
            var slider by remember(limit) { mutableFloatStateOf(limit.toFloat()) }
            Text("Limit: ${slider.roundToInt()} km/h", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { vm.setSpeedLimitKmh(slider.roundToInt()) },
                valueRange = Settings.MIN_SPEED_LIMIT_KMH.toFloat()..Settings.MAX_SPEED_LIMIT_KMH.toFloat(),
                steps = Settings.MAX_SPEED_LIMIT_KMH - Settings.MIN_SPEED_LIMIT_KMH - 1,
            )
        }
    }
}

@Composable
internal fun RideAlertsSection(vm: SettingsViewModel) {
    val voice by vm.voiceAlerts.collectAsStateWithLifecycle()
    val rain by vm.rainAlertNav.collectAsStateWithLifecycle()
    val calendar by vm.calendarAlerts.collectAsStateWithLifecycle()
    SettingsSection("Ride alerts") {
        AlertSwitchRow("Spoken alerts (connected, disconnected, low fuel)", voice, vm::setVoiceAlerts)
        AlertSwitchRow("Rain alert during navigation", rain, vm::setRainAlertNav)
        Text(
            "When the forecast is rainy, the turn arrow alternates with a rain icon for 10 seconds " +
                "at the start of navigation, and again if rain starts.",
            style = MaterialTheme.typography.bodySmall,
        )
        AlertSwitchRow("Calendar and meeting alerts", calendar, vm::setCalendarAlerts)
        Text(
            "Calendar reminders and meeting-app events show the cluster's meeting alert. " +
                "Requires notification access.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun AutoReplySection(vm: SettingsViewModel) {
    val enabled by vm.autoReplyEnabled.collectAsStateWithLifecycle()
    val message by vm.autoReplyMessage.collectAsStateWithLifecycle()
    val reject by vm.autoReplyRejectCall.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun missing(perms: Array<String>) = perms.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    val missingBase = remember(tick, enabled) { missing(AutoReplyController.PERMISSIONS) }
    val missingReject = remember(tick, reject) { missing(AutoReplyController.REJECT_PERMISSIONS) }

    SettingsSection("Auto-reply to calls") {
        Text(
            "While the bike is connected, texts the caller when you miss a SIM call. " +
                "Standard SMS charges apply.",
            style = MaterialTheme.typography.bodySmall,
        )
        AlertSwitchRow("Auto-reply by SMS", enabled) { on ->
            vm.setAutoReplyEnabled(on)
            if (on) {
                val need = missing(AutoReplyController.PERMISSIONS)
                if (need.isNotEmpty()) launcher.launch(need.toTypedArray())
            }
        }
        if (enabled) {
            var customMode by remember(message) { mutableStateOf(message !in Settings.AUTO_REPLY_PRESETS) }
            var typedText by remember { mutableStateOf<String?>(null) }
            val customText = typedText ?: message.takeIf { it !in Settings.AUTO_REPLY_PRESETS }.orEmpty()
            Settings.AUTO_REPLY_PRESETS.forEach { preset ->
                MessageOption(preset, selected = !customMode && message == preset) {
                    customMode = false
                    vm.setAutoReplyMessage(preset)
                }
            }
            MessageOption("Custom", selected = customMode) {
                customMode = true
                if (customText.isNotBlank()) vm.setAutoReplyMessage(customText)
            }
            if (customMode) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        val text = it.take(Settings.MAX_AUTO_REPLY_CHARS)
                        typedText = text
                        if (text.isNotBlank()) vm.setAutoReplyMessage(text)
                    },
                    label = { Text("Message (${Settings.MAX_AUTO_REPLY_CHARS} chars max)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AlertSwitchRow("Reject the call and reply immediately", reject) { on ->
                vm.setAutoReplyRejectCall(on)
                if (on) {
                    val need = missing(AutoReplyController.REJECT_PERMISSIONS)
                    if (need.isNotEmpty()) launcher.launch(need.toTypedArray())
                }
            }
            Text(
                "Off: the caller is texted only if the call is missed. On: the ringing call is " +
                    "rejected and texted at once, as in Ride Connect.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (missingBase.isNotEmpty() || (reject && missingReject.isNotEmpty())) {
                Button(
                    onClick = {
                        val need = missingBase + if (reject) missingReject else emptyList()
                        launcher.launch(need.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant auto-reply permissions") }
            }
        }
    }
}

@Composable
private fun MessageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlertSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = GixxerTokens.surfaceElevated,
                uncheckedThumbColor = GixxerTokens.textMuted,
                uncheckedBorderColor = GixxerTokens.border,
            ),
        )
    }
}
