package com.thinktank.recorder.ondevice.summary;

import com.thinktank.recorder.ondevice.summary.IGemmaInferenceCallback;

interface IGemmaInferenceService {
    void summarize(
        String requestId,
        String modelPath,
        String transcript,
        IGemmaInferenceCallback callback
    );
    void cancel(String requestId);
}
