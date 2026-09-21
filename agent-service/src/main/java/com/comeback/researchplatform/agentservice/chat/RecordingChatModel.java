package com.comeback.researchplatform.agentservice.chat;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Wraps the real, autoconfigured DeepSeek {@code ChatModel} and records every
 * call/response pair verbatim -- makes an ordinary live run (with {@code
 * fixtures.record-mode: true}) the way fixture data gets captured, same
 * reasoning as {@link com.comeback.researchplatform.agentservice.retrieval.HttpRetrievalClient}'s
 * recording. Recording is a side effect on the way out; the delegate's real
 * response is returned unmodified. Registered as a bean in
 * {@link RecordingChatModelConfig}, not here -- this class is a plain
 * decorator, not itself a Spring component.
 */
public class RecordingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final FixtureIO fixtureIO;

    public RecordingChatModel(ChatModel delegate, FixtureIO fixtureIO) {
        this.delegate = delegate;
        this.fixtureIO = fixtureIO;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        fixtureIO.record("chat-responses.json", FixtureIO.keyFor(FixtureChatModel.promptText(prompt)),
                response.getResult().getOutput().getText());
        return response;
    }
}
