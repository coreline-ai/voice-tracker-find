package com.thinktank.recorder.ondevice.summary.litert;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import java.util.Collections;

/**
 * Java-only boundary around LiteRT-LM.
 *
 * LiteRT-LM 0.14.0 publishes Kotlin 2.x metadata while the application remains on Kotlin 1.9.
 * Isolating it in this Java Android library avoids exposing those Kotlin types to KSP/compiler
 * tasks in the main feature.
 */
public final class GemmaLiteRtBridge implements AutoCloseable {
    private final Engine engine;
    private final Conversation conversation;

    public GemmaLiteRtBridge(String modelPath, String cacheDir, String systemPrompt) {
        Engine createdEngine = null;
        Conversation createdConversation = null;
        try {
            createdEngine = new Engine(
                    new EngineConfig(
                            modelPath,
                            new Backend.CPU(),
                            null,
                            null,
                            4096,
                            null,
                            cacheDir
                    )
            );
            createdEngine.initialize();
            createdConversation = createdEngine.createConversation(
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
        } catch (Throwable error) {
            if (createdConversation != null) {
                try {
                    createdConversation.close();
                } catch (Throwable ignored) {
                    // Preserve the initialization error.
                }
            }
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
        conversation = createdConversation;
    }

    public String generate(String prompt) {
        Message response = conversation.sendMessage(prompt, Collections.emptyMap());
        StringBuilder text = new StringBuilder();
        for (Content content : response.getContents().getContents()) {
            if (content instanceof Content.Text) {
                text.append(((Content.Text) content).getText());
            }
        }
        return text.toString();
    }

    public void cancel() {
        conversation.cancelProcess();
    }

    @Override
    public void close() {
        try {
            conversation.close();
        } finally {
            engine.close();
        }
    }
}
