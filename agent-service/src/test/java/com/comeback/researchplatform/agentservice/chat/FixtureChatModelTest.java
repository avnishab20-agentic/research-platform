package com.comeback.researchplatform.agentservice.chat;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixtureChatModelTest {

    @Test
    void repliesWithTheRecordingKeyedOnTheFullPromptText() {
        // The key must match what FixtureIO.recordChat is given: system + "\n---\n" + user.
        FixtureIO fixtureIO = mock(FixtureIO.class);
        when(fixtureIO.read("chat-responses.json", FixtureIO.keyFor("be brief\n---\nwhat is the repo rate?"),
                String.class)).thenReturn("6.5%");
        Prompt prompt = new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("what is the repo rate?")));

        String reply = new FixtureChatModel(fixtureIO).call(prompt).getResult().getOutput().getText();

        assertThat(reply).isEqualTo("6.5%");
    }
}
