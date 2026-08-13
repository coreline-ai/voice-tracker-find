package com.coreline.ai.voice.ondevice.summary;

interface IGemmaInferenceCallback {
    void onSuccess(String requestId, String resultJson, int servicePid);
    void onError(String requestId, String message, int servicePid);
}
