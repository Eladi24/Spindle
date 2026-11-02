package io.github.eladimany.spindle.ui.output

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eladimany.spindle.core.model.BluOsPlayer
import io.github.eladimany.spindle.data.bluos.NetworkPermissions
import io.github.eladimany.spindle.playback.LocalRoute
import io.github.eladimany.spindle.playback.LocalRouteKind
import io.github.eladimany.spindle.playback.OutputTarget

/** [localName] = what "This phone" plays through, from `PlayerViewModel.localOutputName`. */
fun OutputTarget.displayName(localName: String = "This phone"): String = when (this) {
    OutputTarget.Local -> localName
    is OutputTarget.Node -> player.name
}

@Composable
fun OutputPickerSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutputPickerViewModel = hiltViewModel(),
) {
    var hasNearbyWifiPermission by remember { mutableStateOf(viewModel.hasNearbyWifiPermission()) }
    var hasBluetoothConnectPermission by remember { mutableStateOf(viewModel.hasBluetoothConnectPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasNearbyWifiPermission = viewModel.hasNearbyWifiPermission()
        granted[Manifest.permission.BLUETOOTH_CONNECT]?.let { hasBluetoothConnectPermission = it }
    }

    // Re-read paired devices on open, on a grant, and whenever one connects or drops.
    val routesForRefresh by viewModel.localRoutes.collectAsStateWithLifecycle()
    LaunchedEffect(routesForRefresh, hasBluetoothConnectPermission) { viewModel.refreshBluetooth() }

    DisposableEffect(hasNearbyWifiPermission) {
        if (hasNearbyWifiPermission) viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    val target by viewModel.target.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val localRoutes by viewModel.localRoutes.collectAsStateWithLifecycle()
    val activeRoute by viewModel.activeRoute.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()

    var showManualEntry by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }

    val showBatteryCard by viewModel.showBatteryCard.collectAsStateWithLifecycle()
    val showBatterySetup by viewModel.showBatterySetup.collectAsStateWithLifecycle()
    val isUnrestricted by viewModel.isUnrestricted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openBatterySettings = { context.startActivity(viewModel.batterySettingsIntent()) }
    val dismiss = {
        viewModel.finishBatterySetup()
        onDismiss()
    }

    // Coming back from the App info page is the only signal that the setting changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBatteryState()
        onPauseOrDispose { }
    }
    // The setup sheet's job is done — close it rather than fall back to the picker.
    LaunchedEffect(showBatterySetup, isUnrestricted) {
        if (showBatterySetup && isUnrestricted) dismiss()
    }

    // Always fully open: half-expanded, the setup content's buttons sat below the
    // fold, and a partial sheet whose content grows (setup replacing the picker as
    // the manual-IP keyboard closes) was seen dismissing itself on the A73.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = dismiss, modifier = modifier, sheetState = sheetState) {
        if (showBatterySetup) {
            BatterySetupContent(
                nodeName = (target as? OutputTarget.Node)?.player?.name ?: "your Node",
                onOpenSettings = openBatterySettings,
                onLater = dismiss,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Play on", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Choose where sound plays",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val speakerRoute = localRoutes.firstOrNull { it.kind == LocalRouteKind.SPEAKER }
                val otherRoutes = localRoutes.filter { it.kind != LocalRouteKind.SPEAKER }

                OutputCard(
                    icon = Icons.Default.Smartphone,
                    title = "This phone",
                    subtitle = "Built-in speaker",
                    // From where sound really goes, not from the pin: with earbuds on
                    // and nothing pinned, this card used to read ACTIVE (S25+).
                    isActive = target == OutputTarget.Local &&
                        (activeRoute == null || activeRoute?.id == speakerRoute?.id),
                    onClick = { viewModel.selectLocal() },
                )

                // Android Auto / another output no row can pin: shown while it's playing.
                activeRoute?.takeIf { it.kind == LocalRouteKind.OTHER && target == OutputTarget.Local }?.let { route ->
                    OutputCard(
                        icon = Icons.Default.DirectionsCar,
                        title = viewModel.routeDisplayName(route),
                        subtitle = "Chosen by Android",
                        isActive = true,
                        onClick = { },
                    )
                }

                val wiredRoutes = otherRoutes.filter { it.kind == LocalRouteKind.WIRED }
                if (wiredRoutes.isNotEmpty()) {
                    SectionLabel("OUTPUT DEVICE")
                    wiredRoutes.forEach { route ->
                        OutputCard(
                            icon = Icons.Default.Headphones,
                            title = viewModel.routeDisplayName(route),
                            subtitle = "Wired",
                            isActive = target == OutputTarget.Local && activeRoute?.id == route.id,
                            onClick = { viewModel.selectDevice(route) },
                        )
                    }
                }

                // BLUETOOTH (mockup 8A): connected devices play here as before; paired ones
                // that aren't connected get "Connect", which opens Android's output switcher.
                val connectedBluetooth = otherRoutes.filter { it.kind == LocalRouteKind.BLUETOOTH }
                val connectedAddresses = connectedBluetooth.mapNotNull { it.address }.toSet()
                val pairedOnly = pairedDevices.filter { it.address !in connectedAddresses }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("BLUETOOTH", Modifier.weight(1f))
                    TextButton(onClick = viewModel::pairNewBluetooth, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Pair new ›")
                    }
                }
                connectedBluetooth.forEach { route ->
                    OutputCard(
                        icon = Icons.Default.Bluetooth,
                        title = viewModel.routeDisplayName(route),
                        subtitle = "Connected",
                        isActive = target == OutputTarget.Local && activeRoute?.id == route.id,
                        onClick = { viewModel.selectDevice(route) },
                    )
                }
                pairedOnly.forEach { device ->
                    OutputCard(
                        icon = Icons.Default.Bluetooth,
                        title = device.name,
                        subtitle = "Paired · not connected",
                        isActive = false,
                        action = "Connect",
                        onClick = viewModel::connectBluetooth,
                    )
                }
                if (!hasBluetoothConnectPermission) {
                    TextButton(
                        onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("Show your Bluetooth devices")
                    }
                } else if (connectedBluetooth.isEmpty() && pairedOnly.isEmpty()) {
                    Text(
                        "No paired Bluetooth speakers or headphones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }

                NetworkHeader(
                    isScanning = isScanning,
                    canScan = hasNearbyWifiPermission,
                    onScanAgain = viewModel::scanAgain,
                )

                if (!hasNearbyWifiPermission) {
                    PermissionRequestCard(
                        onGrant = {
                            val permissions = buildList {
                                addAll(NetworkPermissions.required())
                                if (!hasBluetoothConnectPermission) add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                    )
                } else {
                    players.forEach { player ->
                        OutputCard(
                            icon = Icons.Default.Cast,
                            title = player.name,
                            subtitle = "BluOS · on your network",
                            isActive = (target as? OutputTarget.Node)?.player == player,
                            onClick = { viewModel.selectNode(player) },
                        )
                    }
                    if (showBatteryCard) {
                        BatteryWarningCard(
                            onOpenSettings = openBatterySettings,
                            onNotNow = viewModel::dismissBatteryCard,
                        )
                    }
                    if (players.isEmpty() && !isScanning) {
                        Text(
                            "No players found on this WiFi. Make sure the player is on and on the same network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }

                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (showManualEntry) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("IP address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                viewModel.connectManually(manualHost)
                                showManualEntry = false
                                manualHost = ""
                            },
                        ) { Text("Connect") }
                    }
                } else {
                    ManualEntryCard(onClick = { showManualEntry = true })
                }
            }
        }
    }
}

@Composable
private fun OutputCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    onClick: () -> Unit,
    action: String? = null,
) {
    val containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val borderColor = if (isActive) containerColor else MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .border(1.dp, borderColor, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isActive && action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (isActive) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** "ON YOUR NETWORK", with "Searching…" while a search runs and ↻ to search again after. */
@Composable
private fun NetworkHeader(isScanning: Boolean, canScan: Boolean, onScanAgain: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ON YOUR NETWORK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        when {
            !canScan -> Unit
            isScanning -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "Searching…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> IconButton(onClick = onScanAgain) {
                Icon(Icons.Default.Refresh, contentDescription = "Search again", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ManualEntryCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "  Enter IP address manually",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PermissionRequestCard(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Spindle needs the \"Nearby devices\" permission to find BluOS players on your network.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Also asks for Bluetooth access, so a connected device can show its real name above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrant) { Text("Grant access") }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}
