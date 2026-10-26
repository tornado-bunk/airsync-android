package com.sameerasw.airsync.presentation.ui.components.cards

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.TrustedNetwork
import com.sameerasw.airsync.utils.TrustedNetworks
import kotlinx.coroutines.delay

@Composable
fun TrustedNetworksCard(context: Context) {
    val networks by TrustedNetworks.networks.collectAsState()
    val status by TrustedNetworks.status.collectAsState()
    val enrolling by TrustedNetworks.enrolling.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionEpoch++
        TrustedNetworks.refresh(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { permissionEpoch++; TrustedNetworks.refresh(context) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        TrustedNetworks.load(context)
        while (true) { TrustedNetworks.refresh(context); delay(10_000) }
    }
    LaunchedEffect(enrolling) { if (enrolling) { adding = false; name = "" } }
    val permission = remember(permissionEpoch) { TrustedNetworks.hasWifiPermission(context) }
    val locationEnabled = remember(permissionEpoch) { (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled }
    val current = if (status.startsWith("Recognized:")) TrustedNetworks.currentObservation()?.fingerprint else null

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trusted_networks_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { adding = !adding }, enabled = !enrolling) {
                    Icon(painterResource(R.drawable.rounded_add_24), contentDescription = stringResource(R.string.trusted_networks_add))
                }
            }
            if (enrolling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.trusted_networks_checking), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else if (!status.startsWith("Recognized:") && status != "Unknown network · using Airbridge") {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (networks.isEmpty()) Text(stringResource(R.string.trusted_networks_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            networks.forEach { network ->
                key(network.id) { TrustedNetworkRow(network, current == network.androidFingerprint) }
            }
            if (adding) {
                HorizontalDivider()
                if (!permission) {
                    TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) {
                        Text(stringResource(R.string.trusted_networks_grant_permission))
                    }
                } else if (!locationEnabled) {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                        Text(stringResource(R.string.trusted_networks_enable_location))
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
                    label = { Text(stringResource(R.string.trusted_networks_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { adding = false; name = "" }) { Text(stringResource(R.string.trusted_networks_cancel)) }
                    Button(onClick = { TrustedNetworks.beginEnrollment(context, name) }, enabled = name.isNotBlank() && permission && locationEnabled) {
                        Text(stringResource(R.string.trusted_networks_add_short))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustedNetworkRow(network: TrustedNetwork, current: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var name by remember(network.name) { mutableStateOf(network.name) }
    var removing by remember { mutableStateOf(false) }
    if (editing) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true,
                label = { Text(stringResource(R.string.trusted_networks_name)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { name = network.name; editing = false }) { Text(stringResource(R.string.trusted_networks_cancel)) }
                TextButton(onClick = { TrustedNetworks.rename(network.id, name); editing = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.trusted_networks_save)) }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.rounded_network_node_24), contentDescription = null,
                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(network.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current) Text(stringResource(R.string.trusted_networks_current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trusted_networks_actions, network.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.trusted_networks_rename)) }, onClick = { menu = false; editing = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.trusted_networks_remove), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; removing = true })
                }
            }
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false },
        title = { Text(stringResource(R.string.trusted_networks_remove_question)) },
        confirmButton = { TextButton(onClick = { TrustedNetworks.remove(network.id); removing = false }) { Text(stringResource(R.string.trusted_networks_remove)) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.trusted_networks_cancel)) } })
}
