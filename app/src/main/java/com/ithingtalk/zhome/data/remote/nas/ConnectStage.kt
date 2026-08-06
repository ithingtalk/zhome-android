package com.ithingtalk.zhome.data.remote.nas

/**
 * Connection progress stages for the device-connecting screen.
 *
 * Keep this enum stable because UI and logs depend on it.
 */
enum class ConnectStage {
    ConnectingRemote,
    UserLogin,
    GetStatus,
    DownloadFileDb,
    ImportFileDb,
    DownloadSharedDb,
    ImportSharedDb,
    Finishing,
}

