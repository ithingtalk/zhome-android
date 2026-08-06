package com.ithingtalk.zhome.playback

import com.ithingtalk.zhome.ui.screens.media.ResolvedAudioTrack

/** Holds the next playlist for [MusicPlaybackService] (resolved NAS tracks). */
object AudioPlaybackQueue {
    @Volatile
    private var pending: Pair<List<ResolvedAudioTrack>, Int>? = null

    @Volatile
    private var nowPlaying: Pair<List<String>, Int>? = null

    fun setPending(tracks: List<ResolvedAudioTrack>, startIndex: Int) {
        pending = tracks to startIndex
    }

    fun takePending(): Pair<List<ResolvedAudioTrack>, Int>? {
        val p = pending
        pending = null
        return p
    }

    fun setNowPlaying(remotePaths: List<String>, startIndex: Int) {
        nowPlaying = remotePaths to startIndex
    }

    fun getNowPlaying(): Pair<List<String>, Int>? = nowPlaying
}
