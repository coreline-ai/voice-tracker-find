package com.thinktank.recorder.ondevice.summary;

interface IQwenInferenceCallback {
    void onSuccess(String requestId, String resultJson, int servicePid);
    void onError(String requestId, String message, int servicePid);
}
