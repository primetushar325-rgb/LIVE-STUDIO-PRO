package com.livevip.app.video

import com.livevip.app.core.ErrorCode

interface PlaybackEvents {
    fun onSourceReady(width: Int, height: Int, durationUs: Long) {}
    fun onFirstFrame() {}
    fun onItemStarted(index: Int, name: String) {}
    fun onItemCompleted(index: Int) {}
    fun onLoopCompleted(loopIndex: Int) {}
    fun onPlaylistFinished() {}
    fun onPcm(data: ByteArray, size: Int, presentationTimeUs: Long) {}
    fun onAudioFormat(sampleRate: Int, channels: Int) {}
    fun onError(code: ErrorCode, detail: String) {}
}
