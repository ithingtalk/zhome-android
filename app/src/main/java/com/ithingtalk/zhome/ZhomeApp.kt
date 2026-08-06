package com.ithingtalk.zhome

import android.app.Application
import com.ithingtalk.zhome.data.local.AppPaths
import com.ithingtalk.zhome.data.local.db.ZhomeDatabase
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import com.ithingtalk.zhome.data.remote.aws.AwsAuthService
import com.ithingtalk.zhome.data.remote.aws.AwsApiService
import com.ithingtalk.zhome.data.remote.aws.AwsIotService
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import com.ithingtalk.zhome.data.remote.nas.NasLocalClient
import com.ithingtalk.zhome.data.repository.AuthRepository
import com.ithingtalk.zhome.data.repository.DeviceRepository
import com.ithingtalk.zhome.data.repository.FileRepository
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.repository.TransferRepository
import com.ithingtalk.zhome.data.transfer.TransferEngine
import com.ithingtalk.zhome.network.LocalDiscovery
import com.ithingtalk.zhome.network.NetworkChangeCoordinator

/**
 * Simple manual DI container – keeps things lightweight without Hilt.
 * All singletons are lazily initialised.
 */
class ZhomeApp : Application() {

    lateinit var prefs: LocalPrefs private set
    lateinit var db: ZhomeDatabase private set
    lateinit var awsAuth: AwsAuthService private set
    lateinit var awsApi: AwsApiService private set
    lateinit var awsIot: AwsIotService private set
    lateinit var nasLocal: NasLocalClient private set
    lateinit var localDiscovery: LocalDiscovery private set

    lateinit var authRepo: AuthRepository private set
    lateinit var deviceRepo: DeviceRepository private set
    lateinit var fileRepo: FileRepository private set
    lateinit var transferRepo: TransferRepository private set
    lateinit var transferEngine: TransferEngine private set

    /** Key used to choose this session's per-user directory tree. */
    var currentUserKey: String = AppPaths.ANONYMOUS_USER_KEY
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AwsConfig.init(this)

        prefs = LocalPrefs(this)
        // Resolve the "last user" hint (persisted on successful sign-in) so we
        // can open this user's per-user zhome.db before DataStore is read.
        val bootstrapUser = AppPaths.readBootstrapLastUser(this)
        currentUserKey =
            if (bootstrapUser.isNotBlank()) AppPaths.userKey(bootstrapUser)
            else AppPaths.ANONYMOUS_USER_KEY
        db = ZhomeDatabase.create(
            this,
            bootstrapUser.ifBlank { AppPaths.ANONYMOUS_USER_KEY },
        )
        awsAuth = AwsAuthService()
        awsApi = AwsApiService()
        awsIot = AwsIotService(this)
        nasLocal = NasLocalClient()
        NasLanLoopbackHttpServer.prepareListenerAtLaunch()
        localDiscovery = LocalDiscovery(this)

        authRepo = AuthRepository(awsAuth, prefs, awsIot)
        deviceRepo = DeviceRepository(db.deviceDao(), awsApi, awsIot, localDiscovery, prefs, nasLocal, authRepo)

        fileRepo = FileRepository(db.fileDao(), db.recentFileDao(), prefs)
        transferRepo = TransferRepository(db.transferDao(), prefs)
        transferEngine = TransferEngine(this, transferRepo).also { it.start() }

        // libp2p is started lazily by RemoteLinkCoordinator only when a remote session is needed.
        RemoteLinkCoordinator.installIotConnectionResetHook()
        NetworkChangeCoordinator.start(this)
    }

    /**
     * Persists [username] as the "last user" hint so the next app launch opens
     * this user's per-user zhome.db. Call on successful sign-in.
     */
    fun rememberLastUser(username: String) {
        AppPaths.writeBootstrapLastUser(this, username)
    }

    companion object {
        lateinit var instance: ZhomeApp private set
    }
}
