package dev.mrwick.redline.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.ui.safety.SafetySection
import dev.mrwick.redline.ui.safety.SafetyViewModel
import dev.mrwick.redline.notifications.TelephonyCallSource

/**
 * Bike sub-screen: rider identity + bike pairing + safety section.
 * Route: settings/bike
 */
@Composable
fun BikeSettingsScreen(
    vm: SettingsViewModel,
    safetyVm: SafetyViewModel,
    onOpenPairing: () -> Unit,
) {
    val riderName by vm.riderName.collectAsStateWithLifecycle()
    val bikeMac by vm.bikeMac.collectAsStateWithLifecycle()
    val pairedSuzuki by vm.pairedSuzuki.collectAsStateWithLifecycle()
    val fuelCapacityL by vm.fuelCapacityL.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Identity") {
                var name by remember(riderName) { mutableStateOf(riderName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(20)
                        vm.setRiderName(name)
                    },
                    label = { Text("Rider name (20 chars max)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection("Bike") {
                Text(
                    "MAC: ${bikeMac ?: "(none paired)"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (bikeMac == null) "Pair a bike" else "Re-pair")
                }
                if (bikeMac != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ProfileSelector(
                        model = pairedSuzuki.model,
                        name = pairedSuzuki.name,
                        onSelect = vm::setSuzukiModel,
                    )
                    Text(
                        "Tank capacity: ${fuelCapacityL?.let { "%.1f L".format(it) } ?: "loading"} (app setting, not a cluster reading). " +
                            "Remaining litres are estimated from cluster fuel bars.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    var confirm by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { confirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Forget this bike") }
                    if (confirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Forget paired bike?") },
                            text = {
                                Text(
                                    "REDLINE will stop trying to auto-connect to " +
                                        "${bikeMac}. You can pair again from this screen.",
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    vm.forgetBike()
                                    confirm = false
                                }) { Text("Forget") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { confirm = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("Location") {
                LocationPermissionRow()
            }
        }
        item {
            SettingsSection("Cluster navigation text") {
                Text("Keeps arrival time for normal left, right and straight guidance, and when no action is known. " +
                    "For U-turns, roundabouts, slight/sharp turns, keep/join/ramp and headings " +
                    "guidance, short text replaces arrival time. Starting also shows text; rerouting and arrival show " +
                    "the cluster's own RE-ROUTING and DESTINATION REACHED messages. " +
                    "Roundabouts show EXIT2/EXIT3 (count exits from entry), or RBLFT/RBRGT for explicit left/right guidance. " +
                    "When Maps says to exit now without a next distance, NOW replaces only the blank next-distance field. " +
                    "The last fresh roundabout exit number stays in the time field; EXIT means no number is known. " +
                    "No exit angle is guessed. " +
                    "MERGE means join the named road with no side supplied; no left/right arrow is invented. " +
                    "MGLFT/MGRGT and directional arrows require a supplied left/right direction. " +
                    "UTLFT/UTRGT show U-turn sides; unspecified U-turns default to right. " +
                    "The time field shows five letters split by the clock bar (KP|LFT = keep left, " +
                    "HD = sharp, SL = slight, RP = ramp, RB = roundabout, UT = U-turn). " +
                    "Arrow and total distance stay unchanged; supplied next distances are never replaced. " +
                    "After 30 seconds without Maps updates, STALE replaces guidance and clears the arrow " +
                    "and distances. New input resumes guidance; stopping Maps " +
                    "resumes idle. The separate ETA label is not changed.")
            }
        }
        item {
            SpeedWarningSection(vm)
        }
        item {
            RideAlertsSection(vm)
        }
        item {
            CallAlertPermissionsSection()
        }
        item {
            AutoReplySection(vm)
        }
        item {
            SafetySection(safetyVm)
        }
    }
}

@Composable
private fun CallAlertPermissionsSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val labels = listOf("Phone calls", "Call logs (number)", "Contacts (name)")
    val granted = remember(tick) {
        TelephonyCallSource.PERMISSIONS.map {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    SettingsSection("SIM call alerts") {
        Text("Shows incoming SIM calls on the cluster with the caller's name or number until you " +
            "answer, decline or miss the call. Unanswered calls are sent as missed calls.")
        labels.zip(granted).forEach { (label, ok) ->
            Text("$label: ${if (ok) "granted" else "not granted"}")
        }
        if (!granted.all { it }) {
            Button(
                onClick = { launcher.launch(TelephonyCallSource.PERMISSIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant call permissions") }
        }
    }
}

/** Titled Material3 card grouping related settings rows — shared across sub-screens. */
@Composable
internal fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
