package com.thinktank.recorder.ondevice.recording

import android.content.Context
import java.io.File

class LocalAudioFileManager(context: Context) {
    private val recordingsRoot = File(context.filesDir, "ondevice/recordings").apply { mkdirs() }
    private val tempRoot = File(context.filesDir, "ondevice/temp").apply { mkdirs() }

    fun recordingFile(sessionId: String): File {
        require(SESSION_ID.matches(sessionId)) { "안전하지 않은 세션 ID입니다" }
        return File(recordingsRoot, "$sessionId.wav")
    }

    fun temporaryFile(name: String): File {
        require(SAFE_NAME.matches(name)) { "안전하지 않은 임시 파일명입니다" }
        return File(tempRoot, name)
    }

    fun deleteRecording(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        val file = File(path)
        val canonicalRoot = recordingsRoot.canonicalFile
        val canonicalFile = file.canonicalFile
        check(canonicalFile.parentFile == canonicalRoot) { "관리 경로 밖의 파일은 삭제할 수 없습니다" }
        return !canonicalFile.exists() || canonicalFile.delete()
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9-]{1,80}")
        val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,120}")
    }
}
