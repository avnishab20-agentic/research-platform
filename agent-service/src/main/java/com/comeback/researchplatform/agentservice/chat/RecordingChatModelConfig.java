package com.comeback.researchplatform.agentservice.chat;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * {@code @Primary} so every consumer (the researcher/writer/critic
 * ChatClient.Builder beans) gets the recording wrapper instead of the plain
 * autoconfigured one, whenever recording is on. {@code @ConditionalOnProperty}
 * rather than a profile, so it composes freely with RESEARCHER/WRITER/CRITIC
 * without a profile-combination conflict, and this bean is entirely absent
 * (zero overhead, no behaviour change) when recording is off.
 */
@Configuration
@Profile("!fixture")
public class RecordingChatModelConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "fixtures", name = "record-mode", havingValue = "true")
    public ChatModel recordingChatModel(ChatModel deepSeekChatModel, FixtureIO fixtureIO) {
        return new RecordingChatModel(deepSeekChatModel, fixtureIO);
    }
}
