package com.ithingtalk.zhome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.repository.DeviceRefreshCoordinator
import com.ithingtalk.zhome.network.NetworkChangeCoordinator
import com.ithingtalk.zhome.ui.navigation.NavGraph
import com.ithingtalk.zhome.ui.navigation.Route
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ithingtalk.zhome.ui.theme.ZhomeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isReady = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("Permissions", "${it.key} = ${it.value}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !isReady }
        enableEdgeToEdge()

        requestRequiredPermissions()

        setContent {
            val app = remember { application as ZhomeApp }
            val fontSizeIdx by produceState(0) {
                app.prefs.observeFontSizeIdx().collect { value = it }
            }
            ZhomeTheme(fontSizeIdx = fontSizeIdx) {
                val scope = rememberCoroutineScope()
                var startRoute by remember { mutableStateOf<Route?>(null) }

                LaunchedEffect(Unit) {
                    scope.launch {
                        val isLoggedIn = app.authRepo.isLoggedIn()
                        if (!isLoggedIn) {
                            startRoute = Route.AwsLogin
                        } else {
                            // Always start at device list — user explicitly chooses a device to connect.
                            // (Matches iOS flow: devices -> connecting -> main.)
                            startRoute = Route.Devices
                        }
                        isReady = true
                    }
                }

                startRoute?.let { route ->
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val nav = rememberNavController()
                        var showNetworkChangedDialog by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            NetworkChangeCoordinator.events.collect { event ->
                                if (!event.isDisruptive) return@collect
                                if (!event.status.supportsLocalDiscovery) {
                                    DeviceRefreshCoordinator.onWifiDisconnected()
                                }
                                RemoteLinkCoordinator.handleNetworkChange()
                                if (isOnDevicesListScreen(nav)) {
                                    if (event.localDiscoveryBecameAvailable) {
                                        DeviceRefreshCoordinator.requestLanDiscoveryOnce()
                                    }
                                    DeviceRefreshCoordinator.requestCloudSync()
                                } else {
                                    showNetworkChangedDialog = true
                                }
                            }
                        }

                        if (showNetworkChangedDialog) {
                            AlertDialog(
                                onDismissRequest = { },
                                title = { Text(stringResource(R.string.network_changed_title)) },
                                text = { Text(stringResource(R.string.network_changed_body)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showNetworkChangedDialog = false
                                        nav.navigate(Route.Devices) {
                                            popUpTo(nav.graph.startDestinationId) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }) {
                                        Text(stringResource(R.string.common_ok))
                                    }
                                },
                            )
                        }

                        NavGraph(nav = nav, startRoute = route)
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }
}

private fun isOnDevicesListScreen(nav: NavHostController): Boolean = runCatching {
    nav.currentBackStackEntry?.toRoute<Route.Devices>()
    true
}.getOrDefault(false)
