package com.coreline.ai.voice.ondevice.runtime

import android.os.Build
import android.os.Process

data class NativeRuntimeCapability(
    val supported: Boolean,
    val reason: String? = null,
)

object NativeRuntimeCapabilities {
    fun current(): NativeRuntimeCapability =
        evaluate(
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toSet(),
            processIs64Bit = Process.is64Bit(),
        )

    internal fun evaluate(
        supported64BitAbis: Set<String>,
        processIs64Bit: Boolean,
    ): NativeRuntimeCapability = when {
        !processIs64Bit -> NativeRuntimeCapability(
            supported = false,
            reason = "이 앱 프로세스는 64비트가 아니어서 native AI를 사용할 수 없습니다.",
        )
        "arm64-v8a" !in supported64BitAbis -> NativeRuntimeCapability(
            supported = false,
            reason = "로컬 STT·AI 모델은 arm64 기기에서만 사용할 수 있습니다.",
        )
        else -> NativeRuntimeCapability(supported = true)
    }
}
