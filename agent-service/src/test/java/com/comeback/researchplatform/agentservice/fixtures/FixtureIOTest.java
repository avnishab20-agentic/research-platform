package com.comeback.researchplatform.agentservice.fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureIOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keyForIsStableForTheSameInput() {
        assertThat(FixtureIO.keyFor("what is the repo rate")).isEqualTo(FixtureIO.keyFor("what is the repo rate"));
    }

    @Test
    void keyForDiffersForDifferentInput() {
        assertThat(FixtureIO.keyFor("question A")).isNotEqualTo(FixtureIO.keyFor("question B"));
    }

    @Test
    void readFindsAValueCommittedOnTheTestClasspath() {
        FixtureIO fixtureIO = new FixtureIO(objectMapper, "unused/");
        assertThat(fixtureIO.read("sample.json", "testkey123", String.class)).isEqualTo("hello from a fixture");
    }

    @Test
    void readThrowsALoudMissRatherThanReturningNull() {
        FixtureIO fixtureIO = new FixtureIO(objectMapper, "unused/");
        assertThatThrownBy(() -> fixtureIO.read("sample.json", "no-such-key", String.class))
                .isInstanceOf(FixtureMissException.class);
    }

    @Test
    void readOfAMissingFileThrowsRatherThanReturningNull() {
        FixtureIO fixtureIO = new FixtureIO(objectMapper, "unused/");
        assertThatThrownBy(() -> fixtureIO.read("does-not-exist.json", "any-key", String.class))
                .isInstanceOf(FixtureMissException.class);
    }

    @Test
    void recordWritesValidJsonToTheConfiguredDirectory(@TempDir Path tempDir) throws Exception {
        FixtureIO fixtureIO = new FixtureIO(objectMapper, tempDir + "/");
        fixtureIO.record("recorded.json", "keyA", "valueA");
        fixtureIO.record("recorded.json", "keyB", "valueB");

        String written = Files.readString(tempDir.resolve("recorded.json"));
        @SuppressWarnings("unchecked")
        var parsed = objectMapper.readValue(written, java.util.Map.class);
        assertThat(parsed).containsEntry("keyA", "valueA").containsEntry("keyB", "valueB");
    }
}
