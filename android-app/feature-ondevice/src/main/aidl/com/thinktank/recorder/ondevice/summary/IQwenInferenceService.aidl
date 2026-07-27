package com.thinktank.recorder.ondevice.summary;

import com.thinktank.recorder.ondevice.summary.IQwenInferenceCallback;

interface IQwenInferenceService {
    void summarize(
        String requestId,
        String modelId,
        String modelPath,
        String transcript,
        IQwenInferenceCallback callback
    );
    void cancel(String requestId);
}
