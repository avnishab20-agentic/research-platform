package com.comeback.researchplatform.agentservice.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake LLM for tests. Each rule says "if the prompt contains this text, reply
 * with that text". Rules are checked in the order they were added, so put the
 * more specific ones first.
 * <p>
 * Lets a test run a real service method end to end (prompt building, JSON
 * parsing, the decisions after it) with no network, no API key and no cost --
 * the same idea as fixture mode's FixtureChatModel.
 */
public class ScriptedChatModel implements ChatModel {

    private final List<String[]> rules = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();

    public ScriptedChatModel reply(String whenPromptContains, String response) {
        rules.add(new String[] {whenPromptContains, response});
        return this;
    }

    /** A ChatClient.Builder backed by this fake, ready to pass to a service constructor. */
    public ChatClient.Builder builder() {
        return ChatClient.builder(this);
    }

    /** Every prompt the service sent, in order. */
    public synchronized List<String> prompts() {
        return new ArrayList<>(prompts);
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            text.append(message.getText()).append('\n');
        }
        prompts.add(text.toString());
        for (String[] rule : rules) {
            if (text.indexOf(rule[0]) >= 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rule[1]))));
            }
        }
        throw new IllegalStateException("No scripted reply for prompt:\n" + text);
    }
}
