package com.roomradar.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun RadarApp(viewModel: RadarViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun permissionsGranted(): Boolean = requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(permissionsGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it } || permissionsGranted()
        viewModel.setPermissionsGranted(granted)
    }

    LaunchedEffect(Unit) {
        viewModel.setPermissionsGranted(granted)
        if (!granted) permissionLauncher.launch(requiredPermissions)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.tab == AppTab.SCAN,
                    onClick = { viewModel.setTab(AppTab.SCAN) },
                    icon = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
                    label = { Text("局域网扫描") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.WALK,
                    onClick = { viewModel.setTab(AppTab.WALK) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, contentDescription = null) },
                    label = { Text("走动测流量") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.GUIDE,
                    onClick = { viewModel.setTab(AppTab.GUIDE) },
                    icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    label = { Text("排查指南") },
                )
            }
        },
    ) { innerPadding ->
        when (state.tab) {
            AppTab.SCAN -> ScanScreen(
                state = state,
                permissionsGranted = granted,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                onScan = viewModel::startScan,
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.WALK -> WalkScreen(
                state = state,
                onGatewayUrlChange = viewModel::updateGatewayUrl,
                onGatewayTokenChange = viewModel::updateGatewayToken,
                onConnect = viewModel::connectGateway,
                onZoneChange = viewModel::updateZone,
                onStartTrial = viewModel::startGuidedTrial,
                onCancelTrial = viewModel::cancelGuidedTrial,
                onClearWindows = viewModel::clearMotionWindows,
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.GUIDE -> GuideScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
