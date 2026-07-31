package com.thinktank.recorder.ondevice.summary.litert;

import android.util.Log;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import java.io.File;
import java.util.Collections;

/**
 * Java-only boundary around LiteRT-LM.
 *
 * LiteRT-LM publishes Kotlin 2.x metadata while the app remains on Kotlin 1.9. Keeping this API
 * Java-only prevents those Kotlin types from entering the feature's KSP/compiler tasks.
 */
public final class GemmaLiteRtBridge implements AutoCloseable {
    private final Engine engine;
    private final String systemPrompt;
    private volatile Conversation activeConversation;

    public GemmaLiteRtBridge(String modelPath, String cacheDir, String systemPrompt) {
        Engine createdEngine = null;
        try {
            File cacheDirectory = new File(cacheDir);
            if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
                throw new IllegalStateException(
                        "LiteRT-LM cache directory could not be created: " + cacheDir
                );
            }
            createdEngine = new Engine(
                    new EngineConfig(
                            modelPath,
                            createCpuBackend(),
                            null,
                            null,
                            4096,
                            null,
                            cacheDirectory.getAbsolutePath()
                    )
            );
            createdEngine.initialize();
        } catch (Throwable error) {
            if (createdEngine != null) {
                try {
                    createdEngine.close();
                } catch (Throwable ignored) {
                    // Preserve the initialization error.
                }
            }
            throw error;
        }
        engine = createdEngine;
        this.systemPrompt = systemPrompt;
    }

    private static Backend createCpuBackend() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int threadCount = Math.min(MAX_CPU_THREADS, Math.max(1, availableProcessors));
        return new Backend.CPU(threadCount, null);
    }

    public String generate(String prompt) {
        Conversation conversation = engine.createConversation(
                new ConversationConfig(
                        Contents.Companion.of(systemPrompt),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        new SamplerConfig(1, 1.0, 0.0, 1),
                        false,
                        Collections.emptyList(),
                        Collections.emptyMap()
                )
        );
        activeConversation = conversation;
        long startedAt = System.nanoTime();
        try {
            Message response = conversation.sendMessage(prompt, Collections.emptyMap());
            StringBuilder text = new StringBuilder();
            for (Content content : response.getContents().getContents()) {
                if (content instanceof Content.Text) {
                    text.append(((Content.Text) content).getText());
                }
            }
            Log.i(
                    TAG,
                    "generate elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000L) +
                            " tokenCount=" + conversation.getTokenCount() +
                            " outputChars=" + text.length()
            );
            return text.toString();
        } finally {
            if (activeConversation == conversation) {
                activeConversation = null;
            }
            conversation.close();
        }
    }

    public void cancel() {
        Conversation conversation = activeConversation;
        if (conversation != null) {
            conversation.cancelProcess();
        }
    }

    @Override
    public void close() {
        Conversation conversation = activeConversation;
        if (conversation != null) {
            try {
                conversation.close();
            } finally {
                activeConversation = null;
            }
        }
        engine.close();
    }

    private static final String TAG = "GemmaLiteRtBridge";
    private static final int MAX_CPU_THREADS = 8;
}
