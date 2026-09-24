package com.comeback.researchplatform.agentservice.chat;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a recorded response for the exact prompt text -- PLAN's fixture
 * mode for the LLM side. The key is the full prompt (every message's text,
 * joined) hashed the same way {@link FixtureIO#keyFor} hashes anything else
 * -- an exact match on what was actually asked, not a fuzzy lookup, matching
 * PLAN's own wording: "replaying recorded ... LLM responses from JSON."
 */
@Component
@Profile("fixture")
public class FixtureChatModel implements ChatModel {

    private final FixtureIO fixtureIO;

    public FixtureChatModel(FixtureIO fixtureIO) {
        this.fixtureIO = fixtureIO;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String key = FixtureIO.keyFor(promptText(prompt));
        String responseText = fixtureIO.read("chat-responses.json", key, String.class);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
    }

    static String promptText(Prompt prompt) {
        List<String> texts = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            texts.add(message.getText());
        }
        return String.join("\n---\n", texts);
    }
}
