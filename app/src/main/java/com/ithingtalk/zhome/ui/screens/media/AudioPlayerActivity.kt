package com.ithingtalk.zhome.ui.screens.media

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.playback.AudioPlaybackQueue
import com.ithingtalk.zhome.ui.navigation.NavGraph
import com.ithingtalk.zhome.ui.navigation.Route
import com.ithingtalk.zhome.ui.theme.ZhomeTheme

/**
 * Entry point from lockscreen / notification: always routes into the audio player UI
 * using the current playlist held by [AudioPlaybackQueue].
 */
class AudioPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val (paths, idx) = AudioPlaybackQueue.getNowPlaying() ?: run {
            finish()
            return
        }

        setContent {
            val app = ZhomeApp.instance
            ZhomeTheme(fontSizeIdx = 0) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavGraph(nav = nav, startRoute = Route.PlayAudio(paths, idx))
                }
            }
        }
    }
}

