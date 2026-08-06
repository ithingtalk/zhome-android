package com.ithingtalk.zhome.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.ui.screens.auth.*
import com.ithingtalk.zhome.ui.screens.about.AboutScreen
import com.ithingtalk.zhome.ui.screens.content.*
import com.ithingtalk.zhome.ui.screens.devices.DeviceQrScreen
import com.ithingtalk.zhome.ui.screens.devices.DeviceSearchScreen
import com.ithingtalk.zhome.ui.screens.devices.ConnectingDeviceScreen
import com.ithingtalk.zhome.ui.screens.devices.DevicesScreen
import com.ithingtalk.zhome.ui.screens.management.*
import com.ithingtalk.zhome.ui.screens.media.*
import com.ithingtalk.zhome.ui.screens.settings.SettingsScreen
import com.ithingtalk.zhome.ui.screens.transfer.TransferScreen
import com.ithingtalk.zhome.ui.screens.welcome.WelcomeScreen
import kotlinx.coroutines.launch

@Composable
fun NavGraph(nav: NavHostController, startRoute: Route) {
    val scope = rememberCoroutineScope()

    fun goToSignIn() {
        nav.navigate(Route.AwsLogin) {
            popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun goHome() {
        scope.launch {
            val mac = ZhomeApp.instance.deviceRepo.getCurrent()?.mac
            if (mac.isNullOrBlank()) {
                nav.navigate(Route.Devices) { launchSingleTop = true }
                return@launch
            }
            nav.navigate(Route.ContentMain(mac)) {
                popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    NavHost(navController = nav, startDestination = startRoute) {

        composable<Route.Welcome> {
            WelcomeScreen(
                onTimeout = { nav.navigate(Route.AwsLogin) { popUpTo<Route.Welcome> { inclusive = true } } }
            )
        }

        composable<Route.AwsLogin> {
            AwsLoginScreen(
                onLoginSuccess = {
                    nav.navigate(Route.Devices) {
                        popUpTo<Route.AwsLogin> { inclusive = true }
                    }
                },
            )
        }

        // Legacy auth screens kept for backward compatibility
        composable<Route.AwsLogin> {
            AwsLoginScreen(
                onLoginSuccess = {
                    nav.navigate(Route.Devices) {
                        popUpTo<Route.AwsLogin> { inclusive = true }
                    }
                },
            )
        }

        // Legacy auth screens kept for backward compatibility
        composable<Route.SignIn> {
            SignInScreen(
                onSignedIn = { nav.navigate(Route.Devices) { popUpTo<Route.SignIn> { inclusive = true } } },
                onSignUp = { nav.navigate(Route.SignUp) },
                onForgotPassword = { nav.navigate(Route.ForgotPassword) },
                onSettings = { nav.navigate(Route.Settings) }
            )
        }

        composable<Route.SignUp> {
            SignUpScreen(
                onSuccess = { nav.navigate(Route.ConfirmAccount) },
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.ForgotPassword> {
            ForgotPasswordScreen(
                onReset = { nav.popBackStack() },
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.ConfirmAccount> {
            ConfirmAccountScreen(
                onConfirmed = { nav.navigate(Route.SignIn) { popUpTo<Route.SignUp> { inclusive = true } } },
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.Devices> {
            DevicesScreen(
                onDeviceClick = { mac ->
                    nav.navigate(Route.Connecting(mac)) { launchSingleTop = true }
                },
                onConfigureDevice = { dev ->
                    val ip = ZhomeApp.instance.deviceRepo.getRuntimeIp(dev.mac)
                    nav.navigate(
                        Route.DeviceConfigure(
                            mac = dev.mac,
                            sn = dev.sn,
                            name = dev.name,
                            cfg = dev.cfg,
                            ip = ip,
                        )
                    )
                },
                onAddDevice = { nav.navigate(Route.DeviceSearch) },
                onOpenDeviceManagement = { mac ->
                    scope.launch {
                        ZhomeApp.instance.deviceRepo.setCurrent(mac)
                        nav.navigate(Route.DeviceManagement()) { launchSingleTop = true }
                    }
                },
                onOpenUserManagement = { mac ->
                    scope.launch {
                        ZhomeApp.instance.deviceRepo.setCurrent(mac)
                        nav.navigate(Route.DeviceUsers()) { launchSingleTop = true }
                    }
                },
                onSettings = { nav.navigate(Route.Settings) },
                onSignOut = { goToSignIn() }
            )
        }

        composable<Route.Connecting> { entry ->
            val route = entry.toRoute<Route.Connecting>()
            ConnectingDeviceScreen(
                mac = route.mac,
                onConnected = { mac ->
                    nav.navigate(Route.ContentMain(mac)) {
                        popUpTo<Route.Devices> { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onBackToDevices = { nav.popBackStack() },
                onOpenDeviceManagement = { mac ->
                    scope.launch {
                        ZhomeApp.instance.deviceRepo.setCurrent(mac)
                        nav.navigate(Route.DeviceManagement(returnToDevicesOnBack = true)) { launchSingleTop = true }
                    }
                },
            )
        }

        composable<Route.DeviceSearch> {
            DeviceSearchScreen(
                onDeviceFound = { nav.popBackStack() },
                onConfigureNew = { dev ->
                    nav.navigate(
                        Route.DeviceConfigure(
                            mac = dev.mac,
                            sn = dev.sn,
                            name = dev.name,
                            cfg = dev.cfg,
                            ip = dev.ip,
                        )
                    )
                },
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.DeviceQr> { entry ->
            val route = entry.toRoute<Route.DeviceQr>()
            DeviceQrScreen(
                mac = route.mac,
                onBack = { nav.popBackStack() },
            )
        }

        composable<Route.ContentMain> { entry ->
            val route = entry.toRoute<Route.ContentMain>()
            ContentMainScreen(
                mac = route.mac,
                onBrowseFiles = { dir ->
                    if (dir == "__shared__") {
                        nav.navigate(Route.SharedBrowser(dir = "/MyFiles", mine = false))
                    } else {
                        nav.navigate(
                            Route.FileBrowser(
                                dir = dir,
                                isTrash = (dir == "__trash__"),
                            ),
                        )
                    }
                },
                onPlayVideo = { paths, idx -> nav.navigate(Route.PlayVideo(paths, idx)) },
                onPlayAudio = { paths, idx -> nav.navigate(Route.PlayAudio(paths, idx)) },
                onPreviewImage = { paths, idx -> nav.navigate(Route.ImagePreview(paths, idx)) },
                onOpenDocument = { path -> nav.navigate(Route.DocumentViewer(path)) },
                onAddDevice = { nav.navigate(Route.DeviceSearch) },
                onManageDevices = { nav.navigate(Route.Devices) { launchSingleTop = true } },
                onConfigureDevice = { dev ->
                    val ip = ZhomeApp.instance.deviceRepo.getRuntimeIp(dev.mac)
                    nav.navigate(
                        Route.DeviceConfigure(
                            mac = dev.mac,
                            sn = dev.sn,
                            name = dev.name,
                            cfg = dev.cfg,
                            ip = ip,
                        )
                    )
                },
                onDeviceManage = { nav.navigate(Route.DeviceManagement()) },

                onAbout = { nav.navigate(Route.About(mac = route.mac)) },
                onTransfers = { nav.navigate(Route.Transfers) },
                onSettings = { nav.navigate(Route.Settings) },
                onBack = { nav.popBackStack() },
                onReturnToDevices = {
                    nav.navigate(Route.Devices) {
                        popUpTo<Route.ContentMain> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onDeviceQr = { nav.navigate(Route.DeviceQr(mac = route.mac)) },
            )
        }

        composable<Route.SharedBrowser> { entry ->
            val route = entry.toRoute<Route.SharedBrowser>()
            SharedBrowserScreen(
                dir = route.dir,
                mine = route.mine,
                onNavigate = { sub, mine ->
                    nav.navigate(Route.SharedBrowser(dir = sub, mine = mine))
                },
                onPlayVideo = { paths, start -> nav.navigate(Route.PlayVideo(paths, start)) },
                onPlayAudio = { paths, start -> nav.navigate(Route.PlayAudio(paths, start)) },
                onPreviewImage = { paths, start -> nav.navigate(Route.ImagePreview(paths, start)) },
                onOpenDocument = { nav.navigate(Route.DocumentViewer(it)) },
                onOpenTransfers = { nav.navigate(Route.Transfers) },
                onGoHome = { goHome() },
                onBack = { nav.popBackStack() },
            )
        }

        composable<Route.FileBrowser> { entry ->
            val route = entry.toRoute<Route.FileBrowser>()
            FileBrowserScreen(
                dir = route.dir,
                isTrash = route.isTrash,
                onOpenDir = { sub -> nav.navigate(Route.FileBrowser(dir = sub, isTrash = route.isTrash)) },
                onOpenCategory = { catDir -> nav.navigate(Route.FileBrowser(dir = catDir, isTrash = false)) },
                onPlayVideo = { paths, start -> nav.navigate(Route.PlayVideo(paths, start)) },
                onPlayAudio = { paths, start -> nav.navigate(Route.PlayAudio(paths, start)) },
                onPreviewImage = { paths, start -> nav.navigate(Route.ImagePreview(paths, start)) },
                onOpenDocument = { nav.navigate(Route.DocumentViewer(it)) },
                onOpenTransfers = { nav.navigate(Route.Transfers) },
                onOpenTrash = {
                    nav.navigate(Route.FileBrowser(dir = "__trash__", isTrash = true))
                },
                onGoHome = { goHome() },
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.DeviceManagement> {
            val route = it.toRoute<Route.DeviceManagement>()
            DeviceManagementScreen(
                initialAdminPass = route.adminPass,
                autoLogin = route.autoLogin,
                onUserManagement = { adminPass ->
                    nav.navigate(Route.DeviceUsers(adminPass = adminPass))
                },
                onReplaceFinished = {
                    nav.navigate(Route.Devices) {
                        popUpTo<Route.Devices> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = {
                    if (route.returnToDevicesOnBack) {
                        nav.navigate(Route.Devices) {
                            popUpTo<Route.Devices> { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }

        composable<Route.DeviceUsers> { entry ->
            val route = entry.toRoute<Route.DeviceUsers>()
            DeviceUsersScreen(
                adminPass = route.adminPass,
                onBack = { nav.popBackStack() },
            )
        }

        composable<Route.DeviceConfigure> { entry ->
            val route = entry.toRoute<Route.DeviceConfigure>()
            DeviceConfigureScreen(
                mac = route.mac,
                sn = route.sn,
                initialName = route.name,
                ip = route.ip,
                onBack = { nav.popBackStack() },
                onConfigured = { adminPass ->
                    // Requirement: after configuring a new device, enter device management
                    // using the admin password used during configuration (no re-input).
                    nav.navigate(
                        Route.DeviceManagement(
                            adminPass = adminPass,
                            autoLogin = true,
                            returnToDevicesOnBack = true,
                        )
                    ) {
                        popUpTo<Route.Devices> { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }



        composable<Route.About> { entry ->
            val route = entry.toRoute<Route.About>()
            AboutScreen(mac = route.mac, onBack = { nav.popBackStack() })
        }

        composable<Route.PlayVideo> { entry ->
            val route = entry.toRoute<Route.PlayVideo>()
            PlayVideoScreen(
                remotePaths = route.urls,
                startIndex = route.startIndex,
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.PlayAudio> { entry ->
            val route = entry.toRoute<Route.PlayAudio>()
            AudioPlayerScreen(
                remotePaths = route.urls,
                startIndex = route.startIndex,
                onBack = { nav.popBackStack() }
            )
        }

        composable<Route.ImagePreview> { entry ->
            val route = entry.toRoute<Route.ImagePreview>()
            ImagePreviewScreen(
                remotePaths = route.urls,
                startIndex = route.startIndex,
                onBack = { nav.popBackStack() },
            )
        }

        composable<Route.DocumentViewer> { entry ->
            val route = entry.toRoute<Route.DocumentViewer>()
            DocumentViewerScreen(remotePath = route.url, onBack = { nav.popBackStack() })
        }

        composable<Route.Transfers> {
            TransferScreen(onBack = { nav.popBackStack() })
        }

        composable<Route.Settings> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSignedOut = { goToSignIn() },
            )
        }
    }
}

private fun androidx.navigation.NavGraph.findStartDestination(): androidx.navigation.NavDestination {
    var current: androidx.navigation.NavDestination = this
    while (current is androidx.navigation.NavGraph) {
        current = current.findNode(current.startDestinationId)
            ?: throw IllegalStateException("No start destination")
    }
    return current
}
