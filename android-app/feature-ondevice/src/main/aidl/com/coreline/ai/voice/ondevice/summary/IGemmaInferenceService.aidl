package com.coreline.ai.voice.ondevice.summary;

import com.coreline.ai.voice.ondevice.summary.IGemmaInferenceCallback;

interface IGemmaInferenceService {
    void summarizeNode(
        String batchId,
        String requestId,
        String inputHash,
        String modelPath,
        String transcript,
        IGemmaInferenceCallback callback
    );
    void finishBatch(String batchId);
    void cancel(String requestId);
}
