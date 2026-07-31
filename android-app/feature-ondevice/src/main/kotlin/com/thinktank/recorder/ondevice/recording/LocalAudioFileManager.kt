package com.thinktank.recorder.ondevice.recording

import android.content.Context
import java.io.File

class LocalAudioFileManager(context: Context) {
    private val recordingsRoot = File(context.filesDir, "ondevice/recordings").apply { mkdirs() }
    private val tempRoot = File(context.filesDir, "ondevice/temp").apply { mkdirs() }
    private val processingRoot = File(context.filesDir, "ondevice/processing").apply { mkdirs() }

    fun recordingFile(sessionId: String): File {
        require(SESSION_ID.matches(sessionId)) { "안전하지 않은 세션 ID입니다" }
        return File(recordingsRoot, "$sessionId.wav")
    }

    fun temporaryFile(name: String): File {
        require(SAFE_NAME.matches(name)) { "안전하지 않은 임시 파일명입니다" }
        return File(tempRoot, name)
    }

    fun processingFile(jobId: String, name: String): File {
        require(SESSION_ID.matches(jobId)) { "안전하지 않은 작업 ID입니다" }
        require(SAFE_NAME.matches(name)) { "안전하지 않은 작업 파일명입니다" }
        val directory = File(processingRoot, jobId).apply { mkdirs() }
        return File(directory, name)
    }

    fun deleteProcessingFiles(jobId: String): Boolean {
        require(SESSION_ID.matches(jobId)) { "안전하지 않은 작업 ID입니다" }
        val canonicalRoot = processingRoot.canonicalFile
        val directory = File(processingRoot, jobId).canonicalFile
        check(directory.parentFile == canonicalRoot) { "관리 경로 밖의 작업 파일은 삭제하지 않습니다" }
        if (!directory.exists()) return true
        return directory.walkBottomUp().all { file -> !file.exists() || file.delete() }
    }

    fun deleteTemporary(file: File): Boolean {
        val canonicalRoot = tempRoot.canonicalFile
        val canonicalFile = file.canonicalFile
        check(canonicalFile.parentFile == canonicalRoot) { "관리 경로 밖의 임시 파일은 삭제하지 않습니다" }
        return !canonicalFile.exists() || canonicalFile.delete()
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
