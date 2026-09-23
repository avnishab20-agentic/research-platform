package com.comeback.researchplatform.controlplane.planner;

import com.comeback.researchplatform.controlplane.config.PlannerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * decompose()'s fan-out cap is one of PLAN's guardrail tripwire cases
 * ("planner emitting 9 sub-questions"). ChatClient's fluent chain is
 * mocked only enough to hand decompose() a scripted 9-line response --
 * what's actually under test is the .limit(maxFanOut) truncation after it.
 */
class PlannerServiceTest {

    private static final int MAX_FAN_OUT = 8;

    private PlannerService serviceReturning(String responseText) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(responseText);

        PlannerProperties props = new PlannerProperties(3, MAX_FAN_OUT, Duration.ofMinutes(3), 20);
        Environment environment = mock(Environment.class);
        return new PlannerService(builder, mock(JdbcTemplate.class), mock(KafkaTemplate.class), props,
                mock(PlannerFixtureStore.class), false, environment);
    }

    @Test
    void nineGeneratedSubQuestionsAreTruncatedToTheFanOutCap() {
        String nineLines = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> "Sub-question " + i)
                .collect(Collectors.joining("\n"));

        List<String> result = serviceReturning(nineLines).decompose("some question");

        assertThat(result).hasSize(MAX_FAN_OUT);
        assertThat(result).doesNotContain("Sub-question 9");
    }

    @Test
    void fewerThanTheCapPassesThroughUnchanged() {
        String threeLines = "Sub-question 1\nSub-question 2\nSub-question 3";

        assertThat(serviceReturning(threeLines).decompose("some question")).hasSize(3);
    }

    @Test
    void nullResponseYieldsNoSubQuestions() {
        assertThat(serviceReturning(null).decompose("some question")).isEmpty();
    }

    @Test
    void theDailyRunCapRefusesBeforeAnyRunIsCreatedOrLlmCalled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(20);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        PlannerService service = new PlannerService(builder, jdbc, mock(KafkaTemplate.class),
                new PlannerProperties(3, MAX_FAN_OUT, Duration.ofMinutes(3), 20),
                mock(PlannerFixtureStore.class), false, mock(Environment.class));

        assertThatThrownBy(() -> service.submit("q"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("429");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
