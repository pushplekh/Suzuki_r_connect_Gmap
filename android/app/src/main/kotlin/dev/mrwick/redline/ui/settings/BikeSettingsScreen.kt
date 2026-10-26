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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.ui.safety.SafetySection
import dev.mrwick.redline.ui.safety.SafetyViewModel
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.nav.DisplayProbe
import kotlinx.coroutines.launch

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
    val instructionText by vm.experimentalInstructionText.collectAsStateWithLifecycle()
    val textInEta by vm.navigationTextInEta.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var stationary by remember { mutableStateOf(false) }
    var acceptFreshIdentity by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val runProbe: (DisplayProbe) -> Unit = { probe ->
        testing = true
        testResult = "Testing: ${probe.label}"
        scope.launch {
            try {
                testResult = AppGraph.testDisplay(probe, riderName, instructionText && !textInEta)
            } finally {
                testing = false
            }
        }
    }

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
            SettingsSection("Experimental cluster instructions") {
                Text("Smart arrival time / instruction text")
                Text("Keeps arrival time for normal left, right and straight guidance, and when no action is known. " +
                    "For U-turns, roundabouts, slight/sharp turns, keep/join/ramp and headings " +
                    "guidance, short text replaces arrival time. Starting, rerouting and arrival also show text. " +
                    "Roundabouts show EXT2/EXT3 (count exits from entry), or RB L/RB R for explicit left/right guidance. " +
                    "When Maps says to exit now without a next distance, NOW replaces only the blank next-distance field. " +
                    "The last fresh roundabout exit number stays in the time field; EXIT means no number is known. " +
                    "No exit angle is guessed. " +
                    "MERGE means join the named road with no side supplied; no left/right arrow is invented. " +
                    "MERG L/MERG R and directional arrows require a supplied left/right direction. " +
                    "U-LEFT/U-RGHT show U-turn sides; unspecified U-turns default to right. " +
                    "Text uses the six transmitted time bytes; cluster separators and suffixes may remain. " +
                    "Arrow and total distance stay unchanged; supplied next distances are never replaced. " +
                    "After 30 seconds without Maps updates, STALE replaces guidance and clears the arrow " +
                    "and distances, even with smart text off. New input resumes guidance; stopping Maps " +
                    "resumes idle. The separate ETA label is not changed.")
                androidx.compose.material3.Switch(
                    checked = textInEta,
                    onCheckedChange = vm::setNavigationTextInEta,
                    enabled = !testing,
                )
                Text("Legacy rider-name banner experiment")
                Text("Reuses the rider-name connection message for short instructions (20 ASCII characters). " +
                    "Disabled while navigation text in time field is enabled. Not verified to refresh the banner.")
                androidx.compose.material3.Switch(
                    checked = instructionText,
                    onCheckedChange = vm::setExperimentalInstructionText,
                    enabled = !testing && !textInEta,
                )
                Text("Letters in the time and both distance fields were reported visible on the Access. " +
                    "The rider-name banner remains unverified. " +
                    "Stop Maps navigation before testing. Each test holds automatic idle display updates " +
                    "for up to 8 seconds; heartbeat stays active. Navigation input cancels the hold.")
                Text("I am stationary and will remain stationary during the test")
                androidx.compose.material3.Checkbox(
                    checked = stationary,
                    onCheckedChange = { stationary = it },
                    enabled = !testing,
                )
                DisplayProbe.entries.filterNot {
                    it.investigatesEtaLabel || it.testsIdentity || it.testsArrivalTime
                }.forEach { probe ->
                    OutlinedButton(
                        enabled = stationary && !testing,
                        onClick = { runProbe(probe) },
                    ) { Text(probe.label) }
                }
                testResult?.let { Text(it) }
            }
        }
        item {
            SettingsSection("Arrival time stationary test") {
                Text("Tests the numeric arrival-time field, not the separate ETA label. " +
                    "AM and PM tests keep next distance at 123 m and total distance at 45 km. " +
                    "No Maps route is needed and the smart-text setting does not affect these tests. " +
                    "Each test pauses automatic display updates for up to 8 seconds; heartbeat stays active.")
                Text("I am stationary and have stopped Maps navigation")
                androidx.compose.material3.Checkbox(
                    checked = stationary,
                    onCheckedChange = { stationary = it },
                    enabled = !testing,
                )
                DisplayProbe.entries.filter { it.testsArrivalTime }.forEach { probe ->
                    OutlinedButton(
                        enabled = stationary && !testing,
                        onClick = { runProbe(probe) },
                    ) { Text(probe.label) }
                }
                testResult?.let { Text(it) }
            }
        }
        item {
            SettingsSection("Rider-name banner investigation") {
                Text("Uses your saved rider name (up to 20 ASCII bytes). Run R first, then F, " +
                    "and photograph whether the name or connected banner appears. " +
                    "R is the reconnect identity message; F is the fresh-connection variant. " +
                    "Hypothesis: F may retrigger the banner. Its effect while already connected " +
                    "is unverified and it may affect the connection. These tests do not change " +
                    "the normal connection handshake or enable instruction banners.")
                Text("Automatic navigation/idle updates pause for up to 8 seconds; heartbeat stays active. " +
                    "The saved name is resent with R afterward; restoration is not a rendering acknowledgement. " +
                    "If the link drops or restoration fails, reconnect. Export Diagnostics after testing.")
                Text("I am stationary and have stopped Maps navigation")
                androidx.compose.material3.Checkbox(
                    checked = stationary,
                    onCheckedChange = { stationary = it },
                    enabled = !testing,
                )
                Text("Enable experimental F test: it may affect the connection")
                androidx.compose.material3.Checkbox(
                    checked = acceptFreshIdentity,
                    onCheckedChange = { acceptFreshIdentity = it },
                    enabled = !testing,
                )
                DisplayProbe.entries.filter { it.testsIdentity }.forEach { probe ->
                    OutlinedButton(
                        enabled = stationary && !testing &&
                            (probe != DisplayProbe.IDENTITY_FRESH || acceptFreshIdentity),
                        onClick = { runProbe(probe) },
                    ) { Text(probe.label) }
                }
                testResult?.let { Text(it) }
            }
        }
        item {
            SettingsSection("ETA label investigation") {
                Text("Investigates the separate ETA label visible in navigation mode, not the six-character time field.")
                Text("No writable offset for this label is known. Hypothesis: the three unassigned " +
                    "bytes immediately after the time field might control it. The ABC test changes " +
                    "only those bytes and the checksum; it may do nothing or affect another area.")
                Text("Stop Maps navigation and remain stationary. Run baseline first, then ABC. " +
                    "Each test lasts up to 8 seconds; automatic display updates pause, heartbeat stays active. " +
                    "Photograph both results, noting whether ETA changes, disappears or stays the same, " +
                    "and whether ABC appears anywhere else. Export Diagnostics afterward.")
                Text("I am stationary and will remain stationary during the test")
                androidx.compose.material3.Checkbox(
                    checked = stationary,
                    onCheckedChange = { stationary = it },
                    enabled = !testing,
                )
                DisplayProbe.entries.filter { it.investigatesEtaLabel }.forEach { probe ->
                    OutlinedButton(
                        enabled = stationary && !testing,
                        onClick = { runProbe(probe) },
                    ) { Text(probe.label) }
                }
                testResult?.let { Text(it) }
            }
        }
        item {
            SafetySection(safetyVm)
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
